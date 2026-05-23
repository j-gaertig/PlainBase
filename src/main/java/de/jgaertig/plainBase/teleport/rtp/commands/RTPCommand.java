package de.jgaertig.plainBase.teleport.rtp.commands;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
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

        if (!plugin.getConfig().getBoolean("modules.teleport", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module is currently disabled."));
            return;
        }

        if (!plugin.getTeleportConfig().getBoolean("rtp.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>RTP has been disabled."));
            return;
        }

        if (!plugin.getTeleportConfig().getBoolean("rtp.commands.rtp.enabled", true)) {
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

        plugin.getRTPManager().startRTPProcess(player);
    }
}
