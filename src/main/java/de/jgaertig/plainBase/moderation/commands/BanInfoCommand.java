package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanManager;
import de.jgaertig.plainBase.moderation.BanRecord;
import de.jgaertig.plainBase.moderation.DurationParser;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
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

        // Captured once: a /plainbase reload racing the async hops below can
        // null plugin.getBanManager() mid-chain — a stale local reference
        // keeps the callback working instead of NPE-ing.
        BanManager manager = plugin.getBanManager();

        resolveTarget(sender, targetName, offlinePlayer -> {
            // Async hop (uncached names resolve off-thread): the sender may
            // have logged off while we waited — skip instead of messaging
            // a stale/gone sender.
            if (isGone(sender)) return;
            if (manager == null) {
                sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Moderation module is reloading, try again shortly."));
                return;
            }

            if (offlinePlayer == null) {
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("player-not-found", "<red>Could not resolve player: %player%").replace("%player%", esc(targetName))));
                return;
            }

            String name = displayName(offlinePlayer, targetName);
            // Counts come from the windowed cache (last 90 days, see
            // ModerationDatabase) — the labels below say so explicitly.
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
                // remaining <= 0 means the ban expired between the active check
                // and this render — show plain "expired", never "expired left".
                String durationText = active.get().isPermanent() ? "permanent"
                        : (remaining <= 0 ? "expired" : DurationParser.format(remaining) + " left");
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("baninfo-status-banned", "<gray>Status: <red>Banned (%duration_left%)").replace("%duration_left%", durationText)));
            } else {
                sender.sendMessage(plugin.getMiniMessage().deserialize(message("baninfo-status-clear", "<gray>Status: <green>Not banned")));
            }

            sender.sendMessage(plugin.getMiniMessage().deserialize(
                    message("baninfo-total-bans", "<gray>Total bans (last 90 days): <yellow>%bans%").replace("%bans%", String.valueOf(bans))));
            sender.sendMessage(plugin.getMiniMessage().deserialize(
                    message("baninfo-total-kicks", "<gray>Total kicks (last 90 days): <yellow>%kicks%").replace("%kicks%", String.valueOf(kicks))));

            manager.getLastBan(offlinePlayer.getUniqueId()).ifPresent(last ->
                    sender.sendMessage(plugin.getMiniMessage().deserialize(
                            message("baninfo-last-ban", "<gray>Last ban reason: <yellow>%reason% by %staff%")
                                    .replace("%reason%", esc(last.reason()))
                                    .replace("%staff%", esc(last.staffName())))));

            // findLastIpByNameStrict() and queryActiveIpBanNow() do blocking
            // JDBC I/O — never call them on the main/region thread. Both run
            // on the async scheduler here; only the final sendMessage hops
            // back to the region thread. Uses the strict variant so a DB
            // failure reports a database error (like IpBanCommand) instead of
            // silently looking like "no IP, nothing to show".
            org.bukkit.Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                String lastIp = null;
                boolean dbError = false;
                try {
                    lastIp = manager.findLastIpByNameStrict(name);
                } catch (SQLException | RuntimeException e) {
                    plugin.getLogger().warning("Could not look up last IP for " + name + ": " + e.getMessage());
                    dbError = true;
                }
                if (dbError) {
                    org.bukkit.Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                        if (isGone(sender)) return;
                        sender.sendMessage(render(
                                message("db-error", "<red>Database error, please try again later.")));
                    });
                    return;
                }
                if (lastIp == null) return;

                // Compare canonical forms (stored rows may use legacy
                // spellings like ::ffff:1.2.3.4); null-guarded on both sides.
                String normLast = normalizeIp(lastIp);
                if (normLast == null) return;
                // Authoritative live check instead of the (possibly stale)
                // local cache: an IP ban issued elsewhere must show here.
                // Still on the async thread — never blocking on the region.
                boolean ipBanned;
                try {
                    ipBanned = manager.queryActiveIpBanNow(normLast) != null;
                } catch (SQLException | RuntimeException e) {
                    plugin.getLogger().warning("Could not check IP ban for " + name + ": " + e.getMessage());
                    org.bukkit.Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                        if (isGone(sender)) return;
                        sender.sendMessage(render(
                                message("db-error", "<red>Database error, please try again later.")));
                    });
                    return;
                }
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
