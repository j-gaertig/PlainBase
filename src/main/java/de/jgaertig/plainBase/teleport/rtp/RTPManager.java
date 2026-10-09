package de.jgaertig.plainBase.teleport.rtp;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class RTPManager {
    private final PlainBase plugin;

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> activeWarmups = new ConcurrentHashMap<>();
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();

    public RTPManager(PlainBase plugin) {
        this.plugin = plugin;
    }

    public void startRTPProcess(Player player) {
        if (isOnCooldown(player)) return;

        UUID uuid = player.getUniqueId();
        if (activeWarmups.containsKey(uuid) || !searching.add(uuid)) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>You are already searching for a safe location!"));
            return;
        }

        setCooldown(player);

        player.sendMessage(plugin.getMiniMessage().deserialize("<gray>Searching for a safe location..."));

        World world = player.getWorld();
        double centerX = world.getWorldBorder().getCenter().getX();
        double centerZ = world.getWorldBorder().getCenter().getZ();
        double halfSize = world.getWorldBorder().getSize() / 2;
        Location spawnLoc = world.getSpawnLocation().clone();
        Location originLoc = player.getLocation().clone();

        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            tryNextAttempt(player, world, centerX, centerZ, halfSize, spawnLoc, originLoc, 0);
        });
    }

    private void tryNextAttempt(Player player, World world, double centerX, double centerZ,
                                double halfSize, Location spawnLoc, Location originLoc, int attempt) {
        int maxAttempts = 20;
        UUID uuid = player.getUniqueId();

        if (attempt >= maxAttempts) {
            // A failed search must not punish with a cooldown.
            cooldowns.remove(uuid);
            player.getScheduler().run(plugin, (t) -> {
                if (!searching.contains(uuid)) return;
                searching.remove(uuid);
                if (player.isOnline()) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>Could not find a safe location. Try again later!"));
                }
            }, null);
            return;
        }

        int x = (int) (centerX + (ThreadLocalRandom.current().nextDouble() * halfSize * 2 - halfSize));
        int z = (int) (centerZ + (ThreadLocalRandom.current().nextDouble() * halfSize * 2 - halfSize));
        final int finalX = x;
        final int finalZ = z;
        int chunkX = finalX >> 4;
        int chunkZ = finalZ >> 4;
        final int nextAttempt = attempt + 1;

        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
            if (!player.isOnline()) {
                searching.remove(uuid);
                return;
            }

            int y;
            try {
                y = world.getHighestBlockYAt(finalX, finalZ);
            } catch (Exception e) {
                Bukkit.getAsyncScheduler().runNow(plugin, (t) ->
                        tryNextAttempt(player, world, centerX, centerZ, halfSize, spawnLoc, originLoc, nextAttempt));
                return;
            }

            if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight() - 2) {
                Bukkit.getAsyncScheduler().runNow(plugin, (t) ->
                        tryNextAttempt(player, world, centerX, centerZ, halfSize, spawnLoc, originLoc, nextAttempt));
                return;
            }

            Location loc = new Location(world, finalX + 0.5, y, finalZ + 0.5);

            if (spawnLoc != null && spawnLoc.getWorld() != null && spawnLoc.getWorld().equals(world)) {
                double dx = (finalX + 0.5) - spawnLoc.getX();
                double dz = (finalZ + 0.5) - spawnLoc.getZ();
                if (dx * dx + dz * dz < 256 * 256) {
                    Bukkit.getAsyncScheduler().runNow(plugin, (t) ->
                            tryNextAttempt(player, world, centerX, centerZ, halfSize, spawnLoc, originLoc, nextAttempt));
                    return;
                }
            }

            if (originLoc != null && originLoc.getWorld() != null && originLoc.getWorld().equals(world)) {
                double dx = (finalX + 0.5) - originLoc.getX();
                double dz = (finalZ + 0.5) - originLoc.getZ();
                if (dx * dx + dz * dz < 500 * 500) {
                    Bukkit.getAsyncScheduler().runNow(plugin, (t) ->
                            tryNextAttempt(player, world, centerX, centerZ, halfSize, spawnLoc, originLoc, nextAttempt));
                    return;
                }
            }

            boolean safe;
            try {
                safe = isLocationSafe(loc);
            } catch (Exception e) {
                safe = false;
            }

            if (safe) {
                Location dest = loc.clone();
                player.getScheduler().run(plugin, (t) -> {
                    try {
                        if (!searching.contains(uuid)) return;
                        if (!player.isOnline()) return;
                        proceedToWarmup(player, dest);
                    } finally {
                        searching.remove(uuid);
                    }
                }, null);
            } else {
                Bukkit.getAsyncScheduler().runNow(plugin, (t) ->
                        tryNextAttempt(player, world, centerX, centerZ, halfSize, spawnLoc, originLoc, nextAttempt));
            }
        });
    }

    private void proceedToWarmup(Player player, Location foundLoc) {
        if (!player.isOnline()) return;

        long seconds = plugin.getTeleportConfig().getLong("rtp.counter.seconds", 3);
        if (!plugin.getTeleportConfig().getBoolean("rtp.counter.enabled", true) || seconds <= 0) {
            executeTeleport(player, foundLoc);
            return;
        }

        ScheduledTask old = activeWarmups.remove(player.getUniqueId());
        if (old != null) old.cancel();

        player.sendMessage(plugin.getMiniMessage().deserialize("<green>Safe location found!"));
        player.sendMessage(plugin.getMiniMessage().deserialize("<gray>Teleporting in <yellow>" + seconds + " <gray>seconds. Do not move, take damage or interact!"));

        ScheduledTask warmupTask = player.getScheduler().runDelayed(plugin, (wt) -> {
            activeWarmups.remove(player.getUniqueId());
            executeTeleport(player, foundLoc);
        }, null, seconds * 20L);

        activeWarmups.put(player.getUniqueId(), warmupTask);
    }

    private void executeTeleport(Player player, Location loc) {
        if (player == null || !player.isOnline()) return;
        player.teleportAsync(loc.clone().add(0, 1, 0)).thenAccept(success -> {
            if (success) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<green>Teleported to a safe random location!"));
            }
        });
    }

    private boolean isLocationSafe(Location loc) {
        boolean blacklistEnabled = plugin.getTeleportConfig().getBoolean("rtp.blacklist.enabled", true);

        if (blacklistEnabled) {
            java.util.List<String> blacklistedBiomes = plugin.getTeleportConfig().getStringList("rtp.blacklist.biomes");
            String biomeKey = loc.getBlock().getBiome().getKey().getKey().toLowerCase();
            for (String b : blacklistedBiomes) {
                if (b != null && !b.isEmpty() && biomeKey.contains(b.toLowerCase())) return false;
            }

            java.util.List<String> blacklist = plugin.getTeleportConfig().getStringList("rtp.blacklist.blocks");
            if (blacklist.contains(loc.getBlock().getType().name())) return false;
        }

        Material ground = loc.getBlock().getType();
        String groundName = ground.name();
        if (groundName.contains("LEAVES")) return false;
        if (!ground.isSolid()) return false;
        if (ground == Material.LAVA || ground == Material.WATER || ground == Material.FIRE
                || ground == Material.CACTUS || ground == Material.MAGMA_BLOCK
                || ground == Material.CAMPFIRE || ground == Material.SOUL_CAMPFIRE) return false;

        org.bukkit.block.Block b1 = loc.getBlock().getRelative(0, 1, 0);
        org.bukkit.block.Block b2 = loc.getBlock().getRelative(0, 2, 0);
        if (!b1.isPassable() || !b2.isPassable()) return false;
        Material head1 = b1.getType();
        Material head2 = b2.getType();
        if (head1 == Material.LAVA || head1 == Material.WATER || head1 == Material.FIRE
                || head1 == Material.CACTUS || head1 == Material.MAGMA_BLOCK) return false;
        if (head2 == Material.LAVA || head2 == Material.WATER || head2 == Material.FIRE
                || head2 == Material.CACTUS || head2 == Material.MAGMA_BLOCK) return false;

        return true;
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

    public void cancelSearch(Player player) {
        if (player == null) return;
        searching.remove(player.getUniqueId());
    }

    public void cancelWarmup(Player player, String reason) {
        if (player == null) return;
        ScheduledTask task = activeWarmups.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            if (player.isOnline()) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: " + reason));
            }
        }
    }

    public void cancelAll() {
        for (ScheduledTask task : new ArrayList<>(activeWarmups.values())) {
            if (task != null) task.cancel();
        }
        activeWarmups.clear();
        searching.clear();
    }
}
