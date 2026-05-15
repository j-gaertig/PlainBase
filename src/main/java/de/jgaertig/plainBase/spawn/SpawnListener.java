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
                teleportToConfigLocation(player, "first-spawn.location");
                return; // Wenn First-Spawn, dann kein normaler Spawn Teleport nötig
            }
        }

        if (config.getBoolean("spawn.enabled", false)) {
            teleportToConfigLocation(player, "spawn.location");
        }
    }

    private void teleportToConfigLocation(Player player, String path) {
        FileConfiguration config = plugin.getSpawnConfig();
        String worldName = config.getString(path + ".world");
        if (worldName == null) return;

        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Location loc = new Location(
                world,
                config.getDouble(path + ".x"),
                config.getDouble(path + ".y"),
                config.getDouble(path + ".z"),
                (float) config.getDouble(path + ".yaw"),
                (float) config.getDouble(path + ".pitch")
        );
        player.teleport(loc);
    }
}
