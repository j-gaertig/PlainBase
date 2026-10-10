package de.jgaertig.plainBase.placeholder;

import de.jgaertig.plainBase.PlainBase;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
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
     * P3: cache of the last constructed expansion instance. PlainBase#onDisable
     * currently unregisters via {@code new PlainBaseExpansion(this).unregister()}
     * (works because PlaceholderAPI unregisters by identifier, but same-instance
     * unregister is more robust). This field keeps the live instance so future
     * code can unregister exactly the registered object; the static helper below
     * is null-guarded and never throws.
     */
    private static volatile PlainBaseExpansion lastInstance;

    public PlainBaseExpansion(PlainBase plugin) {
        this.plugin = plugin;
        lastInstance = this;
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
        } catch (Exception e) {
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
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (params == null || params.isEmpty()) return null;
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "version" -> plugin.getPluginMeta().getVersion();
            case "vanished" -> player != null && plugin.getVanishManager() != null
                    && plugin.getVanishManager().isVanished(player) ? "true" : "false";
            case "moderation_bancount" -> player != null && plugin.getBanManager() != null
                    ? String.valueOf(plugin.getBanManager().getBanCount(player.getUniqueId())) : "0";
            case "moderation_kickcount" -> player != null && plugin.getBanManager() != null
                    ? String.valueOf(plugin.getBanManager().getKickCount(player.getUniqueId())) : "0";
            case "moderation_banned" -> player != null && plugin.getBanManager() != null
                    && plugin.getBanManager().getActiveBan(player.getUniqueId()).isPresent() ? "true" : "false";

            // Spawn module
            case "spawn_enabled" -> plugin.getSpawnConfig() != null
                    && plugin.getSpawnConfig().getBoolean("spawn.enabled", false) ? "true" : "false";
            case "spawn_world" -> plugin.getSpawnConfig() != null
                    ? plugin.getSpawnConfig().getString("spawn.location.world", "") : "";
            case "spawn_x" -> plugin.getSpawnConfig() != null
                    ? String.valueOf(plugin.getSpawnConfig().getDouble("spawn.location.x", 0.0)) : "0.0";
            case "spawn_y" -> plugin.getSpawnConfig() != null
                    ? String.valueOf(plugin.getSpawnConfig().getDouble("spawn.location.y", 0.0)) : "0.0";
            case "spawn_z" -> plugin.getSpawnConfig() != null
                    ? String.valueOf(plugin.getSpawnConfig().getDouble("spawn.location.z", 0.0)) : "0.0";
            case "firstspawn_enabled" -> plugin.getSpawnConfig() != null
                    && plugin.getSpawnConfig().getBoolean("first-spawn.enabled", false) ? "true" : "false";
            case "firstspawn_world" -> plugin.getSpawnConfig() != null
                    ? plugin.getSpawnConfig().getString("first-spawn.location.world", "") : "";
            case "firstspawn_x" -> plugin.getSpawnConfig() != null
                    ? String.valueOf(plugin.getSpawnConfig().getDouble("first-spawn.location.x", 0.0)) : "0.0";
            case "firstspawn_y" -> plugin.getSpawnConfig() != null
                    ? String.valueOf(plugin.getSpawnConfig().getDouble("first-spawn.location.y", 0.0)) : "0.0";
            case "firstspawn_z" -> plugin.getSpawnConfig() != null
                    ? String.valueOf(plugin.getSpawnConfig().getDouble("first-spawn.location.z", 0.0)) : "0.0";

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
            default -> handleTeamParameterized(player, params.toLowerCase(Locale.ROOT));
        };
    }

    private String vanishCanSee(Player player) {
        if (player == null || plugin.getVanishManager() == null) return "false";
        return plugin.getVanishManager().canSeeVanished(player) ? "true" : "false";
    }

    private String handleTeamParameterized(Player player, String params) {
        if (plugin.getTeamManager() == null) return null;

        if (params.startsWith("team_role_")) {
            String teamId = params.substring("team_role_".length());
            if (player == null) return "none";
            var role = plugin.getTeamManager().getRole(player.getUniqueId(), teamId);
            return role != null ? role.name().toLowerCase(Locale.ROOT) : "none";
        }

        if (params.startsWith("team_members_")) {
            String teamId = params.substring("team_members_".length());
            return String.valueOf(plugin.getTeamManager().getMembers(teamId).size());
        }

        return null;
    }
}