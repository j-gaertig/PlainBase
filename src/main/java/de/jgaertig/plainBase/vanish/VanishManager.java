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
    private record PrevSelfState(boolean collidable, boolean silent, boolean invisible) {}
    private final Map<UUID, PrevSelfState> prevSelfStates = new ConcurrentHashMap<>();

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
        try {
            for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), 32, 32, 32)) {
                if (entity instanceof Mob mob && player.equals(mob.getTarget())) {
                    mob.setTarget(null);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to de-target mobs for vanished player " + player.getName() + ": " + e.getMessage());
        }

        savePlayerData(player.getUniqueId(), true);
    }

    public void unvanish(Player player) {
        if (player == null) return;
        vanishedPlayers.remove(player.getUniqueId());
        if (!player.isOnline()) return;
        resetSelfState(player);

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
        // Synchronous pre-hide: must happen before the join message is
        // broadcast (right after the event) and before any viewer can see
        // the joiner. Guarded so disk I/O only happens when persistence is on.
        try {
            FileConfiguration cfg = plugin.getVanishConfig();
            if (cfg != null && cfg.getBoolean("vanish.persist-on-rejoin", true)
                    && hasPersistedVanish(player.getUniqueId())) {
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
     */
    public void flushSync() {
        FileConfiguration vanishConfig;
        try {
            vanishConfig = plugin.getVanishConfig();
        } catch (Exception e) {
            return;
        }
        boolean persist = vanishConfig == null || vanishConfig.getBoolean("vanish.persist-on-rejoin", true);
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
     * Quit cleanup: the in-memory entry is ALWAYS removed so
     * vanishedPlayers can never grow without bound (one stale UUID per
     * ever-vanished player). With persist-on-rejoin=true the file on disk
     * keeps the state — applyOnJoin() re-vanishes on the next join via
     * hasPersistedVanish(). No periodic purge (would be a feature).
     */
    public void handleQuit(Player player) {
        if (player == null) return;
        vanishedPlayers.remove(player.getUniqueId());
    }

    public boolean canSee(Player viewer, Player target) {
        try {
            if (viewer == null || target == null) return false;
            if (viewer.equals(target)) return true;
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
            viewer.hideEntity(plugin, target);
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
            viewer.showEntity(plugin, target);
            viewer.listPlayer(target);
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
            // Save the previous state once (putIfAbsent): a second vanish
            // without unvanish must keep the original, and a rejoin with
            // persist must keep the pre-vanish values (not the still-vanished
            // state from before the quit).
            try {
                UUID uuid = player.getUniqueId();
                try {
                    prevSelfStates.putIfAbsent(uuid,
                            new PrevSelfState(player.isCollidable(), player.isSilent(), player.isInvisible()));
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
        }, null);
    }

    private void resetSelfState(Player player) {
        if (player == null || !player.isOnline()) return;
        final FileConfiguration snapshot = plugin.getVanishConfig();
        player.getScheduler().run(plugin, (t) -> {
            if (!player.isOnline()) return;
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
            // fix, or manager rebuilt): only run while still vanished by us —
            // otherwise the hardcoded setInvisible(false) below would clear
            // third-party invisibility (potions, other plugins).
            if (!isVanished(uuid)) return;
            FileConfiguration config = plugin.getVanishConfig();
            if (config == null) config = snapshot;
            player.setInvisible(false);
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
        try {
            FileConfiguration cfg = plugin.getVanishConfig();
            persist = cfg == null || cfg.getBoolean("vanish.persist-on-rejoin", true);
        } catch (Exception e) {
            persist = true;
        }
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
                    vanishedPlayers.remove(uuid);
                    try {
                        resetSelfState(player);
                    } catch (Exception e) {
                        plugin.getLogger().fine("Failed to reset vanish state for " + uuid + ": " + e.getMessage());
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