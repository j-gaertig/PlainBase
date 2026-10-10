package de.jgaertig.plainBase.teleport;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.entity.Player;

import java.util.List;

public class TeleportListener implements Listener {
    private final PlainBase plugin;

    // Cached cancel_on lists: PlayerMoveEvent fires very frequently, and each
    // checkAndCancel() previously re-parsed both lists from teleport.yml on
    // every event. The cache is keyed by config instance identity — PlainBase
    // swaps in a new FileConfiguration object on every reload (configs map
    // clear + put, teleport.yml is never mutated in place), so a fresh read
    // happens automatically after reload: no stale state, no behaviour change.
    private volatile org.bukkit.configuration.file.FileConfiguration cachedConfig;
    private volatile List<String> cachedTpaCancelOn = List.of();
    private volatile List<String> cachedRtpCancelOn = List.of();

    public TeleportListener(PlainBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.getTPAManager() != null) {
            plugin.getTPAManager().loadPlayerData(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getConfig().getBoolean("modules.teleport", false)) return;
        Player player = event.getPlayer();
        try {
            if (plugin.getTPAManager() != null) {
                plugin.getTPAManager().handleQuit(player);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to handle teleport quit state for " + player.getName() + ": " + e.getMessage());
        }
        try {
            if (plugin.getRTPManager() != null) {
                plugin.getRTPManager().cancelWarmup(player, "You left!");
                try {
                    plugin.getRTPManager().cancelSearch(player);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to cancel RTP search for " + player.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to cancel teleport warmup for " + player.getName() + ": " + e.getMessage());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || to.getWorld() == null) return;
        if (!from.getWorld().equals(to.getWorld())) {
            checkAndCancel(event.getPlayer(), "move");
            return;
        }
        if (from.distanceSquared(to) < 0.01) return;

        checkAndCancel(event.getPlayer(), "move");
    }

    // A world change without a move delta (portal, /world hop, end gate) must
    // break the warmup just like movement does. No new config key: reuses the
    // "move" cancel flag.
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        checkAndCancel(event.getPlayer(), "move");
    }

    // Any teleport (ender pearl, chorus, other plugins) breaks the warmup.
    // Always cancels, including PLUGIN cause: our own teleports only fire
    // after the warmup entry was already removed, so this is a no-op for them.
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        checkAndCancel(event.getPlayer(), "move");
    }

    // A cancelled damage event means no damage was actually taken (spawn
    // protection, god mode, other plugins) — it must not break the warmup.
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p) {
            checkAndCancel(p, "damage");
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        checkAndCancel(event.getEntity(), "death");
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        // Only real clicks break the warmup: PHYSICAL (pressure plates,
        // farmland trampling) and cancelled interactions (other plugins,
        // protection) must not cancel. Consistent with move/damage handling.
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK, LEFT_CLICK_AIR, LEFT_CLICK_BLOCK ->
                    checkAndCancel(event.getPlayer(), "interact");
            default -> {
            }
        }
    }

    private void checkAndCancel(Player p, String flag) {
        if (p == null) return;
        try {
            org.bukkit.configuration.file.FileConfiguration cfg = plugin.getTeleportConfig();
            // Transient null mid-reload (configs cleared, not yet re-put):
            // nothing to check against, same as the old NPE-caught path.
            if (cfg == null) return;
            if (cfg != cachedConfig) {
                cachedTpaCancelOn = List.copyOf(cfg.getStringList("tpa.counter.cancel_on"));
                cachedRtpCancelOn = List.copyOf(cfg.getStringList("rtp.counter.cancel_on"));
                cachedConfig = cfg;
            }

            if (cachedTpaCancelOn.contains(flag) && plugin.getTPAManager() != null) {
                plugin.getTPAManager().cancelWarmup(p, generateReason(flag));
            }

            if (cachedRtpCancelOn.contains(flag) && plugin.getRTPManager() != null) {
                plugin.getRTPManager().cancelWarmup(p, generateReason(flag));
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to check teleport cancel flag '" + flag + "' for " + p.getName() + ": " + e.getMessage());
        }
    }

    private String generateReason(String flag) {
        return switch (flag) {
            case "move" -> "You moved!";
            case "damage" -> "You took damage!";
            case "death" -> "You died!";
            case "interact" -> "You interacted!";
            default -> "Teleport cancelled!";
        };
    }
}
