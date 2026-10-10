package de.jgaertig.plainBase.messages;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.UUID;

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

        // Player#hasPlayedBefore() can hit disk — never call it on the join
        // thread. Snapshot the cheap config strings sync, suppress the vanilla
        // join line now, and resolve first-join vs returning off-thread
        // (SpawnListener 55-58 pattern). The message is broadcast delayed
        // instead of blocking the join.
        String joinRaw = config.getString("messages.join", "");
        String firstJoinRaw = config.getString("messages.first-join", "");
        event.joinMessage(null);
        UUID uuid = player.getUniqueId();
        // Joiner name captured sync: applyPlaceholdersSafe()/getName() must
        // never run off-thread on Folia.
        final String joinerName = player.getName();
        // MOTD snapshot sync, delivery deferred below (never sync on join).
        final boolean motdEnabled = config.getBoolean("motd.enabled", false);
        final List<String> motdLines = motdEnabled ? List.copyOf(config.getStringList("motd.lines")) : List.of();
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                boolean played;
                try {
                    played = Bukkit.getOfflinePlayer(uuid).hasPlayedBefore();
                } catch (Exception e) {
                    // Fail closed as a returning player (same as SpawnListener):
                    // never fire first-join for a stranger on lookup failure.
                    played = true;
                }
                String raw = played ? joinRaw : firstJoinRaw;
                if (raw == null || raw.isBlank()) return;
                try {
                    // Global-scheduler fan-out (not the joiner's entity loop):
                    // broadcasting to every online player must not run on the
                    // joiner's entity thread on Folia.
                    Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                        try {
                            if (!player.isOnline()) return;
                            Component msg;
                            try {
                                // B2 MiniMessage-injection guard: the player name is escaped
                                // BEFORE PlaceholderAPI/deserialize (same pattern as
                                // MenuManager#applyPlaceholdersSafe), so a name like "<red>"
                                // can never inject formatting or click events.
                                // Uses the sync-captured joiner name, never the live
                                // player off-thread (see applyPlaceholdersSafeOffThread).
                                msg = plugin.getMiniMessage().deserialize(applyPlaceholdersSafeOffThread(joinerName, raw));
                            } catch (Exception e) {
                                plugin.getLogger().warning("Failed to format join message: " + e.getMessage());
                                return;
                            }
                            // Event is long finished — broadcast the same component
                            // to everyone (joinMessage semantics), plus console.
                            // Per-recipient entity-scheduler dispatch (same pattern
                            // as BroadcastManager): sending from the global
                            // thread would throw for some recipients on Folia.
                            try {
                                final Component broadcast = msg;
                                for (Player online : Bukkit.getOnlinePlayers()) {
                                    final Player recipient = online;
                                    if (recipient == null) continue;
                                    try {
                                        recipient.getScheduler().run(plugin, send -> {
                                            try {
                                                if (!recipient.isOnline()) return;
                                                recipient.sendMessage(broadcast);
                                            } catch (Exception ignored) {
                                            }
                                        }, null);
                                    } catch (Exception ignored) {
                                    }
                                }
                                try {
                                    Bukkit.getConsoleSender().sendMessage(broadcast);
                                } catch (Exception ignored) {
                                }
                            } catch (Exception e) {
                                plugin.getLogger().fine("Failed to broadcast join message: " + e.getMessage());
                            }
                        } catch (Exception e) {
                            plugin.getLogger().fine("Failed to send delayed join message: " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    plugin.getLogger().fine("Failed to schedule delayed join message: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to resolve join message: " + e.getMessage());
        }

        // MOTD in the deferred path: never send sync on the join thread —
        // hop onto the player's entity scheduler so Folia thread rules hold.
        if (motdEnabled && !motdLines.isEmpty()) {
            try {
                player.getScheduler().run(plugin, t -> {
                    try {
                        if (!player.isOnline()) return;
                        for (String line : motdLines) {
                            try {
                                player.sendMessage(plugin.getMiniMessage().deserialize(applyPlaceholdersSafe(player, line)));
                            } catch (Exception e) {
                                plugin.getLogger().warning("Failed to format motd line: " + e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().fine("Failed to send motd: " + e.getMessage());
                    }
                }, null);
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to schedule motd: " + e.getMessage());
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

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        Player player = event.getPlayer();

        // Defense-in-depth, mirrors onQuit: a kicked vanished player stays
        // silent. Non-vanished kicks are left untouched (no custom kick
        // message configured — only suppression, never formatting).
        try {
            var vanishManager = plugin.getVanishManager();
            var vanishConfig = plugin.getVanishConfig();
            if (vanishManager != null && vanishConfig != null
                    && (vanishManager.isVanished(player) || vanishManager.hasPersistedVanish(player.getUniqueId()))
                    && vanishConfig.getBoolean("vanish.hide-join-quit-messages", true)) {
                event.leaveMessage(null);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to check vanish kick state for " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * B2 MiniMessage-injection guard (pattern from MenuManager:277-283): the
     * viewer's own name is substituted escaped BEFORE PlaceholderAPI runs, so
     * a name containing MiniMessage tags can never inject formatting or click
     * events into join/quit/motd text. The admin-authored template itself
     * stays raw on purpose (MiniMessage by design). Sync/entity-thread only:
     * touches player.getName() and resolves PAPI with the live player.
     */
    private String applyPlaceholdersSafe(Player player, String template) {
        if (template == null) return null;
        String pre = player != null
                ? template.replace("%player%", plugin.getMiniMessage().escapeTags(player.getName()))
                : template;
        return plugin.applyPlaceholders(player, pre);
    }

    /**
     * Off-thread variant for the async join path: works only with the
     * sync-captured name, never with the live player object. PlaceholderBridge
     * #apply(player, text) calls player.getName() and PAPI setPlaceholders
     * with the live player — both must never run off-thread on Folia — so PAPI
     * is deliberately skipped here via a null player (see PlaceholderBridge
     * #apply): only the escaped %player% substitution plus non-PAPI text
     * survive. MOTD/quit keep the live-player variant above on their own
     * (entity/sync) threads.
     */
    private String applyPlaceholdersSafeOffThread(String capturedName, String template) {
        if (template == null) return null;
        String pre = capturedName != null
                ? template.replace("%player%", plugin.getMiniMessage().escapeTags(capturedName))
                : template;
        return plugin.applyPlaceholders(null, pre);
    }
}