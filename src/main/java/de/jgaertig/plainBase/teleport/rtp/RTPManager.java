package de.jgaertig.plainBase.teleport.rtp;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class RTPManager {
    private final PlainBase plugin;

    // In-memory only by design: a /plainbase reload constructs a fresh RTPManager
    // (see PlainBase stopModules/setupTeleport, which also cancels pending warmups
    // and searches), so pending RTP cooldowns reset on reload. No persistence —
    // documented behaviour, not a leak; entries of players who never return are
    // purged opportunistically on read (isOnCooldown) and on quit/cancel.
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> activeWarmups = new ConcurrentHashMap<>();
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();

    public RTPManager(PlainBase plugin) {
        this.plugin = plugin;
    }

    public void startRTPProcess(Player player) {
        if (player == null || !player.isOnline()) return;
        if (isOnCooldown(player)) return;

        // Early TPA-warmup precheck (before setCooldown): a running TPA warmup
        // wins. Aborting here avoids consuming the RTP cooldown and starting
        // a search that proceedToWarmup would only abort (with refund) later.
        try {
            if (plugin.getTPAManager() != null && plugin.getTPAManager().hasWarmup(player)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: a teleport is already in progress."));
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to check TPA warmup for " + player.getName() + ": " + e.getMessage());
        }

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

        try {
            Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
                tryNextAttempt(player, world, centerX, centerZ, halfSize, spawnLoc, originLoc, 0);
            });
        } catch (Exception e) {
            // Scheduler rejected (e.g. plugin disabling): never leak the
            // searching entry or a cooldown for a search that never ran.
            searching.remove(uuid);
            cooldowns.remove(uuid);
            plugin.getLogger().warning("Could not start RTP search for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void tryNextAttempt(Player player, World world, double centerX, double centerZ,
                                double halfSize, Location spawnLoc, Location originLoc, int attempt) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        // Stale async chain: the search was cancelled (quit, TPA start,
        // cancelAll) while this attempt was queued — never continue.
        if (!searching.contains(uuid)) return;
        if (!player.isOnline()) {
            searching.remove(uuid);
            return;
        }
        int maxAttempts = 20;

        if (attempt >= maxAttempts) {
            // A failed search must not punish with a cooldown.
            cooldowns.remove(uuid);
            try {
                player.getScheduler().run(plugin, (t) -> {
                    if (!searching.contains(uuid)) return;
                    searching.remove(uuid);
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getMiniMessage().deserialize("<red>Could not find a safe location. Try again later!"));
                    }
                }, null);
            } catch (Exception e) {
                // Scheduler rejected (player gone / plugin disabling):
                // still release the searching entry so it can never leak.
                searching.remove(uuid);
            }
            return;
        }

        int x = (int) (centerX + (ThreadLocalRandom.current().nextDouble() * halfSize * 2 - halfSize));
        int z = (int) (centerZ + (ThreadLocalRandom.current().nextDouble() * halfSize * 2 - halfSize));
        final int finalX = x;
        final int finalZ = z;
        int chunkX = finalX >> 4;
        int chunkZ = finalZ >> 4;
        final int nextAttempt = attempt + 1;

        // RegionScheduler#execute is called from an async thread and may throw
        // synchronously (e.g. plugin disabling) — that must never leak the
        // searching entry or a cooldown for a search that never ran.
        try {
            Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
                try {
                    runAttemptOnRegion(player, world, centerX, centerZ, halfSize, spawnLoc, originLoc, uuid, finalX, finalZ, nextAttempt);
                } catch (Exception e) {
                    // Unexpected failure on the region thread: retry once via
                    // async unless the search already ended or attempts are up.
                    if (!searching.contains(uuid)) return;
                    try {
                        Bukkit.getAsyncScheduler().runNow(plugin, (t) ->
                                tryNextAttempt(player, world, centerX, centerZ, halfSize, spawnLoc, originLoc, nextAttempt));
                    } catch (Exception ignored) {
                        searching.remove(uuid);
                        cooldowns.remove(uuid);
                    }
                }
            });
        } catch (Exception e) {
            searching.remove(uuid);
            cooldowns.remove(uuid);
            plugin.getLogger().warning("Could not continue RTP search for " + player.getName() + ": " + e.getMessage());
            try {
                player.getScheduler().run(plugin, (t) -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP search failed. Try again later!"));
                    }
                }, null);
            } catch (Exception ignored) {
            }
        }
    }

    private void runAttemptOnRegion(Player player, World world, double centerX, double centerZ,
                                    double halfSize, Location spawnLoc, Location originLoc,
                                    UUID uuid, int finalX, int finalZ, int nextAttempt) {
        if (!player.isOnline()) {
            searching.remove(uuid);
            return;
        }
        // The world was snapshotted at search start; the player may have
        // changed worlds while attempts were queued. Never teleport across
        // the stale snapshot — abort with a cooldown refund.
        try {
            if (world == null || !player.getWorld().equals(world)) {
                searching.remove(uuid);
                cooldowns.remove(uuid);
                try {
                    player.getScheduler().run(plugin, (t) -> {
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: you changed worlds."));
                        }
                    }, null);
                } catch (Exception ignored) {
                }
                return;
            }
        } catch (Exception e) {
            searching.remove(uuid);
            cooldowns.remove(uuid);
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
    }

    private void proceedToWarmup(Player player, Location foundLoc) {
        if (player == null || !player.isOnline()) return;

        // Snapshot: this runs at the end of an async -> region -> player-thread
        // chain, so stopModules() may have cleared teleport.yml in between.
        // Never deref fresh or abort silently — tell the player.
        org.bukkit.configuration.file.FileConfiguration teleportConfig = plugin.getTeleportConfig();
        if (teleportConfig == null) {
            cooldowns.remove(player.getUniqueId());
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport is currently unavailable."));
            return;
        }

        // Double-warmup guard (RTP side): a pending TPA warmup for the same player
        // must not fire after this RTP teleport. cancelWarmup is a no-op with no
        // message when no TPA warmup exists. Mirror direction (TPA start cancels
        // RTP) lives in TPAManager.
        // Priority: a running warmup wins. If a TPA warmup is already pending,
        // the new RTP search aborts (with a cooldown refund) instead of
        // killing the TPA warmup.
        try {
            if (plugin.getTPAManager() != null && plugin.getTPAManager().hasWarmup(player)) {
                cooldowns.remove(player.getUniqueId());
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: a teleport is already in progress."));
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to check TPA warmup for " + player.getName() + ": " + e.getMessage());
        }
        try {
            if (plugin.getTPAManager() != null) {
                plugin.getTPAManager().cancelWarmup(player, "RTP started.");
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to cancel TPA warmup for " + player.getName() + ": " + e.getMessage());
        }

        // World re-check: the player may have changed worlds during the async
        // search. Never warm up towards a stale world snapshot.
        try {
            if (foundLoc == null || foundLoc.getWorld() == null || !player.getWorld().equals(foundLoc.getWorld())) {
                cooldowns.remove(player.getUniqueId());
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: you changed worlds."));
                return;
            }
        } catch (Exception e) {
            cooldowns.remove(player.getUniqueId());
            return;
        }

        long seconds = teleportConfig.getLong("rtp.counter.seconds", 3);
        // Clamp like tpa.counter.seconds: negative/huge values must never
        // leak into the scheduler delay or the displayed countdown.
        seconds = Math.max(0, Math.min(30, seconds));
        if (!teleportConfig.getBoolean("rtp.counter.enabled", true) || seconds <= 0) {
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

        // Atomic reservation: a concurrent TPA warmup for the same player may
        // have been registered after the remove() above — putIfAbsent lets the
        // already-running warmup win instead of overwriting it (TOCTOU with
        // TPAManager.startTeleportProcedure, which mirrors this). Cooldown is
        // refunded, same as the TPA-warmup-present abort above.
        if (activeWarmups.putIfAbsent(player.getUniqueId(), warmupTask) != null) {
            warmupTask.cancel();
            cooldowns.remove(player.getUniqueId());
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: a teleport is already in progress."));
            return;
        }
    }

    private void executeTeleport(Player player, Location loc) {
        if (player == null || !player.isOnline()) return;
        // Runs on the entity thread: re-validate world and safety — the spot
        // was checked seconds ago at search time and the terrain or the
        // player's world may have changed during the warmup.
        // Folia: block/biome access (isLocationSafe → getBlock()) is only
        // legal on the DESTINATION region thread (runAttemptOnRegion already
        // runs there via RegionScheduler). The player's entity thread owns a
        // different region when the target chunk is far away, so the
        // re-check hops via RegionScheduler onto the dest chunk first and
        // only then teleports. Never call getBlock() on the entity thread.
        final Location dest = loc.clone();
        try {
            if (dest.getWorld() == null || !player.getWorld().equals(dest.getWorld())) {
                cooldowns.remove(player.getUniqueId());
                if (player.isOnline()) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: you changed worlds."));
                }
                return;
            }
        } catch (Exception e) {
            cooldowns.remove(player.getUniqueId());
            return;
        }
        final World destWorld = dest.getWorld();
        final int chunkX = dest.getBlockX() >> 4;
        final int chunkZ = dest.getBlockZ() >> 4;
        final UUID uuid = player.getUniqueId();
        try {
            Bukkit.getRegionScheduler().execute(plugin, destWorld, chunkX, chunkZ, () -> {
                boolean safe;
                try {
                    safe = isLocationSafe(dest);
                } catch (Exception e) {
                    safe = false;
                }
                if (!safe) {
                    cooldowns.remove(uuid);
                    try {
                        player.getScheduler().run(plugin, t -> {
                            if (player.isOnline()) {
                                player.sendMessage(plugin.getMiniMessage().deserialize("<red>The location is no longer safe. Try again!"));
                            }
                        }, null);
                    } catch (Exception ignored) {
                    }
                    return;
                }
                teleportValidated(player, dest, uuid);
            });
        } catch (Exception e) {
            cooldowns.remove(uuid);
            plugin.getLogger().warning("Could not validate RTP destination for " + player.getName() + ": " + e.getMessage());
            try {
                player.getScheduler().run(plugin, t -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP search failed. Try again later!"));
                    }
                }, null);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Teleports after the destination region validated the spot (see
     * {@link #executeTeleport}). Runs on the dest region thread; teleportAsync
     * is thread-safe, result messaging hops back onto the entity thread.
     */
    private void teleportValidated(Player player, Location dest, UUID uuid) {
        // isOnline guard before teleportAsync (TPA TPAManager 436-443 pattern):
        // the dest region thread may run after the player quit — never
        // teleport an offline player, refund the cooldown instead.
        if (player == null || !player.isOnline()) {
            if (uuid != null) cooldowns.remove(uuid);
            return;
        }
        if (dest == null || dest.getWorld() == null) {
            if (uuid != null) cooldowns.remove(uuid);
            return;
        }
        try {
            player.teleportAsync(dest.clone().add(0, 1, 0)).thenAccept(success -> {
            // thenAccept runs off the entity thread: hop back onto the
            // EntityScheduler for sendMessage (Folia scheduler retained).
            if (Boolean.TRUE.equals(success)) {
                try {
                    player.getScheduler().run(plugin, t -> {
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getMiniMessage().deserialize("<green>Teleported to a safe random location!"));
                        }
                    }, null);
                } catch (Exception e) {
                    plugin.getLogger().fine("Failed to notify RTP teleport success for " + player.getName() + ": " + e.getMessage());
                }
                return;
            }
            // A failed teleport must not consume the cooldown.
            if (uuid != null) cooldowns.remove(uuid);
            try {
                player.getScheduler().run(plugin, t -> {
                    if (!player.isOnline()) return;
                    try {
                        player.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport failed. Try again!"));
                    } catch (Exception e) {
                        plugin.getLogger().fine("Failed to notify RTP teleport failure for " + player.getName() + ": " + e.getMessage());
                    }
                }, null);
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to schedule RTP teleport failure notify for " + player.getName() + ": " + e.getMessage());
            }
            });
        } catch (Exception e) {
            // Scheduler/teleport threw synchronously (player gone, plugin
            // disabling): a search that never teleported must refund the
            // cooldown, like the failure path above.
            if (uuid != null) cooldowns.remove(uuid);
            plugin.getLogger().fine("Failed to start RTP teleport for " + player.getName() + ": " + e.getMessage());
            try {
                player.getScheduler().run(plugin, t -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport failed. Try again!"));
                    }
                }, null);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isLocationSafe(Location loc) {
        org.bukkit.configuration.file.FileConfiguration teleportConfig = plugin.getTeleportConfig();
        boolean blacklistEnabled = teleportConfig != null && teleportConfig.getBoolean("rtp.blacklist.enabled", true);

        if (blacklistEnabled) {
            java.util.List<String> blacklistedBiomes = teleportConfig.getStringList("rtp.blacklist.biomes");
            String biomeKey = loc.getBlock().getBiome().getKey().getKey().toLowerCase(Locale.ROOT);
            for (String b : blacklistedBiomes) {
                if (b != null && !b.isEmpty() && biomeKey.contains(b.toLowerCase(Locale.ROOT))) return false;
            }

            java.util.List<String> blacklist = teleportConfig.getStringList("rtp.blacklist.blocks");
            if (blacklist.contains(loc.getBlock().getType().name())) return false;
        }

        Material ground = loc.getBlock().getType();
        String groundName = ground.name();
        // Always-unsafe ground regardless of the blacklist toggle: falling
        // through or suffocating spots that must never be an RTP target.
        if (isAlwaysUnsafeBlock(groundName)) return false;
        if (groundName.contains("LEAVES")) return false;
        if (!ground.isSolid()) return false;
        if (ground == Material.LAVA || ground == Material.WATER || ground == Material.FIRE
                || ground == Material.CACTUS || ground == Material.MAGMA_BLOCK
                || ground == Material.CAMPFIRE || ground == Material.SOUL_CAMPFIRE) return false;

        org.bukkit.block.Block b1 = loc.getBlock().getRelative(0, 1, 0);
        org.bukkit.block.Block b2 = loc.getBlock().getRelative(0, 2, 0);
        if (!b1.isPassable() || !b2.isPassable()) return false;
        // Headroom uses the same configured block blacklist as the ground
        // check above (plus passability). AIR/CAVE_AIR/VOID_AIR entries in
        // that list can never match here — headroom blocks at a candidate
        // spot are real blocks — so they are simply never hit, no special
        // handling needed.
        String head1Name = b1.getType().name();
        String head2Name = b2.getType().name();
        // Same always-unsafe basis for headroom (portal/powder-snow are
        // passable, so the passability check above would not catch them).
        if (isAlwaysUnsafeBlock(head1Name) || isAlwaysUnsafeBlock(head2Name)) return false;
        if (head1Name.contains("LEAVES") || head2Name.contains("LEAVES")) return false;
        if (blacklistEnabled) {
            java.util.List<String> blacklist = teleportConfig.getStringList("rtp.blacklist.blocks");
            if (blacklist.contains(head1Name) || blacklist.contains(head2Name)) return false;
        } else {
            Material head1 = b1.getType();
            Material head2 = b2.getType();
            if (head1 == Material.LAVA || head1 == Material.WATER || head1 == Material.FIRE
                    || head1 == Material.CACTUS || head1 == Material.MAGMA_BLOCK
                    || head1 == Material.CAMPFIRE || head1 == Material.SOUL_CAMPFIRE) return false;
            if (head2 == Material.LAVA || head2 == Material.WATER || head2 == Material.FIRE
                    || head2 == Material.CACTUS || head2 == Material.MAGMA_BLOCK
                    || head2 == Material.CAMPFIRE || head2 == Material.SOUL_CAMPFIRE) return false;
        }

        return true;
    }

    /**
     * Hardcoded always-unsafe block basis, applied independently of the
     * {@code rtp.blacklist.enabled} toggle. Name-based (not Material enum) so
     * version renames (PORTAL vs NETHER_PORTAL, END_PORTAL vs ENDER_PORTAL)
     * can never slip through on any Paper build.
     */
    private static boolean isAlwaysUnsafeBlock(String materialName) {
        if (materialName == null) return false;
        return switch (materialName) {
            case "BEDROCK", "BARRIER",
                    "PORTAL", "NETHER_PORTAL",
                    "END_PORTAL", "ENDER_PORTAL", "END_GATEWAY",
                    "POWDER_SNOW" -> true;
            default -> false;
        };
    }

    private boolean isOnCooldown(Player player) {
        Long expiry = cooldowns.get(player.getUniqueId());
        if (expiry == null) return false;
        long timeLeft = (expiry - System.currentTimeMillis()) / 1000;
        if (timeLeft > 0) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Wait " + timeLeft + "s before using RTP again."));
            return true;
        }
        // Expired entries are purged on read so the map cannot grow without bounds.
        cooldowns.remove(player.getUniqueId(), expiry);
        return false;
    }

    private void setCooldown(Player player) {
        org.bukkit.configuration.file.FileConfiguration teleportConfig = plugin.getTeleportConfig();
        if (teleportConfig == null) {
            cooldowns.remove(player.getUniqueId());
            return;
        }
        long seconds = teleportConfig.getLong("rtp.cooldown", 60);
        // Clamp: negative values must not create an instantly-expired (leaked)
        // entry, huge values must not lock players out forever.
        seconds = Math.max(0, Math.min(86400, seconds));
        if (seconds <= 0) {
            cooldowns.remove(player.getUniqueId());
            return;
        }
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + (seconds * 1000));
    }

    /**
     * Quit path: cancels a pending warmup/search and refunds the cooldown —
     * but ONLY when a warmup or search was actually active (like cancelAll).
     * A plain quit with no pending RTP keeps its cooldown, so a relog cannot
     * dodge the anti-spam timer. Unlike cancelWarmup/cancelSearch (which keep
     * the cooldown for move/damage/interact cancels), this is quit-only.
     */
    public void handleQuit(Player player) {
        if (player == null) return;
        UUID uuid;
        try {
            uuid = player.getUniqueId();
        } catch (Exception e) {
            return;
        }
        ScheduledTask warmup = activeWarmups.remove(uuid);
        boolean hadWarmup = warmup != null;
        if (warmup != null) {
            try {
                warmup.cancel();
            } catch (Exception ignored) {
            }
        }
        boolean hadSearch = searching.remove(uuid);
        if (hadWarmup || hadSearch) {
            cooldowns.remove(uuid);
        }
        // Opportunistic purge of expired entries (same as cancelSearch) so
        // cooldowns of players who never return cannot accumulate.
        try {
            cooldowns.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= System.currentTimeMillis());
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to purge expired RTP cooldowns: " + e.getMessage());
        }
    }

    public void cancelSearch(Player player) {
        if (player == null) return;
        searching.remove(player.getUniqueId());
        // Opportunistic purge of this player's entry on quit/cancel so stale
        // entries of players who never come back cannot accumulate. Only
        // expired entries are removed — an active cooldown is never deleted.
        long now = System.currentTimeMillis();
        Long expiry = cooldowns.get(player.getUniqueId());
        if (expiry != null && expiry <= now) {
            cooldowns.remove(player.getUniqueId(), expiry);
        }
        // Global opportunistic purge of all expired cooldowns (quit/cancel
        // path): without this, cooldowns of players who quit with an active
        // cooldown and never return would linger until a map-sized cleanup.
        // Active (unexpired) entries are never touched.
        try {
            cooldowns.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= System.currentTimeMillis());
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to purge expired RTP cooldowns: " + e.getMessage());
        }
    }

    /**
     * Removes the RTP cooldown unconditionally. Called by
     * TPAManager.startTeleportProcedure after cancelSearch(): a search
     * cancelled in favour of a TPA teleport must refund the cooldown, mirroring
     * the reverse direction (RTP start aborts with a cooldown refund).
     */
    public void refundCooldown(UUID uuid) {
        if (uuid == null) return;
        cooldowns.remove(uuid);
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
        java.util.Set<UUID> hadWarmup = new java.util.HashSet<>(activeWarmups.keySet());
        for (ScheduledTask task : new ArrayList<>(activeWarmups.values())) {
            if (task != null) task.cancel();
        }
        for (UUID uuid : hadWarmup) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                try {
                    p.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: server reloading."));
                } catch (Exception ignored) {
                }
            }
        }
        activeWarmups.clear();
        // Warmup cooldowns must be refunded too: a reload-cancelled warmup
        // never teleported, so keeping its cooldown would punish the player.
        for (UUID uuid : hadWarmup) {
            cooldowns.remove(uuid);
        }
        // A stuck search must not vanish silently: notify every searcher and
        // refund the cooldown when the search never reached a warmup — same
        // as TPAManager.cancelAll notifies both sides of pending sessions.
        for (UUID uuid : new ArrayList<>(searching)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                try {
                    p.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: server reloading."));
                } catch (Exception ignored) {
                }
            }
            if (!hadWarmup.contains(uuid)) {
                cooldowns.remove(uuid);
            }
        }
        searching.clear();
    }
}
