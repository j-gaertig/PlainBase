package de.jgaertig.plainBase.team;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Core logic for the Team module: team definitions (config-only), runtime
 * membership/roles/invites/join-requests (persisted by UUID under
 * data/teams/), and mirroring memberships to a real vanilla scoreboard team
 * so any command that accepts a target selector (e.g. /gamemode, /tp, /give)
 * can target a PlainBase team via {@code @a[team=pb_<id>]}.
 * <p>
 * Vanilla limitation: a scoreboard entry (player name) can only belong to
 * ONE scoreboard team at a time. With {@code max-teams-per-player: 1}
 * (the default) this never matters. If an admin raises that limit, only the
 * player's most-recently-joined team is mirrored to the scoreboard for
 * selector purposes — all other memberships are still fully tracked by
 * PlainBase (roles, /team info, placeholders), just not selector-visible
 * at the same time. This is a Minecraft engine constraint, not a bug.
 */
public class TeamManager {

    public enum Role { MEMBER, ADMIN }

    public record TeamDefinition(String id, String displayName, String color, NamedTextColor vanillaColor) {
        public Component renderDisplayName(PlainBase plugin) {
            // Admin-authored MiniMessage can be broken — never let a bad
            // display-name throw into scoreboard/command rendering.
            try {
                return plugin.getMiniMessage().deserialize(displayName);
            } catch (Exception e) {
                return Component.text(displayName != null ? displayName : id);
            }
        }
    }

    private final PlainBase plugin;
    // Copy-on-write snapshot, replaced wholesale by loadTeamDefinitions(): concurrent
    // readers (commands, placeholders, scoreboard sync on other threads) only ever see
    // the old or the new complete map, never a half-cleared one. Reads need no lock;
    // the published map itself is never mutated in place.
    private volatile Map<String, TeamDefinition> teams = Map.of();

    // teamId -> (uuid -> role)
    private final Map<String, Map<UUID, Role>> memberships = new ConcurrentHashMap<>();
    // uuid -> pending invite team ids
    private final Map<UUID, Set<String>> invites = new ConcurrentHashMap<>();
    // teamId -> pending join-request uuids
    private final Map<String, Set<UUID>> requests = new ConcurrentHashMap<>();
    // uuid -> the team currently mirrored on the vanilla scoreboard (see class javadoc).
    // NOTE (audit V21/V22, documented as open, not changed): entries are kept
    // across quit on purpose (offline targeting, relog resync via
    // resyncScoreboard) and no quit hook is wired — one small entry per ever
    // mirrored player stays in memory. Dropping the entry on quit without a
    // matching scoreboard-entry removal would desync the mirror, so any
    // cleanup needs the listener change owned elsewhere.
    private final Map<UUID, String> scoreboardTeamOf = new ConcurrentHashMap<>();

    // Scoreboard teams created by THIS manager instance (vanilla names "pb_<id>").
    // Cleanup (sync/shutdown) only ever unregisters names in this set, so stale
    // "pb_*" teams from other plugins or previous instances are never touched.
    private final Set<String> ownedScoreboardTeams = ConcurrentHashMap.newKeySet();

    // Per-player lock for invite/accept/deny/add/request check-then-act
    // sequences, so two concurrent actions for the same player cannot both
    // pass a check (e.g. max-teams) and then both mutate.
    // Fixed-size stripe array — deliberately NOT one lock object per UUID in a
    // map: a map would grow without bound (one entry per ever-seen player,
    // never removed). Stripes bound memory to a constant; collisions between
    // different players only reduce concurrency slightly, never correctness.
    private final Object[] lockStripes = initStripes(64);

    private static Object[] initStripes(int size) {
        Object[] stripes = new Object[size];
        for (int i = 0; i < size; i++) stripes[i] = new Object();
        return stripes;
    }

    private Object lockFor(UUID uuid) {
        return lockStripes[(uuid.hashCode() & 0x7fffffff) % lockStripes.length];
    }

    // T2 team-scoped locks: the per-player stripes above cannot protect a
    // team-level invariant (two parallel kick/leave on DIFFERENT targets would
    // each hold a different player lock and both pass denyIfLastAdmin, leaving
    // 0 admins). Every denyIfLastAdmin + mutation pair additionally holds the
    // stripe for its teamId, so all admin-count checks on one team serialize.
    // Acquisition order is always player-lock THEN team-lock — never the
    // reverse — so no deadlock is possible.
    private final Object[] teamLockStripes = initStripes(64);

    private Object teamLockFor(String teamId) {
        String key = teamId == null ? "" : teamId.toLowerCase(Locale.ROOT);
        return teamLockStripes[(key.hashCode() & 0x7fffffff) % teamLockStripes.length];
    }

    // T3 stale-overwrite guard: each manager instance gets a monotonically
    // increasing generation at construction. Queued async saves of a PREVIOUS
    // instance (e.g. after /plainbase reload) snapshot that instance's own
    // maps and would otherwise overwrite the fresh file — they now skip with
    // a log line. Never use AsyncScheduler#cancelTasks here: that would also
    // cancel the NEW instance's tasks (same plugin reference).
    private static final AtomicLong GENERATION = new AtomicLong(0);
    private final long generation = GENERATION.incrementAndGet();

    // Built-in defaults for message keys added after team.yml v1.1, so servers
    // still running an older team.yml get sensible text instead of the raw key.
    private static final Map<String, String> BUILTIN_DEFAULTS = Map.of(
            "last-admin", "<red>Cannot remove the last admin of %team%. Promote someone else to admin first.",
            "request-rejected", "<red>Your join request for %team% was rejected.",
            "request-reject-success", "<green>Rejected %player%'s join request for %team%."
    );

    private Scoreboard scoreboard;

