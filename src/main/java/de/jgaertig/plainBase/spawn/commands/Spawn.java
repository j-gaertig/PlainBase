package de.jgaertig.plainBase.spawn.commands;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class Spawn implements BasicCommand {

    private final PlainBase plugin;

    public Spawn(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        // Root-config read under the central configLock (same monitor as
        // PlainBaseCommand toggle/suggest): the instance is fetched INSIDE the
        // lock since reloadConfig() may swap it at any time.
        boolean moduleEnabled;
        synchronized (plugin.getConfigLock()) {
            FileConfiguration rootCfg = plugin.getConfig();
            moduleEnabled = rootCfg != null && rootCfg.getBoolean("modules.spawn", false);
        }
        if (!moduleEnabled) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module is currently disabled."));
            return;
        }

        // Single snapshot, reused below (never re-fetched: a reload between
        // the checks could otherwise hand back a different/null instance).
        FileConfiguration spawnConfig = plugin.getSpawnConfig();
        if (spawnConfig == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is currently unavailable."));
            return;
        }

        if (!sender.hasPermission("plainbase.admin") && !sender.hasPermission("plainbase.spawn.admin") && !sender.hasPermission("plainbase.spawn.spawn")) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return;
        }

        // Snapshot + locked read: writers mutate under synchronized(config).
        // Reuses the spawnConfig snapshot above (never re-fetched: a reload
        // between the checks could otherwise hand back null).
        FileConfiguration config = spawnConfig;
        boolean commandEnabled;
        synchronized (spawnConfig) {
            commandEnabled = spawnConfig.getBoolean("commands.spawn.enabled", true);
        }
        if (!commandEnabled) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command can only be executed by players."));
            return;
        }

        if (!(args.length == 0)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/spawn"));
            return;
        }

        boolean spawnEnabled;
        synchronized (spawnConfig) {
            // Default false: matches spawn.yml + SpawnListener (opt-in via
            // /setspawn); a missing key must not teleport to an unset spot.
            spawnEnabled = spawnConfig.getBoolean("spawn.enabled", false);
        }
        if (!spawnEnabled) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn position has been disabled."));
            return;
        }

        String path = "spawn.location";

        // Consistent snapshot: writers (SetSpawn/saveToSpawnConfig) mutate
        // under synchronized(config), so reads must lock the same monitor to
        // never mix half-written coordinates. Only plain values are copied
        // under the lock — world lookup and teleport happen outside it.
        // Reuses the spawnConfig snapshot above (never re-fetched: a reload
        // between the checks could otherwise hand back null).
        final String worldName;
        final double rawX;
        final double rawY;
        final double rawZ;
        final double rawYaw;
        final double rawPitch;
        synchronized (config) {
            worldName = config.getString(path + ".world");
            rawX = config.getDouble(path + ".x");
            rawY = config.getDouble(path + ".y");
            rawZ = config.getDouble(path + ".z");
            rawYaw = config.getDouble(path + ".yaw");
            rawPitch = config.getDouble(path + ".pitch");
        }
        if (worldName == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is not set correctly. Contact an admin."));
            plugin.getLogger().warning("Spawn location world is missing in spawn.yml!");
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            // worldName comes from config and may contain MiniMessage-looking
            // characters ('<', '>'): escape it so a weird world name cannot
            // break parsing (or inject formatting) into this message.
            try {
                String safeWorld = plugin.getMiniMessage().escapeTags(worldName);
                sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn world '" + safeWorld + "' not found!"));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to format spawn message: " + e.getMessage());
                sender.sendMessage(Component.text("Spawn world '" + worldName + "' not found!"));
            }
            plugin.getLogger().warning("Spawn world '" + worldName + "' not found!");
            return;
        }

        if (!Double.isFinite(rawX) || !Double.isFinite(rawY) || !Double.isFinite(rawZ)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is not set correctly. Contact an admin."));
            plugin.getLogger().warning("Spawn teleport failed for " + player.getName() + ": non-finite coordinates in spawn.yml!");
            return;
        }
        float yaw = Double.isFinite(rawYaw) ? (float) rawYaw : 0f;
        float pitch = Double.isFinite(rawPitch) ? (float) rawPitch : 0f;

        Location loc;
        if (rawY < world.getMinHeight() || rawY >= world.getMaxHeight()) {
            plugin.getLogger().warning("Spawn teleport for " + player.getName() + ": Y=" + rawY
                    + " out of bounds [" + world.getMinHeight() + "," + world.getMaxHeight()
                    + "), falling back to world spawn.");
            player.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Spawn location was out of bounds, using world spawn instead. Contact an admin!"));
            loc = world.getSpawnLocation().clone();
        } else {
            loc = new Location(world, rawX, rawY, rawZ, yaw, pitch);
        }
        validateSpawnThenTeleport(player, loc);
    }

    private void validateSpawnThenTeleport(Player player, Location requested) {
        if (player == null || requested == null || requested.getWorld() == null) return;
        Location target = requested.clone();
        World world = target.getWorld();
        try {
            Bukkit.getRegionScheduler().execute(plugin, world, target.getBlockX() >> 4, target.getBlockZ() >> 4, () -> {
                // Block reads happen only on the region owning the destination.
                Location safe = resolveSafeSpawnLocation(world, target);
                Location validated = safe == null ? null : safe.clone();
                try {
                    player.getScheduler().run(plugin, task -> {
                        if (!player.isOnline()) return;
                        if (validated == null) {
                            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn location could not be verified as safe. Contact an admin."));
                            return;
                        }

                        // Re-check enabled on the entity thread immediately
                        // before initiating teleportAsync.
                        boolean stillEnabled = false;
                        try {
            FileConfiguration live = plugin.getSpawnConfig();
            if (live != null) {
                synchronized (live) {
                    stillEnabled = live.getBoolean("spawn.enabled", false);
                }
            }
                        } catch (Exception e) {
                            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is currently unavailable."));
                            return;
                        }
                        if (!stillEnabled) {
                            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn position has been disabled."));
                            return;
                        }

        try {
        player.teleportAsync(validated).thenAccept(success -> {
            // thenAccept runs off the entity thread: hop back onto the
            // EntityScheduler for sendMessage (Folia scheduler retained).
            try {
                player.getScheduler().run(plugin, t -> {
                    if (!player.isOnline()) return;
                    try {
                        if (Boolean.TRUE.equals(success)) {
                            player.sendMessage(plugin.getMiniMessage().deserialize("<green>Teleported to spawn!"));
                        } else {
                            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport failed. Try again or contact an admin!"));
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to notify " + player.getName() + " about spawn teleport: " + e.getMessage());
                    }
                }, null);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to schedule spawn teleport notify for " + player.getName() + ": " + e.getMessage());
            }
        });
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to start spawn teleport for " + player.getName() + ": " + e.getMessage());
            try {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport failed. Try again or contact an admin!"));
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to notify " + player.getName() + " about spawn teleport failure: " + ex.getMessage());
            }
        }
                    }, null);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to schedule spawn teleport for " + player.getUniqueId() + ": " + e.getMessage());
                }
            });
        } catch (Exception e) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn location could not be verified as safe. Contact an admin."));
            plugin.getLogger().warning("Could not schedule spawn safety check for " + player.getUniqueId() + ": " + e.getMessage());
        }
    }

    /**
     * Spawn safety: when feet/head at the configured location are obstructed,
     * scan up (max +5, same pattern as TPAManager arrival) for feet+head
     * passable with solid ground below. If no candidate can be verified safe,
     * fail closed rather than sending the player to an unsafe or unchecked spot.
     */
    private Location resolveSafeSpawnLocation(World world, Location loc) {
        try {
            if (world == null || loc == null) return loc;
            if (isSpawnSpotSafe(loc)) return loc;
            String reason = unsafeSpawnReason(loc);
            if (reason == null) reason = "obstructed";
            Location found = null;
            int foundOffset = 0;
            for (int i = 1; i <= 5; i++) {
                Location cand = loc.clone().add(0, i, 0);
                if (cand.getY() + 1 >= world.getMaxHeight()) break;
                if (cand.getY() < world.getMinHeight()) continue;
                if (isSpawnSpotSafe(cand)) {
                    found = cand;
                    foundOffset = i;
                    break;
                }
            }
            if (found != null) {
                return found;
            }
            return null;
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to check spawn safety: " + e.getMessage());
            return null;
        }
    }

    private boolean isSpawnSpotSafe(Location cand) {
        return unsafeSpawnReason(cand) == null;
    }

    /**
     * Null when safe; otherwise a reason. Lookup failures are unsafe.
     */
    private String unsafeSpawnReason(Location cand) {
        try {
            org.bukkit.block.Block feet = cand.getBlock();
            org.bukkit.block.Block head = cand.clone().add(0, 1, 0).getBlock();
            org.bukkit.block.Block ground = cand.clone().add(0, -1, 0).getBlock();
            if (!feet.isPassable() || !head.isPassable()) return "obstructed";
            String groundName;
            boolean solid;
            try {
                solid = ground.getType().isSolid();
                groundName = ground.getType().name();
            } catch (Exception e) {
                return "unable to verify ground";
            }
            if (!solid) return "no solid ground below";
            // Leaves are solid safe support (e.g. treehouse spawns): do not
            // reject them as ground. Damaging/unsafe validation below stays.
            try {
                if (isDamagingSpawnGround(groundName)
                        || isAlwaysUnsafeSpawnBlock(groundName)) return "unsafe ground below";
                String feetName = feet.getType().name();
                String headName = head.getType().name();
                if (isAlwaysUnsafeSpawnBlock(feetName) || isAlwaysUnsafeSpawnBlock(headName)
                        || isDamagingSpawnGround(feetName) || isDamagingSpawnGround(headName)
                        || feetName.contains("LEAVES") || headName.contains("LEAVES")) return "unsafe headroom";
            } catch (Exception e) {
                return "unable to verify blocks";
            }
        } catch (Exception e) {
            return "unable to verify location";
        }
        return null;
    }

    /**
     * Damaging ground types (magma, campfire, cactus, wither rose, berry
     * bush — RTPManager ground rejects). Name-based, no Material import.
     */
    private static boolean isDamagingSpawnGround(String materialName) {
        if (materialName == null) return false;
        return switch (materialName) {
            case "LAVA", "WATER", "FIRE",
                    "CACTUS", "MAGMA_BLOCK",
                    "CAMPFIRE", "SOUL_CAMPFIRE",
                    "WITHER_ROSE", "SWEET_BERRY_BUSH" -> true;
            default -> false;
        };
    }

    private static boolean isAlwaysUnsafeSpawnBlock(String materialName) {
        if (materialName == null) return true;
        return switch (materialName) {
            case "BEDROCK", "BARRIER", "PORTAL", "NETHER_PORTAL", "END_PORTAL",
                    "ENDER_PORTAL", "END_GATEWAY", "POWDER_SNOW" -> true;
            default -> false;
        };
    }
}
