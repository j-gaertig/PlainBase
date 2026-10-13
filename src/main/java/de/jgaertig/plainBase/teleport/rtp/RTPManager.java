package de.jgaertig.plainBase.teleport.rtp;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.teleport.WarmupReservations;
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

    // Generation guard for the warmup body (same pattern as TPAManager): a
    // /plainbase reload constructs a fresh RTPManager and cancels pending
    // warmups, but an already-firing task of the previous instance must never
    // teleport. The body checks that this instance is still the live one.
    private static final java.util.concurrent.atomic.AtomicLong GENERATION = new java.util.concurrent.atomic.AtomicLong(0);
    private final long generation = GENERATION.incrementAndGet();

    /**
     * Region-hop budget: only real candidates (after the cheap distance
     * prefilter below) consume an attempt. Prefilter resamples are free pure
     * X/Z math with no world access. Raised from 20 so small borders / large
     * exclusion radii still find a spot without spamming region hops.
     */
    private static final int MAX_ATTEMPTS = 100;
    /** Cheap prefilter resamples per attempt (no world access, no counting). */
    private static final int PREFILTER_TRIES = 20;

    // In-memory only by design: a /plainbase reload constructs a fresh RTPManager
    // (see PlainBase stopModules/setupTeleport, which also cancels pending warmups
    // and searches), so pending RTP cooldowns reset on reload. No persistence —
    // documented behaviour, not a leak; entries of players who never return are
    // purged opportunistically on read (isOnCooldown) and on quit/cancel.
    private record CooldownEntry(long expiresAt, Object operationIdentity) {}

    private final Map<UUID, CooldownEntry> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> activeWarmups = new ConcurrentHashMap<>();
    private final Map<UUID, Object> searching = new ConcurrentHashMap<>();
    private final Map<UUID, Object> warmupIdentities = new ConcurrentHashMap<>();
    private final Object warmupLock = new Object();
    private volatile boolean shuttingDown;

    public RTPManager(PlainBase plugin) {
        this.plugin = plugin;
    }

    private void notifyPlayer(Player player, String message) {
        if (player == null) return;
        try {
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) player.sendMessage(plugin.getMiniMessage().deserialize(message));
            }, null);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Could not schedule RTP notification: " + e.getMessage());
        }
    }

    public void startRTPProcess(Player player) {
        if (player == null) return;
        // Brigadier may invoke command bodies on the global region thread.
        // Snapshot entity-owned state and send replies only on this player's
        // scheduler, which is also valid on a conventional Paper server.
        try {
            player.getScheduler().run(plugin, task -> startRTPProcessOnEntity(player), null);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Could not schedule RTP start for player: " + e.getMessage());
        }
    }

    private void startRTPProcessOnEntity(Player player) {
        if (player == null || !player.isOnline()) return;
        // Dead players must never start a search (warmup death always cancels
        // via TeleportListener, this is the early reject for /rtp while dead).
        try {
            if (player.isDead()) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>You cannot use RTP while dead."));
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to check death state for " + player.getName() + ": " + e.getMessage());
            return;
        }
        if (isOnCooldown(player)) return;
        if (WarmupReservations.isReserved(player.getUniqueId())) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>You are already part of a teleport in progress."));
            return;
        }

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
        Object searchIdentity = new Object();
        boolean alreadyActive;
        boolean unavailable;
        synchronized (warmupLock) {
            // Search publication and its cooldown are one operation relative
            // to cancelSearch/quit. Otherwise cancellation can refund before
            // setCooldown publishes, leaving a cooldown for a dead search.
            unavailable = shuttingDown || plugin.getRTPManager() != this;
            alreadyActive = unavailable || activeWarmups.containsKey(uuid) || warmupIdentities.containsKey(uuid)
                    || searching.putIfAbsent(uuid, searchIdentity) != null;
            if (!alreadyActive) setCooldown(player, searchIdentity);
        }
        if (alreadyActive) {
            player.sendMessage(plugin.getMiniMessage().deserialize(unavailable
                    ? "<red>RTP is reloading. Try again shortly."
                    : "<red>You are already searching for a safe location!"));
            return;
        }

        player.sendMessage(plugin.getMiniMessage().deserialize("<gray>Searching for a safe location..."));

        World world = player.getWorld();
        double centerX = world.getWorldBorder().getCenter().getX();
        double centerZ = world.getWorldBorder().getCenter().getZ();
        double halfSize = world.getWorldBorder().getSize() / 2;
        Location spawnLoc = world.getSpawnLocation().clone();
        Location originLoc = player.getLocation().clone();

        try {
            Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
                tryNextAttempt(player, uuid, searchIdentity, world, centerX, centerZ, halfSize, spawnLoc, originLoc, 0);
            });
        } catch (Exception e) {
            // Scheduler rejected (e.g. plugin disabling): never leak the
            // searching entry or a cooldown for a search that never ran.
            searching.remove(uuid, searchIdentity);
            refundSearchCooldown(uuid, searchIdentity);
            plugin.getLogger().warning("Could not start RTP search for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void scheduleNextAttempt(Player player, UUID uuid, Object searchIdentity, World world, double centerX, double centerZ,
                                       double halfSize, Location spawnLoc, Location originLoc, int nextAttempt) {
        if (player == null || uuid == null || !isCurrentSearch(uuid, searchIdentity)) return;
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, (t) ->
                    tryNextAttempt(player, uuid, searchIdentity, world, centerX, centerZ, halfSize, spawnLoc, originLoc, nextAttempt));
        } catch (Exception e) {
            searching.remove(uuid, searchIdentity);
            refundSearchCooldown(uuid, searchIdentity);
            plugin.getLogger().warning("Could not continue RTP search for " + uuid + ": " + e.getMessage());
        }
    }

    private boolean isCurrentSearch(UUID uuid, Object identity) {
        return uuid != null && identity != null && searching.get(uuid) == identity;
    }

    private boolean isInsideBorder(World world, Location loc) {
        if (world == null || loc == null) return false;
        try {
            var border = world.getWorldBorder();
            if (border == null) return false;
            double centerX = border.getCenter().getX();
            double centerZ = border.getCenter().getZ();
            double half = border.getSize() / 2.0;
            if (!Double.isFinite(centerX) || !Double.isFinite(centerZ) || !Double.isFinite(half)) return false;
            double x = loc.getX();
            double z = loc.getZ();
            if (!Double.isFinite(x) || !Double.isFinite(z)) return false;
            return Math.abs(x - centerX) <= half && Math.abs(z - centerZ) <= half;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Cheap distance prefilter (pure X/Z math, no world/chunk access): mirrors
     * the spawn (256) and origin-snapshot (500) checks of
     * {@link #runAttemptOnRegion} so violating picks are discarded BEFORE the
     * expensive region hop. The live-position re-check stays on the region
     * thread (needs player.getLocation()), as do the border re-checks in
     * proceedToWarmup/executeTeleport. Never throws.
     */
    private static boolean passesDistancePrefilter(int x, int z, World world,
                                                    Location spawnLoc, Location originLoc) {
        double cx = x + 0.5;
        double cz = z + 0.5;
        try {
            if (spawnLoc != null && spawnLoc.getWorld() != null && spawnLoc.getWorld().equals(world)) {
                double dx = cx - spawnLoc.getX();
                double dz = cz - spawnLoc.getZ();
                if (!Double.isFinite(dx) || !Double.isFinite(dz)) return false;
                if (dx * dx + dz * dz < 256 * 256) return false;
            }
        } catch (Exception ignored) {
        }
        try {
            if (originLoc != null && originLoc.getWorld() != null && originLoc.getWorld().equals(world)) {
                double dx = cx - originLoc.getX();
                double dz = cz - originLoc.getZ();
                if (!Double.isFinite(dx) || !Double.isFinite(dz)) return false;
                if (dx * dx + dz * dz < 500 * 500) return false;
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private void tryNextAttempt(Player player, UUID uuid, Object searchIdentity, World world, double centerX, double centerZ,
                                double halfSize, Location spawnLoc, Location originLoc, int attempt) {
        if (player == null || uuid == null) return;
        // Stale async chain: the search was cancelled (quit, TPA start,
        // cancelAll) while this attempt was queued — never continue.
        if (!isCurrentSearch(uuid, searchIdentity)) return;

        if (attempt >= MAX_ATTEMPTS) {
            // A failed search must not punish with a cooldown.
            refundSearchCooldown(uuid, searchIdentity);
            try {
                player.getScheduler().run(plugin, (t) -> {
                    if (!isCurrentSearch(uuid, searchIdentity)) return;
                    searching.remove(uuid, searchIdentity);
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getMiniMessage().deserialize("<red>Could not find a safe location. Try again later!"));
                    }
                }, null);
            } catch (Exception e) {
                // Scheduler rejected (player gone / plugin disabling):
                // still release the searching entry so it can never leak.
                searching.remove(uuid, searchIdentity);
            }
            return;
        }

        int x = (int) (centerX + (ThreadLocalRandom.current().nextDouble() * halfSize * 2 - halfSize));
        int z = (int) (centerZ + (ThreadLocalRandom.current().nextDouble() * halfSize * 2 - halfSize));
        // Cheap prefilter loop: resample without consuming an attempt until a
        // candidate passes the spawn/origin distances (or the budget is up —
        // then the last pick goes to the region thread anyway and counts as
        // the real candidate, so tiny borders still terminate via MAX_ATTEMPTS).
        for (int i = 0; i < PREFILTER_TRIES && !passesDistancePrefilter(x, z, world, spawnLoc, originLoc); i++) {
            x = (int) (centerX + (ThreadLocalRandom.current().nextDouble() * halfSize * 2 - halfSize));
            z = (int) (centerZ + (ThreadLocalRandom.current().nextDouble() * halfSize * 2 - halfSize));
        }
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
                runAttemptOnRegion(player, world, centerX, centerZ, halfSize, spawnLoc, originLoc, uuid, searchIdentity, finalX, finalZ, nextAttempt);
                } catch (Exception e) {
                    // Unexpected failure on the region thread: retry once via
                    // async unless the search already ended or attempts are up.
                    if (!isCurrentSearch(uuid, searchIdentity)) return;
                    try {
                        Bukkit.getAsyncScheduler().runNow(plugin, (t) ->
                                tryNextAttempt(player, uuid, searchIdentity, world, centerX, centerZ, halfSize, spawnLoc, originLoc, nextAttempt));
                    } catch (Exception ignored) {
                        searching.remove(uuid, searchIdentity);
                        refundSearchCooldown(uuid, searchIdentity);
                    }
                }
            });
        } catch (Exception e) {
            searching.remove(uuid, searchIdentity);
            refundSearchCooldown(uuid, searchIdentity);
            plugin.getLogger().warning("Could not continue RTP search for " + uuid + ": " + e.getMessage());
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
                                    UUID uuid, Object searchIdentity, int finalX, int finalZ, int nextAttempt) {
        // Stale async chain: the search was cancelled (quit, TPA start, death,
        // cancelAll) while this attempt was queued — never continue.
        if (uuid == null || !isCurrentSearch(uuid, searchIdentity)) return;
        if (world == null) return;

        int y;
        try {
            // L6: this runs on the destination region thread (via
            // RegionScheduler.execute with chunkX/chunkZ above), so the chunk's
            // region is owned here and getHighestBlockYAt needs no extra hop.
            // A getChunkAtAsync preload before measuring would be the async
            // alternative, but would restructure the attempt chain — kept as a
            // documented option, no risky refactor.
            y = world.getHighestBlockYAt(finalX, finalZ);
        } catch (Exception e) {
            scheduleNextAttempt(player, uuid, searchIdentity, world, centerX, centerZ, halfSize, spawnLoc, originLoc, nextAttempt);
            return;
        }

        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight() - 2) {
            scheduleNextAttempt(player, uuid, searchIdentity, world, centerX, centerZ, halfSize, spawnLoc, originLoc, nextAttempt);
            return;
        }

        Location loc = new Location(world, finalX + 0.5, y, finalZ + 0.5);

        if (spawnLoc != null && spawnLoc.getWorld() != null && spawnLoc.getWorld().equals(world)) {
            double dx = (finalX + 0.5) - spawnLoc.getX();
            double dz = (finalZ + 0.5) - spawnLoc.getZ();
            if (dx * dx + dz * dz < 256 * 256) {
                scheduleNextAttempt(player, uuid, searchIdentity, world, centerX, centerZ, halfSize, spawnLoc, originLoc, nextAttempt);
                return;
            }
        }

        if (originLoc != null && originLoc.getWorld() != null && originLoc.getWorld().equals(world)) {
            double dx = (finalX + 0.5) - originLoc.getX();
            double dz = (finalZ + 0.5) - originLoc.getZ();
            if (dx * dx + dz * dz < 500 * 500) {
                scheduleNextAttempt(player, uuid, searchIdentity, world, centerX, centerZ, halfSize, spawnLoc, originLoc, nextAttempt);
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
            try {
                player.getScheduler().run(plugin, (t) -> {
                    try {
                        if (!isCurrentSearch(uuid, searchIdentity) || !player.isOnline()) return;
                        if (!player.getWorld().equals(dest.getWorld())) {
                            refundSearchCooldown(uuid, searchIdentity);
                            player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: you changed worlds."));
                            return;
                        }
                        Location current = player.getLocation();
                        if (current.getWorld() != null && current.getWorld().equals(dest.getWorld())) {
                            double dx = dest.getX() - current.getX();
                            double dz = dest.getZ() - current.getZ();
                            if (dx * dx + dz * dz < 500 * 500) {
                                refundSearchCooldown(uuid, searchIdentity);
                                player.sendMessage(plugin.getMiniMessage().deserialize("<red>The location is no longer far enough away. Try again!"));
                                return;
                            }
                        }
                        proceedToWarmup(player, dest, uuid, searchIdentity);
                    } finally {
                        searching.remove(uuid, searchIdentity);
                    }
                }, null);
            } catch (Exception e) {
                if (searching.remove(uuid, searchIdentity)) refundSearchCooldown(uuid, searchIdentity);
                plugin.getLogger().fine("Failed to schedule RTP safe-location continuation for " + uuid + ": " + e.getMessage());
            }
        } else {
            scheduleNextAttempt(player, uuid, searchIdentity, world, centerX, centerZ, halfSize, spawnLoc, originLoc, nextAttempt);
        }
    }

    private void proceedToWarmup(Player player, Location foundLoc, UUID uuid, Object searchIdentity) {
        if (player == null || uuid == null || !isCurrentSearch(uuid, searchIdentity) || !player.isOnline()) return;
        // Dead players must never enter a warmup (race guard when death lands
        // between search and warmup). Refunds the cooldown like other aborts.
        try {
            if (player.isDead()) {
                refundSearchCooldown(uuid, searchIdentity);
                return;
            }
        } catch (Exception e) {
            refundSearchCooldown(uuid, searchIdentity);
            return;
        }

        // Snapshot: this runs at the end of an async -> region -> player-thread
        // chain, so stopModules() may have cleared teleport.yml in between.
        // Never deref fresh or abort silently — tell the player.
        org.bukkit.configuration.file.FileConfiguration teleportConfig = plugin.getTeleportConfig();
        if (teleportConfig == null) {
            refundSearchCooldown(uuid, searchIdentity);
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport is currently unavailable."));
            return;
        }

        // TPA and RTP claim the shared reservation atomically below, after
        // validation. Never cancel another manager's token here: that would
        // reopen the race between checking and reserving.

        // World re-check: the player may have changed worlds during the async
        // search. Never warm up towards a stale world snapshot.
        try {
            if (foundLoc == null || foundLoc.getWorld() == null || !player.getWorld().equals(foundLoc.getWorld())) {
                refundSearchCooldown(uuid, searchIdentity);
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: you changed worlds."));
                return;
            }
        } catch (Exception e) {
            refundSearchCooldown(uuid, searchIdentity);
            return;
        }

        // Border re-validation: the border may have shrunk/moved during the
        // async search — never warm up towards a spot outside the CURRENT border.
        try {
            if (!isInsideBorder(player.getWorld(), foundLoc)) {
                refundSearchCooldown(uuid, searchIdentity);
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: location is outside the world border. Try again!"));
                return;
            }
        } catch (Exception e) {
            refundSearchCooldown(uuid, searchIdentity);
            return;
        }

        // Distance re-check against the CURRENT position: the player may have
        // moved since the search started (origin snapshot is stale).
        try {
            Location current = player.getLocation();
            if (current != null && current.getWorld() != null && current.getWorld().equals(foundLoc.getWorld())) {
                double dx = foundLoc.getX() - current.getX();
                double dz = foundLoc.getZ() - current.getZ();
                if (dx * dx + dz * dz < 500 * 500) {
                    refundSearchCooldown(uuid, searchIdentity);
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>The location is no longer far enough away. Try again!"));
                    return;
                }
            }
        } catch (Exception e) {
            refundSearchCooldown(uuid, searchIdentity);
            return;
        }

        long seconds = teleportConfig.getLong("rtp.counter.seconds", 3);
        // Clamp like tpa.counter.seconds: negative/huge values must never
        // leak into the scheduler delay or the displayed countdown.
        seconds = Math.max(0, Math.min(30, seconds));
        Object warmupIdentity = new Object();
        synchronized (warmupLock) {
            if (shuttingDown || plugin.getRTPManager() != this) {
                refundSearchCooldown(uuid, searchIdentity);
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP is reloading. Try again shortly."));
                return;
            }
            if (!WarmupReservations.reserve(uuid, warmupIdentity)) {
                refundSearchCooldown(uuid, searchIdentity);
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: a teleport is already in progress."));
                return;
            }
            // Reservation publication and cancelAll's shutdown barrier share
            // this lock, so reload cannot miss this identity.
            warmupIdentities.put(uuid, warmupIdentity);
            transferSearchCooldown(uuid, searchIdentity, warmupIdentity);
        }
        if (!teleportConfig.getBoolean("rtp.counter.enabled", true) || seconds <= 0) {
            executeTeleport(player, foundLoc, uuid, warmupIdentity);
            return;
        }

        player.sendMessage(plugin.getMiniMessage().deserialize("<green>Safe location found!"));
        player.sendMessage(plugin.getMiniMessage().deserialize("<gray>Teleporting in <yellow>" + seconds + " <gray>seconds. Do not move, take damage or interact!"));

        final long capturedGeneration = generation;
        final ScheduledTask warmupTask;
        try {
            warmupTask = player.getScheduler().runDelayed(plugin, (wt) -> {
                // Generation and identity guards keep stale callbacks from an
                // old manager/search from clearing or using a newer warmup.
                if (!WarmupReservations.isOwner(uuid, warmupIdentity)) return;
                if (capturedGeneration != GENERATION.get() || plugin.getRTPManager() != this) {
                    refundWarmupCooldown(uuid, warmupIdentity);
                    finishWarmup(uuid, warmupIdentity);
                    return;
                }
                activeWarmups.remove(uuid);
                executeTeleport(player, foundLoc, uuid, warmupIdentity);
            }, null, seconds * 20L);
        } catch (Exception e) {
            warmupIdentities.remove(uuid, warmupIdentity);
            WarmupReservations.release(uuid, warmupIdentity);
            refundWarmupCooldown(uuid, warmupIdentity);
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP failed to start. Try again later."));
            return;
        }

        synchronized (warmupLock) {
            if (WarmupReservations.isOwner(uuid, warmupIdentity)
                    && warmupIdentities.get(uuid) == warmupIdentity) {
                activeWarmups.put(uuid, warmupTask);
                if (!WarmupReservations.isOwner(uuid, warmupIdentity)
                        || warmupIdentities.get(uuid) != warmupIdentity) {
                    activeWarmups.remove(uuid, warmupTask);
                    warmupTask.cancel();
                }
            } else {
                warmupTask.cancel();
            }
        }
    }

    private void executeTeleport(Player player, Location loc, UUID uuid, Object warmupIdentity) {
        if (player == null || !WarmupReservations.isOwner(uuid, warmupIdentity)) return;
        // Dead players must never be teleported (warmup death always cancels,
        // this is the race guard when death lands after the warmup fired).
        try {
            if (player.isDead()) {
                refundWarmupCooldown(uuid, warmupIdentity);
                finishWarmup(uuid, warmupIdentity);
                return;
            }
        } catch (Exception ignored) {
            refundWarmupCooldown(uuid, warmupIdentity);
            finishWarmup(uuid, warmupIdentity);
            return;
        }
        try {
            if (!player.isOnline()) {
                refundWarmupCooldown(uuid, warmupIdentity);
                finishWarmup(uuid, warmupIdentity);
                return;
            }
        } catch (Exception e) {
            refundWarmupCooldown(uuid, warmupIdentity);
            finishWarmup(uuid, warmupIdentity);
            return;
        }
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
                refundWarmupCooldown(uuid, warmupIdentity);
                finishWarmup(uuid, warmupIdentity);
                if (player.isOnline()) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: you changed worlds."));
                }
                return;
            }
        } catch (Exception e) {
            refundWarmupCooldown(uuid, warmupIdentity);
            finishWarmup(uuid, warmupIdentity);
            return;
        }
        // Border re-validation: the border may have changed during the warmup.
        try {
            if (!isInsideBorder(dest.getWorld(), dest)) {
                refundWarmupCooldown(uuid, warmupIdentity);
                finishWarmup(uuid, warmupIdentity);
                if (player.isOnline()) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP cancelled: location is outside the world border. Try again!"));
                }
                return;
            }
        } catch (Exception e) {
            refundWarmupCooldown(uuid, warmupIdentity);
            finishWarmup(uuid, warmupIdentity);
            return;
        }
        // Distance re-check against the CURRENT position (warmup move).
        try {
            Location current = player.getLocation();
            if (current != null && current.getWorld() != null && current.getWorld().equals(dest.getWorld())) {
                double dx = dest.getX() - current.getX();
                double dz = dest.getZ() - current.getZ();
                if (dx * dx + dz * dz < 500 * 500) {
                    refundWarmupCooldown(uuid, warmupIdentity);
                    finishWarmup(uuid, warmupIdentity);
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getMiniMessage().deserialize("<red>The location is no longer far enough away. Try again!"));
                    }
                    return;
                }
            }
        } catch (Exception e) {
            refundWarmupCooldown(uuid, warmupIdentity);
            finishWarmup(uuid, warmupIdentity);
            return;
        }
        final World destWorld = dest.getWorld();
        final int chunkX = dest.getBlockX() >> 4;
        final int chunkZ = dest.getBlockZ() >> 4;
        try {
            Bukkit.getRegionScheduler().execute(plugin, destWorld, chunkX, chunkZ, () -> {
                if (!WarmupReservations.isOwner(uuid, warmupIdentity)) return;
                boolean safe;
                try {
                    safe = isLocationSafe(dest);
                } catch (Exception e) {
                    safe = false;
                }
                if (!safe) {
                    refundWarmupCooldown(uuid, warmupIdentity);
                    finishWarmup(uuid, warmupIdentity);
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
                teleportValidated(player, dest, uuid, warmupIdentity);
            });
        } catch (Exception e) {
            refundWarmupCooldown(uuid, warmupIdentity);
            finishWarmup(uuid, warmupIdentity);
            plugin.getLogger().warning("Could not validate RTP destination for " + uuid + ": " + e.getMessage());
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
     * {@link #executeTeleport}). Destination validation runs on the destination
     * region; player state and teleport initiation run on the entity thread.
     */
    private void teleportValidated(Player player, Location dest, UUID uuid, Object warmupIdentity) {
        if (!WarmupReservations.isOwner(uuid, warmupIdentity)) return;
        if (player == null || dest == null || dest.getWorld() == null) {
            refundWarmupCooldown(uuid, warmupIdentity);
            finishWarmup(uuid, warmupIdentity);
            return;
        }
        try {
            player.getScheduler().run(plugin, t -> {
                if (!WarmupReservations.isOwner(uuid, warmupIdentity)) return;
                try {
                    if (!player.isOnline()) {
                        refundWarmupCooldown(uuid, warmupIdentity);
                        finishWarmup(uuid, warmupIdentity);
                        return;
                    }
                } catch (Exception e) {
                    refundWarmupCooldown(uuid, warmupIdentity);
                    finishWarmup(uuid, warmupIdentity);
                    return;
                }
                try {
                    if (player.isDead()) {
                        refundWarmupCooldown(uuid, warmupIdentity);
                        finishWarmup(uuid, warmupIdentity);
                        return;
                    }
                } catch (Exception ignored) {
                    refundWarmupCooldown(uuid, warmupIdentity);
                    finishWarmup(uuid, warmupIdentity);
                    return;
                }
                synchronized (warmupLock) {
                  if (shuttingDown || plugin.getRTPManager() != this
                          || warmupIdentities.get(uuid) != warmupIdentity
                          || !WarmupReservations.isOwner(uuid, warmupIdentity)) return;
                  try {
                    Location teleportDestination = dest.clone().add(0, 1, 0);
                    if (!WarmupReservations.expectTeleport(uuid, warmupIdentity, teleportDestination)) {
                        refundWarmupCooldown(uuid, warmupIdentity);
                        finishWarmup(uuid, warmupIdentity);
                        return;
                    }
                    player.teleportAsync(teleportDestination)
                            .handle((success, error) -> error == null && Boolean.TRUE.equals(success))
                            .thenAccept(success -> {
            if (!WarmupReservations.isOwner(uuid, warmupIdentity)) return;
            // thenAccept runs off the entity thread: hop back onto the
            // EntityScheduler for sendMessage (Folia scheduler retained).
            if (Boolean.TRUE.equals(success)) {
                finishWarmup(uuid, warmupIdentity);
                try {
                    player.getScheduler().run(plugin, successTask -> {
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getMiniMessage().deserialize("<green>Teleported to a safe random location!"));
                        }
                    }, null);
                } catch (Exception e) {
                    plugin.getLogger().fine("Failed to notify RTP teleport success for " + uuid + ": " + e.getMessage());
                }
                return;
            }
            // A failed teleport must not consume the cooldown.
            refundWarmupCooldown(uuid, warmupIdentity);
            finishWarmup(uuid, warmupIdentity);
            try {
                player.getScheduler().run(plugin, failureTask -> {
                    if (!player.isOnline()) return;
                    try {
                        player.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport failed. Try again!"));
                    } catch (Exception e) {
                        plugin.getLogger().fine("Failed to notify RTP teleport failure for " + uuid + ": " + e.getMessage());
                    }
                }, null);
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to schedule RTP teleport failure notify for " + uuid + ": " + e.getMessage());
            }
                    });
                  } catch (Exception e) {
                    refundWarmupCooldown(uuid, warmupIdentity);
                    finishWarmup(uuid, warmupIdentity);
                    plugin.getLogger().fine("Failed to start RTP teleport for " + uuid + ": " + e.getMessage());
                  }
                }
            }, null);
        } catch (Exception e) {
            refundWarmupCooldown(uuid, warmupIdentity);
            finishWarmup(uuid, warmupIdentity);
            plugin.getLogger().fine("Failed to schedule RTP teleport for " + uuid + ": " + e.getMessage());
        }
    }

    private void finishWarmup(UUID uuid, Object identity) {
        synchronized (warmupLock) {
            warmupIdentities.remove(uuid, identity);
            WarmupReservations.release(uuid, identity);
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
                || ground == Material.CAMPFIRE || ground == Material.SOUL_CAMPFIRE
                || ground == Material.WITHER_ROSE || ground == Material.SWEET_BERRY_BUSH
                || ground == Material.POINTED_DRIPSTONE) return false;

        org.bukkit.block.Block b1 = loc.getBlock().getRelative(0, 1, 0);
        org.bukkit.block.Block b2 = loc.getBlock().getRelative(0, 2, 0);
        if (!b1.isPassable() || !b2.isPassable()) return false;
        // Headroom uses the same configured block blacklist as the ground
        // check above (plus passability). AIR/CAVE_AIR/VOID_AIR are exempted
        // code-side: old teleport.yml files keep them in the list (defaults
        // are only merged, never pruned), and headroom at a valid spot is air.
        String head1Name = b1.getType().name();
        String head2Name = b2.getType().name();
        // Same always-unsafe basis for headroom (portal/powder-snow are
        // passable, so the passability check above would not catch them).
        if (isAlwaysUnsafeBlock(head1Name) || isAlwaysUnsafeBlock(head2Name)) return false;
        if (head1Name.contains("LEAVES") || head2Name.contains("LEAVES")) return false;
        if (blacklistEnabled) {
            java.util.List<String> blacklist = teleportConfig.getStringList("rtp.blacklist.blocks");
            if ((!isAirVariant(b1.getType()) && blacklist.contains(head1Name))
                    || (!isAirVariant(b2.getType()) && blacklist.contains(head2Name))) return false;
        } else {
            Material head1 = b1.getType();
            Material head2 = b2.getType();
            if (head1 == Material.LAVA || head1 == Material.WATER || head1 == Material.FIRE
                    || head1 == Material.CACTUS || head1 == Material.MAGMA_BLOCK
                    || head1 == Material.CAMPFIRE || head1 == Material.SOUL_CAMPFIRE
                    || head1 == Material.WITHER_ROSE || head1 == Material.SWEET_BERRY_BUSH
                    || head1 == Material.POINTED_DRIPSTONE) return false;
            if (head2 == Material.LAVA || head2 == Material.WATER || head2 == Material.FIRE
                    || head2 == Material.CACTUS || head2 == Material.MAGMA_BLOCK
                    || head2 == Material.CAMPFIRE || head2 == Material.SOUL_CAMPFIRE
                    || head2 == Material.WITHER_ROSE || head2 == Material.SWEET_BERRY_BUSH
                    || head2 == Material.POINTED_DRIPSTONE) return false;
        }

        return true;
    }

    private static boolean isAirVariant(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
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
        UUID uuid = player.getUniqueId();
        CooldownEntry entry = cooldowns.get(uuid);
        if (entry == null) return false;
        long timeLeft = (entry.expiresAt() - System.currentTimeMillis()) / 1000;
        if (timeLeft > 0) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Wait " + timeLeft + "s before using RTP again."));
            return true;
        }
        // Expired entries are purged on read so the map cannot grow without bounds.
        cooldowns.remove(uuid, entry);
        return false;
    }

    private void setCooldown(Player player, Object searchIdentity) {
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
        cooldowns.put(player.getUniqueId(), new CooldownEntry(System.currentTimeMillis() + (seconds * 1000), searchIdentity));
    }

    private void transferSearchCooldown(UUID uuid, Object searchIdentity, Object warmupIdentity) {
        if (uuid == null || searchIdentity == null || warmupIdentity == null) return;
        cooldowns.computeIfPresent(uuid, (id, entry) -> entry.operationIdentity() == searchIdentity
                ? new CooldownEntry(entry.expiresAt(), warmupIdentity) : entry);
    }

    /** Transfer the cooldown ownership as a search enters its teleport warmup. */
    private void refundSearchCooldown(UUID uuid, Object searchIdentity) {
        if (uuid == null || searchIdentity == null) return;
        refundCooldownOwnedBy(uuid, searchIdentity);
    }

    private void refundWarmupCooldown(UUID uuid, Object warmupIdentity) {
        if (uuid == null || warmupIdentity == null) return;
        refundCooldownOwnedBy(uuid, warmupIdentity);
    }

    private void refundCooldownOwnedBy(UUID uuid, Object operationIdentity) {
        cooldowns.computeIfPresent(uuid, (id, entry) -> entry.operationIdentity() == operationIdentity ? null : entry);
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
        ScheduledTask warmup;
        Object warmupIdentity;
        Object searchIdentity;
        synchronized (warmupLock) {
            warmup = activeWarmups.remove(uuid);
            warmupIdentity = warmupIdentities.remove(uuid);
            WarmupReservations.release(uuid, warmupIdentity);
            searchIdentity = searching.remove(uuid);
            if (searchIdentity != null) refundSearchCooldown(uuid, searchIdentity);
        }
        // The timer removes activeWarmups before the destination-region and
        // teleport scheduler hops finish. The identity remains the authority
        // during that pipeline, so count it as a warmup for quit cleanup too.
        boolean hadWarmup = warmup != null || warmupIdentity != null;
        if (warmup != null) {
            try {
                warmup.cancel();
            } catch (Exception ignored) {
            }
        }
        boolean hadSearch = searchIdentity != null;
        if (hadWarmup) {
            refundWarmupCooldown(uuid, warmupIdentity);
        }
        if (hadSearch) refundSearchCooldown(uuid, searchIdentity);
        // Opportunistic purge of expired entries (same as cancelSearch) so
        // cooldowns of players who never return cannot accumulate.
        try {
            cooldowns.entrySet().removeIf(e -> e.getValue() == null || e.getValue().expiresAt() <= System.currentTimeMillis());
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to purge expired RTP cooldowns: " + e.getMessage());
        }
    }

    public void cancelSearch(Player player) {
        cancelSearch(player, null);
    }

    public void cancelSearch(Player player, String reason) {
        if (player == null) return;
        UUID uuid;
        try {
            uuid = player.getUniqueId();
        } catch (Exception e) {
            return;
        }
        Object searchIdentity;
        synchronized (warmupLock) {
            searchIdentity = searching.remove(uuid);
            if (searchIdentity != null) refundSearchCooldown(uuid, searchIdentity);
        }
        boolean wasSearching = searchIdentity != null;
        if (wasSearching) {
            // A search that never reached a warmup/teleport must not consume
            // the cooldown (same as handleQuit/cancelAll).
            String msg = (reason != null && !reason.isEmpty())
                    ? "<red>RTP cancelled: " + reason
                    : "<red>RTP search cancelled.";
            notifyPlayer(player, msg);
        }
        // Opportunistic purge of this player's entry on quit/cancel so stale
        // entries of players who never come back cannot accumulate. Only
        // expired entries are removed — an active cooldown is never deleted.
        long now = System.currentTimeMillis();
        CooldownEntry entry = cooldowns.get(uuid);
        if (entry != null && entry.expiresAt() <= now) {
            cooldowns.remove(uuid, entry);
        }
        // Global opportunistic purge of all expired cooldowns (quit/cancel
        // path): without this, cooldowns of players who quit with an active
        // cooldown and never return would linger until a map-sized cleanup.
        // Active (unexpired) entries are never touched.
        try {
            cooldowns.entrySet().removeIf(e -> e.getValue() == null || e.getValue().expiresAt() <= System.currentTimeMillis());
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to purge expired RTP cooldowns: " + e.getMessage());
        }
    }

    /**
     * Refunds the current in-flight RTP's cooldown, if any. Completed RTP
     * cooldowns are not cleared by an unrelated stale cancellation.
     */
    public void refundCooldown(UUID uuid) {
        if (uuid == null) return;
        Object warmupIdentity = warmupIdentities.get(uuid);
        if (warmupIdentity != null) {
            refundWarmupCooldown(uuid, warmupIdentity);
            return;
        }
        refundSearchCooldown(uuid, searching.get(uuid));
    }

    public void cancelWarmup(Player player, String reason) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        ScheduledTask task;
        Object identity;
        synchronized (warmupLock) {
            identity = warmupIdentities.get(uuid);
            // teleportAsync cannot be reliably revoked after initiation. Keep
            // ownership until its completion callback reports the outcome.
            if (WarmupReservations.isTeleportInFlight(uuid, identity)) return;
            task = activeWarmups.remove(uuid);
            identity = warmupIdentities.remove(uuid);
            WarmupReservations.release(uuid, identity);
        }
        refundWarmupCooldown(uuid, identity);
        if (task != null || identity != null) {
            if (task != null) task.cancel();
            notifyPlayer(player, "<red>RTP cancelled: " + reason);
        }
    }

    public void cancelAll() {
        Map<UUID, Object> warmupOwners;
        java.util.List<ScheduledTask> warmupTasks;
        java.util.Set<UUID> hadWarmup;
        synchronized (warmupLock) {
            shuttingDown = true;
            warmupOwners = new java.util.HashMap<>(warmupIdentities);
            hadWarmup = new java.util.HashSet<>(warmupOwners.keySet());
            warmupTasks = new ArrayList<>(activeWarmups.values());
            warmupIdentities.forEach(WarmupReservations::release);
            warmupIdentities.clear();
            activeWarmups.clear();
        }
        for (ScheduledTask task : warmupTasks) {
            if (task != null) task.cancel();
        }
        for (UUID uuid : hadWarmup) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) notifyPlayer(p, "<red>RTP cancelled: server reloading.");
        }
        // Warmup cooldowns must be refunded too, including a teleport pipeline
        // whose timer already fired and therefore no longer has a task entry.
        for (Map.Entry<UUID, Object> entry : warmupOwners.entrySet()) {
            refundWarmupCooldown(entry.getKey(), entry.getValue());
        }
        // A stuck search must not vanish silently: notify every searcher and
        // refund the cooldown when the search never reached a warmup — same
        // as TPAManager.cancelAll notifies both sides of pending sessions.
        for (Map.Entry<UUID, Object> entry : new ArrayList<>(searching.entrySet())) {
            UUID uuid = entry.getKey();
            Object searchIdentity = entry.getValue();
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) notifyPlayer(p, "<red>RTP cancelled: server reloading.");
            if (searching.remove(uuid, searchIdentity) && !hadWarmup.contains(uuid))
                refundSearchCooldown(uuid, searchIdentity);
        }
        searching.clear();
    }
}
