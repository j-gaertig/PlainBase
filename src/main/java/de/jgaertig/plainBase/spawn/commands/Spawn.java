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

        if (!plugin.getConfig().getBoolean("modules.spawn", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module is currently disabled."));
            return;
        }

        if (plugin.getSpawnConfig() == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is currently unavailable."));
            return;
        }

        if (!sender.hasPermission("plainbase.admin") && !sender.hasPermission("plainbase.spawn.admin") && !sender.hasPermission("plainbase.spawn.spawn")) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return;
        }

        if (!plugin.getSpawnConfig().getBoolean("commands.spawn.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command can only be executed by players."));
            return;
        }

        if (!plugin.getSpawnConfig().getBoolean("spawn.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn position has been disabled."));
            return;
        }

        String path = "spawn.location";

        FileConfiguration config = plugin.getSpawnConfig();
        String worldName = config.getString(path + ".world");
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

        double rawX = config.getDouble(path + ".x");
        double rawY = config.getDouble(path + ".y");
        double rawZ = config.getDouble(path + ".z");
        if (!Double.isFinite(rawX) || !Double.isFinite(rawY) || !Double.isFinite(rawZ)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is not set correctly. Contact an admin."));
            plugin.getLogger().warning("Spawn teleport failed for " + player.getName() + ": non-finite coordinates in spawn.yml!");
            return;
        }
        double rawYaw = config.getDouble(path + ".yaw");
        double rawPitch = config.getDouble(path + ".pitch");
        float yaw = Double.isFinite(rawYaw) ? (float) rawYaw : 0f;
        float pitch = Double.isFinite(rawPitch) ? (float) rawPitch : 0f;

        Location loc;
        if (rawY < world.getMinHeight() || rawY >= world.getMaxHeight()) {
            plugin.getLogger().warning("Spawn teleport for " + player.getName() + ": Y=" + rawY
                    + " out of bounds [" + world.getMinHeight() + "," + world.getMaxHeight()
                    + "), falling back to world spawn.");
            loc = world.getSpawnLocation().clone();
        } else {
            loc = new Location(world, rawX, rawY, rawZ, yaw, pitch);
        }

        player.teleportAsync(loc).thenAccept(success -> {
            if (!player.isOnline()) {
                return;
            }
            try {
                if (Boolean.TRUE.equals(success)) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<green>Teleported to spawn!"));
                } else {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport failed. Try again or contact an admin!"));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to notify " + player.getName() + " about spawn teleport: " + e.getMessage());
            }
        });

    }
}
