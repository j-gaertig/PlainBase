package de.jgaertig.plainBase.teleport;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.entity.Player;

import java.util.List;

public class TeleportListener implements Listener {
    private final PlainBase plugin;

    public TeleportListener(PlainBase plugin) {
        this.plugin = plugin;
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
        List<String> tpaCancelFlags = plugin.getTeleportConfig().getStringList("tpa.counter.cancel_on");
        if (tpaCancelFlags.contains(flag)) {
            plugin.getTPAManager().cancelWarmup(p, generateReason(flag));
        }

        List<String> rtpCancelFlags = plugin.getTeleportConfig().getStringList("rtp.counter.cancel_on");
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