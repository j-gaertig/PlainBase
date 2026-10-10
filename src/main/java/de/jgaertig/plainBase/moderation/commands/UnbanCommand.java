package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * /unban <player> — revokes the currently active ban, if any.
 */
public class UnbanCommand extends ModerationCommandBase implements BasicCommand {

    public UnbanCommand(PlainBase plugin) {
        super(plugin);
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!checkPreconditions(sender, "plainbase.moderation.unban", "unban")) return;

        if (args.length < 1) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/unban <player>"));
            return;
        }

        String targetName = args[0];
        UUID staffUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
        String staffName = sender.getName();

        resolveTarget(targetName, offlinePlayer -> {
            if (offlinePlayer == null) {
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("player-not-found", "<red>Could not resolve player: %player%").replace("%player%", esc(targetName))));
                return;
            }

            String name = displayName(offlinePlayer, targetName);
            BanManager manager = plugin.getBanManager();

            manager.unbanPlayerAsync(offlinePlayer.getUniqueId(), staffUuid, staffName, unbanned -> {
                if (!unbanned) {
                    sender.sendMessage(plugin.getMiniMessage().deserialize(
                            message("not-banned", "<red>%player% is not currently banned. <gray>(Name change? Bans are UUID-based.)").replace("%player%", esc(name))));
                    return;
                }

                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("unban-success", "<green>%player% has been unbanned.").replace("%player%", esc(name))));

                broadcast(message("unban-broadcast", "").replace("%player%", esc(name)).replace("%staff%", esc(staffName)));
            });
        });
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        if (args.length <= 1) {
            String input = args.length == 0 ? "" : args[0];
            return suggestOnlinePlayers(stack.getSender(), input, "plainbase.moderation.unban");
        }
        return List.of();
    }
}
