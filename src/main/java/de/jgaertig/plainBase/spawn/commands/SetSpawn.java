package de.jgaertig.plainBase.spawn.commands;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class SetSpawn implements CommandExecutor {

    private final PlainBase plugin;

    public SetSpawn(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {

        if (!plugin.getSpawnConfig().getBoolean("commands.setspawn.enabled", true)) {
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
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Failed to set spawn location!"));
            return true;
        }

        player.sendMessage(plugin.getMiniMessage().deserialize("<green>Successfully set spawn location!"));
        return true;
    }

    private double parseCoordinate(String arg, double current) {
        if (arg.equals("~")) return current;
        if (arg.startsWith("~")) return current + Double.parseDouble(arg.substring(1));
        return Double.parseDouble(arg);
    }

    private void saveToSpawnConfig(Location loc) {
        var config = plugin.getSpawnConfig();
        config.set("spawn.location.world", loc.getWorld().getName());
        config.set("spawn.location.x", loc.getX());
        config.set("spawn.location.y", loc.getY());
        config.set("spawn.location.z", loc.getZ());
        config.set("spawn.location.yaw", (double) loc.getYaw());
        config.set("spawn.location.pitch", (double) loc.getPitch());
        config.set("spawn.enabled", true);
        plugin.saveSpawnConfig();
    }
}
