package de.jgaertig.plainBase.spawn.commands;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class SetFirstSpawn implements CommandExecutor {

    private final PlainBase plugin;

    public SetFirstSpawn(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {

        if (!plugin.getSpawnConfig().getBoolean("commands.setfirstspawn.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be executed by players.");
            return true;
        }

        Location loc = null;

        if (args.length == 0) {
            loc = player.getLocation();
        } else if (args.length >= 3) {
            try {
                double x = parseCoordinate(args[0], player.getLocation().getX());
                double y = parseCoordinate(args[1], player.getLocation().getY());
                double z = parseCoordinate(args[2], player.getLocation().getZ());
                loc = new Location(player.getWorld(), x, y, z, player.getLocation().getYaw(), player.getLocation().getPitch());
            } catch (NumberFormatException e) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Invalid coordinates!"));
                return true;
            }
        }

        if (!(loc == null)) {
            saveToSpawnConfig(loc);
        } else {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Failed to set first spawn location!"));
            return true;
        }

        player.sendMessage(plugin.getMiniMessage().deserialize("<green>Successfully set first spawn location!"));
        return true;
    }

    private double parseCoordinate(String arg, double current) {
        if (arg.equals("~")) return current;
        if (arg.startsWith("~")) return current + Double.parseDouble(arg.substring(1));
        return Double.parseDouble(arg);
    }

    private void saveToSpawnConfig(Location loc) {
        var config = plugin.getSpawnConfig();
        config.set("firstspawn.location.world", loc.getWorld().getName());
        config.set("firstspawn.location.x", loc.getX());
        config.set("firstspawn.location.y", loc.getY());
        config.set("firstspawn.location.z", loc.getZ());
        config.set("firstspawn.location.yaw", (double) loc.getYaw());
        config.set("firstspawn.location.pitch", (double) loc.getPitch());
        config.set("firstspawn.enabled", true);
        plugin.saveSpawnConfig();
    }
}
