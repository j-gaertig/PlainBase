package de.jgaertig.plainBase.global;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class GlobalListener implements Listener {

    private final PlainBase plugin;

    public GlobalListener(PlainBase plugin) { // Konstruktor Name korrigiert
        this.plugin = plugin;
    }

    @EventHandler
    public void onAdminJoin(PlayerJoinEvent event) {
        if (event.getPlayer().isOp()) {

            if (plugin.getConfig().getDouble("version", 0.0) < plugin.getLatestConfigV()) {
                event.getPlayer().sendMessage(plugin.getMiniMessage().deserialize(
                        "<red><bold>[PlainBase]</bold> Your <yellow>config.yml</yellow> is outdated! " +
                                "Please update it to version " + plugin.getLatestConfigV() + " from GitHub."
                ));
            }

            if (plugin.getSpawnConfig().getDouble("version", 0.0) < plugin.getLatestSpawnV()) {
                event.getPlayer().sendMessage(plugin.getMiniMessage().deserialize(
                        "<red><bold>[PlainBase]</bold> Your <yellow>spawn.yml</yellow> is outdated! " +
                                "Please update it to version " + plugin.getLatestSpawnV() + " from GitHub."
                ));
            }

            if (plugin.getJoinItemsConfig().getDouble("version", 0.0) < plugin.getLatestJoinItemsV()) {
                event.getPlayer().sendMessage(plugin.getMiniMessage().deserialize(
                        "<red><bold>[PlainBase]</bold> Your <yellow>joinitems.yml</yellow> is outdated! " +
                                "Please update it to version " + plugin.getLatestJoinItemsV() + " from GitHub."
                ));
            }

            if (plugin.getJoinMessagesConfig().getDouble("version", 0.0) < plugin.getLatestJoinMessagesV()) {
                event.getPlayer().sendMessage(plugin.getMiniMessage().deserialize(
                        "<red><bold>[PlainBase]</bold> Your <yellow>joinmessages.yml</yellow> is outdated! " +
                                "Please update it to version " + plugin.getLatestJoinMessagesV() + " from GitHub."
                ));
            }

        }
    }
}