    public TeamManager(PlainBase plugin) {
        this.plugin = plugin;
        loadTeamDefinitions();
        loadState();

        // Folia currently considers ALL scoreboard API broken (global state it
        // hasn't figured out region ownership for yet — not something we can
        // work around by rescheduling). On Folia we skip the vanilla-scoreboard
        // mirror entirely: memberships/roles/invites/commands/placeholders keep
        // working, only the "/gamemode creative @a[team=pb_x]" selector trick
        // is unavailable there.
        if (isFolia()) {
            this.scoreboard = null;
            plugin.getLogger().info("Team module: running on Folia, scoreboard-based team selectors (@a[team=pb_<id>]) are disabled "
                    + "because Folia's scoreboard API is currently unsupported. Team membership, roles, commands and placeholders are unaffected.");
        } else {
            // Scoreboard mirroring must never break setupTeam(): a broken
            // Bukkit scoreboard, a bad color/prefix template or a duplicate
            // team name disables only the selector mirror, not the module.
            try {
                this.scoreboard = Bukkit.getScoreboardManager() != null ? Bukkit.getScoreboardManager().getMainScoreboard() : null;
                syncScoreboardTeamDefinitions();
                for (String teamId : memberships.keySet()) refreshScoreboardEntries(teamId);
            } catch (Exception e) {
                plugin.getLogger().severe("Team module: scoreboard mirror disabled (" + e.getMessage()
                        + "). Memberships, roles, commands and placeholders are unaffected.");
                this.scoreboard = null;
            }
        }
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------
    // Definitions
    // ---------------------------------------------------------------

    private void loadTeamDefinitions() {
        FileConfiguration config = plugin.getTeamConfig();
        if (config == null) return;
        ConfigurationSection section = config.getConfigurationSection("teams");
        if (section == null) return;

        // Built locally, then published atomically (see teams field): readers
        // never observe the intermediate cleared/half-filled state.
        Map<String, TeamDefinition> fresh = new LinkedHashMap<>();
        for (String rawId : section.getKeys(false)) {
            try {
                String id = rawId.toLowerCase(Locale.ROOT);
                // T6 case-collision guard: two ids that normalize to the same
                // key (e.g. "Red" + "red") would otherwise silently overwrite
                // each other — warn loudly and skip the second one.
                if (fresh.containsKey(id)) {
                    plugin.getLogger().severe("Team '" + rawId + "' skipped: normalized id '" + id
                            + "' collides with another team (ids are case-insensitive). Rename one of them in modules/team.yml.");
                    continue;
                }
                // Vanilla scoreboard names are capped at 16 chars ("pb_" + id), so
                // ids longer than 12 chars could never be mirrored. Skip with a loud
                // warning instead of silently truncating (truncation could map two
                // different teams onto one scoreboard team).
                if (id.length() > 12) {
                    plugin.getLogger().severe("Team '" + rawId + "' skipped: id is " + id.length()
                            + " chars, max is 12 (vanilla scoreboard limit is 16 chars for \"pb_<id>\"). "
                            + "Shorten the id in modules/team.yml.");
                    continue;
                }
                String displayName = section.getString(rawId + ".display-name", id);
                String color = section.getString(rawId + ".color", "<white>");
                NamedTextColor vanillaColor = resolveVanillaColor(color);
                fresh.put(id, new TeamDefinition(id, displayName, color, vanillaColor));
            } catch (Exception e) {
                // One broken definition must never abort the whole parse —
                // skip it loudly, keep the remaining teams.
                plugin.getLogger().warning("Team '" + rawId + "' skipped: could not parse definition (" + e.getMessage() + ").");
            }
        }
        teams = Collections.unmodifiableMap(fresh);
    }

    private NamedTextColor resolveVanillaColor(String miniMessageColor) {
        try {
            Component sample = plugin.getMiniMessage().deserialize(miniMessageColor + "X");
            TextColor found = findFirstColor(sample);
            return found != null ? NamedTextColor.nearestTo(found) : NamedTextColor.WHITE;
        } catch (Exception e) {
            return NamedTextColor.WHITE;
        }
    }

    private TextColor findFirstColor(Component component) {
        if (component.color() != null) return component.color();
        for (Component child : component.children()) {
            TextColor found = findFirstColor(child);
            if (found != null) return found;
        }
        return null;
    }

    public boolean teamExists(String id) {
        return teams.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public TeamDefinition getTeam(String id) {
        return teams.get(id.toLowerCase(Locale.ROOT));
    }

    /**
     * Snapshot copy — callers can never mutate live state, and iteration stays
     * safe against a concurrent reload replacing the definitions map.
     */
    public Collection<TeamDefinition> getTeams() {
        return List.copyOf(teams.values());
    }

    public int getMaxTeamsPerPlayer() {
        return maxTeamsOf(plugin.getTeamConfig());
    }

    private static int maxTeamsOf(FileConfiguration cfg) {
        if (cfg == null) return 1;
        return Math.max(1, cfg.getInt("team.max-teams-per-player", 1));
    }

    // ---------------------------------------------------------------
    // Membership queries
    // ---------------------------------------------------------------

    public boolean isMember(UUID uuid, String teamId) {
        Map<UUID, Role> members = memberships.get(teamId.toLowerCase(Locale.ROOT));
        return members != null && members.containsKey(uuid);
    }

    public Role getRole(UUID uuid, String teamId) {
        Map<UUID, Role> members = memberships.get(teamId.toLowerCase(Locale.ROOT));
        return members != null ? members.get(uuid) : null;
    }

    /**
     * True if the sender is allowed to perform admin actions on this team:
     * server console, plainbase.admin / plainbase.team.admin bypass, or an
     * actual stored ADMIN role in this specific team.
     * <p>
     * T8 note: the permission nodes plainbase.team.invite/add/kick/setrole/...
     * intentionally default to TRUE — the permission alone does NOT gate team
     * admin actions. The real protection lives here in code (isTeamAdmin),
     * checked via adminGated in TeamCommand. Do NOT "fix" this by flipping
     * permission defaults to OP (would be breaking for existing servers).
     */
    public boolean isTeamAdmin(CommandSender sender, String teamId) {
        // Permission holders first — applies to every sender type (a command
        // block only passes here if it was explicitly granted the permission).
        // NOTE: intentionally NO player.isOp() shortcut here — hasPermission()
        // already returns true for OPs via PermissionDefault.OP, while a bare
        // isOp() check would defeat an explicit negation (-plainbase.team.admin).
        if (sender.hasPermission("plainbase.admin") || sender.hasPermission("plainbase.team.admin")) return true;
        if (!(sender instanceof Player player)) {
            // Console is always allowed; any other non-player sender (command
            // block, ...) is NOT automatically admin.
            return sender instanceof ConsoleCommandSender;
        }
        if (teamId == null) return false;
        return getRole(player.getUniqueId(), teamId.toLowerCase(Locale.ROOT)) == Role.ADMIN;
    }

    public Set<String> getPlayerTeams(UUID uuid) {
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, Map<UUID, Role>> entry : memberships.entrySet()) {
            if (entry.getValue().containsKey(uuid)) result.add(entry.getKey());
        }
        return result;
    }

    /**
     * Unmodifiable snapshot copy — callers can never mutate live state.
     */
    public Map<UUID, Role> getMembers(String teamId) {
        Map<UUID, Role> live = teamId == null ? null : memberships.get(teamId.toLowerCase(Locale.ROOT));
        return live == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(live));
    }

    public boolean isInvited(UUID uuid, String teamId) {
        return invites.getOrDefault(uuid, Set.of()).contains(teamId.toLowerCase(Locale.ROOT));
    }

