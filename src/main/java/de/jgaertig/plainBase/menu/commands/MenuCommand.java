package de.jgaertig.plainBase.menu.commands;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class MenuCommand implements BasicCommand {

    private final PlainBase plugin;

    public MenuCommand(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!plugin.getConfig().getBoolean("modules.menu", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module is currently disabled."));
            return;
        }

        if (!plugin.getMenuConfig().getBoolean("menu.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>The menu system has been disabled."));
            return;
        }

        if (!plugin.getMenuConfig().getBoolean("menu.commands.menu.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command can only be executed by players."));
            return;
        }

        if (args.length == 0) {
            sendUsage(player);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "new" -> {
                if (!checkPermission(player, "plainbase.menu.new")) return;
                if (args.length < 2) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/menu new <name>"));
                    return;
                }
                String name = args[1].toLowerCase();
                if (!name.matches("[a-z0-9_-]+")) {
                    player.sendMessage(plugin.getMiniMessage().deserialize(
                            "<red>Invalid menu name! Use only <yellow>a-z, 0-9, - and _<red>."
                    ));
                    return;
                }
                if (plugin.getMenuManager().hasMenu(name)) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>A menu with that name already exists!"));
                    return;
                }
                plugin.getMenuManager().createMenu(name);
                player.sendMessage(plugin.getMiniMessage().deserialize(
                        "<green>Menu <yellow>" + name + " <green>created! Edit it in <yellow>modules/menu.yml<green>."
                ));
            }
            case "delete" -> {
                if (!checkPermission(player, "plainbase.menu.delete")) return;
                if (args.length < 2) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/menu delete <name>"));
                    return;
                }
                String name = args[1].toLowerCase();
                if (!plugin.getMenuManager().hasMenu(name)) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>Menu not found!"));
                    return;
                }
                plugin.getMenuManager().deleteMenu(name);
                player.sendMessage(plugin.getMiniMessage().deserialize(
                        "<green>Menu <yellow>" + name + " <green>has been deleted."
                ));
            }
            case "open" -> {
                if (!checkPermission(player, "plainbase.menu.open")) return;
                if (args.length < 2) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/menu open <name>"));
                    return;
                }
                String name = args[1].toLowerCase();
                if (!plugin.getMenuManager().openMenu(player, name)) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<red>Menu not found!"));
                }
            }
            case "list" -> {
                if (!checkPermission(player, "plainbase.menu.list")) return;
                Set<String> names = plugin.getMenuManager().getMenuNames();
                if (names.isEmpty()) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<gray>No menus defined yet."));
                    return;
                }
                player.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Available menus:"));
                for (String name : names) {
                    player.sendMessage(plugin.getMiniMessage().deserialize("<gray>- <white>" + name));
                }
            }
            default -> sendUsage(player);
        }
    }

    private void sendUsage(Player player) {
        player.sendMessage(plugin.getMiniMessage().deserialize(
                "<yellow>Usage: <gray>/menu <new|delete|open|list> [name]"
        ));
    }

    private boolean checkPermission(Player player, String permission) {
        if (!player.hasPermission("plainbase.admin")
                && !player.hasPermission("plainbase.menu.admin")
                && !player.hasPermission(permission)) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return false;
        }
        return true;
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NonNull [] args) {
        if (args.length == 0) {
            return List.of("new", "delete", "open", "list");
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return Stream.of("new", "delete", "open", "list")
                    .filter(s -> s.startsWith(input))
                    .toList();
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("delete") || sub.equals("open")) {
                String input = args[1].toLowerCase();
                return new ArrayList<>(plugin.getMenuManager().getMenuNames()).stream()
                        .filter(n -> n.toLowerCase().startsWith(input))
                        .toList();
            }
        }
        return List.of();
    }
}