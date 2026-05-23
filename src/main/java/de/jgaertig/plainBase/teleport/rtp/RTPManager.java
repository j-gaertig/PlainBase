package de.jgaertig.plainBase.teleport.rtp;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;

public class RTPManager {
    private final PlainBase plugin;
    private final Random random = new Random();

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, ScheduledTask> activeWarmups = new HashMap<>();

    public RTPManager(PlainBase plugin) {
        this.plugin = plugin;
    }

    public void startRTPProcess(Player player) {
        if (isOnCooldown(player)) return;

        player.sendMessage(plugin.getMiniMessage().deserialize("<gray>Searching for a safe location..."));

        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            Location foundLoc = findSafeLocation(player);

            player.getScheduler().run(plugin, (t) -> {
                if (foundLoc == null) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>Could not find a safe location. Try again later!"));
                    return;
                }

                player.sendMessage(plugin.getMiniMessage().deserialize("<green>Safe location found!"));

                long seconds = plugin.getTeleportConfig().getLong("rtp.counter.seconds", 3);
                if (!plugin.getTeleportConfig().getBoolean("rtp.counter.enabled", true) || seconds <= 0) {
                    executeTeleport(player, foundLoc);
                    return;
                }

                player.sendMessage(plugin.getMiniMessage().deserialize("<gray>Teleporting in <yellow>" + seconds + " <gray>seconds. Do not move, take damage or interact!"));

                ScheduledTask warmupTask = Bukkit.getRegionScheduler().runDelayed(plugin, player.getLocation(), (wt) -> {
                    activeWarmups.remove(player.getUniqueId());
                    executeTeleport(player, foundLoc);
                }, seconds * 20L);

                activeWarmups.put(player.getUniqueId(), warmupTask);
            }, null);
        });
    }

    private void executeTeleport(Player player, Location loc) {
        player.teleportAsync(loc.clone().add(0, 1, 0)).thenAccept(success -> {
            if (success) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<green>Teleported to a safe random location!"));
                setCooldown(player);
            }
        });
    }

    private Location findSafeLocation(Player player) {
        World world = player.getWorld();
        int maxAttempts = 100;

        for (int i = 0; i < maxAttempts; i++) {
            Location loc = generateRandomLocation(world, player.getLocation());
            if (loc == null) continue;

            try {
                world.getChunkAtAsync(loc).get(1, java.util.concurrent.TimeUnit.SECONDS);

                if (isLocationSafe(loc)) return loc;
            } catch (Exception e) {

            }
        }
        return null;
    }

    private Location generateRandomLocation(World world, Location origin) {
        var border = world.getWorldBorder();
        double size = border.getSize() / 2;
        int x = (int) (border.getCenter().getX() + (random.nextDouble() * size * 2 - size));
        int z = (int) (border.getCenter().getZ() + (random.nextDouble() * size * 2 - size));

        if (world.getSpawnLocation().distance(new Location(world, x, world.getSpawnLocation().getY(), z)) < 256) return null;
        if (origin.distance(new Location(world, x, origin.getY(), z)) < 500) return null;

        int y = world.getHighestBlockYAt(x, z);
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight() - 2) return null;

        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private boolean isLocationSafe(Location loc) {
        List<String> blacklistedBiomes = plugin.getTeleportConfig().getStringList("rtp.blacklist.biomes");
        if (blacklistedBiomes.stream().anyMatch(b -> loc.getBlock().getBiome().getKey().getKey().toLowerCase().contains(b.toLowerCase()))) return false;

        List<String> blacklist = plugin.getTeleportConfig().getStringList("rtp.blacklist.blocks");
        if (blacklist.contains(loc.getBlock().getType().name())) return false;

        if (!loc.getBlock().getRelative(0, 1, 0).getType().isAir() || !loc.getBlock().getRelative(0, 2, 0).getType().isAir()) return false;

        return !loc.getBlock().getType().name().contains("LEAVES");
    }

    private boolean isOnCooldown(Player player) {
        if (!cooldowns.containsKey(player.getUniqueId())) return false;
        long timeLeft = (cooldowns.get(player.getUniqueId()) - System.currentTimeMillis()) / 1000;
        if (timeLeft > 0) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Wait " + timeLeft + "s before using RTP again."));
            return true;
        }
        return false;
    }

    private void setCooldown(Player player) {
        long seconds = plugin.getTeleportConfig().getLong("rtp.cooldown", 60);
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + (seconds * 1000));
    }

    public void cancelWarmup(Player player, String reason) {
        ScheduledTask task = activeWarmups.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: " + reason));
        }
    }
}