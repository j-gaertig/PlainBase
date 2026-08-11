package de.jgaertig.plainBase.placeholder;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Isolated bridge to PlaceholderAPI so that PlainBase itself never links
 * against PlaceholderAPI classes. This class is plain (no PlaceholderAPI
 * extends) — the PAPI reference is only resolved when apply() actually runs
 * behind the "is PlaceholderAPI installed?" guard, so a missing PlaceholderAPI
 * can never cause a NoClassDefFoundError on this plugin.
 */
public final class PlaceholderBridge {

    private PlaceholderBridge() {
    }

    /**
     * Applies PlaceholderAPI placeholders to the given text, replacing
     * %player% as well. Falls back to the raw text when PlaceholderAPI is
     * not installed. Catches Throwable so a broken PlaceholderAPI can never
     * break a server thread.
     */
    public static String apply(Player player, String text) {
        if (text == null || text.isEmpty()) return text;

        String result = text.replace("%player%", player.getName());

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                result = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, result);
            } catch (Throwable e) {
                // NoClassDefFoundError / NoSuchMethodError when PlaceholderAPI
                // is broken — never let this bubble up into server threads.
            }
        }
        return result;
    }
}