package de.jgaertig.plainBase.placeholder;

import de.jgaertig.plainBase.PlainBase;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion providing %plainbase_*% placeholders.
 * Only loaded when PlaceholderAPI is installed (soft dependency).
 */
public class PlainBaseExpansion extends PlaceholderExpansion {

    private final PlainBase plugin;

    public PlainBaseExpansion(PlainBase plugin) {
        this.plugin = plugin;
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
        return switch (params.toLowerCase()) {
            case "version" -> plugin.getPluginMeta().getVersion();
            case "vanished" -> player != null && plugin.getVanishManager() != null
                    && plugin.getVanishManager().isVanished(player) ? "true" : "false";
            case "moderation_bancount" -> player != null && plugin.getBanManager() != null
                    ? String.valueOf(plugin.getBanManager().getBanCount(player.getUniqueId())) : "0";
            case "moderation_kickcount" -> player != null && plugin.getBanManager() != null
                    ? String.valueOf(plugin.getBanManager().getKickCount(player.getUniqueId())) : "0";
            case "moderation_banned" -> player != null && plugin.getBanManager() != null
                    && plugin.getBanManager().getActiveBan(player.getUniqueId()).isPresent() ? "true" : "false";
            default -> null;
        };
    }
}