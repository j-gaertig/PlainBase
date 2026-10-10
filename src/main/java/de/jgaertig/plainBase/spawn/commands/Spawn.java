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

        if (!plugin.getConfig().getBoolean("modules.spawn", false)) {
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

        // Snapshot + locked read: writers mutate under synchronized(config).
        FileConfiguration spawnConfig = plugin.getSpawnConfig();
        if (spawnConfig == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is currently unavailable."));
            return;
        }
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
        FileConfiguration config = spawnConfig;
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
            loc = world.getSpawnLocation().clone();
        } else {
            loc = new Location(world, rawX, rawY, rawZ, yaw, pitch);
        }

        // Re-check enabled right before teleportAsync: the config may have been
        // disabled/reloaded between the check above and now.
        try {
            FileConfiguration live = plugin.getSpawnConfig();
            boolean stillEnabled = false;
            if (live != null) {
                synchronized (live) {
                    stillEnabled = live.getBoolean("spawn.enabled", false);
                }
            }
            if (!stillEnabled) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn position has been disabled."));
                return;
            }
        } catch (Exception e) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is currently unavailable."));
            return;
        }

        player.teleportAsync(loc).thenAccept(success -> {
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

    }
}
