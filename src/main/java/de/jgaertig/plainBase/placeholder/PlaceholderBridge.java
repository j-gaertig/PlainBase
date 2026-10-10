package de.jgaertig.plainBase.placeholder;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

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

        // The result is usually deserialized as MiniMessage by the caller —
        // escape the player name first so a name like "<red>" can never
        // inject formatting or click events (same pattern as
        // MenuManager#applyPlaceholdersSafe).
        String result = text;
        if (player != null) {
            String safeName;
            try {
                safeName = MiniMessage.miniMessage().escapeTags(player.getName());
            } catch (Exception e) {
                safeName = player.getName();
            }
            result = text.replace("%player%", safeName);
        }

        // A present-but-disabled PlaceholderAPI must be treated like a missing
        // one: its classes may be visible while the plugin itself is off.
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (player != null && papi != null && papi.isEnabled()) {
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