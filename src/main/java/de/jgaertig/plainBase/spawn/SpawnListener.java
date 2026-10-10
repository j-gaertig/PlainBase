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

            if (!player.hasPlayedBefore()) {
                if (config.getBoolean("first-spawn.enabled", false)) {
                    player.getScheduler().runDelayed(plugin, t -> teleportToConfigLocation(player, "first-spawn.location"), null, 1L);
                    return; // Wenn First-Spawn, dann kein normaler Spawn Teleport nötig
                }
            }

            if (config.getBoolean("spawn.enabled", false)) {
                player.getScheduler().runDelayed(plugin, t -> teleportToConfigLocation(player, "spawn.location"), null, 1L);
            }
        } catch (Exception e) {
            // A failing spawn teleport must never break the join event itself.
            plugin.getLogger().warning("Failed to handle spawn join teleport: " + e.getMessage());
        }
    }

    private void teleportToConfigLocation(Player player, String path) {
        try {
            if (player == null || !player.isOnline()) return;
            FileConfiguration config = plugin.getSpawnConfig();
            if (config == null) return;
            String worldName = config.getString(path + ".world");
            if (worldName == null) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn location is not set correctly. Contact an admin!"));
                plugin.getLogger().warning("Spawn teleport failed for " + player.getName() + ": missing world at '" + path + ".world'.");
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
                return;
            }

            Location loc;
            double rawX = config.getDouble(path + ".x");
            double rawY = config.getDouble(path + ".y");
            double rawZ = config.getDouble(path + ".z");
            if (!Double.isFinite(rawX) || !Double.isFinite(rawY) || !Double.isFinite(rawZ)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn location is not set correctly. Contact an admin!"));
                plugin.getLogger().warning("Spawn teleport failed for " + player.getName() + ": non-finite coordinates at '" + path + "'.");
                return;
            }
            double rawYaw = config.getDouble(path + ".yaw");
            double rawPitch = config.getDouble(path + ".pitch");
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
                if (!Boolean.TRUE.equals(success) && player.isOnline()) {
                    try {
                        player.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport failed. Try again or contact an admin!"));
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Failed to notify " + player.getName() + " about spawn teleport failure: " + ex.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            // Runs on a delayed scheduler task after join: must never throw.
            plugin.getLogger().warning("Spawn teleport failed for "
                    + (player != null ? player.getName() : "<unknown>") + ": " + e.getMessage());
        }
    }
}
