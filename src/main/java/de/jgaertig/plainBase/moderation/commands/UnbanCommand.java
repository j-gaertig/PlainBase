package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanManager;
import de.jgaertig.plainBase.moderation.BanRecord;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

        if (args.length != 1) {
            sender.sendMessage(render("<yellow>Usage: <gray>/unban <player>"));
            return;
        }

        String targetName = args[0];
        UUID staffUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
        String staffName = sender.getName();

        // Captured once: a /plainbase reload racing the async hops below can
        // null plugin.getBanManager() mid-chain — a stale local reference
        // keeps the callback working instead of NPE-ing.
        BanManager manager = plugin.getBanManager();

        resolveTarget(sender, targetName, offlinePlayer -> runOnSender(sender, () -> {
            if (isGone(sender)) return;
            if (manager == null) {
                sender.sendMessage(render("<red>Moderation module is reloading, try again shortly."));
                return;
            }
            if (offlinePlayer == null) {
                sender.sendMessage(render(
                        message("player-not-found", "<red>Could not resolve player: %player%").replace("%player%", esc(targetName))));
                return;
            }

            String name = displayName(offlinePlayer, targetName);
            final UUID targetUuid = offlinePlayer.getUniqueId();
            final String displayNameFinal = name;

            // R3 admin shield: a ban on a protected (exempt/admin) target, or
            // issued from console/by admin staff, may only be lifted by an
            // admin. The record is read LIVE (not from the possibly stale
            // cache) in an async hop, then evaluated back on the region thread
            // (Bukkit permission APIs are not thread-safe). Uses the same
            // "exempt" message as the ban side — no oracle about which of the
            // two conditions matched.
            if (!isAdmin(sender)) {
                // Scheduler-rejection guard (disable race): runNow on a
                // disabled plugin throws — answer fail-closed via the
                // sender scheduler instead of leaving the sender hanging
                // (same pattern as IpBanCommand#checkOnlineIpOwners).
                try {
                    Bukkit.getAsyncScheduler().runNow(plugin, asyncTask -> {
                    List<BanRecord> liveRows = List.of();
                    boolean liveOk = false;
                    try {
                        liveRows = manager.queryActiveBansNow(targetUuid);
                        liveOk = true;
                    } catch (Exception e) {
                        plugin.getLogger().warning("Could not live-check ban for unban shield '"
                                + targetName + "': " + e.getMessage());
                    }
                    final List<BanRecord> shieldRows = liveRows;
                    final boolean shieldRead = liveOk;
                    if (!shieldRead) {
                        // Fail closed, but report the database error
                        // distinctly from an exempt rejection so staff do
                        // not misread an outage as target protection.
                        runOnSender(sender, () -> {
                            if (isGone(sender)) return;
                            sender.sendMessage(render(message("db-error", "<red>Database error, please try again later.")));
                        });
                        return;
                    }
                    if (shieldRows.isEmpty()) {
                        // A successful live read with no active rows has
                        // nothing whose protection needs authorization. Pass
                        // an empty allow-list: BanManager will report
                        // "not banned" and cannot revoke a row inserted after
                        // this snapshot.
                        requestUnban(manager, sender, targetUuid, displayNameFinal, staffUuid, staffName, List.of());
                        return;
                    }
                    checkProtectedBans(shieldRows, offlinePlayer, protectedBan -> runOnSender(sender, () -> {
                        if (isGone(sender)) return;
                        // The shield may only authorize an unban when the live
                        // record was actually read and is verifiable. Cache
                        // state can be stale, and a missing record cannot be
                        // used to prove that the target is unprotected.
                        if (protectedBan) {
                            sender.sendMessage(render(message("exempt", "<red>You cannot punish this player.")));
                            return;
                        }
                        List<Integer> authorizedIds = shieldRows.stream()
                                .map(row -> row == null ? -1 : row.id()).toList();
                        if (authorizedIds.stream().anyMatch(id -> id < 0)) {
                            sender.sendMessage(render(message("exempt", "<red>You cannot punish this player.")));
                            return;
                        }
                        requestUnban(manager, sender, targetUuid, displayNameFinal, staffUuid, staffName, authorizedIds);
                    }));
                    });
                } catch (RuntimeException e) {
                    plugin.getLogger().fine("Could not schedule unban shield check: " + e.getMessage());
                    runOnSender(sender, () -> {
                        if (isGone(sender)) return;
                        sender.sendMessage(render(message("db-error", "<red>Database error, please try again later.")));
                    });
                }
                return;
            }

            requestUnban(manager, sender, targetUuid, displayNameFinal, staffUuid, staffName, null);
        }));
    }

    private void requestUnban(BanManager manager, CommandSender sender, UUID targetUuid,
                              String name, UUID staffUuid, String staffName, List<Integer> authorizedIds) {
        manager.unbanPlayerAsync(targetUuid, staffUuid, staffName, authorizedIds, (unbanned, dbError) -> runOnSender(sender, () -> {
                if (isGone(sender)) return;
                if (dbError) {
                    sender.sendMessage(render(
                            message("db-error", "<red>Database error, please try again later.")));
                    return;
                }
                if (!unbanned) {
                    sender.sendMessage(render(
                            message("not-banned", "<red>%player% is not currently banned. <gray>(Name change? Bans are UUID-based.)").replace("%player%", esc(name))));
                    return;
                }

                sender.sendMessage(render(
                        message("unban-success", "<green>%player% has been unbanned.").replace("%player%", esc(name))));

                broadcast(message("unban-broadcast", "").replace("%player%", esc(name)).replace("%staff%", esc(staffName)));
            }));
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        if (args.length > 1) return List.of();
        String input = args.length == 0 ? "" : args[0];
        Set<String> names = new LinkedHashSet<>(suggestOnlinePlayers(stack.getSender(), input, "plainbase.moderation.unban"));
        // Banned players are often offline: complete them from the cache too.
        BanManager manager = plugin.getBanManager();
        if (manager != null && hasSuggestPermission(stack.getSender(), "plainbase.moderation.unban")) {
            try {
                String prefix = input == null ? "" : input.toLowerCase(Locale.ROOT);
                CommandSender suggestSender = stack.getSender();
                for (BanRecord record : manager.getActiveBans()) {
                    if (record == null) continue;
                    String n = record.name();
                    if (n == null || !n.toLowerCase(Locale.ROOT).startsWith(prefix)) continue;
                    // Vanish-oracle guard: never suggest a banned player who is
                    // currently online-vanished and invisible to the sender.
                    try {
                        Player online = record.uuid() == null ? null : Bukkit.getPlayer(record.uuid());
                        if (online != null && !isSuggestVisible(suggestSender, online)) continue;
                    } catch (RuntimeException ignored) {
                    }
                    names.add(n);
                }
            } catch (RuntimeException e) {
                // A cache read must never break tab-completion.
            }
        }
        return List.copyOf(names);
    }
}
