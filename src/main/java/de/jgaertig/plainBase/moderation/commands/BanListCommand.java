package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanRecord;
import de.jgaertig.plainBase.moderation.DurationParser;
import de.jgaertig.plainBase.moderation.IpBanRecord;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /banlist [page] — lists currently active name bans (10 per page) and, if
 * any exist, active IP bans below.
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
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.getMiniMessage().deserialize("<red>'" + esc(args[0]) + "' is not a valid page number — showing page 1."));
            }
        }

        List<BanRecord> active = plugin.getBanManager().getActiveBans();
        long now = System.currentTimeMillis();

        if (active.isEmpty()) {
            sender.sendMessage(plugin.getMiniMessage().deserialize(message("banlist-empty", "<gray>There are currently no active bans.")));
        } else {
            int from = (page - 1) * PAGE_SIZE;
            if (from < active.size()) {
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("banlist-header", "<gray>--- Active bans (%count%) ---").replace("%count%", String.valueOf(active.size()))));

                int to = Math.min(from + PAGE_SIZE, active.size());
                for (BanRecord record : active.subList(from, to)) {
                    String duration = record.isPermanent() ? "permanent" : DurationParser.format(record.remainingMillis(now)) + " left";
                    sender.sendMessage(plugin.getMiniMessage().deserialize(
                            message("banlist-entry", "<yellow>%player% <gray>- %reason% (%duration%)")
                                    .replace("%player%", esc(record.name()))
                                    .replace("%reason%", esc(record.reason()))
                                    .replace("%duration%", duration)));
                }
            } else {
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("banlist-empty-page", "<gray>There are no entries on this page (page %page%).").replace("%page%", String.valueOf(page))));
            }
        }

        List<IpBanRecord> activeIps = plugin.getBanManager().getActiveIpBans();
        if (!activeIps.isEmpty()) {
            int from = (page - 1) * PAGE_SIZE;
            if (from < activeIps.size()) {
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("banlist-ip-header", "<gray>--- Active IP bans (%count%) ---").replace("%count%", String.valueOf(activeIps.size()))));

                int to = Math.min(from + PAGE_SIZE, activeIps.size());
                for (IpBanRecord record : activeIps.subList(from, to)) {
                    String duration = record.isPermanent() ? "permanent" : DurationParser.format(record.remainingMillis(now)) + " left";
                    sender.sendMessage(plugin.getMiniMessage().deserialize(
                            message("banlist-ip-entry", "<yellow>%ip% <gray>- %reason% (%duration%)")
                                    .replace("%ip%", esc(record.ip()))
                                    .replace("%reason%", esc(record.reason()))
                                    .replace("%duration%", duration)));
                }
            } else {
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("banlist-ip-empty-page", "<gray>There are no IP bans on this page (page %page%).").replace("%page%", String.valueOf(page))));
            }
        }
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        return List.of();
    }
}
