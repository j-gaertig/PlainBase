package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /kick <player> [reason] — only works on online players (kicks are not
 * persistent bans), but is still recorded in the database for /baninfo history.
 */
public class KickCommand extends ModerationCommandBase implements BasicCommand {

    public KickCommand(PlainBase plugin) {
        super(plugin);
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!checkPreconditions(sender, "plainbase.moderation.kick", "kick")) return;

        // Captured once: a /plainbase reload racing this command can null
        // the manager/config between checkPreconditions and use — stale
        // locals keep working instead of NPE-ing.
        FileConfiguration moderationConfig = plugin.getModerationConfig();
        BanManager banManager = plugin.getBanManager();
        if (moderationConfig == null || banManager == null) {
            sender.sendMessage(render("<red>Moderation module is reloading, try again shortly."));
            return;
        }

        if (!moderationConfig.getBoolean("kick.enabled", true)) {
            sender.sendMessage(render("<red>Kicking is currently disabled."));
            return;
        }

        if (args.length < 1) {
            sender.sendMessage(render("<yellow>Usage: <gray>/kick <player> [reason]"));
            return;
        }

        // Exact name first: Bukkit#getPlayer does prefix matching and could
        // kick the wrong player on a typo ("Alex" also matches "Alexander").
        Player target = onlinePlayerExactFirst(args[0], sender);
        if (target == null) {
            sender.sendMessage(render(
                    message("player-not-online", "<red>%player% is not online.").replace("%player%", esc(args[0]))));
            return;
        }

        if (isExempt(target, sender) || isProtectedTarget(target, sender)) {
            sender.sendMessage(render(message("exempt", "<red>You cannot punish this player.")));
            return;
        }

        String reason = defaultReason(args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : null);

        UUID staffUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
        String staffName = sender.getName();
        String targetName = target.getName();
        UUID targetUuid = target.getUniqueId();

        // Online check BEFORE the insert: a kick that can never land (target
        // already gone) must not be recorded as history either. The re-check
        // after the async write below stays — the target can still leave
        // during the DB hop.
        if (Bukkit.getPlayer(targetUuid) == null) {
            sender.sendMessage(render(
                    message("player-not-online", "<red>%player% is not online.").replace("%player%", esc(targetName))));
            return;
        }

        banManager.recordKickAsync(targetUuid, targetName, reason, staffUuid, staffName, () -> {
            // Re-check AFTER the DB write: the target may have logged off
            // during the async hop. A kick that never landed must not report
            // success — tell staff the player left instead (the kick is still
            // recorded in history, but no broadcast goes out for a kick that
            // never happened).
            Player stillOnline = Bukkit.getPlayer(targetUuid);
            if (stillOnline == null) {
                sender.sendMessage(render(
                        message("player-not-online", "<red>%player% is not online.").replace("%player%", esc(targetName))));
                return;
            }
            kickSafely(stillOnline, render(
                    message("kick-screen", "<red>You have been kicked.\n<gray>Reason: %reason%")
                            .replace("%reason%", esc(reason))
                            .replace("%staff%", esc(staffName))));

            sender.sendMessage(render(
                    message("kick-success", "<green>%player% has been kicked. <gray>(%reason%)")
                            .replace("%player%", esc(targetName)).replace("%reason%", esc(reason))));

            broadcast(message("kick-broadcast", "")
                    .replace("%player%", esc(targetName)).replace("%staff%", esc(staffName)).replace("%reason%", esc(reason)));
        });
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        if (args.length <= 1) {
            String input = args.length == 0 ? "" : args[0];
            return suggestOnlinePlayers(stack.getSender(), input, "plainbase.moderation.kick");
        }
        return List.of();
    }
}
