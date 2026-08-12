package de.jgaertig.plainBase.vanish;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class VanishListener implements Listener {

    private final PlainBase plugin;

    public VanishListener(PlainBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        FileConfiguration config = plugin.getVanishConfig();

        // A vanished player should not announce their join (persist-on-rejoin).
        // Done synchronously because the join message is broadcast right after
        // the event — the single read source is VanishManager.hasPersistedVanish
        // (file is tiny; the actual vanish state application stays async).
        if (config.getBoolean("vanish.hide-join-quit-messages", true)
                && plugin.getVanishManager().hasPersistedVanish(player.getUniqueId())) {
            event.joinMessage(null);
        }

        plugin.getVanishManager().applyOnJoin(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        if (plugin.getVanishManager().isVanished(player)
                && plugin.getVanishConfig().getBoolean("vanish.hide-join-quit-messages", true)) {
            event.quitMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!plugin.getVanishConfig().getBoolean("vanish.projectiles-pass-through", true)) return;
        if (!(event.getHitEntity() instanceof Player player)) return;

        if (plugin.getVanishManager().isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileDamage(EntityDamageByEntityEvent event) {
        // ProjectileHitEvent.setCancelled stops the arrow from sticking, but the
        // actual damage is dealt via EntityDamageByEntityEvent — cancel it here
        // so projectiles really pass through vanished players. (Deliberately
        // mirrors the PROJECTILE branch in onDamage: arrows without a shooter
        // only fire EntityDamageEvent, shots with a shooter fire this event.)
        if (!plugin.getVanishConfig().getBoolean("vanish.projectiles-pass-through", true)) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!(event.getDamager() instanceof Projectile)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.PROJECTILE) return;

        if (plugin.getVanishManager().isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!plugin.getVanishConfig().getBoolean("vanish.mobs-ignore", true)) return;
        if (!(event.getTarget() instanceof Player player)) return;

        if (plugin.getVanishManager().isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // projectiles-pass-through: damage from arrows/eggs/etc. is dealt via
        // EntityDamageEvent with DamageCause.PROJECTILE (no EntityDamageByEntityEvent
        // is fired for arrows without a shooter), so cancel it here as well.
        if (event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE
                && plugin.getVanishConfig().getBoolean("vanish.projectiles-pass-through", true)
                && plugin.getVanishManager().isVanished(player)) {
            event.setCancelled(true);
            return;
        }

        if (!plugin.getVanishConfig().getBoolean("vanish.invincible", false)) return;

        if (plugin.getVanishManager().isVanished(player)) {
            event.setCancelled(true);
        }
    }
}