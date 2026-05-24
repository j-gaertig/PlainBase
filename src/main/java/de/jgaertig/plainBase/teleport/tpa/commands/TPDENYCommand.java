package de.jgaertig.plainBase.teleport.tpa.commands;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
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

        if (!plugin.getConfig().getBoolean("modules.teleport", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize(plugin.getMessagesConfig().getString("errors.module-disabled", "<red>This module is currently disabled.")));
            return;
        }

        if (!plugin.getTeleportConfig().getBoolean("tpa.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize(plugin.getMessagesConfig().getString("errors.command-disabled", "<red>TPA has been disabled.")));
            return;
        }

        if (!plugin.getTeleportConfig().getBoolean("tpa.commands.tpdeny.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize(plugin.getMessagesConfig().getString("errors.command-disabled", "<red>This command has been disabled.")));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize(plugin.getMessagesConfig().getString("errors.player-only", "<red>This command can only be executed by players.")));
            return;
        }

        plugin.getTPAManager().denyRequest(player);
    }
}
