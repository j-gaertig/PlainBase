package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanManager;
import de.jgaertig.plainBase.moderation.BanRecord;
import de.jgaertig.plainBase.moderation.DurationParser;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * /baninfo <player> — current ban status + total ban/kick counts + last ban
 * reason + whether their last-known IP is currently banned too. Everything
 * keyed by UUID, so this survives name changes.
 */
public class BanInfoCommand extends ModerationCommandBase implements BasicCommand {

    public BanInfoCommand(PlainBase plugin) {
        super(plugin);
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!checkPreconditions(sender, "plainbase.moderation.baninfo", "baninfo")) return;

        if (args.length < 1) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/baninfo <player>"));
            return;
        }

        String targetName = args[0];

        resolveTarget(targetName, offlinePlayer -> {
            // Async hop (uncached names resolve off-thread): the sender may
            // have logged off while we waited — skip instead of messaging
            // a stale/gone sender.
            if (isGone(sender)) return;

            if (offlinePlayer == null) {
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("player-not-found", "<red>Could not resolve player: %player%").replace("%player%", esc(targetName))));
                return;
            }

            String name = displayName(offlinePlayer, targetName);
            BanManager manager = plugin.getBanManager();
            int bans = manager.getBanCount(offlinePlayer.getUniqueId());
            int kicks = manager.getKickCount(offlinePlayer.getUniqueId());

            if (bans == 0 && kicks == 0) {
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("baninfo-header", "<gray>--- Ban info for %player% ---").replace("%player%", esc(name))));
                sender.sendMessage(plugin.getMiniMessage().deserialize(message("baninfo-no-history", "<gray>No ban or kick history.")));
                return;
            }

            sender.sendMessage(plugin.getMiniMessage().deserialize(
                    message("baninfo-header", "<gray>--- Ban info for %player% ---").replace("%player%", esc(name))));

            Optional<BanRecord> active = manager.getActiveBan(offlinePlayer.getUniqueId());
            if (active.isPresent()) {
                long remaining = active.get().remainingMillis(System.currentTimeMillis());
                String durationText = active.get().isPermanent() ? "permanent" : DurationParser.format(remaining) + " left";
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("baninfo-status-banned", "<gray>Status: <red>Banned (%duration_left%)").replace("%duration_left%", durationText)));
            } else {
                sender.sendMessage(plugin.getMiniMessage().deserialize(message("baninfo-status-clear", "<gray>Status: <green>Not banned")));
            }

            sender.sendMessage(plugin.getMiniMessage().deserialize(
                    message("baninfo-total-bans", "<gray>Total bans: <yellow>%bans%").replace("%bans%", String.valueOf(bans))));
            sender.sendMessage(plugin.getMiniMessage().deserialize(
                    message("baninfo-total-kicks", "<gray>Total kicks: <yellow>%kicks%").replace("%kicks%", String.valueOf(kicks))));

            manager.getLastBan(offlinePlayer.getUniqueId()).ifPresent(last ->
                    sender.sendMessage(plugin.getMiniMessage().deserialize(
                            message("baninfo-last-ban", "<gray>Last ban reason: <yellow>%reason% by %staff%")
                                    .replace("%reason%", esc(last.reason()))
                                    .replace("%staff%", esc(last.staffName())))));

            // findLastIpByName() does blocking JDBC I/O — never call it directly
            // on this main/region thread. Hop to the async scheduler, then back.
            org.bukkit.Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                String lastIp;
                try {
                    lastIp = manager.findLastIpByName(name);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Could not look up last IP for " + name + ": " + e.getMessage());
                    return;
                }
                if (lastIp == null) return;

                long now = System.currentTimeMillis();
                boolean ipBanned = manager.getActiveIpBans().stream().anyMatch(r -> r.ip().equals(lastIp) && r.isActive(now));
                if (!ipBanned) return;

                org.bukkit.Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                    if (isGone(sender)) return;
                    sender.sendMessage(render(
                            message("baninfo-ip-banned", "<gray>Note: their last known IP address is currently banned too.")));
                });
            });
        });
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        if (args.length <= 1) {
            String input = args.length == 0 ? "" : args[0];
            return suggestOnlinePlayers(stack.getSender(), input, "plainbase.moderation.baninfo");
        }
        return List.of();
    }
}
