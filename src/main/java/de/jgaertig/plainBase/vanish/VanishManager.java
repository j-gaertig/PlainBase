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
        vanishedPlayers.add(player.getUniqueId());
        dirtyUnvanished.remove(player.getUniqueId());
        applySelfState(player);

        // Hide this player from everyone who can't see through vanish.
        // V1 race fix: hide immediately (same pattern as applyOnJoin) AND keep
        // the scheduled follow-up — the immediate call closes the 1-tick window,
        // the scheduled hideFrom covers cross-region viewers that reject a
        // direct call.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) continue;
            try {
                if (!canSee(viewer, player)) {
                    viewer.hideEntity(plugin, player);
                }
            } catch (Exception e) {
                // Cross-region viewers may reject a direct hide call —
                // fall back to the scheduled variant (never breaks vanish).
            }
            hideFrom(viewer, player);
        }

        // De-target nearby mobs that are currently targeting this player so
        // they stop chasing/attacking a now-vanished player. Runs directly:
        // vanish() is called from the entity/region thread (command or join),
        // where nearby-entity lookups are safe. Guarded so a Folia/thread
        // violation can never break the vanish itself.
        detargetMobs(player);

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
     * the hide must be re-applied. Same immediate + scheduled pattern as
     * vanish()/applyOnJoin() — immediate hideEntity closes the 1-tick window,
     * the scheduled hideFrom covers cross-region viewers that reject a direct
     * call. No-ops for non-vanished players. Never throws.
     */
    public void rehide(Player player) {
        if (player == null || !player.isOnline()) return;
        if (!isVanished(player)) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) continue;
            try {
                if (!canSee(viewer, player)) {
                    viewer.hideEntity(plugin, player);
                }
            } catch (Exception e) {
                // Cross-region viewers may reject a direct hide call —
                // fall back to the scheduled variant (never breaks vanish).
            }
            try {
                hideFrom(viewer, player);
            } catch (Exception ignored) {
            }
        }
        detargetMobs(player);
    }

    public void unvanish(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) {
            vanishedPlayers.remove(uuid);
            prevSelfStates.remove(uuid);
            // Still track for the false-flush: the async save below may be
            // cancelled on kill before hitting disk (see flushSync).
            dirtyUnvanished.add(uuid);
            savePlayerData(uuid, false);
            return;
        }
        // BUG-1 order: resetSelfState BEFORE removing from vanishedPlayers so
        // its synchronous wasVanished capture still sees us as vanished (the
        // fallback path depends on it — the async task runs after the remove).
        resetSelfState(player);
        vanishedPlayers.remove(uuid);
        dirtyUnvanished.add(uuid);

        // V1 race fix (mirror of vanish): show immediately AND keep the
        // scheduled follow-up via showTo.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) continue;
            try {
                viewer.showEntity(plugin, player);
                try {
                    viewer.listPlayer(player);
                } catch (Exception ignored) {
                    // listPlayer may throw while entity state settles —
                    // the scheduled showTo retries both.
                }
            } catch (Exception e) {
                // Fall back to the scheduled variant (never breaks unvanish).
            }
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
        // Synchronous pre-hide: must happen before the join message is
        // broadcast (right after the event) and before any viewer can see
        // the joiner. Guarded so disk I/O only happens when persistence is on.
        try {
            FileConfiguration cfg = plugin.getVanishConfig();
            boolean persistOn = cfg != null && cfg.getBoolean("vanish.persist-on-rejoin", true);
            boolean persisted = persistOn && (persistedVanishHint != null
                    ? persistedVanishHint
                    : hasPersistedVanish(player.getUniqueId()));
            if (persisted) {
                vanishedPlayers.add(player.getUniqueId());
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (viewer.equals(player)) continue;
                    try {
                        if (!canSee(viewer, player)) {
                            viewer.hideEntity(plugin, player);
                        }
                    } catch (Exception e) {
                        // Cross-region viewers may reject a direct hide call —
                        // fall back to the scheduled variant (never breaks join).
                        try {
                            hideFrom(viewer, player);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to pre-hide rejoining vanished player " + player.getName() + ": " + e.getMessage());
        }

        loadPlayerData(player);

        // A new viewer must not see players who are already vanished:
        // hide immediately and keep the scheduled hideFrom as fallback
        // (same pattern as vanish()/pre-hide above — closes the 1-tick window,
        // covers cross-region viewers that reject a direct call).
        for (UUID uuid : vanishedPlayers) {
            Player vanishedPlayer = Bukkit.getPlayer(uuid);
            if (vanishedPlayer != null && !vanishedPlayer.equals(player)) {
                try {
                    if (!canSee(player, vanishedPlayer)) {
                        player.hideEntity(plugin, vanishedPlayer);
                    }
                } catch (Exception ignored) {
                }
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
            // Shared per-UUID lock with TPAManager (same physical file): a
            // concurrent tpauto save must never interleave with this read.
            synchronized (PlayerDataLocks.lockFor(uuid)) {
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

        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            // The sync pre-hide in applyOnJoin() already added the player when
            // the persisted flag was true. This async step only confirms and
            // applies state — it must not drop a freshly vanished player whose
            // save has not hit disk yet, so a pre-hidden entry counts as proof.
            if (!vanishedPlayers.contains(uuid)
                    && !hasPersistedVanish(uuid)) return;

            player.getScheduler().run(plugin, (t) -> {
                if (!player.isOnline()) {
                    // Stale-race: the player may have quit and rejoined between
                    // scheduling and execution — the new session owns the UUID
                    // now. Only remove when the currently online player is still
                    // this exact instance.
                    try {
                        if (Bukkit.getPlayer(uuid) == player) {
                            vanishedPlayers.remove(uuid);
                        }
                    } catch (Exception ignored) {
                    }
                    return;
                }

                vanishedPlayers.add(uuid);
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

    private void savePlayerData(UUID uuid, boolean vanished) {
        FileConfiguration vanishConfig = plugin.getVanishConfig();
        if (vanishConfig == null) return;
        boolean persist = vanishConfig.getBoolean("vanish.persist-on-rejoin", true);

        // Tracked so stopModules() can await it (a kill right after /vanish
        // must not lose the state to a cancelled async task — see flushSync).
        CompletableFuture<Void> pending = new CompletableFuture<>();
        plugin.trackPendingSave(pending);
        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            try {
                // Shared per-UUID lock with TPAManager: load-merge-save must
                // never interleave with a concurrent tpauto save of the same
                // file (both keys would otherwise overwrite each other).
                synchronized (PlayerDataLocks.lockFor(uuid)) {
                    File file = getPlayerDataFileForWrite(uuid);

                    if (!persist) {
                        // Never leave stale vanished:true files around when persistence is
                        // disabled — they would re-vanish the player if persistence is
                        // enabled later.
                        if (file.exists()) file.delete();
                        dirtyUnvanished.remove(uuid);
                        return;
                    }

                    YamlConfiguration config = new YamlConfiguration();
                    if (file.isFile()) {
                        try {
                            config.load(file);
                        } catch (org.bukkit.configuration.InvalidConfigurationException corrupt) {
                            backupCorrupt(file);
                            plugin.getLogger().warning("Corrupt player data for " + uuid
                                    + " moved aside, rewriting vanish state.");
                        } catch (Exception e) {
                            plugin.getLogger().warning("Could not read player data for " + uuid + ": " + e.getMessage());
                        }
                    }

                    config.set("vanished", vanished);

                    saveAtomically(config, file, uuid);
                    // Successful false-write settles the dirty entry: a later
                    // flushSync need not rewrite it (see dirtyUnvanished).
                    if (!vanished) dirtyUnvanished.remove(uuid);
                }
            } finally {
                pending.complete(null);
            }
        });
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
        boolean persist = snapshot == null || snapshot.getBoolean("vanish.persist-on-rejoin", true);
        for (UUID uuid : new java.util.HashSet<>(vanishedPlayers)) {
            if (uuid == null) continue;
            try {
                synchronized (PlayerDataLocks.lockFor(uuid)) {
                    if (!persist) {
                        try {
                            File stale = getPlayerDataFile(uuid);
                            if (stale.isFile()) stale.delete();
                        } catch (Exception ignored) {
                        }
                        continue;
                    }
                    File file = getPlayerDataFileForWrite(uuid);
                    YamlConfiguration config = new YamlConfiguration();
                    if (file.isFile()) {
                        try {
                            config.load(file);
                        } catch (Exception ignored) {
                        }
                    }
                    config.set("vanished", true);
                    saveAtomically(config, file, uuid);
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to flush vanish state for " + uuid + ": " + e.getMessage());
            }
        }
        // Freshly-unvanished dirty set: rewrite false (or delete when
        // persistence is off) unless re-vanished since. Consumed regardless
        // so a second flush need not repeat it.
        for (UUID uuid : new java.util.HashSet<>(dirtyUnvanished)) {
            if (uuid == null) {
                dirtyUnvanished.remove(null);
                continue;
            }
            if (vanishedPlayers.contains(uuid)) {
                dirtyUnvanished.remove(uuid);
                continue;
            }
            try {
                synchronized (PlayerDataLocks.lockFor(uuid)) {
                    if (!persist) {
                        try {
                            File stale = getPlayerDataFile(uuid);
                            if (stale.isFile() && !stale.delete()) {
                                plugin.getLogger().fine("Could not delete stale vanish state for " + uuid);
                            }
                        } catch (Exception ignored) {
                        }
                    } else {
                        try {
                            File file = getPlayerDataFileForWrite(uuid);
                            YamlConfiguration config = new YamlConfiguration();
                            if (file.isFile()) {
                                try {
                                    config.load(file);
                                } catch (Exception ignored) {
                                }
                            }
                            config.set("vanished", false);
                            saveAtomically(config, file, uuid);
                        } catch (Exception e) {
                            plugin.getLogger().fine("Failed to flush unvanish state for " + uuid + ": " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to flush unvanish state for " + uuid + ": " + e.getMessage());
            } finally {
                dirtyUnvanished.remove(uuid);
                try {
                    prevSelfStates.remove(uuid);
                } catch (Exception ignored) {
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
    private void backupCorrupt(File file) {
        try {
            File backup = new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            plugin.getLogger().fine("Could not back up corrupt player data " + file.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Crash-safe write: dump to "{@code <uuid>.yml.tmp}" in the same directory,
     * then move over the target atomically (non-atomic fallback where the file
     * system lacks atomic-move support), so a crash can never leave a
     * half-written playerdata file behind. Same pattern as
     * TeamManager.saveQuietly(). Never throws.
     */
    private void saveAtomically(YamlConfiguration config, File target, UUID uuid) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            config.save(tmp);
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save player data for " + uuid + ": " + e.getMessage());
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
        boolean wasVanished = vanishedPlayers.remove(uuid);
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
    }

    public boolean canSee(Player viewer, Player target) {
        try {
            if (viewer == null || target == null) return false;
            if (viewer.equals(target)) return true;
            if (!isVanished(target)) return true;
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
        if (canSee(viewer, target)) return;

        viewer.getScheduler().run(plugin, (t) -> {
            // The target or viewer may have disconnected between scheduling and
            // execution (next tick) — guard against Paper throwing on stale entities.
            if (!viewer.isOnline() || !target.isOnline()) return;
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
        viewer.getScheduler().run(plugin, (t) -> {
            // The target may have disconnected between scheduling and execution
            // (next tick) — guard against Paper throwing on stale entities.
            if (!viewer.isOnline() || !target.isOnline()) return;
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
        if (player == null || !player.isOnline()) return;
        UUID syncUuid;
        try {
            syncUuid = player.getUniqueId();
        } catch (Exception e) {
            return;
        }
        // BUG-1 guard fix: capture wasVanished synchronously — callers
        // (unvanish/resetAll) invoke this BEFORE removing from vanishedPlayers,
        // the async task below runs after the remove, so a live isVanished()
        // check there would always see false and the fallback would never run.
        final boolean wasVanished = isVanished(syncUuid);
        final FileConfiguration snapshot = plugin.getVanishConfig();
        player.getScheduler().run(plugin, (t) -> {
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
        }, null);
    }

    /**
     * Reveals all currently vanished players (used when the module is stopped/reloaded).
     * <p>
     * When persist-on-rejoin is disabled the persisted files are deleted
     * synchronously per player: without this a stale {@code vanished:true}
     * file would re-vanish the player as soon as persistence is enabled again.
     */
    public void resetAll() {
        boolean persist;
        FileConfiguration cfgSnapshot;
        try {
            cfgSnapshot = plugin.getVanishConfig();
            persist = cfgSnapshot == null || cfgSnapshot.getBoolean("vanish.persist-on-rejoin", true);
        } catch (Exception e) {
            persist = true;
            cfgSnapshot = null;
        }
        final FileConfiguration snapshot = cfgSnapshot;
        for (UUID uuid : new java.util.HashSet<>(vanishedPlayers)) {
            try {
                if (!persist) {
                    try {
                        synchronized (PlayerDataLocks.lockFor(uuid)) {
                            File file = getPlayerDataFile(uuid);
                            if (file.isFile() && !file.delete()) {
                                plugin.getLogger().fine("Could not delete stale vanish state for " + uuid);
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().fine("Failed to clear persisted vanish state for " + uuid + ": " + e.getMessage());
                    }
                }
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    // P1 sync-first: EntityScheduler tasks scheduled here may be
                    // discarded on disable — attempt the direct reveal first
                    // (best-effort, guarded), keep the scheduled variants as
                    // fallback for cross-region viewers that reject direct calls.
                    boolean restoredSync = false;
                    try {
                        restoredSync = tryResetSelfStateSync(player, snapshot);
                    } catch (Exception e) {
                        plugin.getLogger().fine("Failed to sync-reset vanish state for " + uuid + ": " + e.getMessage());
                    }
                    // BUG-1 order: resetSelfState before remove (see unvanish).
                    // Skipped when the sync path already restored a snapshot —
                    // the fallback would find no snapshot and take the generic
                    // path instead (same values when scheduler is alive, but
                    // must not run when it already consumed the prev state).
                    try {
                        if (!restoredSync) resetSelfState(player);
                    } catch (Exception e) {
                        plugin.getLogger().fine("Failed to reset vanish state for " + uuid + ": " + e.getMessage());
                    }
                    vanishedPlayers.remove(uuid);
                    try {
                        for (Player viewer : Bukkit.getOnlinePlayers()) {
                            if (viewer.equals(player)) continue;
                            try {
                                viewer.showEntity(plugin, player);
                            } catch (Exception ignored) {
                            }
                            try {
                                viewer.listPlayer(player);
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().fine("Failed to sync-reveal vanished player " + uuid + ": " + e.getMessage());
                    }
                    try {
                        for (Player viewer : Bukkit.getOnlinePlayers()) {
                            if (viewer.equals(player)) continue;
                            showTo(viewer, player);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().fine("Failed to reveal vanished player " + uuid + ": " + e.getMessage());
                    }
                } else {
                    vanishedPlayers.remove(uuid);
                    prevSelfStates.remove(uuid);
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to reset vanished player " + uuid + ": " + e.getMessage());
            }
        }
    }
}