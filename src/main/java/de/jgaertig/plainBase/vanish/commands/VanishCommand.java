package de.jgaertig.plainBase.vanish.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.vanish.VanishManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class VanishCommand implements BasicCommand {

    private final PlainBase plugin;

    public VanishCommand(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!plugin.getConfig().getBoolean("modules.vanish", false)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module is currently disabled."));
            return;
        }

        FileConfiguration vanishConfig = plugin.getVanishConfig();
        // Captured once: a /plainbase reload racing this command can null
        // the manager between the guard below and later use.
        VanishManager vanishManager = plugin.getVanishManager();
        if (vanishConfig == null || vanishManager == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Vanish is currently unavailable."));
            return;
        }

        if (!vanishConfig.getBoolean("vanish.enabled", true)) {
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
            if (!vanishConfig.getBoolean("vanish.world.enabled", true)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
                return;
            }
            if (!vanishConfig.getBoolean("vanish.commands.vanish.enabled", true)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
                return;
            }

            List<Player> targets = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getWorld().equals(player.getWorld())) {
                    targets.add(online);
                }
            }
            toggleAll(player, targets);
            return;
        }

        // /vanish all — vanish all online players (including the executor)
        if (args.length == 1 && args[0].equalsIgnoreCase("all")) {
            if (!checkPermission(player, "plainbase.vanish.all")) return;
            if (!vanishConfig.getBoolean("vanish.all.enabled", true)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
                return;
            }
            if (!vanishConfig.getBoolean("vanish.commands.vanish.enabled", true)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
                return;
            }

            List<Player> targets = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                targets.add(online);
            }
            toggleAll(player, targets);
            return;
        }

        // /vanish <player> — vanish a specific player
        if (args.length == 1) {
            if (!checkPermission(player, "plainbase.vanish.vanish.other")) return;
            if (!vanishConfig.getBoolean("vanish.commands.vanish.enabled", true)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
                return;
            }

            // Exact first: Bukkit#getPlayer does prefix matching ("Alex" also
            // matches "Alexander") and could vanish the wrong player on a
            // typo. The canSee oracle guard below still applies to both paths.
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                try {
                    target = Bukkit.getPlayer(args[0]);
                } catch (Exception e) {
                    target = null;
                }
            }
            if (target == null) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Player not found!"));
                return;
            }
            if (target.equals(player)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Use /vanish without arguments to vanish yourself."));
                return;
            }
            // Oracle guard (mirrors suggest() filtering): a viewer who cannot
            // see the target must get the same "not found" as for an offline
            // player — otherwise /vanish <name> reveals whether a hidden
            // (vanished) player is online.
            if (!vanishManager.canSee(player, target)) {
                player.sendMessage(plugin.getMiniMessage().deserialize("<red>Player not found!"));
                return;
            }

            boolean nowVanished = vanishManager.toggleVanish(target);
            player.sendMessage(plugin.getMiniMessage().deserialize(
                    "<gray>" + plugin.getMiniMessage().escapeTags(target.getName()) + " is now " + (nowVanished ? "<green>vanished" : "<red>visible") + "<gray>."
            ));
            return;
        }

        // More than one argument is never valid — without this, /vanish a b c
        // would silently fall through to a self-vanish.
        if (args.length > 1) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/vanish [player|world|all]"));
            return;
        }

        // /vanish — vanish/unvanish self
        if (!checkPermission(player, "plainbase.vanish.vanish")) return;
        if (!vanishConfig.getBoolean("vanish.commands.vanish.enabled", true)) {
            player.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return;
        }

        boolean nowVanished = vanishManager.toggleVanish(player);
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

    /**
     * Single-state bulk toggle (NOT per-player flips): when ANY target is
     * still visible, EVERY target is vanished (the executor included — they
     * are part of the target list); only when ALL targets are already vanished
     * are they revealed together. Per-player toggling would leave a mixed
     * group in the exact same mixed state (a no-op with noise), hence the
     * anyVisible election below. /vanish world scopes the targets to the
     * executor's current world, /vanish all covers every online player.
     * No-op targets are skipped silently; only actual state changes count
     * toward the vanished/revealed totals. Non-executor targets always get a
     * direct notice so nobody is vanished without knowing it.
     */
    private void toggleAll(Player executor, List<Player> targets) {
        // Determine a single target state instead of toggling each player
        // individually: if anyone is still visible, vanish everyone —
        // otherwise reveal everyone. Per-player toggling would leave a
        // mixed group in the exact same mixed state (no-op with noise).
        // Captured once: a /plainbase reload racing this loop can null
        // the manager between calls.
        VanishManager vanishManager = plugin.getVanishManager();
        if (vanishManager == null) return;
        boolean anyVisible = targets.stream().anyMatch(p -> !vanishManager.isVanished(p));

        int vanished = 0;
        int revealed = 0;
        for (Player target : targets) {
            boolean isVanished = vanishManager.isVanished(target);
            if (anyVisible && !isVanished) {
                vanishManager.vanish(target);
                vanished++;
                if (!target.equals(executor)) {
                    target.sendMessage(plugin.getMiniMessage().deserialize("<green>You are now vanished!"));
                }
            } else if (!anyVisible && isVanished) {
                vanishManager.unvanish(target);
                revealed++;
                if (!target.equals(executor)) {
                    target.sendMessage(plugin.getMiniMessage().deserialize("<gray>You are no longer vanished."));
                }
            }
        }
        executor.sendMessage(plugin.getMiniMessage().deserialize(
                "<gray>Vanished: <green>" + vanished + " <gray>| Made visible: <red>" + revealed
        ));
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NonNull [] args) {
        // Mirror execute() guards: no suggestions when the module is off or unavailable.
        try {
            if (!plugin.getConfig().getBoolean("modules.vanish", false)) return List.of();
        } catch (Exception e) {
            return List.of();
        }
        if (plugin.getVanishConfig() == null || plugin.getVanishManager() == null) return List.of();
        VanishManager vanishManager = plugin.getVanishManager();
        if (vanishManager == null) return List.of();

        CommandSender sender = stack.getSender();
        if (!hasAnyVanishPermission(sender)) return List.of();

        if (args.length <= 1) {
            String input = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> literals = Stream.of("world", "all")
                    .filter(s -> s.startsWith(input))
                    .filter(s -> hasVanishPermission(sender, s.equals("world") ? "plainbase.vanish.world" : "plainbase.vanish.all"))
                    .toList();

            // Player names require vanish.other — self-vanish needs no target.
            List<String> players;
            if (!hasVanishPermission(sender, "plainbase.vanish.vanish.other")) {
                players = List.of();
            } else if (sender instanceof Player viewer) {
                players = Bukkit.getOnlinePlayers().stream()
                        .filter(p -> vanishManager.canSee(viewer, p))
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input))
                        .toList();
            } else {
                players = Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input))
                        .toList();
            }

            return Stream.concat(literals.stream(), players.stream())
                    .distinct()
                    .toList();
        }
        return List.of();
    }

    private boolean hasVanishPermission(CommandSender sender, String permission) {
        try {
            return sender.hasPermission("plainbase.admin")
                    || sender.hasPermission("plainbase.vanish.admin")
                    || sender.hasPermission(permission);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasAnyVanishPermission(CommandSender sender) {
        return hasVanishPermission(sender, "plainbase.vanish.vanish")
                || hasVanishPermission(sender, "plainbase.vanish.vanish.other")
                || hasVanishPermission(sender, "plainbase.vanish.world")
                || hasVanishPermission(sender, "plainbase.vanish.all");
    }
}