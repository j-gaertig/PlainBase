package de.jgaertig.plainBase.messages;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class BroadcastManager {

    private final PlainBase plugin;
    private final List<ScheduledTask> activeTasks = new CopyOnWriteArrayList<>();

    public BroadcastManager(PlainBase plugin) {
        this.plugin = plugin;
    }

    public void startBroadcasts() {
        // Double-start guard: starting twice without a stop must never stack
        // duplicate repeating tasks (each start would otherwise add a full new
        // set of timers on top of the still-running ones).
        stopBroadcasts();
        var messagesConfig = plugin.getMessagesConfig();
        if (messagesConfig == null) return;
        ConfigurationSection section = messagesConfig.getConfigurationSection("broadcasts");
        if (section == null || !section.getBoolean("enabled", false)) return;

        for (String key : section.getKeys(false)) {
            if (key.equals("enabled")) continue;

            String text = section.getString(key + ".text");
            if (text == null || text.isBlank()) {
                plugin.getLogger().warning("Empty broadcast text for '" + key + "', skipping.");
                continue;
            }

            // Clamp like tpa.request_timeout: negative/huge values must never
            // leak into the scheduler delay (huge values could overflow ticks).
            long cooldownSeconds = Math.max(5, Math.min(86400, section.getLong(key + ".cooldown", 60)));
            long ticks = cooldownSeconds * 20L; // At least 5 seconds cooldown

            final String broadcastKey = key;
            final String broadcastText = text;
            ScheduledTask task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, (t) -> {
                try {
                    // Placeholders are resolved per recipient so player-specific
                    // placeholders (e.g. %player%) are correct for everyone.
                    // B2: name escaped before deserialize (see MessagesListener),
                    // so a name like "<red>" cannot inject MiniMessage.
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        final Player recipient = player;
                        if (recipient == null) continue;
                        try {
                            // Entity-scheduler dispatch (Folia): placeholder
                            // resolution and sending both run on the
                            // recipient's own entity thread — getName()/PAPI
                            // must never run on the global thread.
                            try {
                                recipient.getScheduler().run(plugin, send -> {
                                    try {
                                        if (!recipient.isOnline()) return;
                                        Component message = plugin.getMiniMessage()
                                                .deserialize(applyPlaceholdersSafe(recipient, broadcastText));
                                        recipient.sendMessage(message);
                                    } catch (Exception e) {
                                        plugin.getLogger().fine("Failed broadcast to " + recipient.getName() + ": " + e.getMessage());
                                    }
                                }, null);
                            } catch (Exception e) {
                                plugin.getLogger().fine("Failed to schedule broadcast '" + broadcastKey + "': " + e.getMessage());
                            }
                        } catch (Exception e) {
                            plugin.getLogger().fine("Failed to dispatch broadcast '" + broadcastKey + "': " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to send broadcast '" + broadcastKey + "': " + e.getMessage());
                }
            }, ticks, ticks);

            activeTasks.add(task);
        }
    }

    public void stopBroadcasts() {
        for (ScheduledTask task : activeTasks) {
            try {
                task.cancel();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to cancel broadcast task: " + e.getMessage());
            }
        }
        activeTasks.clear();
    }

    /**
     * B2 MiniMessage-injection guard (pattern from MenuManager): escape the
     * recipient's own name BEFORE PlaceholderAPI runs.
     */
    private String applyPlaceholdersSafe(Player player, String template) {
        if (template == null) return null;
        String pre = player != null
                ? template.replace("%player%", plugin.getMiniMessage().escapeTags(player.getName()))
                : template;
        return plugin.applyPlaceholders(player, pre);
    }
}