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
            default -> null;
        };
    }
}