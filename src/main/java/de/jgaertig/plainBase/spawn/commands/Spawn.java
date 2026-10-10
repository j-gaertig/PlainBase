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
            loc = resolveSafeSpawnLocation(world, new Location(world, rawX, rawY, rawZ, yaw, pitch), player);
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

    /**
     * Spawn safety: when feet/head at the configured location are obstructed,
     * scan up (max +5, same pattern as TPAManager arrival) for feet+head
     * passable with solid ground below. Never throws and never blocks: when no
     * safe spot is found the original location is used with a warning, so
     * existing admin setups keep working.
     */
    private Location resolveSafeSpawnLocation(World world, Location loc, Player player) {
        try {
            if (world == null || loc == null) return loc;
            if (isSpawnSpotSafe(loc)) return loc;
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
                plugin.getLogger().warning("Spawn teleport for " + player.getName()
                        + ": configured location obstructed, using safe spot +" + foundOffset + " up.");
                player.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Spawn location was obstructed, using nearest safe spot instead. Contact an admin!"));
                return found;
            }
            plugin.getLogger().warning("Spawn teleport for " + player.getName()
                    + ": configured location obstructed and no safe spot found within +5, teleporting anyway.");
            player.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Warning: spawn location looks obstructed. Contact an admin!"));
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to check spawn safety for " + player.getName() + ": " + e.getMessage());
        }
        return loc;
    }

    private boolean isSpawnSpotSafe(Location cand) {
        try {
            org.bukkit.block.Block feet = cand.getBlock();
            org.bukkit.block.Block head = cand.clone().add(0, 1, 0).getBlock();
            org.bukkit.block.Block ground = cand.clone().add(0, -1, 0).getBlock();
            if (!feet.isPassable() || !head.isPassable()) return false;
            return ground.getType().isSolid();
        } catch (Exception e) {
            // Fail-open: a lookup error must never block the teleport.
            return true;
        }
    }
}
