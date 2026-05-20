package de.jgaertig.plainBase.global;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class GlobalListener implements Listener {

    private final PlainBase plugin;

    public GlobalListener(PlainBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onAdminJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().isOp()) return;

        double currentMain = plugin.getConfig().getDouble("version", 0.0);
        double latestMain = plugin.getLatestVersions().getOrDefault("config.yml", 0.0);

        if (currentMain < latestMain) {
            sendWarning(event, "config.yml", currentMain, latestMain);
        }

        plugin.getConfigs().forEach((name, config) -> {
            double current = config.getDouble("version", 0.0);
            double latest = plugin.getLatestVersions().getOrDefault(name, 0.0);

            if (current < latest) {
                sendWarning(event, "modules/" + name, current, latest);
            }
        });
    }

    private void sendWarning(PlayerJoinEvent event, String name, double current, double latest) {
        event.getPlayer().sendMessage(plugin.getMiniMessage().deserialize(
                "<red><bold>[PlainBase]</bold> Your <yellow>" + name + "</yellow> is outdated! " +
                        "<gray>(v" + current + " < v" + latest + ")"
        ));
    }
}