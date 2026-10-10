package de.jgaertig.plainBase.vanish;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VanishManager {

    private final PlainBase plugin;
    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();

    public VanishManager(PlainBase plugin) {
        this.plugin = plugin;
    }

    public boolean isVanished(Player player) {
        return isVanished(player.getUniqueId());
    }

    public boolean isVanished(UUID uuid) {
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
     * Toggles the vanish state of a player.
     *
     * @return true if the player is now vanished, false if un-vanished
     */
    public boolean toggleVanish(Player player) {
        if (isVanished(player)) {
            unvanish(player);
            return false;
        }
        vanish(player);
        return true;
    }

    public void vanish(Player player) {
        vanishedPlayers.add(player.getUniqueId());
        applySelfState(player);

        // Hide this player from everyone who can't see through vanish
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) continue;
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
        vanishedPlayers.remove(player.getUniqueId());
        resetSelfState(player);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) continue;
            showTo(viewer, player);
        }

        savePlayerData(player.getUniqueId(), false);
    }

    /**
     * Applies all vanish state to a player who just joined (e.g. after a rejoin
     * with persist-on-rejoin) and hides all existing vanished players from them.
     */
    public void applyOnJoin(Player player) {
        loadPlayerData(player);

        // A new viewer must not see players who are already vanished
        for (UUID uuid : vanishedPlayers) {
            Player vanishedPlayer = Bukkit.getPlayer(uuid);
            if (vanishedPlayer != null && !vanishedPlayer.equals(player)) {
                hideFrom(player, vanishedPlayer);
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
            File file = getPlayerDataFile(uuid);
            if (!file.isFile()) return false;
            return YamlConfiguration.loadConfiguration(file).getBoolean("vanished", false);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read persisted vanish state for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    public void loadPlayerData(Player player) {
        if (plugin.getVanishConfig() == null) return;
        if (!plugin.getVanishConfig().getBoolean("vanish.persist-on-rejoin", true)) return;

        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            if (!hasPersistedVanish(player.getUniqueId())) return;

            player.getScheduler().run(plugin, (t) -> {
                if (!player.isOnline()) {
                    vanishedPlayers.remove(player.getUniqueId());
                    return;
                }

                vanishedPlayers.add(player.getUniqueId());
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
        if (plugin.getVanishConfig() == null) return;
        boolean persist = plugin.getVanishConfig().getBoolean("vanish.persist-on-rejoin", true);

        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            File file = getPlayerDataFile(uuid);

            if (!persist) {
                // Never leave stale vanished:true files around when persistence is
                // disabled — they would re-vanish the player if persistence is
                // enabled later.
                if (file.exists()) file.delete();
                return;
            }

            FileConfiguration config = YamlConfiguration.loadConfiguration(file);

            config.set("vanished", vanished);

            try {
                config.save(file);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save player data for " + uuid + ": " + e.getMessage());
            }
        });
    }

    private File getPlayerDataFile(UUID uuid) {
        File folder = new File(plugin.getDataFolder(), "data/playerdata");
        if (!folder.exists()) folder.mkdirs();
        return new File(folder, uuid.toString() + ".yml");
    }

    /**
     * Quit cleanup: with persist-on-rejoin=false the in-memory entry must not
     * outlive the session, otherwise vanishedPlayers would grow without bound
     * (one stale UUID per ever-vanished player). With persist enabled the
     * entry is intentionally kept so a rejoin stays vanished.
     */
    public void handleQuit(Player player) {
        if (player == null) return;
        FileConfiguration cfg = plugin.getVanishConfig();
        boolean persist = cfg != null && cfg.getBoolean("vanish.persist-on-rejoin", true);
        if (!persist) {
            vanishedPlayers.remove(player.getUniqueId());
        }
    }

    public boolean canSee(Player viewer, Player target) {
        try {
            if (viewer.equals(target)) return true;
            if (viewer.hasPermission("plainbase.vanish.see")) return true;
            if (plugin.getVanishConfig() != null && plugin.getVanishConfig().getBoolean("vanish.op-see", true) && viewer.isOp()) return true;
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
            if (plugin.getVanishConfig() != null && plugin.getVanishConfig().getBoolean("vanish.op-see", true) && viewer.isOp()) return true;
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
     */
    private void applySelfState(Player player) {
        // Snapshot for the synchronous part; the scheduler body re-reads the
        // config with a null-guard because stopModules() may have cleared it
        // between scheduling and execution (next tick) — the captured reference
        // alone would NPE when the module was reloaded/disabled in between.
        final FileConfiguration snapshot = plugin.getVanishConfig();

        player.getScheduler().run(plugin, (t) -> {
            FileConfiguration config = plugin.getVanishConfig();
            if (config == null) config = snapshot;
            if (config == null) return;
            if (config.getBoolean("vanish.no-collision", true)) {
                player.setCollidable(false);
            }

            if (config.getBoolean("vanish.no-step-sound", true)) {
                player.setSilent(true);
            }
        }, null);
    }

    private void resetSelfState(Player player) {
        player.getScheduler().run(plugin, (t) -> {
            player.setInvisible(false);
            player.setCollidable(true);
            player.setSilent(false);
        }, null);
    }

    /**
     * Reveals all currently vanished players (used when the module is stopped/reloaded).
     */
    public void resetAll() {
        for (UUID uuid : new java.util.HashSet<>(vanishedPlayers)) {
            try {
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
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to reset vanished player " + uuid + ": " + e.getMessage());
            }
        }
    }
}