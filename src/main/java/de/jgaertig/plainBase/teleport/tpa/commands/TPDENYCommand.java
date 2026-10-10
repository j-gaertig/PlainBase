package de.jgaertig.plainBase.teleport.tpa.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.teleport.tpa.TPAManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class TPDENYCommand implements BasicCommand {

    private final PlainBase plugin;

    public TPDENYCommand(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!plugin.getConfig().getBoolean("modules.teleport", false)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module is currently disabled."));
            return;
        }

        // Captured once: a /plainbase reload racing this command can null
        // the manager/config between the guard below and later use.
        FileConfiguration teleportConfig = plugin.getTeleportConfig();
        TPAManager tpaManager = plugin.getTPAManager();
        if (teleportConfig == null || tpaManager == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport is currently unavailable."));
            return;
        }

        if (!teleportConfig.getBoolean("tpa.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>TPA has been disabled."));
            return;
        }

        if (!sender.hasPermission("plainbase.admin") && !sender.hasPermission("plainbase.teleport.admin") && !sender.hasPermission("plainbase.teleport.tpa.admin") && !sender.hasPermission("plainbase.teleport.tpa.tpdeny")) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return;
        }

        if (!teleportConfig.getBoolean("tpa.commands.tpdeny.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command can only be executed by players."));
            return;
        }

        if (tpaManager == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>TPA is currently unavailable."));
            return;
        }

        if (args.length != 0) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/tpdeny"));
            return;
        }

        tpaManager.denyRequest(player);

    }
}
