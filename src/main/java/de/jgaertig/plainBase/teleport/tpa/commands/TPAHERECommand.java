package de.jgaertig.plainBase.teleport.tpa.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.teleport.tpa.TPAManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class TPAHERECommand implements BasicCommand {

    private final PlainBase plugin;

    public TPAHERECommand(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!plugin.getConfig().getBoolean("modules.teleport", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module is currently disabled."));
            return;
        }

        if (!plugin.getTeleportConfig().getBoolean("tpa.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>TPA has been disabled."));
            return;
        }

        if (!plugin.getTeleportConfig().getBoolean("tpa.commands.tpahere.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command can only be executed by players."));
            return;
        }

        if (args.length != 1) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/tpahere <player>"));
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);

        if (target == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Player not found!"));
            return;
        }

        if (target.equals(player)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>You cannot teleport yourself to yourself!"));
            return;
        }

        plugin.getTPAManager().sendRequest(player, target, TPAManager.RequestType.TPAHERE);

    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NonNull [] args) {
        if (args.length <= 1) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args.length == 0 ? "" : args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
