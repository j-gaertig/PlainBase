package de.jgaertig.plainBase.vanish;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
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

    public Set<UUID> getVanishedPlayers() {
        return vanishedPlayers;
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
     */
    public boolean hasPersistedVanish(UUID uuid) {
        File file = getPlayerDataFile(uuid);
        if (!file.exists()) return false;
        return YamlConfiguration.loadConfiguration(file).getBoolean("vanished", false);
    }

    public void loadPlayerData(Player player) {
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
        Bukkit.getAsyncScheduler().runNow(plugin, (task) -> {
            File file = getPlayerDataFile(uuid);
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);

            config.set("vanished", vanished);

            try {
                config.save(file);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save player data for " + uuid);
            }
        });
    }

    private File getPlayerDataFile(UUID uuid) {
        File folder = new File(plugin.getDataFolder(), "data/playerdata");
        if (!folder.exists()) folder.mkdirs();
        return new File(folder, uuid.toString() + ".yml");
    }

    private boolean canSee(Player viewer, Player target) {
        if (viewer.equals(target)) return true;
        if (viewer.hasPermission("plainbase.vanish.see")) return true;
        if (plugin.getVanishConfig().getBoolean("vanish.op-see", true) && viewer.isOp()) return true;
        return isVanished(viewer); // Staff who is vanished can see other vanished players
    }

    /**
     * Hides the target player from the given viewer (entity + tab list).
     * If hide-armor is disabled, the player's skin is hidden via setInvisible
     * but their armor stays visible, so we only remove them from the tab list.
     */
    private void hideFrom(Player viewer, Player target) {
        if (canSee(viewer, target)) return;

        viewer.getScheduler().run(plugin, (t) -> {
            if (plugin.getVanishConfig().getBoolean("vanish.hide-armor", true)) {
                viewer.hidePlayer(plugin, target);
            } else {
                // "Ghost mode": keep the player visible (armor stays visible too),
                // only remove them from the tab list.
                viewer.showPlayer(plugin, target);
            }
            if (plugin.getVanishConfig().getBoolean("vanish.tab-hidden", true)) {
                viewer.unlistPlayer(target);
            }
        }, null);
    }

    /**
     * Shows the target player to the given viewer again (entity + tab list).
     */
    private void showTo(Player viewer, Player target) {
        viewer.getScheduler().run(plugin, (t) -> {
            viewer.showPlayer(plugin, target);
            viewer.listPlayer(target);
        }, null);
    }

    /**
     * Applies the player's own vanish state (visibility, collision, sounds).
     * Note: without packet-level rendering we cannot hide the skin but keep the
     * armor visible — hide-armor=false therefore keeps the player fully visible
     * ("ghost mode": tab-hidden + protection, but no visual hide).
     */
    private void applySelfState(Player player) {
        FileConfiguration config = plugin.getVanishConfig();

        player.getScheduler().run(plugin, (t) -> {
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
        for (UUID uuid : vanishedPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                vanishedPlayers.remove(uuid);
                resetSelfState(player);

                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (viewer.equals(player)) continue;
                    showTo(viewer, player);
                }
            } else {
                vanishedPlayers.remove(uuid);
            }
        }
    }
}