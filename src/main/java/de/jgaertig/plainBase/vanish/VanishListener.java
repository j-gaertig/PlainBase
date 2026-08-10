package de.jgaertig.plainBase.vanish;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!plugin.getVanishConfig().getBoolean("vanish.mobs-ignore", true)) return;
        if (!(event.getTarget() instanceof Player player)) return;

        if (plugin.getVanishManager().isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getVanishConfig().getBoolean("vanish.invincible", false)) return;
        if (!(event.getEntity() instanceof Player player)) return;

        if (plugin.getVanishManager().isVanished(player)) {
            event.setCancelled(true);
        }
    }
}