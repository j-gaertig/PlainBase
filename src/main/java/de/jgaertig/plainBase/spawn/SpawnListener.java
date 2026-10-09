package de.jgaertig.plainBase.spawn;

import de.jgaertig.plainBase.PlainBase;
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
        Player player = event.getPlayer();
        FileConfiguration config = plugin.getSpawnConfig();

        if (!player.hasPlayedBefore()) {
            if (config.getBoolean("first-spawn.enabled", false)) {
                player.getScheduler().runDelayed(plugin, t -> teleportToConfigLocation(player, "first-spawn.location"), null, 1L);
                return; // Wenn First-Spawn, dann kein normaler Spawn Teleport nötig
            }
        }

        if (config.getBoolean("spawn.enabled", false)) {
            player.getScheduler().runDelayed(plugin, t -> teleportToConfigLocation(player, "spawn.location"), null, 1L);
        }
    }

    private void teleportToConfigLocation(Player player, String path) {
        if (player == null || !player.isOnline()) return;
        FileConfiguration config = plugin.getSpawnConfig();
        String worldName = config.getString(path + ".world");
        if (worldName == null) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn location is not set correctly. Contact an admin!"));
            plugin.getLogger().warning("Spawn teleport failed for " + player.getName() + ": missing world at '" + path + ".world'.");
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn world '" + worldName + "' not found. Contact an admin!"));
            plugin.getLogger().warning("Spawn teleport failed for " + player.getName() + ": world '" + worldName + "' not found (path '" + path + "').");
            return;
        }

        Location loc = new Location(
                world,
                config.getDouble(path + ".x"),
                config.getDouble(path + ".y"),
                config.getDouble(path + ".z"),
                (float) config.getDouble(path + ".yaw"),
                (float) config.getDouble(path + ".pitch")
        );
        player.teleportAsync(loc);
    }
}
