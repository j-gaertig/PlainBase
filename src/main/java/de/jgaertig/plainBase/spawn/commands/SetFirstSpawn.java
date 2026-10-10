package de.jgaertig.plainBase.spawn.commands;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
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

        if (!plugin.getConfig().getBoolean("modules.spawn", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module is currently disabled."));
            return;
        }

        if (plugin.getSpawnConfig() == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is currently unavailable."));
            return;
        }

        if (!sender.hasPermission("plainbase.admin") && !sender.hasPermission("plainbase.spawn.admin") && !sender.hasPermission("plainbase.spawn.setfirstspawn")) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return;
        }

        if (!plugin.getSpawnConfig().getBoolean("commands.setfirstspawn.enabled", true)) {
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
            saveToSpawnConfig(loc);
        } else {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Failed to set first spawn location!"));
            return;
        }

        player.sendMessage(plugin.getMiniMessage().deserialize("<green>Successfully set first spawn location!"));
    }

    private double parseCoordinate(String arg, double current) {
        if (arg.equals("~")) return current;
        if (arg.startsWith("~")) return current + Double.parseDouble(arg.substring(1));
        return Double.parseDouble(arg);
    }

    private void saveToSpawnConfig(Location loc) {
        var config = plugin.getSpawnConfig();
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
