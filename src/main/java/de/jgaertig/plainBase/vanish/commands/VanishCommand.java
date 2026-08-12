package de.jgaertig.plainBase.vanish.commands;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class VanishCommand implements BasicCommand {

    private final PlainBase plugin;

    public VanishCommand(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!plugin.getConfig().getBoolean("modules.vanish", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module is currently disabled."));
            return;
        }

        if (!plugin.getVanishConfig().getBoolean("vanish.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Vanish has been disabled."));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command can only be executed by players."));
            return;
        }

        // /vanish world — vanish all players in the sender's world
        if (args.length == 1 && args[0].equalsIgnoreCase("world")) {
            if (!checkPermission(player, "plainbase.vanish.world")) return;
            if (!plugin.getVanishConfig().getBoolean("vanish.world.enabled", true)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
                return;
            }
            if (!plugin.getVanishConfig().getBoolean("vanish.commands.vanish.enabled", true)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
                return;
            }

            List<Player> targets = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player) && online.getWorld().equals(player.getWorld())) {
                    targets.add(online);
                }
            }
            if (targets.isEmpty()) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>No other players found in your world."));
                return;
            }
            toggleAll(player, targets);
            return;
        }

        // /vanish all — vanish all online players
        if (args.length == 1 && args[0].equalsIgnoreCase("all")) {
            if (!checkPermission(player, "plainbase.vanish.all")) return;
            if (!plugin.getVanishConfig().getBoolean("vanish.all.enabled", true)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
                return;
            }
            if (!plugin.getVanishConfig().getBoolean("vanish.commands.vanish.enabled", true)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
                return;
            }

            List<Player> targets = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) targets.add(online);
            }
            if (targets.isEmpty()) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>No other players found."));
                return;
            }
            toggleAll(player, targets);
            return;
        }

        // /vanish <player> — vanish a specific player
        if (args.length == 1) {
            if (!checkPermission(player, "plainbase.vanish.vanish.other")) return;
            if (!plugin.getVanishConfig().getBoolean("vanish.commands.vanish.enabled", true)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
                return;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Player not found!"));
                return;
            }
            if (target.equals(player)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Use /vanish without arguments to vanish yourself."));
                return;
            }

            boolean nowVanished = plugin.getVanishManager().toggleVanish(target);
            player.sendMessage(plugin.getMiniMessage().deserialize(
                    "<gray>" + target.getName() + " is now " + (nowVanished ? "<green>vanished" : "<red>visible") + "<gray>."
            ));
            return;
        }

        // /vanish — vanish/unvanish self
        if (!checkPermission(player, "plainbase.vanish.vanish")) return;
        if (!plugin.getVanishConfig().getBoolean("vanish.commands.vanish.enabled", true)) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return;
        }

        boolean nowVanished = plugin.getVanishManager().toggleVanish(player);
        player.sendMessage(plugin.getMiniMessage().deserialize(
                nowVanished ? "<green>You are now vanished!" : "<gray>You are no longer vanished."
        ));
    }

    private boolean checkPermission(Player player, String permission) {
        if (!player.hasPermission("plainbase.admin")
                && !player.hasPermission("plainbase.vanish.admin")
                && !player.hasPermission(permission)) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return false;
        }
        return true;
    }

    private void toggleAll(Player executor, List<Player> targets) {
        int vanished = 0;
        int revealed = 0;
        for (Player target : targets) {
            if (plugin.getVanishManager().toggleVanish(target)) vanished++;
            else revealed++;
        }
        executor.sendMessage(plugin.getMiniMessage().deserialize(
                "<gray>Vanished: <green>" + vanished + " <gray>| Made visible: <red>" + revealed
        ));
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NonNull [] args) {
        if (args.length <= 1) {
            String input = args.length == 0 ? "" : args[0].toLowerCase();

            List<String> literals = Stream.of("world", "all")
                    .filter(s -> s.startsWith(input))
                    .toList();

            List<String> players = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .toList();

            return Stream.concat(literals.stream(), players.stream())
                    .distinct()
                    .toList();
        }
        return List.of();
    }
}