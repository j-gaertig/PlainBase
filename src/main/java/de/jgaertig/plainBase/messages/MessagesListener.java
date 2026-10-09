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
        try {
            if (plugin.getVanishManager() != null
                    && (plugin.getVanishManager().isVanished(player) || plugin.getVanishManager().hasPersistedVanish(player.getUniqueId()))
                    && plugin.getVanishConfig().getBoolean("vanish.hide-join-quit-messages", true)) {
                event.joinMessage(null);
                return;
            }
        } catch (Exception ignored) {
        }

        var config = plugin.getMessagesConfig();

        // join message (empty string = disabled)
        String path = player.hasPlayedBefore() ? "messages.join" : "messages.first-join";
        String raw = config.getString(path, "");
        if (raw == null || raw.isBlank()) {
            event.joinMessage(null);
        } else {
            try {
                event.joinMessage(plugin.getMiniMessage().deserialize(plugin.applyPlaceholders(player, raw)));
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
                    player.sendMessage(plugin.getMiniMessage().deserialize(plugin.applyPlaceholders(player, line)));
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
        try {
            if (plugin.getVanishManager() != null
                    && (plugin.getVanishManager().isVanished(player) || plugin.getVanishManager().hasPersistedVanish(player.getUniqueId()))
                    && plugin.getVanishConfig().getBoolean("vanish.hide-join-quit-messages", true)) {
                event.quitMessage(null);
                return;
            }
        } catch (Exception ignored) {
        }

        var config = plugin.getMessagesConfig();
        String raw = config.getString("messages.quit", "");
        if (raw == null || raw.isBlank()) {
            event.quitMessage(null);
        } else {
            try {
                event.quitMessage(plugin.getMiniMessage().deserialize(plugin.applyPlaceholders(player, raw)));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to format quit message: " + e.getMessage());
                event.quitMessage(null);
            }
        }
    }
}