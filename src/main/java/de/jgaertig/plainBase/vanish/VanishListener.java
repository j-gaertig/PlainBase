package de.jgaertig.plainBase.vanish;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class VanishListener implements Listener {

    private final PlainBase plugin;

    public VanishListener(PlainBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        FileConfiguration config = plugin.getVanishConfig();
        var vanishManager = plugin.getVanishManager();

        // A vanished player should not announce their join (persist-on-rejoin).
        // Done synchronously because the join message is broadcast right after
        // the event — the single read source is VanishManager.hasPersistedVanish
        // (file is tiny; the actual vanish state application stays async).
        // HIGHEST so this runs after MessagesListener and cannot be overwritten.
        // Null-guards first: manager, then config, then the (file-IO) check last
        // so no disk read happens when it is not needed.
        try {
            if (vanishManager != null && config != null
                    && config.getBoolean("vanish.hide-join-quit-messages", true)
                    && vanishManager.hasPersistedVanish(player.getUniqueId())) {
                event.joinMessage(null);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to handle vanish join state for " + player.getName() + ": " + e.getMessage());
        }

        if (vanishManager != null) {
            vanishManager.applyOnJoin(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        try {
            FileConfiguration vanishConfig = plugin.getVanishConfig();
            if (plugin.getVanishManager() != null
                    && (plugin.getVanishManager().isVanished(player)
                        || plugin.getVanishManager().hasPersistedVanish(player.getUniqueId()))
                    && vanishConfig != null
                    && vanishConfig.getBoolean("vanish.hide-join-quit-messages", true)) {
                event.quitMessage(null);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to handle vanish quit state for " + player.getName() + ": " + e.getMessage());
        }

        try {
            if (plugin.getVanishManager() != null) {
                plugin.getVanishManager().handleQuit(player);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to purge vanish state for " + player.getName() + ": " + e.getMessage());
        }
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        // A kicked vanished player must not leak via the leave message either
        // (kick fires no PlayerQuitEvent path that would hide it — same
        // hide-join-quit + isVanished/hasPersistedVanish condition as onQuit).
        Player player = event.getPlayer();

        try {
            FileConfiguration vanishConfig = plugin.getVanishConfig();
            if (plugin.getVanishManager() != null
                    && (plugin.getVanishManager().isVanished(player)
                        || plugin.getVanishManager().hasPersistedVanish(player.getUniqueId()))
                    && vanishConfig != null
                    && vanishConfig.getBoolean("vanish.hide-join-quit-messages", true)) {
                event.leaveMessage(null);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to handle vanish kick state for " + player.getName() + ": " + e.getMessage());
        }

        try {
            if (plugin.getVanishManager() != null) {
                plugin.getVanishManager().handleQuit(player);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to purge vanish state for " + player.getName() + ": " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        // A vanished player must not leak via the death message — neither as
        // the victim ("X died") nor as the killer ("X was slain by Y").
        try {
            if (plugin.getVanishManager() == null) return;
            if (plugin.getVanishManager().isVanished(event.getEntity())) {
                event.deathMessage(null);
                return;
            }
            Player killer = event.getEntity().getKiller();
            if (killer != null && plugin.getVanishManager().isVanished(killer)) {
                event.deathMessage(null);
                return;
            }
            try {
                if (event.getDamageSource() != null
                        && event.getDamageSource().getCausingEntity() instanceof Player causing
                        && plugin.getVanishManager().isVanished(causing)) {
                    event.deathMessage(null);
                }
            } catch (NoSuchMethodError | Exception ignored) {
                // Older API without DamageSource#getCausingEntity — killer check above covers melee.
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to handle vanish death state for " + event.getEntity().getName() + ": " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        // A vanished player must not leak via the advancement announcement.
        try {
            if (plugin.getVanishManager() != null
                    && plugin.getVanishManager().isVanished(event.getPlayer())) {
                event.message(null);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to handle vanish advancement state for " + event.getPlayer().getName() + ": " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        FileConfiguration vanishConfig = plugin.getVanishConfig();
        if (plugin.getVanishManager() == null || vanishConfig == null) return;
        if (!vanishConfig.getBoolean("vanish.pickup-block", true)) return;
        if (!(event.getEntity() instanceof Player player)) return;

        if (plugin.getVanishManager().isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent event) {
        FileConfiguration vanishConfig = plugin.getVanishConfig();
        if (plugin.getVanishManager() == null || vanishConfig == null) return;
        if (!vanishConfig.getBoolean("vanish.pickup-block", true)) return;

        if (plugin.getVanishManager().isVanished(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileHit(ProjectileHitEvent event) {
        FileConfiguration vanishConfig = plugin.getVanishConfig();
        if (plugin.getVanishManager() == null || vanishConfig == null) return;
        if (!vanishConfig.getBoolean("vanish.projectiles-pass-through", true)) return;
        if (!(event.getHitEntity() instanceof Player player)) return;

        if (plugin.getVanishManager().isVanished(player)) {
            event.setCancelled(true);
            return;
        }
        // A vanished shooter's projectile must not hit either (outgoing
        // protection, mirror of onDamage's attacker guard).
        try {
            Player shooter = resolveCausalPlayer(event.getEntity());
            if (shooter != null && plugin.getVanishManager().isVanished(shooter)) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to check vanish shooter state: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileDamage(EntityDamageByEntityEvent event) {
        // ProjectileHitEvent.setCancelled stops the arrow from sticking, but the
        // actual damage is dealt via EntityDamageByEntityEvent — cancel it here
        // so projectiles really pass through vanished players. (Deliberately
        // mirrors the PROJECTILE branch in onDamage: arrows without a shooter
        // only fire EntityDamageEvent, shots with a shooter fire this event.)
        FileConfiguration vanishConfig = plugin.getVanishConfig();
        if (plugin.getVanishManager() == null || vanishConfig == null) return;
        if (!vanishConfig.getBoolean("vanish.projectiles-pass-through", true)) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!(event.getDamager() instanceof Projectile)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.PROJECTILE) return;

        if (plugin.getVanishManager().isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        FileConfiguration vanishConfig = plugin.getVanishConfig();
        if (plugin.getVanishManager() == null || vanishConfig == null) return;
        if (!vanishConfig.getBoolean("vanish.mobs-ignore", true)) return;
        if (!(event.getTarget() instanceof Player player)) return;

        if (plugin.getVanishManager().isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        FileConfiguration vanishConfig = plugin.getVanishConfig();
        if (plugin.getVanishManager() == null || vanishConfig == null) return;
        if (!(event.getEntity() instanceof Player player)) return;

        // V5 outgoing-damage guard (MINIMAL, no new config key, no auto-reveal):
        // a vanished attacker must never deal invisible PvP damage. Same
        // cancel-only logic as the victim protection below — just cancelled.
        try {
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                Player attacker = resolveCausalPlayer(byEntity.getDamager());
                if (attacker == null) {
                    try {
                        if (event.getDamageSource() != null
                                && event.getDamageSource().getCausingEntity() instanceof Player causing) {
                            attacker = causing;
                        }
                    } catch (NoSuchMethodError | Exception ignored) {
                        // Older API without DamageSource#getCausingEntity.
                    }
                }
                if (attacker != null && plugin.getVanishManager().isVanished(attacker)) {
                    event.setCancelled(true);
                    return;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to check vanish attacker state: " + e.getMessage());
        }

        // projectiles-pass-through: damage from arrows/eggs/etc. is dealt via
        // EntityDamageEvent with DamageCause.PROJECTILE (no EntityDamageByEntityEvent
        // is fired for arrows without a shooter), so cancel it here as well.
        if (event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE
                && vanishConfig.getBoolean("vanish.projectiles-pass-through", true)
                && plugin.getVanishManager().isVanished(player)) {
            event.setCancelled(true);
            return;
        }

        if (!vanishConfig.getBoolean("vanish.invincible", false)) return;

        if (plugin.getVanishManager().isVanished(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * Resolves the causal player behind a damager: direct melee attacker, the
     * shooter of a projectile, the igniter of primed TNT, or the source of a
     * lingering effect cloud. Returns null for non-player causes.
     * <p>
     * NOTE: FallingBlock (and similar physics entities) expose no source API,
     * so a vanished player dropping an anvil/sand on someone cannot be
     * attributed here — the DamageSource#getCausingEntity fallback in the
     * caller covers whatever the server tracks, the rest is unattributable
     * without NMS.
     */
    private static Player resolveCausalPlayer(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile projectile) {
            try {
                if (projectile.getShooter() instanceof Player shooter) return shooter;
            } catch (Exception ignored) {
            }
        }
        if (damager instanceof org.bukkit.entity.TNTPrimed tnt) {
            try {
                if (tnt.getSource() instanceof Player igniter) return igniter;
            } catch (Exception ignored) {
            }
        }
        if (damager instanceof org.bukkit.entity.AreaEffectCloud cloud) {
            try {
                if (cloud.getSource() instanceof Player source) return source;
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}