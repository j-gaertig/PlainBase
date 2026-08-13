package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanRecord;
import de.jgaertig.plainBase.moderation.DurationParser;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /banlist [page] — lists currently active bans, 10 per page.
 */
public class BanListCommand extends ModerationCommandBase implements BasicCommand {

    private static final int PAGE_SIZE = 10;

    public BanListCommand(PlainBase plugin) {
        super(plugin);
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!checkPreconditions(sender, "plainbase.moderation.banlist", "banlist")) return;

        int page = 1;
        if (args.length >= 1) {
            try {
                page = Math.max(1, Integer.parseInt(args[0]));
            } catch (NumberFormatException ignored) {
                // keep page 1 on garbage input
            }
        }

        List<BanRecord> active = plugin.getBanManager().getActiveBans();
        long now = System.currentTimeMillis();

        if (active.isEmpty()) {
            sender.sendMessage(plugin.getMiniMessage().deserialize(message("banlist-empty", "<gray>There are currently no active bans.")));
            return;
        }

        sender.sendMessage(plugin.getMiniMessage().deserialize(
                message("banlist-header", "<gray>--- Active bans (%count%) ---").replace("%count%", String.valueOf(active.size()))));

        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, active.size());
        if (from >= active.size()) return;

        for (BanRecord record : active.subList(from, to)) {
            String duration = record.isPermanent() ? "permanent" : DurationParser.format(record.remainingMillis(now)) + " left";
            sender.sendMessage(plugin.getMiniMessage().deserialize(
                    message("banlist-entry", "<yellow>%player% <gray>- %reason% (%duration%)")
                            .replace("%player%", record.name())
                            .replace("%reason%", record.reason())
                            .replace("%duration%", duration)));
        }
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        return List.of();
    }
}
