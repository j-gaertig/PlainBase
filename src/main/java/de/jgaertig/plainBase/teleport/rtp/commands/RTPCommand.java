package de.jgaertig.plainBase.teleport.rtp.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.teleport.rtp.RTPManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class RTPCommand implements BasicCommand {

    private final PlainBase plugin;

    public RTPCommand(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        // Root-config read under the central configLock: the instance is
        // fetched INSIDE the lock since reloadConfig() may swap it.
        boolean moduleEnabled;
        synchronized (plugin.getConfigLock()) {
            FileConfiguration rootCfg = plugin.getConfig();
            moduleEnabled = rootCfg != null && rootCfg.getBoolean("modules.teleport", false);
        }
        if (!moduleEnabled) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module is currently disabled."));
            return;
        }

        // Captured once: a /plainbase reload racing this command can null
        // the manager/config between the guard below and later use.
        FileConfiguration teleportConfig = plugin.getTeleportConfig();
        RTPManager rtpManager = plugin.getRTPManager();
        if (teleportConfig == null || rtpManager == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Teleport is currently unavailable."));
            return;
        }

        if (!teleportConfig.getBoolean("rtp.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP has been disabled."));
            return;
        }

        if (!sender.hasPermission("plainbase.admin") && !sender.hasPermission("plainbase.teleport.admin") && !sender.hasPermission("plainbase.teleport.rtp.admin") && !sender.hasPermission("plainbase.teleport.rtp.rtp")) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return;
        }

        if (!teleportConfig.getBoolean("rtp.commands.rtp.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command can only be executed by players."));
            return;
        }

        if (!(args.length == 0)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/rtp"));
            return;
        }

        if (rtpManager == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP is currently unavailable."));
            return;
        }

        rtpManager.startRTPProcess(player);
    }
}
