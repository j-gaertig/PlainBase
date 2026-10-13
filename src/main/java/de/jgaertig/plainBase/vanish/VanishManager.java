package de.jgaertig.plainBase.vanish;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.PlayerDataLocks;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class VanishManager {

    private final PlainBase plugin;
    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();
    /**
     * Freshly unvanished UUIDs with a pending false-flush: an unvanish writes
     * {@code vanished:false} async (see savePlayerData) which may be cancelled
     * on kill/reload before hitting disk — the file would then stay stale
     * {@code true} and re-vanish on next join. flushSync() rewrites these
     * explicitly (false, or delete when persistence is off) so an unvanish
     * right before a kill can never leave stale true behind.
     */
    private final Set<UUID> dirtyUnvanished = ConcurrentHashMap.newKeySet();
    /** Quit transitions that removed a vanished UUID but still owe a true write. */
    private final Set<UUID> dirtyVanished = ConcurrentHashMap.newKeySet();
    private final Object statePersistenceLock = new Object();
    private volatile boolean persistenceFinalizing;
    // Versions fence asynchronous persistence and join-load work against newer
    // state transitions for the same UUID.
    private final Map<UUID, Long> stateVersions = new ConcurrentHashMap<>();
    private final AtomicLong stateVersionSequence = new AtomicLong();
    public record PrevSelfState(boolean collidable, boolean silent, boolean invisible) {}
    private final Map<UUID, PrevSelfState> prevSelfStates = new ConcurrentHashMap<>();

    /**
     * BUG-1 carry-over: snapshot of pre-vanish self states for reload.
     * PlainBase.stopModules() copies this before dropping the manager so
     * setupVanish() can restore it into the fresh instance — a re-vanish
     * after reload then keeps the ORIGINAL pre-vanish values via putIfAbsent
     * instead of snapshotting the still-vanished flags.
     */
    public Map<UUID, PrevSelfState> copyPrevSelfStates() {
        return new java.util.HashMap<>(prevSelfStates);
    }

    public Set<UUID> copyDirtyUnvanished() {
        synchronized (statePersistenceLock) {
            return Set.copyOf(dirtyUnvanished);
        }
    }

    public Set<UUID> copyDirtyVanished() {
        synchronized (statePersistenceLock) {
            return Set.copyOf(dirtyVanished);
        }
    }

    public record DirtyState(Set<UUID> unvanished, Set<UUID> vanished) {}

    public DirtyState copyDirtyState() {
        synchronized (statePersistenceLock) {
            return new DirtyState(Set.copyOf(dirtyUnvanished), Set.copyOf(dirtyVanished));
        }
    }

    public void restoreDirtyUnvanished(Set<UUID> uuids) {
        if (uuids == null) return;
        for (UUID uuid : uuids) {
            if (uuid == null) continue;
            synchronized (statePersistenceLock) {
                dirtyUnvanished.add(uuid);
                dirtyVanished.remove(uuid);
                vanishedPlayers.remove(uuid);
            }
            // Retry the authoritative false write in the fresh manager; the
            // guard stays active until that write succeeds.
            savePlayerData(uuid, false);
        }
    }

    public void restoreDirtyVanished(Set<UUID> uuids) {
        if (uuids == null) return;
        for (UUID uuid : uuids) {
            if (uuid == null) continue;
            Player onlinePlayer = Bukkit.getPlayer(uuid);
            boolean restoreOnline = onlinePlayer != null && onlinePlayer.isOnline();
            synchronized (statePersistenceLock) {
                dirtyVanished.add(uuid);
                dirtyUnvanished.remove(uuid);
                if (restoreOnline) vanishedPlayers.add(uuid);
            }
            savePlayerData(uuid, true);
            if (restoreOnline) {
                applySelfState(onlinePlayer);
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (viewer.equals(onlinePlayer)) continue;
                    hideFrom(viewer, onlinePlayer);
                }
                scheduleDetargetMobs(onlinePlayer);
            }
        }
    }

    /** Restores a carried true obligation without creating a new state version or save cycle. */
    public void restoreVanishedPlayer(Player player) {
        if (player == null || !player.isOnline()) return;
        synchronized (statePersistenceLock) {
            if (persistenceFinalizing) return;
            vanishedPlayers.add(player.getUniqueId());
        }
        applySelfState(player);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) continue;
            hideFrom(viewer, player);
        }
        scheduleDetargetMobs(player);
    }

    /** Nearby entity access must run on the player's owning entity thread. */
    private void scheduleDetargetMobs(Player player) {
        if (player == null || !player.isOnline()) return;
        try {
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) detargetMobs(player);
            }, null);
        } catch (Exception e) {
            plugin.getLogger().fine("Could not schedule mob de-target for vanished player: " + e.getMessage());
        }
    }

    /** Atomically merges a persisted vanish value while preserving other player data. */
    public boolean persistVanishValue(UUID uuid, Boolean vanished) {
        if (uuid == null) return false;
        try {
            synchronized (PlayerDataLocks.lockFor(uuid)) {
                File file = vanished == null ? getPlayerDataFile(uuid) : getPlayerDataFileForWrite(uuid);
                if (!file.isFile() && vanished == null) return true;
                YamlConfiguration config = new YamlConfiguration();
                if (!loadPlayerDataForUpdate(config, file, uuid)) return false;
                config.set("vanished", vanished);
                if (config.getKeys(true).isEmpty()) {
                    return !file.exists() || file.delete();
                }
                return saveAtomically(config, file, uuid);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Could not update persisted vanish state for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    /** Converts pending true writes to false obligations when the module is disabled. */
    public void prepareForRevealPersistence() {
        synchronized (statePersistenceLock) {
            for (UUID uuid : vanishedPlayers) {
                if (uuid != null) {
                    nextStateVersion(uuid);
                    dirtyUnvanished.add(uuid);
                    dirtyVanished.remove(uuid);
                }
            }
            for (UUID uuid : new java.util.HashSet<>(dirtyVanished)) {
                if (uuid == null) continue;
                nextStateVersion(uuid);
                dirtyUnvanished.add(uuid);
                dirtyVanished.remove(uuid);
            }
        }
    }

    /**
     * BUG-1 carry-over: restores states snapshotted from the previous manager.
     * Must run before restoreVanishState() re-vanishes, so putIfAbsent keeps them.
     */
    public void restorePrevSelfStates(Map<UUID, PrevSelfState> src) {
        if (src == null || src.isEmpty()) return;
        for (Map.Entry<UUID, PrevSelfState> e : src.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            prevSelfStates.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    public VanishManager(PlainBase plugin) {
        this.plugin = plugin;
    }

    public boolean isVanished(Player player) {
        if (player == null) return false;
        return isVanished(player.getUniqueId());
    }

    public boolean isVanished(UUID uuid) {
        if (uuid == null) return false;
        return vanishedPlayers.contains(uuid);
    }

    /**
     * Unmodifiable snapshot copy — callers can never mutate (or observe live
     * mutations of) internal vanish state.
     */
    public Set<UUID> getVanishedPlayers() {
        return Set.copyOf(vanishedPlayers);
    }

    /**
     * Closes this manager to new state transitions before its reload carry-over
     * is captured. The caller must do this before copying vanished players,
     * self-state snapshots, or dirty unvanishes; otherwise a callback could
     * publish state after the copies but before the async persistence flush
     * happens to set the finalization fence.
     */
    public void beginPersistenceFinalization() {
        synchronized (statePersistenceLock) {
            persistenceFinalizing = true;
        }
    }

    // NOTE: intentionally no bulk copyFrom(Set) — PlainBase.restoreVanishState()
    // re-applies via vanish() so hide effects are preserved (resetAll did not
    // run on reload with persist-on-rejoin=true). A plain addAll would copy
    // the set without re-hiding.

    /**
     * Toggles the vanish state of a player.
     *
     * @return true if the player is now vanished, false if un-vanished
     */
    public boolean toggleVanish(Player player) {
        if (player == null) return false;
        if (isVanished(player)) {
            unvanish(player);
            return false;
        }
        vanish(player);
        return true;
    }

    public void vanish(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        synchronized (statePersistenceLock) {
            if (persistenceFinalizing) return;
            nextStateVersion(uuid);
            vanishedPlayers.add(uuid);
            dirtyUnvanished.remove(uuid);
            dirtyVanished.remove(uuid);
        }
        applySelfState(player);

        // Hide this player from everyone who can't see through vanish.
        // Folia-safe: hideFrom schedules on each viewer's entity thread
        // (no direct cross-region calls); the version fence drops stale hides.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) continue;
            hideFrom(viewer, player);
        }

        // De-target nearby mobs that are currently targeting this player so
        // they stop chasing/attacking a now-vanished player. Folia-safe:
        // scheduleDetargetMobs runs the nearby-entity reads and retarget on
        // the target's entity thread, so vanish() stays safe when invoked
        // for a remote target (/vanish <other|world|all>). Never throws;
        // scheduling or lookup failures only log.
        scheduleDetargetMobs(player);

        savePlayerData(player.getUniqueId(), true);
    }

    /**
     * BUG-6 helper: de-target loop extracted from vanish() so loadPlayerData's
     * player-scheduler continuation can reuse it (same entity-thread safety).
     * Never throws.
     */
    private void detargetMobs(Player player) {
        if (player == null) return;
        try {
            for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), 32, 32, 32)) {
                if (entity instanceof Mob mob && player.equals(mob.getTarget())) {
                    mob.setTarget(null);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to de-target mobs for vanished player " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Re-hides an already-vanished player from all viewers (respawn /
     * world-change path): the client rebuilds its entity tracking on both, so
     * the hide must be re-applied. Folia-safe: hideFrom schedules on each
     * viewer's entity thread with no direct cross-region calls.
     * No-ops for non-vanished players. Never throws.
     */
    public void rehide(Player player) {
        if (player == null || !player.isOnline()) return;
        if (!isVanished(player)) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) continue;
            try {
                hideFrom(viewer, player);
            } catch (Exception ignored) {
            }
        }
        scheduleDetargetMobs(player);
    }

    public void unvanish(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        boolean online = player.isOnline();
        synchronized (statePersistenceLock) {
            if (persistenceFinalizing) return;
            nextStateVersion(uuid);
            if (!online) {
                vanishedPlayers.remove(uuid);
                prevSelfStates.remove(uuid);
                dirtyUnvanished.add(uuid);
            } else {
                resetSelfState(player);
                vanishedPlayers.remove(uuid);
                dirtyUnvanished.add(uuid);
            }
            dirtyVanished.remove(uuid);
        }
        if (!online) {
            savePlayerData(uuid, false);
            return;
        }
        // BUG-1 order: resetSelfState BEFORE removing from vanishedPlayers so
        // its synchronous wasVanished capture still sees us as vanished (the
        // fallback path depends on it — the async task runs after the remove).

        // Reveal via the scheduler fallback showTo (Folia-safe: each
        // viewer's entity thread, no direct cross-region calls).
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) continue;
            showTo(viewer, player);
        }

        savePlayerData(player.getUniqueId(), false);
    }

    /**
     * Applies all vanish state to a player who just joined (e.g. after a rejoin
     * with persist-on-rejoin) and hides all existing vanished players from them.
     * <p>
     * P0 rejoin-window fix: the persisted-vanish check runs synchronously and
     * the rejoining player is added to the in-memory set plus hidden from all
     * online viewers immediately. The async load below then only confirms and
     * applies self state — there is no window where the player is visible.
     */
    public void applyOnJoin(Player player) {
        applyOnJoin(player, null);
    }

    /**
     * P2 single-read join path: the listener already read the persisted flag
     * (for join-message suppression) — pass it as {@code persistedVanishHint}
     * so the file is not read twice per join. A null hint means "not
     * pre-read": the pre-hide reads it itself. Never throws.
     */
    public void applyOnJoin(Player player, Boolean persistedVanishHint) {
        // Pre-hide: schedule hides before the join message is broadcast
        // (right after the event) and before any viewer can see the joiner.
        // Folia-safe: hideFrom runs on each viewer's entity thread (no
        // direct cross-region calls). Guarded so disk I/O only happens when
        // persistence is on.
        try {
            FileConfiguration cfg = plugin.getVanishConfig();
            boolean persistOn = cfg != null && cfg.getBoolean("vanish.persist-on-rejoin", true);
            boolean persisted = persistOn && !dirtyUnvanished.contains(player.getUniqueId())
                    && (dirtyVanished.contains(player.getUniqueId()) || (persistedVanishHint != null
                    ? persistedVanishHint
                    : hasPersistedVanish(player.getUniqueId())));
            if (persisted) {
                UUID uuid = player.getUniqueId();
                long version;
                synchronized (statePersistenceLock) {
                    if (persistenceFinalizing) return;
                    if (vanishedPlayers.add(uuid)) version = nextStateVersion(uuid);
                    else version = currentStateVersion(uuid);
                }
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (viewer.equals(player)) continue;
                    hideFrom(viewer, player, version);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to pre-hide rejoining vanished player " + player.getName() + ": " + e.getMessage());
        }

        loadPlayerData(player);

        // A new viewer must not see players who are already vanished:
        // schedule hides via hideFrom (Folia-safe, viewer entity thread).
        for (UUID uuid : vanishedPlayers) {
            Player vanishedPlayer = Bukkit.getPlayer(uuid);
            if (vanishedPlayer != null && !vanishedPlayer.equals(player)) {
                try {
                    hideFrom(player, vanishedPlayer);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Single source of truth for reading the persisted vanish flag.
     * Used by the async load and by the join handler, where the decision must
     * be made synchronously (the join message is broadcast right after the
     * event, so it cannot be changed from an async continuation).
     * <p>
     * NOTE on sync file I/O: this reads one tiny per-player file from the
     * join path. That is deliberate — the alternative (async read) cannot
     * suppress the join message in time. The read is guarded (missing file
     * returns false without I/O beyond an exists check) and can never throw:
     * any I/O or parse failure logs a warning and returns false, so no
     * exception can ever escape into the join event.
     */
    public boolean hasPersistedVanish(UUID uuid) {
        try {
            if (uuid == null) return false;
            if (dirtyUnvanished.contains(uuid)) return false;
            if (dirtyVanished.contains(uuid)) return true;
            // Shared per-UUID lock with TPAManager (same physical file): a
            // concurrent tpauto save must never interleave with this read.
            synchronized (PlayerDataLocks.lockFor(uuid)) {
                if (dirtyUnvanished.contains(uuid)) return false;
                if (dirtyVanished.contains(uuid)) return true;
                File file = getPlayerDataFile(uuid);
                if (!file.isFile()) return false;
                YamlConfiguration config = new YamlConfiguration();
                try {
                    config.load(file);
                } catch (org.bukkit.configuration.InvalidConfigurationException corrupt) {
                    backupCorrupt(file);
                    plugin.getLogger().warning("Corrupt persisted vanish state for " + uuid
                            + " moved aside, assuming not vanished: " + corrupt.getMessage());
                    return false;
                }
                return config.getBoolean("vanished", false);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read persisted vanish state for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    public void loadPlayerData(Player player) {
        if (player == null) return;
        UUID uuid;
        try {
            uuid = player.getUniqueId();
        } catch (Exception e) {
            return;
        }
        FileConfiguration vanishConfig = plugin.getVanishConfig();
        if (vanishConfig == null) return;
        if (!vanishConfig.getBoolean("vanish.persist-on-rejoin", true)) return;
        final long capturedVersion = currentStateVersion(uuid);

        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            // The sync pre-hide in applyOnJoin() already added the player when
            // the persisted flag was true. This async step only confirms and
            // applies state — it must not drop a freshly vanished player whose
            // save has not hit disk yet, so a pre-hidden entry counts as proof.
            boolean persisted = hasPersistedVanish(uuid);
            if (currentStateVersion(uuid) != capturedVersion) return;
            synchronized (statePersistenceLock) {
                if (persistenceFinalizing) return;
            }
            if (!vanishedPlayers.contains(uuid) && !persisted) return;

            player.getScheduler().run(plugin, (t) -> {
                if (currentStateVersion(uuid) != capturedVersion) return;
                if (!player.isOnline()) {
                    // Stale-race: the player may have quit and rejoined between
                    // scheduling and execution — the new session owns the UUID
                    // now. Only remove when the currently online player is still
                    // this exact instance.
                    try {
                        if (Bukkit.getPlayer(uuid) == player) {
                            synchronized (statePersistenceLock) {
                                if (!persistenceFinalizing && currentStateVersion(uuid) == capturedVersion) {
                                    vanishedPlayers.remove(uuid);
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    return;
                }

                synchronized (statePersistenceLock) {
                    if (persistenceFinalizing || currentStateVersion(uuid) != capturedVersion) return;
                    vanishedPlayers.add(uuid);
                }
                applySelfState(player);

                // BUG-6: same de-target as vanish() — runs here on the player
                // scheduler (entity thread), so nearby-entity lookups are safe.
                detargetMobs(player);

                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (viewer.equals(player)) continue;
                    if (isVanished(viewer)) {
                        // Vanished players see each other (see canSee logic)
                        showTo(viewer, player);
                    } else {
                        hideFrom(viewer, player);
                    }
                }
            }, null);
        });
    }

    private CompletableFuture<Void> savePlayerData(UUID uuid, boolean vanished) {
        FileConfiguration vanishConfig = plugin.getVanishConfig();
        if (vanishConfig == null) return CompletableFuture.completedFuture(null);
        boolean persist = vanishConfig.getBoolean("vanish.persist-on-rejoin", true);
        final long version = currentStateVersion(uuid);

        // Tracked so stopModules() can await it (a kill right after /vanish
        // must not lose the state to a cancelled async task — see flushSync).
        CompletableFuture<Void> pending = new CompletableFuture<>();
        plugin.trackPendingSave(pending);
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
              try {
                // Shared per-UUID lock with TPAManager: load-merge-save must
                // never interleave with a concurrent tpauto save of the same
                // file (both keys would otherwise overwrite each other).
                synchronized (PlayerDataLocks.lockFor(uuid)) {
                    if (currentStateVersion(uuid) != version) return;
                    File file = getPlayerDataFileForWrite(uuid);

                    if (!persist) {
                        // playerdata is shared with TPAManager. Remove only our
                        // key and preserve unrelated values in the same file.
                        if (clearPersistedVanish(uuid)) {
                            dirtyUnvanished.remove(uuid);
                            dirtyVanished.remove(uuid);
                        }
                        return;
                    }

                    YamlConfiguration config = new YamlConfiguration();
                    if (!loadPlayerDataForUpdate(config, file, uuid)) return;

                    config.set("vanished", vanished);

                    boolean saved = saveAtomically(config, file, uuid);
                    if (vanished && saved) dirtyVanished.remove(uuid);
                    // Successful false-write settles the dirty entry: a later
                    // flushSync need not rewrite it (see dirtyUnvanished).
                    if (!vanished && saved) dirtyUnvanished.remove(uuid);
                }
              } finally {
                  pending.complete(null);
              }
            });
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Could not schedule vanish-state save for " + uuid + ": " + e.getMessage());
            pending.completeExceptionally(e);
        }
        return pending;
    }

    /**
     * Synchronous flush of the current in-memory vanish set, called from
     * stopModules() after awaiting tracked async saves: async tasks may be
     * cancelled on disable/reload and their changes would be lost, so the
     * authoritative state is rewritten directly. Never throws.
     * <p>
     * Also flushes freshly-unvanished UUIDs (dirtyUnvanished) as
     * {@code vanished:false} (or deletes their files when persistence is off)
     * so an unvanish right before a kill cannot leave a stale {@code true}
     * behind, and purges orphaned prevSelfStates snapshots for UUIDs no
     * longer vanished so players who never rejoin cannot leak memory.
     */
    public void flushSync() {
        FileConfiguration snapshot = null;
        try {
            snapshot = plugin.getVanishConfig();
        } catch (Exception ignored) {
        }
        flushSync(snapshot);
    }

    /**
     * Snapshot variant for the reload path: stopModules(false) schedules the
     * flush async while configs.clear() runs immediately — the task must use
     * the config captured BEFORE clear (a live lookup would see null and fall
     * back to the wrong persist default). A null snapshot keeps the previous
     * default (persist on). Never throws.
     */
    public void flushSync(FileConfiguration snapshot) {
        // Also called directly on shutdown. On reload the fence is normally
        // already established synchronously by stopModules before carry-over
        // snapshots are copied; keep this idempotent guard for other callers.
        beginPersistenceFinalization();
        boolean persist = snapshot == null || snapshot.getBoolean("vanish.persist-on-rejoin", true);
        Set<UUID> trueWrites = new java.util.HashSet<>(vanishedPlayers);
        trueWrites.addAll(dirtyVanished);
        for (UUID uuid : trueWrites) {
            if (uuid == null) continue;
            try {
                synchronized (PlayerDataLocks.lockFor(uuid)) {
                    // This UUID was only in the earlier snapshot. An unvanish
                    // may have completed and settled its false-write before
                    // this flush acquired the file lock; never resurrect that
                    // stale true state on disk.
                    if (!vanishedPlayers.contains(uuid) && !dirtyVanished.contains(uuid)) continue;
                    if (!persist) {
                        if (clearPersistedVanish(uuid)) dirtyVanished.remove(uuid);
                        continue;
                    }
                    File file = getPlayerDataFileForWrite(uuid);
                    YamlConfiguration config = new YamlConfiguration();
                    if (file.isFile()) {
                        try {
                            config.load(file);
                        } catch (org.bukkit.configuration.InvalidConfigurationException corrupt) {
                            if (!backupCorrupt(file)) continue;
                            plugin.getLogger().warning("Corrupt player data for " + uuid
                                    + " moved aside while flushing vanish state.");
                        } catch (Exception e) {
                            plugin.getLogger().warning("Could not read player data for " + uuid
                                    + "; preserving it during vanish flush: " + e.getMessage());
                            continue;
                        }
                    }
                    config.set("vanished", true);
                    if (saveAtomically(config, file, uuid)) dirtyVanished.remove(uuid);
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to flush vanish state for " + uuid + ": " + e.getMessage());
            }
        }
        // Freshly-unvanished dirty set: rewrite false (or delete when
        // persistence is off) unless re-vanished since. Keep failed writes
        // dirty so a later join still treats the stale on-disk true as false.
        for (UUID uuid : new java.util.HashSet<>(dirtyUnvanished)) {
            if (uuid == null) {
                dirtyUnvanished.remove(null);
                continue;
            }
            boolean persisted = false;
            boolean stateChanged = false;
            try {
                synchronized (PlayerDataLocks.lockFor(uuid)) {
                    // State can change after the dirty snapshot but before
                    // this file lock is acquired. Recheck under the same lock
                    // used by async persistence so a concurrent re-vanish
                    // cannot be overwritten with false during shutdown.
                    if (vanishedPlayers.contains(uuid)) {
                        dirtyUnvanished.remove(uuid);
                        stateChanged = true;
                    } else if (!dirtyUnvanished.contains(uuid)) {
                        // The regular async false-write already settled it.
                        stateChanged = true;
                    } else {
                    if (!persist) {
                        persisted = clearPersistedVanish(uuid);
                    } else {
                        try {
                            File file = getPlayerDataFileForWrite(uuid);
                            YamlConfiguration config = new YamlConfiguration();
                            if (file.isFile()) {
                                try {
                                    config.load(file);
                                } catch (org.bukkit.configuration.InvalidConfigurationException corrupt) {
                                    if (!backupCorrupt(file)) continue;
                                    plugin.getLogger().warning("Corrupt player data for " + uuid
                                            + " moved aside while flushing unvanish state.");
                                } catch (Exception e) {
                                    plugin.getLogger().warning("Could not read player data for " + uuid
                                            + "; preserving it during unvanish flush: " + e.getMessage());
                                    continue;
                                }
                            }
                            config.set("vanished", false);
                            persisted = saveAtomically(config, file, uuid);
                        } catch (Exception e) {
                            plugin.getLogger().fine("Failed to flush unvanish state for " + uuid + ": " + e.getMessage());
                        }
                    }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to flush unvanish state for " + uuid + ": " + e.getMessage());
            } finally {
                if (persisted) dirtyUnvanished.remove(uuid);
                if (persisted || (!stateChanged && !dirtyUnvanished.contains(uuid))) {
                    try {
                        prevSelfStates.remove(uuid);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        // Purge orphaned self-state snapshots for UUIDs no longer vanished
        // (quit while vanished but never rejoined): without this each such
        // player leaks one map entry forever.
        try {
            for (UUID uuid : new java.util.HashSet<>(prevSelfStates.keySet())) {
                if (uuid != null && !vanishedPlayers.contains(uuid)) {
                    try {
                        if (Bukkit.getPlayer(uuid) == null) prevSelfStates.remove(uuid);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Moves a corrupt playerdata file aside to {@code <uuid>.yml.corrupt-<ts>}
     * instead of overwriting or deleting it. Never throws.
     */
    private boolean backupCorrupt(File file) {
        try {
            File backup = new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            plugin.getLogger().fine("Could not back up corrupt player data " + file.getName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Load the shared playerdata file before changing one key. A YAML syntax
     * error is safe to replace only after the original has been preserved;
     * ordinary I/O failures must leave the unreadable file untouched.
     */
    private boolean loadPlayerDataForUpdate(YamlConfiguration config, File file, UUID uuid) {
        if (!file.isFile()) return true;
        try {
            config.load(file);
            return true;
        } catch (org.bukkit.configuration.InvalidConfigurationException corrupt) {
            if (!backupCorrupt(file)) return false;
            plugin.getLogger().warning("Corrupt player data for " + uuid
                    + " moved aside, rewriting vanish state.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read player data for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Crash-safe write: dump to "{@code <uuid>.yml.tmp}" in the same directory,
     * then move over the target atomically (non-atomic fallback where the file
     * system lacks atomic-move support), so a crash can never leave a
     * half-written playerdata file behind. Same pattern as
     * TeamManager.saveQuietly(). Never throws.
     */
    private boolean saveAtomically(YamlConfiguration config, File target, UUID uuid) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            config.save(tmp);
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save player data for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    private File getPlayerDataFile(UUID uuid) {
        // Read path: never creates directories as a side effect.
        return new File(new File(plugin.getDataFolder(), "data/playerdata"), uuid.toString() + ".yml");
    }

    private File getPlayerDataFileForWrite(UUID uuid) {
        File folder = new File(plugin.getDataFolder(), "data/playerdata");
        if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
            plugin.getLogger().warning("Could not create directory: " + folder);
        }
        return new File(folder, uuid.toString() + ".yml");
    }

    /** Caller holds the shared per-playerdata lock. */
    private boolean clearPersistedVanish(UUID uuid) {
        File file = getPlayerDataFile(uuid);
        if (!file.isFile()) return true;
        YamlConfiguration shared = new YamlConfiguration();
        try {
            shared.load(file);
        } catch (Exception e) {
            // A corrupt shared file cannot safely be rewritten from a partial
            // parse. Preserve it for diagnosis instead of deleting other data.
            plugin.getLogger().fine("Could not clear vanish flag in corrupt player data for " + uuid + ": " + e.getMessage());
            return false;
        }
        shared.set("vanished", null);
        if (shared.getKeys(true).isEmpty()) {
            if (!file.delete()) {
                plugin.getLogger().fine("Could not delete empty player data for " + uuid);
                return false;
            }
            return true;
        } else {
            return saveAtomically(shared, file, uuid);
        }
    }

    /**
     * Quit cleanup: the in-memory vanish entry is ALWAYS removed so
     * vanishedPlayers can never grow without bound (one stale UUID per
     * ever-vanished player). With persist-on-rejoin=true the file on disk
     * keeps the state — applyOnJoin() re-vanishes on the next join via
     * hasPersistedVanish(). No periodic purge (would be a feature).
     * <p>
     * The self-state snapshot is kept only when the player quit while
     * vanished AND persistence is on (the rejoin re-vanish then restores the
     * ORIGINAL pre-vanish values via putIfAbsent). Otherwise it is dropped
     * with the vanish entry so prevSelfStates can never leak.
     */
    public void handleQuit(Player player) {
        if (player == null) return;
        UUID uuid;
        try {
            uuid = player.getUniqueId();
        } catch (Exception e) {
            return;
        }
        final boolean wasVanished;
        final long quitVersion;
        synchronized (statePersistenceLock) {
            if (persistenceFinalizing) return;
            wasVanished = vanishedPlayers.remove(uuid);
            if (wasVanished) {
                dirtyVanished.add(uuid);
                dirtyUnvanished.remove(uuid);
            } else {
                dirtyUnvanished.add(uuid);
                dirtyVanished.remove(uuid);
            }
            quitVersion = nextStateVersion(uuid); // invalidate old callbacks and queue the authoritative state
        }
        boolean persist = true;
        try {
            FileConfiguration cfg = plugin.getVanishConfig();
            if (cfg != null) persist = cfg.getBoolean("vanish.persist-on-rejoin", true);
        } catch (Exception ignored) {
        }
        if (!(persist && wasVanished)) {
            try {
                prevSelfStates.remove(uuid);
            } catch (Exception ignored) {
            }
        }
        // Persist the authoritative quit state even when the player had
        // already unvanished. An earlier `vanished: false` write may still
        // be queued and was invalidated above; without this replacement
        // write, an older `true` value can survive and re-vanish the player
        // on their next join.
        savePlayerData(uuid, wasVanished && persist)
                .whenComplete((ignored, error) -> stateVersions.remove(uuid, quitVersion));
    }

    public boolean canSee(Player viewer, Player target) {
        try {
            if (viewer == null || target == null) return false;
            if (viewer.equals(target)) return true;
            return canSee(viewer, target.getUniqueId());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean canSee(Player viewer, UUID targetUuid) {
        try {
            if (viewer == null || targetUuid == null) return false;
            if (viewer.getUniqueId().equals(targetUuid)) return true;
            if (!isVanished(targetUuid)) return true;
            if (viewer.hasPermission("plainbase.vanish.see")) return true;
            FileConfiguration vanishConfig = plugin.getVanishConfig();
            if (vanishConfig != null && vanishConfig.getBoolean("vanish.op-see", true) && viewer.isOp()) return true;
            return isVanished(viewer); // Staff who is vanished can see other vanished players
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * General capability check: can this viewer see vanished players at all
     * (permission, op-see or vanished-sees-vanished)? Used by tab-completion
     * gating and the vanish_cansee placeholder where no specific target exists.
     */
    public boolean canSeeVanished(Player viewer) {
        try {
            if (viewer == null) return false;
            if (viewer.hasPermission("plainbase.vanish.see")) return true;
            FileConfiguration vanishConfig = plugin.getVanishConfig();
            if (vanishConfig != null && vanishConfig.getBoolean("vanish.op-see", true) && viewer.isOp()) return true;
            return isVanished(viewer);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Hides the target player from the given viewer (entity + tab list).
     * <p>
     * Note on Paper behaviour: hiding a player entity ALWAYS also removes the
     * tab-list entry (CraftPlayer.unregisterEntity sends a PlayerInfoRemove
     * packet), and {@code Player#listPlayer} throws IllegalStateException while
     * the entity is hidden. Keeping the tab entry visible for a hidden player
     * is therefore not possible with the public Bukkit/Paper API (would
     * require packet-level NMS).
     */
    private void hideFrom(Player viewer, Player target) {
        hideFrom(viewer, target, -1L);
    }

    private void hideFrom(Player viewer, Player target, long targetVersion) {
        if (viewer == null || target == null) return;
        final UUID targetUuid = target.getUniqueId();
        final long scheduledVersion = targetVersion >= 0 ? targetVersion : currentStateVersion(targetUuid);

        viewer.getScheduler().run(plugin, (t) -> {
            // The target or viewer may have disconnected between scheduling and
            // execution (next tick) — guard against Paper throwing on stale entities.
            if (!viewer.isOnline() || Bukkit.getPlayer(targetUuid) != target) return;
            if (currentStateVersion(targetUuid) != scheduledVersion) return;
            if (canSee(viewer, targetUuid)) return;
            // Hiding the entity removes it from view and from the tab list.
            try {
                viewer.hideEntity(plugin, target);
            } catch (Exception ignored) {
            }
        }, null);
    }

    /**
     * Shows the target player to the given viewer again (entity + tab list).
     */
    private void showTo(Player viewer, Player target) {
        if (viewer == null || target == null) return;
        final UUID targetUuid = target.getUniqueId();
        viewer.getScheduler().run(plugin, (t) -> {
            // The target may have disconnected between scheduling and execution
            // (next tick) — guard against Paper throwing on stale entities.
            if (!viewer.isOnline() || Bukkit.getPlayer(targetUuid) != target || isVanished(targetUuid)) return;
            try {
                viewer.showEntity(plugin, target);
            } catch (Exception ignored) {
                return;
            }
            // BUG-4: listPlayer in try/catch like unvanish (line ~126) — it may
            // throw while entity state settles; showEntity above already ran.
            try {
                viewer.listPlayer(target);
            } catch (Exception ignored) {
            }
        }, null);
    }

    private long nextStateVersion(UUID uuid) {
        long version = stateVersionSequence.incrementAndGet();
        stateVersions.put(uuid, version);
        return version;
    }

    private long currentStateVersion(UUID uuid) {
        return stateVersions.getOrDefault(uuid, 0L);
    }

    /**
     * Applies the player's own vanish state (visibility, collision, sounds).
     * Folia: entity state is entity-thread-only — hops via the player's
     * scheduler. Null- and isOnline-guarded both before scheduling (stale or
     * offline callers like resetAll races) and inside the task (disconnect
     * between scheduling and execution).
     */
    private void applySelfState(Player player) {
        if (player == null || !player.isOnline()) return;
        // Snapshot for the synchronous part; the scheduler body re-reads the
        // config with a null-guard because stopModules() may have cleared it
        // between scheduling and execution (next tick) — the captured reference
        // alone would NPE when the module was reloaded/disabled in between.
        final FileConfiguration snapshot = plugin.getVanishConfig();

        player.getScheduler().run(plugin, (t) -> {
            if (!player.isOnline()) return;
            FileConfiguration config = plugin.getVanishConfig();
            if (config == null) config = snapshot;
            if (config == null) return;
            // Snapshot the ORIGINAL pre-vanish self state exactly once
            // (putIfAbsent): a second vanish without unvanish must keep the
            // original, and a rejoin with persist must keep the pre-vanish
            // values (not the still-vanished state from before the quit).
            // After a reload restoreVanishState() re-vanishes while the flags
            // are still applied — that case is covered by the prevSelfStates
            // carry-over in setupVanish() (putIfAbsent keeps the carried
            // original), so every vanish with no stored prev snapshots here:
            // skipping the snapshot when the flags "look vanished" would
            // leave third-party states (silent/non-collidable set by another
            // plugin) without a snapshot, and the unvanish fallback would
            // then hardcode them away. Always snapshot when no prev exists.
            try {
                UUID uuid = player.getUniqueId();
                try {
                    if (!prevSelfStates.containsKey(uuid)) {
                        prevSelfStates.putIfAbsent(uuid,
                                new PrevSelfState(player.isCollidable(), player.isSilent(), player.isInvisible()));
                    }
                } catch (Exception ignored) {
                }
            } catch (Exception ignored) {
            }
            if (config.getBoolean("vanish.no-collision", true)) {
                player.setCollidable(false);
            }

            if (config.getBoolean("vanish.no-step-sound", true)) {
                player.setSilent(true);
            }

            if (config.getBoolean("vanish.invisible", true)) {
                try {
                    player.setInvisible(true);
                } catch (Exception ignored) {
                }
            }
        }, null);
    }

    /**
     * P1 sync-first mirror of {@link #resetSelfState(Player)} for
     * {@link #resetAll()}: EntityScheduler tasks scheduled during disable may
     * be discarded, leaving stuck invisibility/collision behind. Every direct
     * call may throw off the entity thread — on the first throw this returns
     * false WITHOUT consuming the snapshot so the scheduled fallback still
     * restores it. Returns true when fully applied (caller then skips the
     * scheduled self-state fallback). Never throws.
     */
    private boolean tryResetSelfStateSync(Player player, FileConfiguration snapshot) {
        try {
            if (player == null || !player.isOnline()) return false;
            UUID uuid;
            try {
                uuid = player.getUniqueId();
            } catch (Exception e) {
                return false;
            }
            PrevSelfState prev = prevSelfStates.get(uuid);
            if (prev != null) {
                player.setInvisible(prev.invisible());
                player.setCollidable(prev.collidable());
                player.setSilent(prev.silent());
                prevSelfStates.remove(uuid, prev);
                return true;
            }
            // No stored state: same generic fallback as resetSelfState(),
            // config-gated (only reset what vanish would have applied).
            // The caller iterates vanishedPlayers, so wasVanished holds.
            FileConfiguration config = plugin.getVanishConfig();
            if (config == null) config = snapshot;
            boolean resetInvisible = config == null || config.getBoolean("vanish.invisible", true);
            if (resetInvisible) player.setInvisible(false);
            boolean resetCollision = config == null || config.getBoolean("vanish.no-collision", true);
            boolean resetSilent = config == null || config.getBoolean("vanish.no-step-sound", true);
            if (resetCollision) player.setCollidable(true);
            if (resetSilent) player.setSilent(false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void resetSelfState(Player player) {
        scheduleSelfStateReset(player, null);
    }

    private CompletableFuture<Void> resetSelfStateTracked(Player player) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        scheduleSelfStateReset(player, completed);
        return completed;
    }

    private void scheduleSelfStateReset(Player player, CompletableFuture<Void> completed) {
        if (player == null || !player.isOnline()) {
            if (completed != null) completed.complete(null);
            return;
        }
        UUID syncUuid;
        try {
            syncUuid = player.getUniqueId();
        } catch (Exception e) {
            if (completed != null) completed.complete(null);
            return;
        }
        // BUG-1 guard fix: capture wasVanished synchronously — callers
        // (unvanish/resetAll) invoke this BEFORE removing from vanishedPlayers,
        // the async task below runs after the remove, so a live isVanished()
        // check there would always see false and the fallback would never run.
        final boolean wasVanished = isVanished(syncUuid);
        final FileConfiguration snapshot = plugin.getVanishConfig();
        try {
          player.getScheduler().run(plugin, (t) -> {
          try {
            if (!player.isOnline()) {
                // Stale task: the player quit before the reset ran. Drop any
                // leftover snapshot so prevSelfStates can never leak via this
                // early-return (unvanish/resetAll already removed the vanish
                // entry synchronously; quit persistence needs no snapshot).
                try {
                    prevSelfStates.remove(syncUuid);
                } catch (Exception ignored) {
                }
                return;
            }
            UUID uuid;
            try {
                uuid = player.getUniqueId();
            } catch (Exception e) {
                return;
            }
            // Restore the previously saved state when available — never
            // hardcode over third-party changes.
            PrevSelfState prev = prevSelfStates.remove(uuid);
            if (prev != null) {
                try {
                    player.setInvisible(prev.invisible());
                } catch (Exception ignored) {
                }
                try {
                    player.setCollidable(prev.collidable());
                } catch (Exception ignored) {
                }
                try {
                    player.setSilent(prev.silent());
                } catch (Exception ignored) {
                }
                return;
            }
            // Fallback when no stored state exists (e.g. vanished before this
            // fix, or manager rebuilt): only run when we were still vanished by
            // us at call time (wasVanished, not live isVanished) — otherwise
            // the hardcoded setInvisible(false) below would clear third-party
            // invisibility (potions, other plugins).
            if (!wasVanished) return;
            FileConfiguration config = plugin.getVanishConfig();
            if (config == null) config = snapshot;
            boolean resetInvisible = config == null || config.getBoolean("vanish.invisible", true);
            if (resetInvisible) {
                try {
                    player.setInvisible(false);
                } catch (Exception ignored) {
                }
            }
            boolean resetCollision = config == null || config.getBoolean("vanish.no-collision", true);
            boolean resetSilent = config == null || config.getBoolean("vanish.no-step-sound", true);
            if (resetCollision) {
                try {
                    player.setCollidable(true);
                } catch (Exception ignored) {
                }
            }
            if (resetSilent) {
                try {
                    player.setSilent(false);
                } catch (Exception ignored) {
                }
            }
          } finally {
              if (completed != null) completed.complete(null);
          }
          }, completed == null ? null : () -> completed.complete(null));
        } catch (Exception e) {
            if (completed != null) completed.completeExceptionally(e);
        }
    }

    /**
     * Reveals all currently vanished players (used when the module is stopped/reloaded).
     * <p>
     * When persist-on-rejoin is disabled the persisted files are deleted
     * synchronously per player: without this a stale {@code vanished:true}
     * file would re-vanish the player as soon as persistence is enabled again.
     */
    public void resetAll() {
        resetAll(true);
    }

    /**
     * Reveals live state. Reload callers pass false so persistence cleanup is
     * handled by the tracked async reload flush instead of blocking the global
     * region thread; shutdown keeps the synchronous cleanup behavior.
     */
    public void resetAll(boolean clearPersistedSynchronously) {
        java.util.List<CompletableFuture<Void>> scheduledSelfResets = new java.util.ArrayList<>();
        boolean persist;
        FileConfiguration cfgSnapshot;
        try {
            cfgSnapshot = plugin.getVanishConfig();
            persist = cfgSnapshot == null || cfgSnapshot.getBoolean("vanish.persist-on-rejoin", true);
        } catch (Exception e) {
            persist = true;
            cfgSnapshot = null;
        }
        for (UUID uuid : new java.util.HashSet<>(vanishedPlayers)) {
            try {
                nextStateVersion(uuid);
                if (!persist && clearPersistedSynchronously) {
                    try {
                        synchronized (PlayerDataLocks.lockFor(uuid)) {
                            clearPersistedVanish(uuid);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().fine("Failed to clear persisted vanish state for " + uuid + ": " + e.getMessage());
                    }
                }
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    // Apply immediately where this platform/thread permits it
                    // (especially Paper's shutdown thread). A queued task
                    // owned by this plugin may be discarded as disable
                    // completes, so retain the scheduler fallback only when
                    // the synchronous attempt is rejected (e.g. Folia region
                    // ownership).
                    if (!tryResetSelfStateSync(player, cfgSnapshot)) {
                        try {
                            CompletableFuture<Void> reset = resetSelfStateTracked(player);
                            scheduledSelfResets.add(reset);
                            // Reload setup waits for this entity-thread reset through
                            // reloadModulesAsync's existing pending-save barrier. Do not
                            // synchronously block the global region thread here.
                            plugin.trackPendingSave(reset);
                        } catch (Exception e) {
                            plugin.getLogger().fine("Failed to reset vanish state for " + uuid + ": " + e.getMessage());
                        }
                    }
                    vanishedPlayers.remove(uuid);
                    try {
                        for (Player viewer : Bukkit.getOnlinePlayers()) {
                            if (viewer.equals(player)) continue;
                            if (!tryShowToSync(viewer, player)) showTo(viewer, player);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().fine("Failed to sync-reveal vanished player " + uuid + ": " + e.getMessage());
                    }
                } else {
                    vanishedPlayers.remove(uuid);
                    prevSelfStates.remove(uuid);
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to reset vanished player " + uuid + ": " + e.getMessage());
            }
        }
        // On Folia the direct self-state calls above are rejected off the
        // player's entity thread. Wait briefly for the entity scheduler tasks
        // to apply before returning from disable/reload, when plugin-owned
        // tasks may be cancelled. One shared deadline bounds shutdown time
        // regardless of online player count.
        if (clearPersistedSynchronously && !scheduledSelfResets.isEmpty()) {
            try {
                CompletableFuture.allOf(scheduledSelfResets.toArray(CompletableFuture[]::new))
                        .get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().fine("Timed out waiting for Folia vanish self-state resets: " + e.getMessage());
            }
        }
    }

    /** Best-effort immediate reveal for shutdown; false means use scheduler fallback. */
    private boolean tryShowToSync(Player viewer, Player target) {
        if (viewer == null || target == null) return false;
        try {
            if (!viewer.isOnline() || !target.isOnline() || isVanished(target.getUniqueId())) return false;
            viewer.showEntity(plugin, target);
            try {
                viewer.listPlayer(target);
            } catch (Exception ignored) {
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
