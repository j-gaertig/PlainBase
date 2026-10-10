package de.jgaertig.plainBase.spawn;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

public class SpawnListener implements Listener {

    private final PlainBase plugin;

    public SpawnListener(PlainBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        try {
            Player player = event.getPlayer();
            FileConfiguration config = plugin.getSpawnConfig();
            if (config == null) return;

            // Locked reads: writers (SetSpawn/DisableSpawn) mutate under
            // synchronized(config); join runs on a different thread.
            final boolean firstSpawnEnabled;
            final boolean spawnEnabled;
            synchronized (config) {
                firstSpawnEnabled = config.getBoolean("first-spawn.enabled", false);
                spawnEnabled = config.getBoolean("spawn.enabled", false);
            }

            // NOTE: join only — there is intentionally no respawn hook here
            // (that would be a new feature, not a bugfix).
            if (!firstSpawnEnabled && !spawnEnabled) return;

            // Player#hasPlayedBefore() can hit disk — never call it on the
            // join (region) thread. Async hop, then schedule back on the
            // entity thread: the delayed teleport below is unchanged, only the
            // first-join check moved off-thread. No behaviour change — the
            // entity task re-checks isOnline (quit mid-hop no-ops) and the
            // teleport path still reads the config snapshot fresh. On lookup
            // failure fail closed as a returning player (regular spawn path)
            // rather than firing first-spawn for a stranger.
            final UUID uuid = player.getUniqueId();
            final boolean firstEnabled = firstSpawnEnabled;
            final boolean spawnOnJoin = spawnEnabled;
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                boolean played;
                try {
                    played = Bukkit.getOfflinePlayer(uuid).hasPlayedBefore();
                } catch (RuntimeException e) {
                    played = true;
                }
                boolean firstJoin = !played;
                try {
                    player.getScheduler().run(plugin, t -> {
                        try {
                            if (!player.isOnline()) return;
                            if (firstJoin && firstEnabled) {
                                player.getScheduler().runDelayed(plugin, t2 -> teleportToConfigLocation(player, "first-spawn.location"), null, 1L);
                                return; // Wenn First-Spawn, dann kein normaler Spawn Teleport nötig
                            }
                            if (spawnOnJoin) {
                                player.getScheduler().runDelayed(plugin, t2 -> teleportToConfigLocation(player, "spawn.location"), null, 1L);
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to handle spawn join teleport for " + player.getName() + ": " + e.getMessage());
                        }
                    }, null);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to schedule spawn join teleport for " + player.getName() + ": " + e.getMessage());
                }
            });
        } catch (Exception e) {
            // A failing spawn teleport must never break the join event itself.
            plugin.getLogger().warning("Failed to handle spawn join teleport: " + e.getMessage());
        }
    }

    private void teleportToConfigLocation(Player player, String path) {
        teleportToConfigLocation(player, path, !"spawn.location".equals(path));
    }

    /**
     * @param fallbackToSpawn when the first-spawn location is unusable, fall
     *                        back to the regular spawn location instead of
     *                        stranding the player. Never recurses: the spawn
     *                        path itself passes false.
     */
    private void teleportToConfigLocation(Player player, String path, boolean fallbackToSpawn) {
        try {
            if (player == null || !player.isOnline()) return;
            FileConfiguration config = plugin.getSpawnConfig();
            if (config == null) return;
            // Consistent snapshot: writers mutate under synchronized(config).
            final String worldName;
            final double rawX;
            final double rawY;
            final double rawZ;
            final double rawYaw;
            final double rawPitch;
            final boolean spawnEnabled;
            synchronized (config) {
                worldName = config.getString(path + ".world");
                rawX = config.getDouble(path + ".x");
                rawY = config.getDouble(path + ".y");
                rawZ = config.getDouble(path + ".z");
                rawYaw = config.getDouble(path + ".yaw");
                rawPitch = config.getDouble(path + ".pitch");
                spawnEnabled = config.getBoolean("spawn.enabled", false);
            }
            if (worldName == null) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn location is not set correctly. Contact an admin!"));
                plugin.getLogger().warning("Spawn teleport failed for " + player.getName() + ": missing world at '" + path + ".world'.");
                tryFallback(player, fallbackToSpawn && spawnEnabled);
                return;
            }

            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                // worldName comes from config and may contain MiniMessage-looking
                // characters ('<', '>'): escape it so it cannot break parsing
                // (or inject formatting) into this message.
                try {
                    String safeWorld = plugin.getMiniMessage().escapeTags(worldName);
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn world '" + safeWorld + "' not found. Contact an admin!"));
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to format spawn message: " + e.getMessage());
                    player.sendMessage(Component.text("Spawn world '" + worldName + "' not found. Contact an admin!"));
                }
                plugin.getLogger().warning("Spawn teleport failed for " + player.getName() + ": world '" + worldName + "' not found (path '" + path + "').");
                tryFallback(player, fallbackToSpawn && spawnEnabled);
                return;
            }

            Location loc;
            if (!Double.isFinite(rawX) || !Double.isFinite(rawY) || !Double.isFinite(rawZ)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn location is not set correctly. Contact an admin!"));
                plugin.getLogger().warning("Spawn teleport failed for " + player.getName() + ": non-finite coordinates at '" + path + "'.");
                tryFallback(player, fallbackToSpawn && spawnEnabled);
                return;
            }
            float yaw = Double.isFinite(rawYaw) ? (float) rawYaw : 0f;
            float pitch = Double.isFinite(rawPitch) ? (float) rawPitch : 0f;

            if (rawY < world.getMinHeight() || rawY >= world.getMaxHeight()) {
                plugin.getLogger().warning("Spawn teleport for " + player.getName() + ": Y=" + rawY
                        + " out of bounds [" + world.getMinHeight() + "," + world.getMaxHeight()
                        + ") at '" + path + "', falling back to world spawn.");
                loc = world.getSpawnLocation().clone();
            } else {
                loc = new Location(world, rawX, rawY, rawZ, yaw, pitch);
            }
            player.teleportAsync(loc).thenAccept(success -> {
                // thenAccept runs off the entity thread: hop back onto the
                // EntityScheduler before any sendMessage/fallback scheduling
                // (Folia scheduler retained).
                try {
                    player.getScheduler().run(plugin, t -> {
                        if (!Boolean.TRUE.equals(success) && player.isOnline()) {
                            // First-spawn failed (e.g. target chunk unavailable): fall
                            // back to the regular spawn instead of stranding the
                            // player. Now on the entity thread — re-check
                            // online state and never throw.
                            if (fallbackToSpawn) {
                                try {
                                    FileConfiguration cfg = plugin.getSpawnConfig();
                                    boolean enabled = false;
                                    if (cfg != null) {
                                        synchronized (cfg) {
                                            enabled = cfg.getBoolean("spawn.enabled", false);
                                        }
                                    }
                                    if (enabled && player.isOnline()) {
                                        player.getScheduler().run(plugin, t2 -> teleportToConfigLocation(player, "spawn.location", false), null);
                                        return;
                                    }
                                } catch (Exception ex) {
                                    plugin.getLogger().warning("Spawn fallback failed for " + player.getName() + ": " + ex.getMessage());
                                }
                            }
                            try {
                                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport failed. Try again or contact an admin!"));
                            } catch (Exception ex) {
                                plugin.getLogger().warning("Failed to notify " + player.getName() + " about spawn teleport failure: " + ex.getMessage());
                            }
                        }
                    }, null);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Failed to schedule spawn teleport notify for " + player.getName() + ": " + ex.getMessage());
                }
            });
        } catch (Exception e) {
            // Runs on a delayed scheduler task after join: must never throw.
            plugin.getLogger().warning("Spawn teleport failed for "
                    + (player != null ? player.getName() : "<unknown>") + ": " + e.getMessage());
        }
    }

    /**
     * Fallback helper: tries the regular spawn location when the first-spawn
     * location turned out to be unusable. No-op unless explicitly allowed.
     */
    private void tryFallback(Player player, boolean allowed) {
        if (!allowed || player == null || !player.isOnline()) return;
        try {
            teleportToConfigLocation(player, "spawn.location", false);
        } catch (Exception e) {
            plugin.getLogger().warning("Spawn fallback failed for " + player.getName() + ": " + e.getMessage());
        }
    }
}
