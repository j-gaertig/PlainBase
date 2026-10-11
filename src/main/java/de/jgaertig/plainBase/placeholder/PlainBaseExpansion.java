package de.jgaertig.plainBase.placeholder;

import de.jgaertig.plainBase.PlainBase;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * PlaceholderAPI expansion providing %plainbase_*% placeholders.
 * Only loaded when PlaceholderAPI is installed (soft dependency).
 */
public class PlainBaseExpansion extends PlaceholderExpansion {

    private final PlainBase plugin;

    /**
     * P3: cache of the last successfully registered expansion instance.
     * Set only after register()==true via {@link #setLastInstance} (called from
     * PlainBase#registerPlaceholderExpansion) — a merely constructed but never
     * registered instance must never become the cached one. This field keeps
     * the live instance so future code can unregister exactly the registered
     * object; the static helper below is null-guarded and never throws.
     */
    private static volatile PlainBaseExpansion lastInstance;

    public PlainBaseExpansion(PlainBase plugin) {
        this.plugin = plugin;
    }

    /**
     * BUG-7: records the live instance after a successful register(). Called
     * only when register()==true so unregisterCached() always targets the
     * actually registered object.
     */
    public static void setLastInstance(PlainBaseExpansion instance) {
        lastInstance = instance;
    }

