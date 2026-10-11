package de.jgaertig.plainBase.spawn.commands;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class SetFirstSpawn implements BasicCommand {

    private final PlainBase plugin;

    public SetFirstSpawn(PlainBase plugin) {
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

        // Single snapshot, reused for the checks and the write below (never
        // re-fetched: a reload between the checks could otherwise hand back a
        // different/null instance).
        FileConfiguration firstSpawnConfig = plugin.getSpawnConfig();
        if (firstSpawnConfig == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is currently unavailable."));
            return;
        }

        if (!sender.hasPermission("plainbase.admin") && !sender.hasPermission("plainbase.spawn.admin") && !sender.hasPermission("plainbase.spawn.setfirstspawn")) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return;
        }

        // Snapshot + locked read: writers mutate under synchronized(config).
        // Reuses the firstSpawnConfig snapshot above (never re-fetched).
        boolean commandEnabled;
        synchronized (firstSpawnConfig) {
            commandEnabled = firstSpawnConfig.getBoolean("commands.setfirstspawn.enabled", true);
        }
        if (!commandEnabled) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command can only be executed by players."));
            return;
        }

        Location loc = null;

        if (args.length == 0) {
            loc = player.getLocation();
        } else if (args.length == 3) {
            try {
                double x = parseCoordinate(args[0], player.getLocation().getX());
                double y = parseCoordinate(args[1], player.getLocation().getY());
                double z = parseCoordinate(args[2], player.getLocation().getZ());
                loc = new Location(player.getWorld(), x, y, z, player.getLocation().getYaw(), player.getLocation().getPitch());
            } catch (NumberFormatException e) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Invalid coordinates!"));
                return;
            }
        } else {
            player.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/setfirstspawn [x y z]"));
            return;
        }

        if (!(loc == null)) {
            if (!Double.isFinite(loc.getX()) || !Double.isFinite(loc.getY()) || !Double.isFinite(loc.getZ())) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Invalid coordinates!"));
                return;
            }
            if (loc.getWorld() == null) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Failed to set first spawn location!"));
                return;
            }
            try {
                double y = loc.getY();
                int min = loc.getWorld().getMinHeight();
                int max = loc.getWorld().getMaxHeight();
                if (!(y >= min && y < max)) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>Invalid coordinates! Y out of world bounds."));
                    return;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to check world height for /setfirstspawn: " + e.getMessage());
            }
            try {
                var border = loc.getWorld().getWorldBorder();
                double centerX = border.getCenter().getX();
                double centerZ = border.getCenter().getZ();
                double half = border.getSize() / 2.0;
                if (!Double.isFinite(centerX) || !Double.isFinite(centerZ) || !Double.isFinite(half)
                        || Math.abs(loc.getX() - centerX) > half
                        || Math.abs(loc.getZ() - centerZ) > half) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>Location is outside the world border!"));
                    return;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to check world border for /setfirstspawn: " + e.getMessage());
            }
            // Spawn safety (warn-only): an obstructed spawn suffocates players
            // on arrival, damaging ground (magma, campfire, cactus, wither
            // rose, berry bush — RTPManager ground rejects) hurts them.
            // Never blocks saving — intentional admin setups (e.g.
            // enclosed spawn rooms) must keep working.
            try {
                org.bukkit.block.Block feet = loc.getBlock();
                org.bukkit.block.Block head = loc.clone().add(0, 1, 0).getBlock();
                org.bukkit.block.Block ground = loc.clone().add(0, -1, 0).getBlock();
                boolean obstructed = !feet.isPassable() || !head.isPassable();
                boolean noGround;
                try {
                    noGround = !ground.getType().isSolid();
                } catch (Exception e) {
                    noGround = false;
                }
                boolean hazardous = false;
                try {
                    hazardous = isDamagingSpawnGround(ground.getType().name());
                } catch (Exception e) {
                    hazardous = false;
                }
                if (obstructed || noGround || hazardous) {
                    String detail = obstructed ? "target block obstructed" : noGround ? "no solid ground below" : "damaging ground below";
                    plugin.getLogger().warning("/setfirstspawn by " + player.getName() + ": location "
                            + String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ())
                            + " in '" + loc.getWorld().getName() + "' may be unsafe (" + detail + ") — players may suffocate, fall or take damage.");
                    player.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Warning: this first-spawn location may be unsafe (" + detail + "). Players may suffocate, fall or take damage — move to a safe spot and re-run to silence this warning."));
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Failed to check spawn safety for /setfirstspawn: " + e.getMessage());
            }
            saveToSpawnConfig(firstSpawnConfig, loc);
        } else {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Failed to set first spawn location!"));
            return;
        }

        player.sendMessage(plugin.getMiniMessage().deserialize("<green>Successfully set first spawn location!"));
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

    private double parseCoordinate(String arg, double current) {
        if (arg.equals("~")) return current;
        if (arg.startsWith("~")) return current + Double.parseDouble(arg.substring(1));
        return Double.parseDouble(arg);
    }

    private void saveToSpawnConfig(FileConfiguration config, Location loc) {
        if (config == null) return;
        synchronized (config) {
            config.set("first-spawn.location.world", loc.getWorld().getName());
            config.set("first-spawn.location.x", loc.getX());
            config.set("first-spawn.location.y", loc.getY());
            config.set("first-spawn.location.z", loc.getZ());
            config.set("first-spawn.location.yaw", (double) loc.getYaw());
            config.set("first-spawn.location.pitch", (double) loc.getPitch());
            config.set("first-spawn.enabled", true);
        }
        plugin.saveSpawnConfigAsync();
    }
}
