package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanManager;
import de.jgaertig.plainBase.moderation.DurationParser;
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
 * /tempban <player> <duration> [reason] — e.g. "1d", "2h30m", "7d".
 * "permanent"/"perm"/"-1" behave like /ban.
 */
public class TempBanCommand extends ModerationCommandBase implements BasicCommand {

    public TempBanCommand(PlainBase plugin) {
        super(plugin);
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!checkPreconditions(sender, "plainbase.moderation.tempban", "tempban")) return;

        if (!plugin.getModerationConfig().getBoolean("ban.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Banning is currently disabled."));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/tempban <player> <duration> [reason]"));
            return;
        }

        String targetName = args[0];
        long durationMillis;
        try {
            durationMillis = DurationParser.parse(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(plugin.getMiniMessage().deserialize(
                    message("invalid-duration", "<red>Invalid duration. Use e.g. 1d, 2h30m, 7d or permanent.")));
            return;
        }

        String reason = args.length > 2
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : message("default-reason", "No reason specified.");

        UUID staffUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
        String staffName = sender.getName();
        long finalDuration = durationMillis;

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
            manager.tryBanAsync(offlinePlayer.getUniqueId(), name, reason, staffUuid, staffName, finalDuration, result -> {
                if (result.isEmpty()) {
                    sender.sendMessage(plugin.getMiniMessage().deserialize(
                            message("already-banned", "<red>%player% is already banned.").replace("%player%", name)));
                    return;
                }

                String durationText = DurationParser.format(finalDuration);

                Player online = offlinePlayer.getPlayer();
                if (online != null) {
                    kickSafely(online, plugin.getMiniMessage().deserialize(
                            message("tempban-screen", "<red>You are temporarily banned.\n<gray>Reason: %reason%\n<gray>Remaining: %remaining%")
                                    .replace("%reason%", reason)
                                    .replace("%staff%", staffName)
                                    .replace("%remaining%", durationText)));
                } else if (!offlinePlayer.hasPlayedBefore()) {
                    sender.sendMessage(plugin.getMiniMessage().deserialize(
                            message("never-played", "<yellow>Warning: %player% has never played on this server.").replace("%player%", name)));
                }

                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("tempban-success", "<green>%player% has been banned for %duration%. <gray>(%reason%)")
                                .replace("%player%", name).replace("%duration%", durationText).replace("%reason%", reason)));

                broadcast(message("tempban-broadcast", "")
                        .replace("%player%", name).replace("%staff%", staffName)
                        .replace("%duration%", durationText).replace("%reason%", reason));
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
        if (args.length == 2) {
            return List.of("1d", "7d", "1h", "30m", "permanent");
        }
        return List.of();
    }
}
