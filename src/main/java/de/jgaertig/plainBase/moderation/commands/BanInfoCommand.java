package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanManager;
import de.jgaertig.plainBase.moderation.BanRecord;
import de.jgaertig.plainBase.moderation.DurationParser;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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
            if (offlinePlayer == null) {
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("player-not-found", "<red>Could not resolve player: %player%").replace("%player%", targetName)));
                return;
            }

            String name = displayName(offlinePlayer, targetName);
            BanManager manager = plugin.getBanManager();
            int bans = manager.getBanCount(offlinePlayer.getUniqueId());
            int kicks = manager.getKickCount(offlinePlayer.getUniqueId());

            if (bans == 0 && kicks == 0) {
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("baninfo-header", "<gray>--- Ban info for %player% ---").replace("%player%", name)));
                sender.sendMessage(plugin.getMiniMessage().deserialize(message("baninfo-no-history", "<gray>No ban or kick history.")));
                return;
            }

            sender.sendMessage(plugin.getMiniMessage().deserialize(
                    message("baninfo-header", "<gray>--- Ban info for %player% ---").replace("%player%", name)));

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
                                    .replace("%reason%", last.reason())
                                    .replace("%staff%", last.staffName()))));

            // findLastIpByName() does blocking JDBC I/O — never call it directly
            // on this main/region thread. Hop to the async scheduler, then back.
            org.bukkit.Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                String lastIp = manager.findLastIpByName(name);
                if (lastIp == null) return;

                long now = System.currentTimeMillis();
                boolean ipBanned = manager.getActiveIpBans().stream().anyMatch(r -> r.ip().equals(lastIp) && r.isActive(now));
                if (!ipBanned) return;

                org.bukkit.Bukkit.getGlobalRegionScheduler().run(plugin, t -> sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("baninfo-ip-banned", "<gray>Note: their last known IP address is currently banned too."))));
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
