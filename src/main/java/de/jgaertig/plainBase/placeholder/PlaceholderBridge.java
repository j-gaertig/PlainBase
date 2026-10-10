package de.jgaertig.plainBase.placeholder;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Isolated bridge to PlaceholderAPI so that PlainBase itself never links
 * against PlaceholderAPI classes. This class is plain (no PlaceholderAPI
 * extends) — the PAPI reference is only resolved when apply() actually runs
 * behind the "is PlaceholderAPI installed?" guard, so a missing PlaceholderAPI
 * can never cause a NoClassDefFoundError on this plugin.
 * <p>
 * P3 note: the PlaceholderAPI expansion instance cache deliberately lives in
 * {@link PlainBaseExpansion} (which already links PAPI), NOT here — caching it
 * here would force this bridge to reference PAPI types and break the isolation
 * above. Use {@link PlainBaseExpansion#unregisterCached()} to unregister the
 * same instance that was registered (null-guarded); PlainBase currently
 * unregisters via a fresh instance, which works (unregister is by identifier)
 * but is more fragile.
 */
public final class PlaceholderBridge {

    private PlaceholderBridge() {
    }

    /**
     * Applies PlaceholderAPI placeholders to the given text, replacing
     * %player% as well. Falls back to the raw text when PlaceholderAPI is
     * not installed. Catches Throwable so a broken PlaceholderAPI can never
     * break a server thread. A null player is handled safely (no %player%
     * replacement, no PlaceholderAPI call) — some callers legitimately have
     * no player context (console, broadcasts).
     */
    public static String apply(Player player, String text) {
        if (text == null || text.isEmpty()) return text;

        String result = player != null ? text.replace("%player%", player.getName()) : text;

        if (player != null && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
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