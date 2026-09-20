package de.jgaertig.plainBase.teleport.rtp;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class RTPManager {
    private final PlainBase plugin;
    private final Random random = new Random();

    // RTP commands can run on different region threads (Folia) — a plain
    // HashMap could corrupt under concurrent access.
    private final Map<UUID, Long> cooldowns = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> activeWarmups = new java.util.concurrent.ConcurrentHashMap<>();

    public RTPManager(PlainBase plugin) {
        this.plugin = plugin;
    }

    public void startRTPProcess(Player player) {
        if (isOnCooldown(player)) return;

        player.sendMessage(plugin.getMiniMessage().deserialize("<gray>Searching for a safe location..."));

        // Capture every piece of world state the search needs ON the caller's
        // (region) thread — the search itself runs async and must not read
        // world objects from there (unsafe on Folia, discouraged on Paper).
        World world = player.getWorld();
        WorldBorder border = world.getWorldBorder();
        double borderCenterX = border.getCenter().getX();
        double borderCenterZ = border.getCenter().getZ();
        double borderHalfSize = border.getSize() / 2;
        Location spawn = world.getSpawnLocation();
        Location origin = player.getLocation();

        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            Location foundLoc = findSafeLocation(world, origin, spawn, borderCenterX, borderCenterZ, borderHalfSize);

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

    /**
     * Runs entirely on an async thread. Order matters: the target chunk is
     * loaded asynchronously FIRST (getChunkAtAsync), and only afterwards are
     * heightmap/blocks read — calling getHighestBlockYAt() before the chunk
     * is loaded would silently fall back to a synchronous chunk load on this
     * async thread, defeating the whole point of the async pipeline.
     */
    private Location findSafeLocation(World world, Location origin, Location spawn,
                                      double borderCenterX, double borderCenterZ, double borderHalfSize) {
        int maxAttempts = 100;
        Exception firstFailure = null;

        for (int i = 0; i < maxAttempts; i++) {
            int[] candidate = generateRandomXZ(origin, spawn, borderCenterX, borderCenterZ, borderHalfSize);
            if (candidate == null) continue;
            int x = candidate[0];
            int z = candidate[1];

            try {
                // Async chunk load (with a timeout so a stuck generation
                // server can't hang the search forever).
                world.getChunkAtAsync(x >> 4, z >> 4).get(2, TimeUnit.SECONDS);

                // Chunk is now loaded: reading the heightmap no longer
                // triggers a synchronous chunk load.
                int y = world.getHighestBlockYAt(x, z);
                if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight() - 2) continue;

                Location loc = new Location(world, x + 0.5, y, z + 0.5);
                if (isLocationSafe(loc)) return loc;
            } catch (Exception e) {
                if (firstFailure == null) firstFailure = e;
            }
        }

        if (firstFailure != null) {
            plugin.getLogger().warning("RTP: no safe location found; first failed candidate was caused by: " + firstFailure);
        }
        return null;
    }

    /**
     * Pure coordinate math (no world/chunk access) — safe to call from any
     * thread. Returns null when the candidate is rejected.
     */
    private int[] generateRandomXZ(Location origin, Location spawn,
                                   double borderCenterX, double borderCenterZ, double borderHalfSize) {
        int x = (int) (borderCenterX + (random.nextDouble() * borderHalfSize * 2 - borderHalfSize));
        int z = (int) (borderCenterZ + (random.nextDouble() * borderHalfSize * 2 - borderHalfSize));

        if (distance2D(spawn.getX(), spawn.getZ(), x, z) < 256) return null;
        if (distance2D(origin.getX(), origin.getZ(), x, z) < 500) return null;

        return new int[]{x, z};
    }

    private double distance2D(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean isLocationSafe(Location loc) {
        // One switch for the whole blacklist section (biomes AND blocks).
        if (plugin.getTeleportConfig().getBoolean("rtp.blacklist.enabled", true)) {
            List<String> blacklistedBiomes = plugin.getTeleportConfig().getStringList("rtp.blacklist.biomes");
            if (blacklistedBiomes.stream().anyMatch(b -> loc.getBlock().getBiome().getKey().getKey().toLowerCase().contains(b.toLowerCase()))) return false;

            List<String> blacklist = plugin.getTeleportConfig().getStringList("rtp.blacklist.blocks");
            if (blacklist.contains(loc.getBlock().getType().name())) return false;
        }

        // Hard safety requirements (independent of the blacklist switch): the
        // player must actually fit at the target spot.
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
