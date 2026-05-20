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
        var config = plugin.getMessagesConfig();

        // join message
        String path = player.hasPlayedBefore() ? "messages.join" : "messages.first-join";
        String joinMsg = config.getString(path, "");
        if (!joinMsg.isEmpty()) {
            event.joinMessage(plugin.getMiniMessage().deserialize(joinMsg.replace("%player%", player.getName())));
        }

        // motd
        if (config.getBoolean("motd.enabled", false)) {
            List<String> motdLines = config.getStringList("motd.lines");

            for (String line : motdLines) {
                player.sendMessage(plugin.getMiniMessage().deserialize(line.replace("%player%", player.getName())));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var config = plugin.getMessagesConfig();
        String quitMsg = config.getString("messages.quit", "");
        if (!quitMsg.isEmpty()) {
            event.quitMessage(plugin.getMiniMessage().deserialize(quitMsg.replace("%player%", event.getPlayer().getName())));
        }
    }
}