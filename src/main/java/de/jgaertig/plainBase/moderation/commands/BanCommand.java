package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /ban <player> [reason] — permanent ban. Usable from console. Reason
 * defaults to messages.default-reason when omitted.
 */
public class BanCommand extends ModerationCommandBase implements BasicCommand {

    public BanCommand(PlainBase plugin) {
        super(plugin);
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!checkPreconditions(sender, "plainbase.moderation.ban", "ban")) return;

        if (!plugin.getModerationConfig().getBoolean("ban.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Banning is currently disabled."));
            return;
        }

        if (args.length < 1) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/ban <player> [reason]"));
            return;
        }

        String targetName = args[0];
        String reason = args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : message("default-reason", "No reason specified.");

        UUID staffUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
        String staffName = sender.getName();

        resolveTarget(targetName, offlinePlayer -> {
            if (offlinePlayer == null) {
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("player-not-found", "<red>Could not resolve player: %player%").replace("%player%", targetName)));
                return;
            }

            String name = displayName(offlinePlayer, targetName);

            if (isExempt(offlinePlayer, sender)) {
                sender.sendMessage(plugin.getMiniMessage().deserialize(message("exempt", "<red>You cannot punish this player.")));
                return;
            }

            BanManager manager = plugin.getBanManager();
            manager.tryBanAsync(offlinePlayer.getUniqueId(), name, reason, staffUuid, staffName, -1L, result -> {
                if (result.isEmpty()) {
                    sender.sendMessage(plugin.getMiniMessage().deserialize(
                            message("already-banned", "<red>%player% is already banned.").replace("%player%", name)));
                    return;
                }

                Player online = offlinePlayer.getPlayer();
                if (online != null) {
                    kickSafely(online, plugin.getMiniMessage().deserialize(
                            message("ban-screen", "<red>You are banned.\n<gray>Reason: %reason%")
                                    .replace("%reason%", reason)
                                    .replace("%staff%", staffName)));
                } else if (!offlinePlayer.hasPlayedBefore()) {
                    sender.sendMessage(plugin.getMiniMessage().deserialize(
                            message("never-played", "<yellow>Warning: %player% has never played on this server.").replace("%player%", name)));
                }

                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("ban-success", "<green>%player% has been permanently banned. <gray>(%reason%)")
                                .replace("%player%", name).replace("%reason%", reason)));

                broadcast(message("ban-broadcast", "")
                        .replace("%player%", name).replace("%staff%", staffName).replace("%reason%", reason));
            });
        });
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        if (args.length <= 1) {
            String input = args.length == 0 ? "" : args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(input)).collect(Collectors.toList());
        }
        return List.of();
    }
}
