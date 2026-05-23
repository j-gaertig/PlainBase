package de.jgaertig.plainBase.messages;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class BroadcastManager {

    private final PlainBase plugin;
    private final List<ScheduledTask> activeTasks = new ArrayList<>();

    public BroadcastManager(PlainBase plugin) {
        this.plugin = plugin;
    }

    public void startBroadcasts() {
        ConfigurationSection section = plugin.getMessagesConfig().getConfigurationSection("broadcasts");
        if (section == null || !section.getBoolean("enabled", false)) return;

        for (String key : section.getKeys(false)) {
            if (key.equals("enabled")) continue;

            String text = section.getString(key + ".text");
            long cooldownSeconds = section.getLong(key + ".cooldown", 60);
            long ticks = Math.max(1, cooldownSeconds * 20); // Mindestens 1 Tick

            ScheduledTask task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, (t) -> {
                Component message = plugin.getMiniMessage().deserialize(text);

                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.sendMessage(message);
                }
            }, ticks, ticks);

            activeTasks.add(task);
        }
    }

    public void stopBroadcasts() {
        for (ScheduledTask task : activeTasks) {
            task.cancel();
        }
        activeTasks.clear();
    }
}