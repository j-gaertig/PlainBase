package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * /unbanip <ip> — revokes an active IP ban.
 */
public class UnbanIpCommand extends ModerationCommandBase implements BasicCommand {

    public UnbanIpCommand(PlainBase plugin) {
        super(plugin);
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!checkPreconditions(sender, "plainbase.moderation.unbanip", "unbanip")) return;

        if (args.length < 1) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/unbanip <ip>"));
            return;
        }

        String ip = args[0];
        UUID staffUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
        String staffName = sender.getName();

        plugin.getBanManager().unbanIpAsync(ip, staffUuid, staffName, unbanned -> {
            if (!unbanned) {
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("ip-not-banned", "<red>%ip% is not currently banned.").replace("%ip%", ip)));
                return;
            }

            sender.sendMessage(plugin.getMiniMessage().deserialize(
                    message("unbanip-success", "<green>%ip% has been unbanned.").replace("%ip%", ip)));

            broadcast(message("unbanip-broadcast", "").replace("%ip%", ip).replace("%staff%", staffName));
        });
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        return List.of();
    }
}
