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

            // "text" may be a single string or a list of lines; skip (with a
            // warning) instead of NPE-ing the repeating task every interval
            // when the entry is malformed.
            List<String> lines = resolveBroadcastLines(section, key);
            if (lines.isEmpty()) {
                plugin.getLogger().warning("Broadcast '" + key + "' has no (valid) text — skipped. Add a 'text' entry in messages.yml.");
                continue;
            }

            long cooldownSeconds = section.getLong(key + ".cooldown", 60);
            long ticks = Math.max(1, cooldownSeconds * 20); // Mindestens 1 Tick

            ScheduledTask task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, (t) -> {
                List<Component> messages = new ArrayList<>(lines.size());
                for (String line : lines) {
                    messages.add(plugin.getMiniMessage().deserialize(line));
                }

                for (Player player : Bukkit.getOnlinePlayers()) {
                    for (Component message : messages) {
                        player.sendMessage(message);
                    }
                }
            }, ticks, ticks);

            activeTasks.add(task);
        }
    }

    private List<String> resolveBroadcastLines(ConfigurationSection section, String key) {
        List<String> lines = section.getStringList(key + ".text");
        if (!lines.isEmpty()) return lines;

        String single = section.getString(key + ".text");
        if (single != null && !single.isEmpty()) return List.of(single);

        return List.of();
    }

    public void stopBroadcasts() {
        for (ScheduledTask task : activeTasks) {
            task.cancel();
        }
        activeTasks.clear();
    }
}
