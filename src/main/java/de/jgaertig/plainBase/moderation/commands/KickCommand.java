package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
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
 * /kick <player> [reason] — only works on online players (kicks are not
 * persistent bans), but is still recorded in kicks.yml for /baninfo history.
 */
public class KickCommand extends ModerationCommandBase implements BasicCommand {

    public KickCommand(PlainBase plugin) {
        super(plugin);
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!checkPreconditions(sender, "plainbase.moderation.kick", "kick")) return;

        if (!plugin.getModerationConfig().getBoolean("kick.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Kicking is currently disabled."));
            return;
        }

        if (args.length < 1) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/kick <player> [reason]"));
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize(
                    message("player-not-online", "<red>%player% is not online.").replace("%player%", args[0])));
            return;
        }

        if (isExempt(target, sender)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize(message("exempt", "<red>You cannot punish this player.")));
            return;
        }

        String reason = args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : message("default-reason", "No reason specified.");

        UUID staffUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
        String staffName = sender.getName();
        String targetName = target.getName();

        plugin.getBanManager().recordKick(target.getUniqueId(), targetName, reason, staffUuid, staffName);

        target.kick(plugin.getMiniMessage().deserialize(
                message("kick-screen", "<red>You have been kicked.\n<gray>Reason: %reason%")
                        .replace("%reason%", reason)
                        .replace("%staff%", staffName)));

        sender.sendMessage(plugin.getMiniMessage().deserialize(
                message("kick-success", "<green>%player% has been kicked. <gray>(%reason%)")
                        .replace("%player%", targetName).replace("%reason%", reason)));

        broadcast(message("kick-broadcast", "")
                .replace("%player%", targetName).replace("%staff%", staffName).replace("%reason%", reason));
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
