package de.jgaertig.plainBase.teleport;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashSet;
import java.util.Set;

public class TeleportListener implements Listener {
    private final PlainBase plugin;

    // Cached once per module (re)load instead of re-reading the YAML config on
    // every single event — PlayerMoveEvent in particular fires extremely often
    // and getStringList() re-walks the config tree on every call.
    private final Set<String> tpaCancelFlags;
    private final Set<String> rtpCancelFlags;

    public TeleportListener(PlainBase plugin) {
        this.plugin = plugin;
        this.tpaCancelFlags = new HashSet<>(plugin.getTeleportConfig().getStringList("tpa.counter.cancel_on"));
        this.rtpCancelFlags = new HashSet<>(plugin.getTeleportConfig().getStringList("rtp.counter.cancel_on"));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getTPAManager().loadPlayerData(event.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
                event.getFrom().getBlockY() == event.getTo().getBlockY() &&
                event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        checkAndCancel(event.getPlayer(), "move");
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p) {
            checkAndCancel(p, "damage");
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        checkAndCancel(event.getEntity(), "death");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        checkAndCancel(event.getPlayer(), "interact");
    }

    private void checkAndCancel(Player p, String flag) {
        if (tpaCancelFlags.contains(flag)) {
            plugin.getTPAManager().cancelWarmup(p, generateReason(flag));
        }

        if (rtpCancelFlags.contains(flag)) {
            plugin.getRTPManager().cancelWarmup(p, generateReason(flag));
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