    /** Snapshot of a team's pending join requests (admin-facing). */
    public Set<UUID> getPendingRequests(String teamId) {
        return Set.copyOf(requests.getOrDefault(teamId.toLowerCase(Locale.ROOT), Set.of()));
    }

    /** Snapshot of a player's own pending invites, across every team. */
    public Set<String> getPendingInvites(UUID uuid) {
        return Set.copyOf(invites.getOrDefault(uuid, Set.of()));
    }

    // ---------------------------------------------------------------
    // Actions (send their own feedback messages, matching TPAManager/VanishManager style)
    // ---------------------------------------------------------------

    /**
     * V4 oracle guard: invite/add resolve a target by name. Without this, a
     * staff member without vanish-see could probe for vanished players
     * ("invited" vs "not found" leaks existence). When the named player is
     * online, vanished and invisible to the staff viewer, pretend the player
     * does not exist — using the existing vanishManager, no new API.
     * Returns true when the caller must abort with "player not found".
     */
    private boolean denyVanishedOracle(CommandSender staff, String targetName, FileConfiguration cfgSnapshot) {
        try {
            if (!(staff instanceof Player viewer)) return false;
            var vanishManager = plugin.getVanishManager();
            if (vanishManager == null) return false;
            Player maybe = Bukkit.getPlayer(targetName);
            if (maybe != null && vanishManager.isVanished(maybe) && !vanishManager.canSee(viewer, maybe)) {
                staff.sendMessage(msg(cfgSnapshot, "player-not-found", "player", targetName));
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed vanish-oracle check for '" + targetName + "': " + e.getMessage());
        }
        return false;
    }

    /**
     * Same oracle check for the already-resolved target (covers the race where
     * the target vanished between the pre-check and the async callback).
     */
    private boolean denyVanishedOracleResolved(CommandSender staff, OfflinePlayer target, String targetName, FileConfiguration cfgSnapshot) {
        try {
            if (!(staff instanceof Player viewer)) return false;
            var vanishManager = plugin.getVanishManager();
            if (vanishManager == null) return false;
            if (target instanceof Player targetPlayer
                    && vanishManager.isVanished(targetPlayer)
                    && !vanishManager.canSee(viewer, targetPlayer)) {
                staff.sendMessage(msg(cfgSnapshot, "player-not-found", "player", targetName));
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed vanish-oracle check for resolved target: " + e.getMessage());
        }
        return false;
    }

    /**
     * T1 ghost-team guard: runtime state may reference a team id that no
     * longer exists in modules/team.yml (deleted definition). Accepting or
     * mutating such an id would create ghost memberships. Returns true when
     * the id is unknown (caller must abort); also purges the stale pending
     * entry so it cannot linger.
     */
    private boolean denyUnknownTeam(Player player, FileConfiguration cfgSnapshot, String id, UUID ownerUuid, boolean isInvitePending) {
        if (id != null && teamExists(id)) return false;
        player.sendMessage(msg(cfgSnapshot, "unknown-team", "team", String.valueOf(id)));
        try {
            if (ownerUuid != null && id != null) {
                if (isInvitePending) {
                    Set<String> set = invites.get(ownerUuid);
                    if (set != null) {
                        set.remove(id);
                        if (set.isEmpty()) invites.remove(ownerUuid);
                        saveInvites();
                    }
                } else {
                    Set<UUID> set = requests.get(id);
                    if (set != null) {
                        set.remove(ownerUuid);
                        if (set.isEmpty()) requests.remove(id);
                        saveRequests();
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to purge stale pending for unknown team '" + id + "': " + e.getMessage());
        }
        return true;
    }

    private boolean denyUnknownTeamStaff(CommandSender staff, FileConfiguration cfgSnapshot, String id) {
        if (id != null && teamExists(id)) return false;
        staff.sendMessage(msg(cfgSnapshot, "unknown-team", "team", String.valueOf(id)));
        return true;
    }

    public void invite(CommandSender staff, String teamId, String targetName) {
        String id = teamId.toLowerCase(Locale.ROOT);
        // Snapshot the config at entry: the callback below may run later on
        // another thread (async lookup -> region thread), where a concurrent
        // /plainbase reload could have cleared getTeamConfig() to null.
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        final int maxTeams = maxTeamsOf(cfgSnapshot);
        // T1: inviting to a deleted team must not create ghost state.
        if (denyUnknownTeamStaff(staff, cfgSnapshot, id)) return;
        // V4: never reveal a vanished player's existence via invite probing.
        if (denyVanishedOracle(staff, targetName, cfgSnapshot)) return;
        resolveTarget(staff, targetName, cfgSnapshot, target -> {
            if (denyVanishedOracleResolved(staff, target, targetName, cfgSnapshot)) return;
            UUID uuid = target.getUniqueId();
            synchronized (lockFor(uuid)) {
                if (isMember(uuid, id)) {
                    staff.sendMessage(msg(cfgSnapshot, "already-in-team", "player", targetName, "team", id));
                    return;
                }
                if (invites.getOrDefault(uuid, Set.of()).contains(id)) {
                    staff.sendMessage(msg(cfgSnapshot, "invite-already-pending", "player", targetName, "team", id));
                    return;
                }
                if (getPlayerTeams(uuid).size() >= maxTeams) {
                    staff.sendMessage(msg(cfgSnapshot, "max-teams-reached", "player", targetName, "max", String.valueOf(maxTeams)));
                    return;
                }

                invites.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(id);
                saveInvites();
                staff.sendMessage(msg(cfgSnapshot, "invite-sent", "player", targetName, "team", id));

                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    online.sendMessage(msg(cfgSnapshot, "invite-received", "team", id));
                }
            }
        });
    }

    public void accept(Player player, String teamIdOrNull) {
        UUID uuid = player.getUniqueId();
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        final int maxTeams = maxTeamsOf(cfgSnapshot);
        synchronized (lockFor(uuid)) {
            Set<String> pending = invites.getOrDefault(uuid, Set.of());
            String id = resolveSingle(player, cfgSnapshot, pending, teamIdOrNull, "invite-not-found");
            if (id == null) return;
            // T1: pending invite for a deleted team — purge + unknown-team, never create ghost membership.
            if (denyUnknownTeam(player, cfgSnapshot, id, uuid, true)) return;

            if (getPlayerTeams(uuid).size() >= maxTeams) {
                player.sendMessage(msg(cfgSnapshot, "max-teams-reached", "player", player.getName(), "max", String.valueOf(maxTeams)));
                return;
            }

            invites.computeIfPresent(uuid, (k, set) -> {
                set.remove(id);
                return set.isEmpty() ? null : set;
            });
            saveInvites();
            setMember(uuid, id, Role.MEMBER);
            player.sendMessage(msg(cfgSnapshot, "invite-accepted", "team", id));
        }
    }

    public void deny(Player player, String teamIdOrNull) {
        UUID uuid = player.getUniqueId();
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        synchronized (lockFor(uuid)) {
            Set<String> pending = invites.getOrDefault(uuid, Set.of());
            String id = resolveSingle(player, cfgSnapshot, pending, teamIdOrNull, "invite-not-found");
            if (id == null) return;
            // T1: same ghost purge as accept.
            if (denyUnknownTeam(player, cfgSnapshot, id, uuid, true)) return;

            invites.computeIfPresent(uuid, (k, set) -> {
                set.remove(id);
                return set.isEmpty() ? null : set;
            });
            saveInvites();
            player.sendMessage(msg(cfgSnapshot, "invite-denied", "team", id));
        }
    }

    public void add(CommandSender staff, String teamId, String targetName) {
        String id = teamId.toLowerCase(Locale.ROOT);
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        final int maxTeams = maxTeamsOf(cfgSnapshot);
        if (denyUnknownTeamStaff(staff, cfgSnapshot, id)) return;
        if (denyVanishedOracle(staff, targetName, cfgSnapshot)) return;
        resolveTarget(staff, targetName, cfgSnapshot, target -> {
            if (denyVanishedOracleResolved(staff, target, targetName, cfgSnapshot)) return;
            UUID uuid = target.getUniqueId();
            synchronized (lockFor(uuid)) {
                synchronized (teamLockFor(id)) {
                    if (isMember(uuid, id)) {
                        staff.sendMessage(msg(cfgSnapshot, "already-in-team", "player", targetName, "team", id));
                        return;
                    }
                    if (getPlayerTeams(uuid).size() >= maxTeams) {
                        staff.sendMessage(msg(cfgSnapshot, "max-teams-reached", "player", targetName, "max", String.valueOf(maxTeams)));
                        return;
                    }

                    // Adding directly also clears any pending invite/request for this team.
                    Set<String> pendingInvites = invites.get(uuid);
                    if (pendingInvites != null) pendingInvites.remove(id);
                    Set<UUID> pendingRequests = requests.get(id);
                    if (pendingRequests != null) pendingRequests.remove(uuid);
                    saveInvites();
                    saveRequests();

                    // Founder rule: a team with no admins yet gains one — the first
                    // member added becomes ADMIN so the team stays manageable.
                    Role assigned = countAdmins(id) == 0 ? Role.ADMIN : Role.MEMBER;
                    setMember(uuid, id, assigned);
                    if (assigned == Role.ADMIN) {
                        plugin.getLogger().info("Team '" + id + "': " + targetName + " added as ADMIN (team had no admins).");
                    }
                    staff.sendMessage(msg(cfgSnapshot, "add-success", "player", targetName, "team", id));

                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null) online.sendMessage(msg(cfgSnapshot, "add-success", "player", online.getName(), "team", id));
                }
            }
        });
    }

    public void kick(CommandSender staff, String teamId, String targetName) {
        String id = teamId.toLowerCase(Locale.ROOT);
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        if (denyUnknownTeamStaff(staff, cfgSnapshot, id)) return;
        resolveTarget(staff, targetName, cfgSnapshot, target -> {
            UUID uuid = target.getUniqueId();
            synchronized (lockFor(uuid)) {
                synchronized (teamLockFor(id)) {
                    if (!isMember(uuid, id)) {
                        staff.sendMessage(msg(cfgSnapshot, "not-in-team", "team", id));
                        return;
                    }
                    if (denyIfLastAdmin(staff, cfgSnapshot, id, uuid)) return;

                    removeMember(uuid, id);
                    staff.sendMessage(msg(cfgSnapshot, "kick-success", "player", targetName, "team", id));

                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null) online.sendMessage(msg(cfgSnapshot, "kick-success", "player", online.getName(), "team", id));
                }
            }
        });
    }

    public void leave(Player player, String teamIdOrNull) {
        UUID uuid = player.getUniqueId();
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        synchronized (lockFor(uuid)) {
            Set<String> memberOf = getPlayerTeams(uuid);
            String id;
            if (teamIdOrNull != null) {
                id = teamIdOrNull.toLowerCase(Locale.ROOT);
                if (!teamExists(id)) {
                    player.sendMessage(msg(cfgSnapshot, "unknown-team", "team", teamIdOrNull));
                    return;
                }
                if (!memberOf.contains(id)) {
                    player.sendMessage(msg(cfgSnapshot, "not-in-team", "team", id));
                    return;
                }
            } else if (memberOf.size() == 1) {
                id = memberOf.iterator().next();
            } else if (memberOf.isEmpty()) {
                player.sendMessage(msg(cfgSnapshot, "not-in-team", "team", "?"));
                return;
            } else {
                player.sendMessage(msg(cfgSnapshot, "leave-usage-multiple"));
                return;
            }

            // T2: team-scoped lock around last-admin check + mutation.
            synchronized (teamLockFor(id)) {
                if (denyIfLastAdmin(player, cfgSnapshot, id, uuid)) return;
                removeMember(uuid, id);
            }
            player.sendMessage(msg(cfgSnapshot, "leave-success", "team", id));
        }
    }

    public void request(Player player, String teamId) {
        String id = teamId.toLowerCase(Locale.ROOT);
        UUID uuid = player.getUniqueId();
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        final int maxTeams = maxTeamsOf(cfgSnapshot);
        synchronized (lockFor(uuid)) {
            // T1: requesting a deleted team must not create ghost requests.
            if (!teamExists(id)) {
                player.sendMessage(msg(cfgSnapshot, "unknown-team", "team", teamId));
                return;
            }
            if (isMember(uuid, id)) {
                player.sendMessage(msg(cfgSnapshot, "already-member", "team", id));
                return;
            }
            if (requests.getOrDefault(id, Set.of()).contains(uuid)) {
                player.sendMessage(msg(cfgSnapshot, "request-already-pending", "team", id));
                return;
            }
            if (getPlayerTeams(uuid).size() >= maxTeams) {
                player.sendMessage(msg(cfgSnapshot, "max-teams-reached", "player", player.getName(), "max", String.valueOf(maxTeams)));
                return;
            }

            requests.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(uuid);
            saveRequests();
            player.sendMessage(msg(cfgSnapshot, "request-sent", "team", id));

            Component notice = msg(cfgSnapshot, "request-received", "player", player.getName(), "team", id);
            for (Map.Entry<UUID, Role> entry : getMembers(id).entrySet()) {
                if (entry.getValue() != Role.ADMIN) continue;
                Player admin = Bukkit.getPlayer(entry.getKey());
                if (admin != null) admin.sendMessage(notice);
            }
        }
    }

    public void denyRequest(CommandSender staff, String teamId, String targetName) {
        String id = teamId.toLowerCase(Locale.ROOT);
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        if (denyUnknownTeamStaff(staff, cfgSnapshot, id)) return;
        resolveTarget(staff, targetName, cfgSnapshot, target -> {
            UUID uuid = target.getUniqueId();
            synchronized (lockFor(uuid)) {
                Set<UUID> pending = requests.get(id);
                if (pending == null || !pending.contains(uuid)) {
                    staff.sendMessage(msg(cfgSnapshot, "request-not-found", "player", targetName, "team", id));
                    return;
                }
                requests.computeIfPresent(id, (k, set) -> {
                    set.remove(uuid);
                    return set.isEmpty() ? null : set;
                });
                saveRequests();
                staff.sendMessage(msg(cfgSnapshot, "request-reject-success", "player", targetName, "team", id));

                Player online = Bukkit.getPlayer(uuid);
                if (online != null) online.sendMessage(msg(cfgSnapshot, "request-rejected", "team", id));
            }
        });
    }

    public void setRole(CommandSender staff, String teamId, String targetName, String roleStr) {
        String id = teamId.toLowerCase(Locale.ROOT);
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        Role role;
        try {
            role = Role.valueOf(roleStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            staff.sendMessage(msg(cfgSnapshot, "invalid-role"));
            return;
        }
        if (denyUnknownTeamStaff(staff, cfgSnapshot, id)) return;

        resolveTarget(staff, targetName, cfgSnapshot, target -> {
            UUID uuid = target.getUniqueId();
            synchronized (lockFor(uuid)) {
                synchronized (teamLockFor(id)) {
                    if (!isMember(uuid, id)) {
                        staff.sendMessage(msg(cfgSnapshot, "not-in-team", "team", id));
                        return;
                    }
                    // Demoting the last remaining admin would orphan the team.
                    // T2: guarded by the team lock above (see teamLockFor).
                    if (role == Role.MEMBER && denyIfLastAdmin(staff, cfgSnapshot, id, uuid)) return;

                    setMember(uuid, id, role);
                    staff.sendMessage(msg(cfgSnapshot, "setrole-success", "player", targetName, "team", id, "role", role.name().toLowerCase(Locale.ROOT)));

                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null) {
                        online.sendMessage(msg(cfgSnapshot, "setrole-success", "player", online.getName(), "team", id, "role", role.name().toLowerCase(Locale.ROOT)));
                    }
                }
            }
        });
    }

    public void listTeams(CommandSender sender) {
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        sender.sendMessage(msg(cfgSnapshot, "list-header"));
        for (TeamDefinition def : getTeams()) {
            int count = getMembers(def.id()).size();
            String fallback = "%team-display% (%count% members)";
            String displayLegacy = cfgSnapshot != null ? cfgSnapshot.getString("messages.list-entry", fallback) : fallback;
            if (displayLegacy == null) displayLegacy = fallback;
            String rendered = displayLegacy
                    .replace("%team-display%", def.color() + def.displayName())
                    .replace("%count%", String.valueOf(count));
            sender.sendMessage(safeDeserialize(rendered));
        }
    }

    public void info(CommandSender sender, String teamId) {
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        if (teamId == null) {
            sender.sendMessage(msg(cfgSnapshot, "unknown-team", "team", "?"));
            return;
        }
        TeamDefinition def = getTeam(teamId);
        if (def == null) {
            sender.sendMessage(msg(cfgSnapshot, "unknown-team", "team", teamId));
            return;
        }
        Map<UUID, Role> members = getMembers(teamId.toLowerCase(Locale.ROOT));

        String fallbackHeader = "--- %team-display% ---";
        String headerRaw = cfgSnapshot != null ? cfgSnapshot.getString("messages.info-header", fallbackHeader) : fallbackHeader;
        if (headerRaw == null) headerRaw = fallbackHeader;
        String header = headerRaw
                .replace("%team-display%", def.color() + def.displayName());
        sender.sendMessage(safeDeserialize(header));

        if (members.isEmpty()) {
            sender.sendMessage(msg(cfgSnapshot, "info-empty"));
            return;
        }
        for (Map.Entry<UUID, Role> entry : members.entrySet()) {
            String name = Optional.ofNullable(lookupName(entry.getKey())).orElse(entry.getKey().toString());
            sender.sendMessage(msg(cfgSnapshot, "info-member", "player", name, "role", entry.getValue().name().toLowerCase(Locale.ROOT)));
        }
    }

    /**
     * Admin-facing: list a team's pending join requests (people who ran
     * /team &lt;team&gt; request and are waiting on an admin to /team add them).
     */
    public void listRequests(CommandSender sender, String teamId) {
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        String id = teamId.toLowerCase(Locale.ROOT);
        Set<UUID> pending = getPendingRequests(id);
        sender.sendMessage(msg(cfgSnapshot, "requests-header", "team", id));
        if (pending.isEmpty()) {
            sender.sendMessage(msg(cfgSnapshot, "requests-empty", "team", id));
            return;
        }
        for (UUID uuid : pending) {
            String name = Optional.ofNullable(lookupName(uuid)).orElse(uuid.toString());
            sender.sendMessage(msg(cfgSnapshot, "requests-entry", "player", name));
        }
    }

    /** Self-facing: list the invites a player is currently sitting on. */
    public void listInvites(Player player) {
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        Set<String> pending = getPendingInvites(player.getUniqueId());
        player.sendMessage(msg(cfgSnapshot, "invites-header"));
        if (pending.isEmpty()) {
            player.sendMessage(msg(cfgSnapshot, "invites-empty"));
            return;
        }
        for (String teamId : pending) {
            player.sendMessage(msg(cfgSnapshot, "invites-entry", "team", teamId));
        }
    }

    /** Self-facing summary used by "/team info" with no team argument. */
    public void infoSelf(Player player) {
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        Set<String> memberOf = getPlayerTeams(player.getUniqueId());
        player.sendMessage(msg(cfgSnapshot, "your-teams-header"));
        if (memberOf.isEmpty()) {
            player.sendMessage(msg(cfgSnapshot, "your-teams-empty"));
            return;
        }
        for (String teamId : memberOf) {
            Role role = getRole(player.getUniqueId(), teamId);
            player.sendMessage(msg(cfgSnapshot, "your-teams-entry", "team", teamId, "role", role.name().toLowerCase(Locale.ROOT)));
        }
    }

    /**
     * Called on player join: delivers reminders for any pending invites and
     * re-syncs this player's scoreboard entry under their current name.
     */
    public void handleJoin(Player player) {
        final FileConfiguration cfgSnapshot = plugin.getTeamConfig();
        Set<String> pending = invites.get(player.getUniqueId());
        if (pending != null) {
            for (String teamId : pending) {
                player.sendMessage(msg(cfgSnapshot, "invite-reminder-on-join", "team", teamId));
            }
        }
        resyncScoreboard(player);
    }

    /**
     * Re-applies this player's scoreboard team entry without sending any
     * invite reminders — used after a config/module reload for players who
     * are already online (they don't need to be re-notified about invites
     * they've already seen).
     */
    public void resyncScoreboard(Player player) {
        for (String teamId : getPlayerTeams(player.getUniqueId())) {
            assignScoreboardTeam(player.getUniqueId(), teamId);
        }
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    private String resolveSingle(Player player, FileConfiguration cfgSnapshot, Set<String> pending, String teamIdOrNull, String notFoundKey) {
        if (teamIdOrNull != null) {
            String id = teamIdOrNull.toLowerCase(Locale.ROOT);
            if (!pending.contains(id)) {
                player.sendMessage(msg(cfgSnapshot, notFoundKey, "team", id));
                return null;
            }
            return id;
        }
        if (pending.size() == 1) return pending.iterator().next();
        if (pending.isEmpty()) {
            player.sendMessage(msg(cfgSnapshot, notFoundKey, "team", "?"));
            return null;
        }
        player.sendMessage(msg(cfgSnapshot, "leave-usage-multiple"));
        return null;
    }

    /**
     * Resolves a target by name without ever blocking the calling thread:
     * online players and Paper's cached offline-player lookup resolve
     * instantly; only an uncached, never-joined name falls back to the
     * deprecated Bukkit#getOfflinePlayer(String), which can block on a
     * Mojang lookup — so that call always runs on the async scheduler, with
     * the callback dispatched back onto the main/region thread afterwards
     * (same pattern as ModerationCommandBase#resolveTarget).
     */
    private void resolveTarget(CommandSender staff, String name, Consumer<OfflinePlayer> callback) {
        resolveTarget(staff, name, plugin.getTeamConfig(), callback);
    }

    /**
     * Snapshot-aware variant: the caller passes the config captured at entry
     * so the async continuation never re-reads getTeamConfig() fresh (which
     * could be null after a concurrent reload). Same pattern as TeamCommand,
     * which snapshots its FileConfiguration once per execution.
     */
    private void resolveTarget(CommandSender staff, String name, FileConfiguration cfgSnapshot, Consumer<OfflinePlayer> callback) {
        Player online = Bukkit.getPlayer(name);
        if (online != null) {
            callback.accept(online);
            return;
        }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            callback.accept(cached);
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            @SuppressWarnings("deprecation")
            OfflinePlayer resolved = Bukkit.getOfflinePlayer(name);
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                if (resolved.getName() == null && !resolved.hasPlayedBefore()) {
                    staff.sendMessage(msg(cfgSnapshot, "player-not-found", "player", name));
                    return;
                }
                callback.accept(resolved);
            });
        });
    }

    /**
     * Best-effort display name for a member UUID, safe to call synchronously in
     * a loop over large teams: online players resolve with zero I/O, and the
     * UUID-based offline lookup issues no Mojang request (unlike the deprecated
     * name-based Bukkit#getOfflinePlayer(String), which resolveTarget() above
     * already keeps off the calling thread). Returns null when unknown.
     */
    private static String lookupName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        try {
            return Bukkit.getOfflinePlayer(uuid).getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void setMember(UUID uuid, String teamId, Role role) {
        memberships.computeIfAbsent(teamId, k -> new ConcurrentHashMap<>()).put(uuid, role);
        saveMemberships();
        refreshScoreboardEntries(teamId);
        assignScoreboardTeam(uuid, teamId);
    }

    private long countAdmins(String teamId) {
        Map<UUID, Role> members = memberships.get(teamId);
        if (members == null) return 0;
        return members.values().stream().filter(r -> r == Role.ADMIN).count();
    }

    /**
     * Last-admin guard: refuses to remove/demote the final ADMIN of a team.
     * Must be called BEFORE the mutation. Returns true when the action was
     * denied (caller must return immediately).
     * Callers must hold synchronized(lockFor(targetUuid)) AND
     * synchronized(teamLockFor(teamId)) when invoking this, so the admin-count
     * check and the following mutation are atomic per team (T2). Player-lock
     * first, team-lock second — always in this order.
     */
    private boolean denyIfLastAdmin(CommandSender sender, FileConfiguration cfgSnapshot, String teamId, UUID targetUuid) {
        Map<UUID, Role> members = memberships.get(teamId);
        if (members == null || members.get(targetUuid) != Role.ADMIN) return false;
        long admins = members.values().stream().filter(r -> r == Role.ADMIN).count();
        if (admins <= 1) {
            sender.sendMessage(msg(cfgSnapshot, "last-admin", "team", teamId));
            return true;
        }
        return false;
    }

    private void removeMember(UUID uuid, String teamId) {
        Map<UUID, Role> members = memberships.get(teamId);
        if (members != null) members.remove(uuid);
        saveMemberships();
        refreshScoreboardEntries(teamId);
        if (teamId.equals(scoreboardTeamOf.get(uuid))) {
            scoreboardTeamOf.remove(uuid);
            // fall back to another team this player is still in, if any
            Set<String> remaining = getPlayerTeams(uuid);
            if (!remaining.isEmpty()) assignScoreboardTeam(uuid, remaining.iterator().next());
        }
    }

    private Component msg(String key, String... placeholders) {
        return msg(plugin.getTeamConfig(), key, placeholders);
    }

    private Component msg(FileConfiguration cfg, String key, String... placeholders) {
        String fallback = BUILTIN_DEFAULTS.getOrDefault(key, key);
        String raw = cfg != null ? cfg.getString("messages." + key, fallback) : fallback;
        if (raw == null) raw = fallback;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            // Player/team inputs are untrusted — escape them so a name like
            // "<red>" can never inject MiniMessage formatting or click events.
            // (Team display-names from team.yml stay raw on purpose: those are
            // admin-authored MiniMessage by design.)
            String value = placeholders[i + 1] == null ? "" : placeholders[i + 1];
            raw = raw.replace("%" + placeholders[i] + "%", plugin.getMiniMessage().escapeTags(value));
        }
        return safeDeserialize(raw);
    }

    /**
     * MiniMessage with a plain-text fallback: a single broken admin message
     * must never break the command that renders it.
     */
    private Component safeDeserialize(String raw) {
        try {
            return plugin.getMiniMessage().deserialize(raw);
        } catch (Exception e) {
            return Component.text(raw);
        }
    }

    // ---------------------------------------------------------------
    // Scoreboard mirroring (see class javadoc for the single-team-per-player limitation)
    // ---------------------------------------------------------------

    private void syncScoreboardTeamDefinitions() {
        if (scoreboard == null) return;

        Set<String> validNames = new HashSet<>();
        for (TeamDefinition def : teams.values()) {
            String name = scoreboardName(def.id());
            validNames.add(name);
            Team team;
            try {
                team = scoreboard.getTeam(name);
                if (team == null) team = scoreboard.registerNewTeam(name);
            } catch (IllegalArgumentException | IllegalStateException e) {
                // Duplicate/stale registration or a broken scoreboard — skip
                // this team loudly, keep syncing the rest.
                plugin.getLogger().warning("Could not register scoreboard team '" + name + "': " + e.getMessage());
                continue;
            }
            ownedScoreboardTeams.add(name);
            try {
                team.color(def.vanillaColor());
            } catch (Exception e) {
                try {
                    team.color(NamedTextColor.WHITE);
                } catch (Exception ignored) {
                }
            }
            // A broken admin color template must never abort the whole sync —
            // fall back to a plain prefix for this team.
            Component prefix;
            try {
                prefix = plugin.getMiniMessage().deserialize(def.color() + "[" + def.id() + "] <reset>");
            } catch (Exception e) {
                plugin.getLogger().warning("Invalid color template for team '" + def.id() + "', using plain prefix.");
                prefix = Component.text("[" + def.id() + "] ");
            }
            try {
                team.prefix(prefix);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not set prefix for scoreboard team '" + name + "': " + e.getMessage());
            }
        }

        for (Team team : new ArrayList<>(scoreboard.getTeams())) {
            if (team.getName().startsWith("pb_") && !validNames.contains(team.getName())
                    && ownedScoreboardTeams.contains(team.getName())) {
                team.unregister();
                ownedScoreboardTeams.remove(team.getName());
            }
        }
    }

    private void refreshScoreboardEntries(String teamId) {
        if (scoreboard == null) return;
        if (!ownedScoreboardTeams.contains(scoreboardName(teamId))) return;
        Team team = scoreboard.getTeam(scoreboardName(teamId));
        if (team == null) return;

        for (String entry : new HashSet<>(team.getEntries())) {
            team.removeEntry(entry);
        }
        for (UUID uuid : getMembers(teamId).keySet()) {
            // Only the team currently assigned for selector purposes gets the entry
            // (a scoreboard entry can only be on one team at a time).
            if (!teamId.equals(scoreboardTeamOf.getOrDefault(uuid, teamId))) continue;
            String name = lookupName(uuid);
            if (name != null) team.addEntry(name);
        }
    }

    private void assignScoreboardTeam(UUID uuid, String teamId) {
        if (scoreboard == null) return;
        String previous = scoreboardTeamOf.put(uuid, teamId);
        if (previous != null && !previous.equals(teamId)) {
            refreshScoreboardEntries(previous);
        }
        refreshScoreboardEntries(teamId);
    }

    public void shutdown() {
        // Synchronous final flush — the server may be stopping, so async
        // tasks might never run. Write directly instead of via runNow().
        saveMembershipsSync();
        saveInvitesSync();
        saveRequestsSync();
        if (scoreboard == null) return;
        for (Team team : new ArrayList<>(scoreboard.getTeams())) {
            if (team.getName().startsWith("pb_") && ownedScoreboardTeams.contains(team.getName())) {
                team.unregister();
            }
        }
        ownedScoreboardTeams.clear();
    }

    /**
     * Vanilla scoreboard names are limited to 16 chars; team ids longer than
     * 12 chars are rejected at load (see loadTeamDefinitions), so "pb_<id>"
     * always fits and must never be truncated.
     */
    private String scoreboardName(String teamId) {
        return "pb_" + teamId;
    }

    // ---------------------------------------------------------------
    // Persistence (plugins/PlainBase/data/teams/*.yml)
    // ---------------------------------------------------------------

    private File dataFile(String name) {
        File folder = new File(plugin.getDataFolder(), "data/teams");
        if (!folder.exists()) folder.mkdirs();
        return new File(folder, name);
    }

    /**
     * Fail-closed guard against a corrupt/emptied team.yml: when no team
     * definitions loaded but persisted state files are non-empty, the
     * definition filter in loadState() and every save*Sync() must stand down
     * instead of purging members/invites/requests. Fail closed (keep the
     * files) rather than purge; fix team.yml and reload to restore.
     */
    private boolean hasPersistedTeamState() {
        return isStateFileNonEmpty("members.yml")
                || isStateFileNonEmpty("invites.yml")
                || isStateFileNonEmpty("requests.yml");
    }

    private boolean isStateFileNonEmpty(String name) {
        // Deliberately not dataFile(): that helper creates the directory as a
        // side effect, which a pure existence check must not do.
        File file = new File(new File(plugin.getDataFolder(), "data/teams"), name);
        return file.isFile() && file.length() > 0;
    }

    private void loadState() {
        // Serialized against every manager instance's async save*Sync() via the
        // same static per-file locks: a reload must never repopulate the maps
        // while another instance's save task is snapshotting them (or vice
        // versa), which could otherwise persist or load a torn mix of old and
        // new state across a /plainbase reload. (Does not drain already-queued
        // async saves of a previous instance — those snapshot that instance's
        // own maps, so they can only rewrite data that instance already held.
        // T3 generation guard in save*Sync additionally skips those stale
        // writes once a newer instance exists.)
        // T1: every loaded id is filtered against the definitions map —
        // orphan state for deleted teams is logged and dropped, never
        // resurrected as ghost memberships/invites/requests.
        // Fail-closed: with zero definitions but non-empty state files
        // (corrupt/emptied team.yml) the filter below would drop everything
        // and the next save would purge the files — stand down instead so the
        // persisted state survives until team.yml is fixed and reloaded.
        if (teams.isEmpty() && hasPersistedTeamState()) {
            plugin.getLogger().severe("No team definitions loaded (modules/team.yml corrupt or empty) but persisted "
                    + "team state exists — refusing to filter/purge it. Fix team.yml and reload to restore teams.");
            return;
        }
        synchronized (MEMBERS_LOCK) {
            memberships.clear();
            FileConfiguration members = YamlConfiguration.loadConfiguration(dataFile("members.yml"));
            for (String teamId : members.getKeys(false)) {
                String norm = teamId.toLowerCase(Locale.ROOT);
                if (!teams.containsKey(norm)) {
                    plugin.getLogger().warning("Team state for unknown team '" + teamId
                            + "' ignored (no such definition in modules/team.yml); orphan membership dropped.");
                    continue;
                }
                ConfigurationSection section = members.getConfigurationSection(teamId);
                if (section == null) continue;
                Map<UUID, Role> map = new ConcurrentHashMap<>();
                for (String uuidStr : section.getKeys(false)) {
                    try {
                        map.put(UUID.fromString(uuidStr), Role.valueOf(section.getString(uuidStr, "MEMBER")));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                if (!map.isEmpty()) memberships.put(norm, map);
            }
        }

        synchronized (INVITES_LOCK) {
            invites.clear();
            FileConfiguration invitesConfig = YamlConfiguration.loadConfiguration(dataFile("invites.yml"));
            for (String uuidStr : invitesConfig.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    for (String team : invitesConfig.getStringList(uuidStr)) {
                        String norm = team.toLowerCase(Locale.ROOT);
                        if (!teams.containsKey(norm)) {
                            plugin.getLogger().warning("Pending invite for unknown team '" + team
                                    + "' ignored (player " + uuidStr + "); orphan dropped.");
                            continue;
                        }
                        set.add(norm);
                    }
                    if (!set.isEmpty()) invites.put(uuid, set);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        synchronized (REQUESTS_LOCK) {
            requests.clear();
            FileConfiguration requestsConfig = YamlConfiguration.loadConfiguration(dataFile("requests.yml"));
            for (String teamId : requestsConfig.getKeys(false)) {
                String norm = teamId.toLowerCase(Locale.ROOT);
                if (!teams.containsKey(norm)) {
                    plugin.getLogger().warning("Pending join requests for unknown team '" + teamId
                            + "' ignored; orphans dropped.");
                    continue;
                }
                Set<UUID> set = ConcurrentHashMap.newKeySet();
                for (String uuidStr : requestsConfig.getStringList(teamId)) {
                    try {
                        set.add(UUID.fromString(uuidStr));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                if (!set.isEmpty()) requests.put(norm, set);
            }
        }
    }

    // Each save*() schedules an independent async task; without a per-file
    // lock, two near-simultaneous mutations to the same team could produce
    // two tasks racing to write the same YAML file concurrently, risking an
    // interleaved/corrupted write. Serializing per file (not one lock for
    // all three) keeps members/invites/requests writes from blocking each
    // other while still ruling out that race for each file individually.
    // Static on purpose: the lock must also hold across manager instances
    // (e.g. a /plainbase reload creating a second TeamManager while the old
    // one's async saves are still in flight).
    private static final Object MEMBERS_LOCK = new Object();
    private static final Object INVITES_LOCK = new Object();
    private static final Object REQUESTS_LOCK = new Object();

    private void saveMemberships() {
        final long captured = generation;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> saveMembershipsSync(captured));
    }

    private void saveMembershipsSync() {
        saveMembershipsSync(generation);
    }

    private void saveMembershipsSync(long captured) {
        // T3: skip stale writes from a previous manager instance. Warning (not
        // fine) so a lost write is visible. shutdown()'s synchronous flush is
        // unaffected and still runs (it uses the current generation).
        if (captured != GENERATION.get()) {
            plugin.getLogger().warning("Skipping stale members.yml save from previous TeamManager instance.");
            return;
        }
        // Fail-closed (see loadState): never overwrite persisted state while
        // no definitions are loaded — that would purge members.yml for good.
        if (teams.isEmpty() && hasPersistedTeamState()) {
            plugin.getLogger().severe("Refusing to overwrite members.yml: no team definitions loaded "
                    + "(modules/team.yml corrupt or empty). State preserved — fix team.yml and reload.");
            return;
        }
        synchronized (MEMBERS_LOCK) {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<String, Map<UUID, Role>> teamEntry : memberships.entrySet()) {
                for (Map.Entry<UUID, Role> memberEntry : teamEntry.getValue().entrySet()) {
                    config.set(teamEntry.getKey() + "." + memberEntry.getKey(), memberEntry.getValue().name());
                }
            }
            saveQuietly(config, "members.yml");
        }
    }

    private void saveInvites() {
        final long captured = generation;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> saveInvitesSync(captured));
    }

    private void saveInvitesSync() {
        saveInvitesSync(generation);
    }

    private void saveInvitesSync(long captured) {
        // T3: skip stale writes from a previous manager instance. Warning (not
        // fine) so a lost write is visible. shutdown()'s synchronous flush is
        // unaffected and still runs (it uses the current generation).
        if (captured != GENERATION.get()) {
            plugin.getLogger().warning("Skipping stale invites.yml save from previous TeamManager instance.");
            return;
        }
        // Fail-closed (see loadState): never overwrite persisted state while
        // no definitions are loaded — that would purge invites.yml for good.
        if (teams.isEmpty() && hasPersistedTeamState()) {
            plugin.getLogger().severe("Refusing to overwrite invites.yml: no team definitions loaded "
                    + "(modules/team.yml corrupt or empty). State preserved — fix team.yml and reload.");
            return;
        }
        synchronized (INVITES_LOCK) {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<UUID, Set<String>> entry : invites.entrySet()) {
                if (!entry.getValue().isEmpty()) config.set(entry.getKey().toString(), new ArrayList<>(entry.getValue()));
            }
            saveQuietly(config, "invites.yml");
        }
    }

    private void saveRequests() {
        final long captured = generation;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> saveRequestsSync(captured));
    }

    private void saveRequestsSync() {
        saveRequestsSync(generation);
    }

    private void saveRequestsSync(long captured) {
        // T3: skip stale writes from a previous manager instance. Warning (not
        // fine) so a lost write is visible. shutdown()'s synchronous flush is
        // unaffected and still runs (it uses the current generation).
        if (captured != GENERATION.get()) {
            plugin.getLogger().warning("Skipping stale requests.yml save from previous TeamManager instance.");
            return;
        }
        // Fail-closed (see loadState): never overwrite persisted state while
        // no definitions are loaded — that would purge requests.yml for good.
        if (teams.isEmpty() && hasPersistedTeamState()) {
            plugin.getLogger().severe("Refusing to overwrite requests.yml: no team definitions loaded "
                    + "(modules/team.yml corrupt or empty). State preserved — fix team.yml and reload.");
            return;
        }
        synchronized (REQUESTS_LOCK) {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<String, Set<UUID>> entry : requests.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    List<String> uuids = entry.getValue().stream().map(UUID::toString).toList();
                    config.set(entry.getKey(), uuids);
                }
            }
            saveQuietly(config, "requests.yml");
        }
    }

    /**
     * Crash-safe write: dump to "{@code <file>.tmp}" in the same directory, then move over
     * the target atomically (with a non-atomic fallback for file systems
     * without atomic-move support), so a crash can never leave a
     * half-written YAML behind.
     */
    private void saveQuietly(YamlConfiguration config, String fileName) {
        File target = dataFile(fileName);
        File tmp = new File(target.getParentFile(), fileName + ".tmp");
        try {
            config.save(tmp);
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + fileName + ": " + e.getMessage());
        }
    }
}
