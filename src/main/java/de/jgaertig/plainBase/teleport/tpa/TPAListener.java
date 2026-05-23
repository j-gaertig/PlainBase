package de.jgaertig.plainBase.teleport.tpa;

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

public class TPAListener implements Listener {
    private final PlainBase plugin;

    public TPAListener(PlainBase plugin) {
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
        List<String> cancelFlags = plugin.getTeleportConfig().getStringList("tpa.counter.cancel_on");
        if (!cancelFlags.contains(flag)) return;

        String reason = switch (flag) {
            case "move" -> "You moved!";
            case "damage" -> "You took damage!";
            case "death" -> "You died!";
            case "interact" -> "You interacted!";
            default -> "Action not allowed during teleport!";
        };

        plugin.getTPAManager().cancelWarmup(p, reason);
    }
}