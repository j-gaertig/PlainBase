package de.jgaertig.plainBase.spawn.commands;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

public class DisableSpawn implements BasicCommand {

    private final PlainBase plugin;

    public DisableSpawn(PlainBase plugin) {
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

        // Single snapshot, reused for the check and the write below (never
        // re-fetched: a reload between the checks could otherwise hand back a
        // different/null instance).
        FileConfiguration disableConfig = plugin.getSpawnConfig();
        if (disableConfig == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is currently unavailable."));
            return;
        }

        if (!sender.hasPermission("plainbase.admin") && !sender.hasPermission("plainbase.spawn.admin") && !sender.hasPermission("plainbase.spawn.disablespawn")) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return;
        }

        // Snapshot + locked read: writers mutate under synchronized(config).
        // Reuses the disableConfig snapshot above (never re-fetched).
        boolean commandEnabled;
        synchronized (disableConfig) {
            commandEnabled = disableConfig.getBoolean("commands.disablespawn.enabled", true);
        }
        if (!commandEnabled) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return;
        }

        if (args.length != 0) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/disablespawn"));
            return;
        }

        // Reuses the disableConfig snapshot (never re-fetched, see above).
        synchronized (disableConfig) {
            disableConfig.set("spawn.enabled", false);
        }
        plugin.saveSpawnConfigAsync();

        sender.sendMessage(plugin.getMiniMessage().deserialize("<green>Spawn has been disabled!"));
    }
}