    /**
     * Unregisters the cached instance when present. Null-guarded: returns false
     * when nothing was registered. Prefer this over {@code new ...unregister()}.
     */
    public static boolean unregisterCached() {
        PlainBaseExpansion cached = lastInstance;
        if (cached == null) return false;
        try {
            return cached.unregister();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public @NotNull String getIdentifier() {
        return "plainbase";
    }

    @Override
    public @NotNull String getAuthor() {
        return "j-gaertig";
    }

    @Override
    public @NotNull String getVersion() {
        try {
            String version = plugin.getPluginMeta().getVersion();
            return version != null ? version : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (params == null || params.isEmpty()) return null;
        // Only the static keys are matched case-insensitively via the lowered
        // copy — the parameterized team branch below keeps the original params
        // so team IDs (suffix after team_role_/team_members_) are NOT lowercased.
        String lower = params.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "version" -> getVersion();
            case "vanished" -> player != null && plugin.getVanishManager() != null
                    && plugin.getVanishManager().isVanished(player) ? "true" : "false";
            case "moderation_bancount" -> player != null && plugin.getBanManager() != null
                    ? String.valueOf(plugin.getBanManager().getBanCount(player.getUniqueId())) : "0";
            case "moderation_kickcount" -> player != null && plugin.getBanManager() != null
                    ? String.valueOf(plugin.getBanManager().getKickCount(player.getUniqueId())) : "0";
            case "moderation_banned" -> player != null && plugin.getBanManager() != null
                    && plugin.getBanManager().getActiveBan(player.getUniqueId()).isPresent() ? "true" : "false";

            // Spawn module (spawn.yml reads under the instance monitor, same as
            // Spawn/SetSpawn: writers mutate under synchronized(config)).
            case "spawn_enabled" -> spawnFlag("spawn.enabled", false) ? "true" : "false";
            case "spawn_world" -> spawnText("spawn.location.world", "");
            case "spawn_x" -> String.valueOf(spawnNumber("spawn.location.x", 0.0));
            case "spawn_y" -> String.valueOf(spawnNumber("spawn.location.y", 0.0));
            case "spawn_z" -> String.valueOf(spawnNumber("spawn.location.z", 0.0));
            case "firstspawn_enabled" -> spawnFlag("first-spawn.enabled", false) ? "true" : "false";
            case "firstspawn_world" -> spawnText("first-spawn.location.world", "");
            case "firstspawn_x" -> String.valueOf(spawnNumber("first-spawn.location.x", 0.0));
            case "firstspawn_y" -> String.valueOf(spawnNumber("first-spawn.location.y", 0.0));
            case "firstspawn_z" -> String.valueOf(spawnNumber("first-spawn.location.z", 0.0));

            // Teleport module
            case "teleport_tpa_autoaccept" -> player != null && plugin.getTPAManager() != null
                    && plugin.getTPAManager().isTpAutoEnabled(player.getUniqueId()) ? "true" : "false";

            // Join Items module
            case "joinitems_count" -> plugin.getJoinItemsConfig() != null
                    && plugin.getJoinItemsConfig().getConfigurationSection("items") != null
                    ? String.valueOf(plugin.getJoinItemsConfig().getConfigurationSection("items").getKeys(false).size())
                    : "0";

            // Vanish module
            case "vanish_cansee" -> vanishCanSee(player);

            // Menu module
            case "menu_count" -> plugin.getMenuManager() != null
                    ? String.valueOf(plugin.getMenuManager().getMenuNames().size()) : "0";

            // Team module (static placeholders)
            case "team_names" -> player != null && plugin.getTeamManager() != null
                    // P2: sorted for determinism (getPlayerTeams order is not stable with max-teams>1).
                    ? String.join(", ", plugin.getTeamManager().getPlayerTeams(player.getUniqueId()).stream().sorted().toList()) : "";
            case "team_count" -> player != null && plugin.getTeamManager() != null
                    ? String.valueOf(plugin.getTeamManager().getPlayerTeams(player.getUniqueId()).size()) : "0";
            case "team_primary" -> player != null && plugin.getTeamManager() != null
                    // P2: never stream().findFirst() on the raw set — its iteration order is
                    // non-deterministic (backed by ConcurrentHashMap); sort by team id first.
                    ? plugin.getTeamManager().getPlayerTeams(player.getUniqueId()).stream().sorted().findFirst().orElse("") : "";
            case "team_pending_invites" -> player != null && plugin.getTeamManager() != null
                    ? String.valueOf(plugin.getTeamManager().getPendingInvites(player.getUniqueId()).size()) : "0";
            case "teams_count" -> plugin.getTeamManager() != null
                    ? String.valueOf(plugin.getTeamManager().getTeams().size()) : "0";

            // Team module (parameterized: %plainbase_team_role_<team>% / %plainbase_team_members_<team>%)
            default -> handleTeamParameterized(player, params);
        };
    }

    private String vanishCanSee(Player player) {
        if (player == null || plugin.getVanishManager() == null) return "false";
        return plugin.getVanishManager().canSeeVanished(player) ? "true" : "false";
    }

    /**
     * Locked spawn.yml reads (Spawn-Muster): single snapshot per call, read
     * under the instance monitor. Leaf-only (no configLock nesting).
     */
    private boolean spawnFlag(String path, boolean def) {
        try {
            FileConfiguration sc = plugin.getSpawnConfig();
            if (sc == null) return def;
            synchronized (sc) {
                return sc.getBoolean(path, def);
            }
        } catch (Exception e) {
            return def;
        }
    }

    private String spawnText(String path, String def) {
        try {
            FileConfiguration sc = plugin.getSpawnConfig();
            if (sc == null) return def;
            synchronized (sc) {
                String v = sc.getString(path, def);
                return v != null ? v : def;
            }
        } catch (Exception e) {
            return def;
        }
    }

    private double spawnNumber(String path, double def) {
        try {
            FileConfiguration sc = plugin.getSpawnConfig();
            if (sc == null) return def;
            synchronized (sc) {
                return sc.getDouble(path, def);
            }
        } catch (Exception e) {
            return def;
        }
    }

    private String handleTeamParameterized(Player player, String params) {
        if (plugin.getTeamManager() == null) return null;

        // Prefix match is case-insensitive, but the team-ID suffix keeps its
        // original case (team IDs are case-sensitive; lowercasing the whole
        // params string broke lookups for mixed-case IDs).
        if (params.regionMatches(true, 0, "team_role_", 0, "team_role_".length())) {
            String teamId = params.substring("team_role_".length());
            if (player == null) return "none";
            var role = plugin.getTeamManager().getRole(player.getUniqueId(), teamId);
            return role != null ? role.name().toLowerCase(Locale.ROOT) : "none";
        }

        if (params.regionMatches(true, 0, "team_members_", 0, "team_members_".length())) {
            String teamId = params.substring("team_members_".length());
            return String.valueOf(plugin.getTeamManager().getMembers(teamId).size());
        }

        return null;
    }
}