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
import java.util.UUID;

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

        // Captured once (manager + config): a /plainbase reload racing the
        // async hops below can null either mid-chain — stale locals keep the
        // callback working instead of NPE-ing (same pattern as KickCommand).
        BanManager manager = plugin.getBanManager();
        FileConfiguration moderationConfig = plugin.getModerationConfig();
        if (manager == null || moderationConfig == null) {
            sender.sendMessage(render("<red>Moderation module is reloading, try again shortly."));
            return;
        }

        if (!moderationConfig.getBoolean("ban.enabled", true)) {
            sender.sendMessage(render("<red>Banning is currently disabled."));
            return;
        }

        if (args.length < 1) {
            sender.sendMessage(render("<yellow>Usage: <gray>/ban <player> [reason]"));
            return;
        }

        String targetName = args[0];
        String reason = defaultReason(args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : null);

        UUID staffUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
        String staffName = sender.getName();

        resolveTarget(sender, targetName, offlinePlayer -> {
            if (isGone(sender)) return;
            if (manager == null || moderationConfig == null) {
                sender.sendMessage(render("<red>Moderation module is reloading, try again shortly."));
                return;
            }
            if (offlinePlayer == null) {
                sender.sendMessage(render(
                        message("player-not-found", "<red>Could not resolve player: %player%").replace("%player%", esc(targetName))));
                return;
            }

            String name = displayName(offlinePlayer, targetName);

            // Never allow self-bans (would instantly lock the staffer out).
            if (staffUuid != null && offlinePlayer.getUniqueId().equals(staffUuid)) {
                sender.sendMessage(render(
                        message("self-ban", "<red>You cannot ban yourself.")));
                return;
            }

            Player onlineTarget = offlinePlayer.getPlayer();
            if (onlineTarget != null) {
                if (isExempt(offlinePlayer, sender) || isProtectedTarget(onlineTarget, sender)) {
                    sender.sendMessage(render(message("exempt", "<red>You cannot punish this player.")));
                    return;
                }
            } else if (!isAdmin(sender)) {
                // Offline players expose no permission API, so exempt/admin
                // status cannot be verified — non-admins must not ban them at
                // all (hard reject, not just a log line). Admins bypass.
                sender.sendMessage(render(message("exempt", "<red>You cannot punish this player.")));
                return;
            }

            manager.tryBanAsync(offlinePlayer.getUniqueId(), name, reason, staffUuid, staffName, -1L, (result, dbError) -> {
                if (isGone(sender)) return;
                if (dbError) {
                    sender.sendMessage(render(
                            message("db-error", "<red>Database error, please try again later.")));
                    return;
                }
                if (result.isEmpty()) {
                    sender.sendMessage(render(
                            message("already-banned", "<red>%player% is already banned.").replace("%player%", esc(name))));
                    return;
                }

                Player online = offlinePlayer.getPlayer();
                if (online != null) {
                    kickSafely(online, render(
                            message("ban-screen", "<red>You are banned.\n<gray>Reason: %reason%")
                                    .replace("%reason%", esc(reason))
                                    .replace("%staff%", esc(staffName))));
                } else {
                    // hasPlayedBefore() can hit disk — never call it on this
                    // region thread. Hop async, then report back.
                    Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                        boolean played;
                        try {
                            played = offlinePlayer.hasPlayedBefore();
                        } catch (RuntimeException e) {
                            played = true;
                        }
                        if (!played) {
                            Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                                if (isGone(sender)) return;
                                sender.sendMessage(render(
                                        message("never-played", "<yellow>Warning: %player% has never played on this server.").replace("%player%", esc(name))));
                            });
                        }
                    });
                }

                sender.sendMessage(render(
                        message("ban-success", "<green>%player% has been permanently banned. <gray>(%reason%)")
                                .replace("%player%", esc(name)).replace("%reason%", esc(reason))));

                broadcast(message("ban-broadcast", "")
                        .replace("%player%", esc(name)).replace("%staff%", esc(staffName)).replace("%reason%", esc(reason)));
            });
        });
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        if (args.length <= 1) {
            String input = args.length == 0 ? "" : args[0];
            return suggestOnlinePlayers(stack.getSender(), input, "plainbase.moderation.ban");
        }
        return List.of();
    }
}
