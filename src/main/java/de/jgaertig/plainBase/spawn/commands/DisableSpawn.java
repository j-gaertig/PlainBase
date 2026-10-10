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

        if (!plugin.getConfig().getBoolean("modules.spawn", false)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module is currently disabled."));
            return;
        }

        if (plugin.getSpawnConfig() == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is currently unavailable."));
            return;
        }

        if (!sender.hasPermission("plainbase.admin") && !sender.hasPermission("plainbase.spawn.admin") && !sender.hasPermission("plainbase.spawn.disablespawn")) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return;
        }

        // Snapshot + locked read: writers mutate under synchronized(config).
        FileConfiguration disableConfig = plugin.getSpawnConfig();
        if (disableConfig == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is currently unavailable."));
            return;
        }
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

        var config = plugin.getSpawnConfig();
        if (config == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Spawn is currently unavailable."));
            return;
        }
        synchronized (config) {
            config.set("spawn.enabled", false);
        }
        plugin.saveSpawnConfigAsync();

        sender.sendMessage(plugin.getMiniMessage().deserialize("<green>Spawn has been disabled!"));
    }
}
