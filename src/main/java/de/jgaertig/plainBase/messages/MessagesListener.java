package de.jgaertig.plainBase.messages;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;

public class MessagesListener implements Listener {

    private final PlainBase plugin;

    public MessagesListener(PlainBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Defense-in-depth: a vanished player must never get a join message,
        // even if this handler runs before VanishListener. (VanishListener on
        // HIGHEST is the primary guard, including the persist-on-rejoin case.)
        // Explicit null-guards: a missing manager or config must simply skip
        // this check, never rely on the catch below.
        try {
            var vanishManager = plugin.getVanishManager();
            var vanishConfig = plugin.getVanishConfig();
            if (vanishManager != null && vanishConfig != null
                    && (vanishManager.isVanished(player) || vanishManager.hasPersistedVanish(player.getUniqueId()))
                    && vanishConfig.getBoolean("vanish.hide-join-quit-messages", true)) {
                event.joinMessage(null);
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to check vanish join state for " + player.getName() + ": " + e.getMessage());
        }

        var config = plugin.getMessagesConfig();
        if (config == null) return;

        // join message (empty string = disabled)
        String path = player.hasPlayedBefore() ? "messages.join" : "messages.first-join";
        String raw = config.getString(path, "");
        if (raw == null || raw.isBlank()) {
            event.joinMessage(null);
        } else {
            try {
                // B2 MiniMessage-injection guard: the player name is escaped
                // BEFORE PlaceholderAPI/deserialize (same pattern as
                // MenuManager#applyPlaceholdersSafe), so a name like "<red>"
                // can never inject formatting or click events.
                event.joinMessage(plugin.getMiniMessage().deserialize(applyPlaceholdersSafe(player, raw)));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to format join message: " + e.getMessage());
                event.joinMessage(null);
            }
        }

        // motd
        if (config.getBoolean("motd.enabled", false)) {
            List<String> motdLines = config.getStringList("motd.lines");

            for (String line : motdLines) {
                try {
                    player.sendMessage(plugin.getMiniMessage().deserialize(applyPlaceholdersSafe(player, line)));
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to format motd line: " + e.getMessage());
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Defense-in-depth, mirrors onJoin: vanished quits stay silent.
        // Explicit null-guards, never rely on the catch below.
        try {
            var vanishManager = plugin.getVanishManager();
            var vanishConfig = plugin.getVanishConfig();
            if (vanishManager != null && vanishConfig != null
                    && (vanishManager.isVanished(player) || vanishManager.hasPersistedVanish(player.getUniqueId()))
                    && vanishConfig.getBoolean("vanish.hide-join-quit-messages", true)) {
                event.quitMessage(null);
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to check vanish quit state for " + player.getName() + ": " + e.getMessage());
        }

        var config = plugin.getMessagesConfig();
        if (config == null) return;
        String raw = config.getString("messages.quit", "");
        if (raw == null || raw.isBlank()) {
            event.quitMessage(null);
        } else {
            try {
                // B2: same escaping as onJoin (see above).
                event.quitMessage(plugin.getMiniMessage().deserialize(applyPlaceholdersSafe(player, raw)));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to format quit message: " + e.getMessage());
                event.quitMessage(null);
            }
        }
    }

    /**
     * B2 MiniMessage-injection guard (pattern from MenuManager:277-283): the
     * viewer's own name is substituted escaped BEFORE PlaceholderAPI runs, so
     * a name containing MiniMessage tags can never inject formatting or click
     * events into join/quit/motd text. The admin-authored template itself
     * stays raw on purpose (MiniMessage by design).
     */
    private String applyPlaceholdersSafe(Player player, String template) {
        if (template == null) return null;
        String pre = player != null
                ? template.replace("%player%", plugin.getMiniMessage().escapeTags(player.getName()))
                : template;
        return plugin.applyPlaceholders(player, pre);
    }
}