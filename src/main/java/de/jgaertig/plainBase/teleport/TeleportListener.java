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
    // Type-error warnings once per key (not per move event): PlayerMoveEvent
    // fires very frequently, a config type mistake must never spam the log.
    private static final java.util.Set<String> WARNED_TYPE_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public TeleportListener(PlainBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("modules.teleport", false)) return;
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
                // Quit refunds the cooldown when a warmup/search was active
                // (like cancelAll); a plain quit keeps it. Replaces the
                // cancelWarmup + cancelSearch pair (which never refunded).
                plugin.getRTPManager().handleQuit(player);
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
    // always break the warmup — even when "move" is not in cancel_on. A
    // portal/ender-pearl hop during warmup must never survive; cancel_on only
    // gates move/damage/death/interact.
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        cancelWarmupsUnconditionally(event.getPlayer());
    }

    // Any teleport (ender pearl, chorus, other plugins) breaks the warmup.
    // Always cancels, including PLUGIN cause: our own teleports only fire
    // after the warmup entry was already removed, so this is a no-op for them.
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        cancelWarmupsUnconditionally(event.getPlayer());
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
        // Death always breaks the warmup (like world change/teleport) — even
        // when "death" is not in cancel_on. A dead player must never be
        // teleported when the warmup fires afterwards (isDead guards in the
        // managers are only the second line of defense for the firing race).
        checkAndCancel(event.getEntity(), "death");
        cancelWarmupsUnconditionally(event.getEntity(), "death");
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
                cachedTpaCancelOn = List.copyOf(readCancelList(cfg, "tpa.counter.cancel_on"));
                cachedRtpCancelOn = List.copyOf(readCancelList(cfg, "rtp.counter.cancel_on"));
                cachedConfig = cfg;
            }

            if (cachedTpaCancelOn.contains(flag) && plugin.getTPAManager() != null) {
                plugin.getTPAManager().cancelWarmup(p, generateReason(flag));
            }

            if (cachedRtpCancelOn.contains(flag) && plugin.getRTPManager() != null) {
                plugin.getRTPManager().cancelWarmup(p, generateReason(flag));
                try {
                    plugin.getRTPManager().cancelSearch(p, generateReason(flag));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to check teleport cancel flag '" + flag + "' for " + p.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Reads a cancel_on list tolerantly: a proper YAML list is used as-is, a
     * single plain string (common admin mistake: {@code cancel_on: move}
     * instead of {@code cancel_on: [move]}) is accepted as a one-element list
     * with a warning. Anything else warns once and yields an empty list.
     * Only called on cache rebuild (once per config instance), never per event.
     */
    private List<String> readCancelList(org.bukkit.configuration.file.FileConfiguration cfg, String path) {
        Object raw;
        try {
            raw = cfg.get(path);
        } catch (Exception e) {
            warnOnce(path, "Could not read teleport.yml '" + path + "', ignoring cancel_on: " + e.getMessage());
            return List.of();
        }
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<String> out = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o != null) out.add(o.toString());
            }
            return out;
        }
        if (raw instanceof String single) {
            plugin.getLogger().warning("teleport.yml '" + path + "' should be a list (e.g. [move, damage]), "
                    + "got a single value '" + single + "' — accepting it as a one-element list. Fix the config to silence this warning.");
            String trimmed = single.trim();
            return trimmed.isEmpty() ? List.of() : List.of(trimmed);
        }
        warnOnce(path, "teleport.yml '" + path + "' has an unsupported type ("
                + raw.getClass().getSimpleName() + "), expected a list of strings — ignoring.");
        return List.of();
    }

    private void warnOnce(String key, String message) {
        if (WARNED_TYPE_KEYS.add(key)) {
            plugin.getLogger().warning(message);
        }
    }

    /**
     * Unconditional warmup abort for PlayerTeleportEvent and
     * PlayerChangedWorldEvent (ender pearl, chorus, portal, other plugins).
     * Unlike checkAndCancel, this ignores the cancel_on lists entirely —
     * an external position change during warmup must never survive, while
     * cancel_on only gates move/damage/death/interact.
     */
    private void cancelWarmupsUnconditionally(Player p) {
        cancelWarmupsUnconditionally(p, "move");
    }

    private void cancelWarmupsUnconditionally(Player p, String flag) {
        if (p == null) return;
        String reason = generateReason(flag);
        try {
            if (plugin.getTPAManager() != null) {
                plugin.getTPAManager().cancelWarmup(p, reason);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to cancel TPA warmup on teleport/world-change for " + p.getName() + ": " + e.getMessage());
        }
        try {
            if (plugin.getRTPManager() != null) {
                plugin.getRTPManager().cancelWarmup(p, reason);
                try {
                    plugin.getRTPManager().cancelSearch(p, reason);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to cancel RTP warmup on teleport/world-change for " + p.getName() + ": " + e.getMessage());
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
