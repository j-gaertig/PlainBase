package de.jgaertig.plainBase.teleport.tpa.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.teleport.tpa.TPAManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

public class TPAHERECommand implements BasicCommand {

    private final PlainBase plugin;

    public TPAHERECommand(PlainBase plugin) {
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

        if (!sender.hasPermission("plainbase.admin") && !sender.hasPermission("plainbase.teleport.admin") && !sender.hasPermission("plainbase.teleport.tpa.admin") && !sender.hasPermission("plainbase.teleport.tpa.tpahere")) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return;
        }

        if (!teleportConfig.getBoolean("tpa.commands.tpahere.enabled", true)) {
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

        if (!player.canSee(target)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Player not found!"));
            return;
        }

        if (target.equals(player)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>You cannot teleport yourself to yourself!"));
            return;
        }

        tpaManager.sendRequest(player, target, TPAManager.RequestType.TPAHERE);

    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NonNull [] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            CommandSender sender = stack.getSender();
            if (sender instanceof Player player) {
                // Suggest may run off the entity thread (Folia): copy the
                // player list first and guard canSee per player — on failure
                // fall back to the unfiltered list instead of breaking.
                try {
                    List<Player> online = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
                    List<String> out = new java.util.ArrayList<>();
                    for (Player o : online) {
                        boolean visible = true;
                        try {
                            visible = player.canSee(o);
                        } catch (Exception ignored) {
                        }
                        if (visible && o.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                            out.add(o.getName());
                        }
                    }
                    return out;
                } catch (Exception e) {
                    return List.of();
                }
            }
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
