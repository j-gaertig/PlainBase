package de.jgaertig.plainBase.team;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
            return plugin.getMiniMessage().deserialize(displayName);
        }
    }

    private final PlainBase plugin;
    private final Map<String, TeamDefinition> teams = new LinkedHashMap<>();

    // teamId -> (uuid -> role)
    private final Map<String, Map<UUID, Role>> memberships = new ConcurrentHashMap<>();
    // uuid -> pending invite team ids
    private final Map<UUID, Set<String>> invites = new ConcurrentHashMap<>();
    // teamId -> pending join-request uuids
    private final Map<String, Set<UUID>> requests = new ConcurrentHashMap<>();
    // uuid -> the team currently mirrored on the vanilla scoreboard (see class javadoc)
    private final Map<UUID, String> scoreboardTeamOf = new ConcurrentHashMap<>();

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
            this.scoreboard = Bukkit.getScoreboardManager() != null ? Bukkit.getScoreboardManager().getMainScoreboard() : null;
            syncScoreboardTeamDefinitions();
            for (String teamId : memberships.keySet()) refreshScoreboardEntries(teamId);
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
        teams.clear();
        FileConfiguration config = plugin.getTeamConfig();
        if (config == null) return;
        ConfigurationSection section = config.getConfigurationSection("teams");
        if (section == null) return;

        for (String rawId : section.getKeys(false)) {
            String id = rawId.toLowerCase();
            String displayName = section.getString(rawId + ".display-name", id);
            String color = section.getString(rawId + ".color", "<white>");
            NamedTextColor vanillaColor = resolveVanillaColor(color);
            teams.put(id, new TeamDefinition(id, displayName, color, vanillaColor));
        }
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
        return teams.containsKey(id.toLowerCase());
    }

    public TeamDefinition getTeam(String id) {
        return teams.get(id.toLowerCase());
    }

    public Collection<TeamDefinition> getTeams() {
        return teams.values();
    }

    public int getMaxTeamsPerPlayer() {
        return Math.max(1, plugin.getTeamConfig().getInt("team.max-teams-per-player", 1));
    }

    // ---------------------------------------------------------------
    // Membership queries
    // ---------------------------------------------------------------

    public boolean isMember(UUID uuid, String teamId) {
        Map<UUID, Role> members = memberships.get(teamId.toLowerCase());
        return members != null && members.containsKey(uuid);
    }

    public Role getRole(UUID uuid, String teamId) {
        Map<UUID, Role> members = memberships.get(teamId.toLowerCase());
        return members != null ? members.get(uuid) : null;
    }

    /**
     * True if the sender is allowed to perform admin actions on this team:
     * server console, OP, plainbase.team.admin bypass, or an actual stored
     * ADMIN role in this specific team.
     */
    public boolean isTeamAdmin(CommandSender sender, String teamId) {
        if (!(sender instanceof Player player)) return true; // console always allowed
        if (player.isOp()) return true;
        if (player.hasPermission("plainbase.admin") || player.hasPermission("plainbase.team.admin")) return true;
        return getRole(player.getUniqueId(), teamId.toLowerCase()) == Role.ADMIN;
    }

    public Set<String> getPlayerTeams(UUID uuid) {
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, Map<UUID, Role>> entry : memberships.entrySet()) {
            if (entry.getValue().containsKey(uuid)) result.add(entry.getKey());
        }
        return result;
    }

    public Map<UUID, Role> getMembers(String teamId) {
        return memberships.getOrDefault(teamId.toLowerCase(), Map.of());
    }

    public boolean isInvited(UUID uuid, String teamId) {
        return invites.getOrDefault(uuid, Set.of()).contains(teamId.toLowerCase());
    }

    /** Snapshot of a team's pending join requests (admin-facing). */
    public Set<UUID> getPendingRequests(String teamId) {
        return Set.copyOf(requests.getOrDefault(teamId.toLowerCase(), Set.of()));
    }

    /** Snapshot of a player's own pending invites, across every team. */
    public Set<String> getPendingInvites(UUID uuid) {
        return Set.copyOf(invites.getOrDefault(uuid, Set.of()));
    }

    // ---------------------------------------------------------------
    // Actions (send their own feedback messages, matching TPAManager/VanishManager style)
    // ---------------------------------------------------------------

    public void invite(CommandSender staff, String teamId, String targetName) {
        String id = teamId.toLowerCase();
        resolveTarget(staff, targetName, target -> {
            UUID uuid = target.getUniqueId();

            if (isMember(uuid, id)) {
                staff.sendMessage(msg("already-in-team", "player", targetName, "team", id));
                return;
            }
            if (invites.getOrDefault(uuid, Set.of()).contains(id)) {
                staff.sendMessage(msg("invite-already-pending", "player", targetName, "team", id));
                return;
            }
            if (getPlayerTeams(uuid).size() >= getMaxTeamsPerPlayer()) {
                staff.sendMessage(msg("max-teams-reached", "player", targetName, "max", String.valueOf(getMaxTeamsPerPlayer())));
                return;
            }

            invites.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(id);
            saveInvites();
            staff.sendMessage(msg("invite-sent", "player", targetName, "team", id));

            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                online.sendMessage(msg("invite-received", "team", id));
            }
        });
    }

    public void accept(Player player, String teamIdOrNull) {
        UUID uuid = player.getUniqueId();
        Set<String> pending = invites.getOrDefault(uuid, Set.of());
        String id = resolveSingle(player, pending, teamIdOrNull, "invite-not-found");
        if (id == null) return;

        if (getPlayerTeams(uuid).size() >= getMaxTeamsPerPlayer()) {
            player.sendMessage(msg("max-teams-reached", "player", player.getName(), "max", String.valueOf(getMaxTeamsPerPlayer())));
            return;
        }

        invites.get(uuid).remove(id);
        saveInvites();
        setMember(uuid, id, Role.MEMBER);
        player.sendMessage(msg("invite-accepted", "team", id));
    }

    public void deny(Player player, String teamIdOrNull) {
        UUID uuid = player.getUniqueId();
        Set<String> pending = invites.getOrDefault(uuid, Set.of());
        String id = resolveSingle(player, pending, teamIdOrNull, "invite-not-found");
        if (id == null) return;

        invites.get(uuid).remove(id);
        saveInvites();
        player.sendMessage(msg("invite-denied", "team", id));
    }

    public void add(CommandSender staff, String teamId, String targetName) {
        String id = teamId.toLowerCase();
        resolveTarget(staff, targetName, target -> {
            UUID uuid = target.getUniqueId();

            if (isMember(uuid, id)) {
                staff.sendMessage(msg("already-in-team", "player", targetName, "team", id));
                return;
            }
            if (getPlayerTeams(uuid).size() >= getMaxTeamsPerPlayer()) {
                staff.sendMessage(msg("max-teams-reached", "player", targetName, "max", String.valueOf(getMaxTeamsPerPlayer())));
                return;
            }

            // Adding directly also clears any pending invite/request for this team.
            // (getOrDefault falls back to the immutable Set.of() when there's no
            // entry yet — remove() on that throws, so only touch a real set.)
            Set<String> pendingInvites = invites.get(uuid);
            if (pendingInvites != null) pendingInvites.remove(id);
            Set<UUID> pendingRequests = requests.get(id);
            if (pendingRequests != null) pendingRequests.remove(uuid);
            saveInvites();
            saveRequests();

            setMember(uuid, id, Role.MEMBER);
            staff.sendMessage(msg("add-success", "player", targetName, "team", id));

            Player online = Bukkit.getPlayer(uuid);
            if (online != null) online.sendMessage(msg("add-success", "player", online.getName(), "team", id));
        });
    }

    public void kick(CommandSender staff, String teamId, String targetName) {
        String id = teamId.toLowerCase();
        resolveTarget(staff, targetName, target -> {
            UUID uuid = target.getUniqueId();

            if (!isMember(uuid, id)) {
                staff.sendMessage(msg("not-in-team", "team", id));
                return;
            }

            removeMember(uuid, id);
            staff.sendMessage(msg("kick-success", "player", targetName, "team", id));

            Player online = Bukkit.getPlayer(uuid);
            if (online != null) online.sendMessage(msg("kick-success", "player", online.getName(), "team", id));
        });
    }

    public void leave(Player player, String teamIdOrNull) {
        UUID uuid = player.getUniqueId();
        Set<String> memberOf = getPlayerTeams(uuid);
        String id;
        if (teamIdOrNull != null) {
            id = teamIdOrNull.toLowerCase();
            if (!memberOf.contains(id)) {
                player.sendMessage(msg("not-in-team", "team", id));
                return;
            }
        } else if (memberOf.size() == 1) {
            id = memberOf.iterator().next();
        } else if (memberOf.isEmpty()) {
            player.sendMessage(msg("not-in-team", "team", "?"));
            return;
        } else {
            player.sendMessage(msg("leave-usage-multiple"));
            return;
        }

        removeMember(uuid, id);
        player.sendMessage(msg("leave-success", "team", id));
    }

    public void request(Player player, String teamId) {
        String id = teamId.toLowerCase();
        UUID uuid = player.getUniqueId();

        if (isMember(uuid, id)) {
            player.sendMessage(msg("already-member", "team", id));
            return;
        }
        if (requests.getOrDefault(id, Set.of()).contains(uuid)) {
            player.sendMessage(msg("request-already-pending", "team", id));
            return;
        }
        if (getPlayerTeams(uuid).size() >= getMaxTeamsPerPlayer()) {
            player.sendMessage(msg("max-teams-reached", "player", player.getName(), "max", String.valueOf(getMaxTeamsPerPlayer())));
            return;
        }

        requests.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(uuid);
        saveRequests();
        player.sendMessage(msg("request-sent", "team", id));

        Component notice = msg("request-received", "player", player.getName(), "team", id);
        for (Map.Entry<UUID, Role> entry : getMembers(id).entrySet()) {
            if (entry.getValue() != Role.ADMIN) continue;
            Player admin = Bukkit.getPlayer(entry.getKey());
            if (admin != null) admin.sendMessage(notice);
        }
    }

    public void denyRequest(CommandSender staff, String teamId, String targetName) {
        String id = teamId.toLowerCase();
        resolveTarget(staff, targetName, target -> {
            UUID uuid = target.getUniqueId();

            Set<UUID> pending = requests.getOrDefault(id, Set.of());
            if (!pending.contains(uuid)) {
                staff.sendMessage(msg("request-not-found", "player", targetName, "team", id));
                return;
            }
            pending.remove(uuid);
            saveRequests();
            staff.sendMessage(msg("kick-success", "player", targetName, "team", id)); // reuse: "removed/rejected"
        });
    }

    public void setRole(CommandSender staff, String teamId, String targetName, String roleStr) {
        String id = teamId.toLowerCase();
        Role role;
        try {
            role = Role.valueOf(roleStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            staff.sendMessage(msg("invalid-role"));
            return;
        }

        resolveTarget(staff, targetName, target -> {
            UUID uuid = target.getUniqueId();

            if (!isMember(uuid, id)) {
                staff.sendMessage(msg("not-in-team", "team", id));
                return;
            }

            setMember(uuid, id, role);
            staff.sendMessage(msg("setrole-success", "player", targetName, "team", id, "role", role.name().toLowerCase()));

            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                online.sendMessage(msg("setrole-success", "player", online.getName(), "team", id, "role", role.name().toLowerCase()));
            }
        });
    }

    public void listTeams(CommandSender sender) {
        sender.sendMessage(msg("list-header"));
        for (TeamDefinition def : teams.values()) {
            int count = getMembers(def.id()).size();
            String displayLegacy = plugin.getTeamConfig().getString("messages.list-entry", "%team-display% (%count% members)");
            String rendered = displayLegacy
                    .replace("%team-display%", def.color() + def.displayName())
                    .replace("%count%", String.valueOf(count));
            sender.sendMessage(plugin.getMiniMessage().deserialize(rendered));
        }
    }

    public void info(CommandSender sender, String teamId) {
        TeamDefinition def = getTeam(teamId);
        Map<UUID, Role> members = getMembers(teamId.toLowerCase());

        String header = plugin.getTeamConfig().getString("messages.info-header", "--- %team-display% ---")
                .replace("%team-display%", def.color() + def.displayName());
        sender.sendMessage(plugin.getMiniMessage().deserialize(header));

        if (members.isEmpty()) {
            sender.sendMessage(msg("info-empty"));
            return;
        }
        for (Map.Entry<UUID, Role> entry : members.entrySet()) {
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(entry.getKey()).getName()).orElse(entry.getKey().toString());
            sender.sendMessage(msg("info-member", "player", name, "role", entry.getValue().name().toLowerCase()));
        }
    }

    /**
     * Admin-facing: list a team's pending join requests (people who ran
     * /team &lt;team&gt; request and are waiting on an admin to /team add them).
     */
    public void listRequests(CommandSender sender, String teamId) {
        String id = teamId.toLowerCase();
        Set<UUID> pending = getPendingRequests(id);
        sender.sendMessage(msg("requests-header", "team", id));
        if (pending.isEmpty()) {
            sender.sendMessage(msg("requests-empty", "team", id));
            return;
        }
        for (UUID uuid : pending) {
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName()).orElse(uuid.toString());
            sender.sendMessage(msg("requests-entry", "player", name));
        }
    }

    /** Self-facing: list the invites a player is currently sitting on. */
    public void listInvites(Player player) {
        Set<String> pending = getPendingInvites(player.getUniqueId());
        player.sendMessage(msg("invites-header"));
        if (pending.isEmpty()) {
            player.sendMessage(msg("invites-empty"));
            return;
        }
        for (String teamId : pending) {
            player.sendMessage(msg("invites-entry", "team", teamId));
        }
    }

    /** Self-facing summary used by "/team info" with no team argument. */
    public void infoSelf(Player player) {
        Set<String> memberOf = getPlayerTeams(player.getUniqueId());
        player.sendMessage(msg("your-teams-header"));
        if (memberOf.isEmpty()) {
            player.sendMessage(msg("your-teams-empty"));
            return;
        }
        for (String teamId : memberOf) {
            Role role = getRole(player.getUniqueId(), teamId);
            player.sendMessage(msg("your-teams-entry", "team", teamId, "role", role.name().toLowerCase()));
        }
    }

    /**
     * Called on player join: delivers reminders for any pending invites and
     * re-syncs this player's scoreboard entry under their current name.
     */
    public void handleJoin(Player player) {
        Set<String> pending = invites.get(player.getUniqueId());
        if (pending != null) {
            for (String teamId : pending) {
                player.sendMessage(msg("invite-reminder-on-join", "team", teamId));
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

    private String resolveSingle(Player player, Set<String> pending, String teamIdOrNull, String notFoundKey) {
        if (teamIdOrNull != null) {
            String id = teamIdOrNull.toLowerCase();
            if (!pending.contains(id)) {
                player.sendMessage(msg(notFoundKey, "team", id));
                return null;
            }
            return id;
        }
        if (pending.size() == 1) return pending.iterator().next();
        if (pending.isEmpty()) {
            player.sendMessage(msg(notFoundKey, "team", "?"));
            return null;
        }
        player.sendMessage(msg("leave-usage-multiple"));
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
                    staff.sendMessage(msg("player-not-found", "player", name));
                    return;
                }
                callback.accept(resolved);
            });
        });
    }

    private void setMember(UUID uuid, String teamId, Role role) {
        memberships.computeIfAbsent(teamId, k -> new ConcurrentHashMap<>()).put(uuid, role);
        saveMemberships();
        refreshScoreboardEntries(teamId);
        assignScoreboardTeam(uuid, teamId);
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
        String raw = plugin.getTeamConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
        }
        return plugin.getMiniMessage().deserialize(raw);
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
            Team team = scoreboard.getTeam(name);
            if (team == null) team = scoreboard.registerNewTeam(name);
            team.color(def.vanillaColor());
            team.prefix(plugin.getMiniMessage().deserialize(def.color() + "[" + def.id() + "] <reset>"));
        }

        for (Team team : new ArrayList<>(scoreboard.getTeams())) {
            if (team.getName().startsWith("pb_") && !validNames.contains(team.getName())) {
                team.unregister();
            }
        }
    }

    private void refreshScoreboardEntries(String teamId) {
        if (scoreboard == null) return;
        Team team = scoreboard.getTeam(scoreboardName(teamId));
        if (team == null) return;

        for (String entry : new HashSet<>(team.getEntries())) {
            team.removeEntry(entry);
        }
        for (UUID uuid : getMembers(teamId).keySet()) {
            // Only the team currently assigned for selector purposes gets the entry
            // (a scoreboard entry can only be on one team at a time).
            if (!teamId.equals(scoreboardTeamOf.getOrDefault(uuid, teamId))) continue;
            String name = Bukkit.getOfflinePlayer(uuid).getName();
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
        if (scoreboard == null) return;
        for (Team team : new ArrayList<>(scoreboard.getTeams())) {
            if (team.getName().startsWith("pb_")) team.unregister();
        }
    }

    private String scoreboardName(String teamId) {
        String name = "pb_" + teamId;
        return name.length() > 40 ? name.substring(0, 40) : name; // generous modern limit, just a sanity cap
    }

    // ---------------------------------------------------------------
    // Persistence (plugins/PlainBase/data/teams/*.yml)
    // ---------------------------------------------------------------

    private File dataFile(String name) {
        File folder = new File(plugin.getDataFolder(), "data/teams");
        if (!folder.exists()) folder.mkdirs();
        return new File(folder, name);
    }

    private void loadState() {
        memberships.clear();
        FileConfiguration members = YamlConfiguration.loadConfiguration(dataFile("members.yml"));
        for (String teamId : members.getKeys(false)) {
            ConfigurationSection section = members.getConfigurationSection(teamId);
            if (section == null) continue;
            Map<UUID, Role> map = new ConcurrentHashMap<>();
            for (String uuidStr : section.getKeys(false)) {
                try {
                    map.put(UUID.fromString(uuidStr), Role.valueOf(section.getString(uuidStr, "MEMBER")));
                } catch (IllegalArgumentException ignored) {
                }
            }
            memberships.put(teamId, map);
        }

        invites.clear();
        FileConfiguration invitesConfig = YamlConfiguration.loadConfiguration(dataFile("invites.yml"));
        for (String uuidStr : invitesConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                invites.put(uuid, ConcurrentHashMap.newKeySet());
                invites.get(uuid).addAll(invitesConfig.getStringList(uuidStr));
            } catch (IllegalArgumentException ignored) {
            }
        }

        requests.clear();
        FileConfiguration requestsConfig = YamlConfiguration.loadConfiguration(dataFile("requests.yml"));
        for (String teamId : requestsConfig.getKeys(false)) {
            Set<UUID> set = ConcurrentHashMap.newKeySet();
            for (String uuidStr : requestsConfig.getStringList(teamId)) {
                try {
                    set.add(UUID.fromString(uuidStr));
                } catch (IllegalArgumentException ignored) {
                }
            }
            requests.put(teamId, set);
        }
    }

    private void saveMemberships() {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<String, Map<UUID, Role>> teamEntry : memberships.entrySet()) {
                for (Map.Entry<UUID, Role> memberEntry : teamEntry.getValue().entrySet()) {
                    config.set(teamEntry.getKey() + "." + memberEntry.getKey(), memberEntry.getValue().name());
                }
            }
            saveQuietly(config, "members.yml");
        });
    }

    private void saveInvites() {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<UUID, Set<String>> entry : invites.entrySet()) {
                if (!entry.getValue().isEmpty()) config.set(entry.getKey().toString(), new ArrayList<>(entry.getValue()));
            }
            saveQuietly(config, "invites.yml");
        });
    }

    private void saveRequests() {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<String, Set<UUID>> entry : requests.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    List<String> uuids = entry.getValue().stream().map(UUID::toString).toList();
                    config.set(entry.getKey(), uuids);
                }
            }
            saveQuietly(config, "requests.yml");
        });
    }

    private void saveQuietly(YamlConfiguration config, String fileName) {
        try {
            config.save(dataFile(fileName));
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + fileName + ": " + e.getMessage());
        }
    }
}
