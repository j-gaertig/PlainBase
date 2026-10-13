package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanManager;
import de.jgaertig.plainBase.moderation.IpBanRecord;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
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

        if (args.length != 1) {
            sender.sendMessage(render("<yellow>Usage: <gray>/unbanip <ip>"));
            return;
        }

        String rawIp = args[0];
        // Gate first (same as IpBanCommand): hostnames/player names are never
        // resolved here — only raw IP literals. normalizeIp itself is also
        // DNS-free, so this is defense-in-depth.
        if (!isIpLike(rawIp)) {
            sender.sendMessage(render(
                    message("invalid-ip", "<red>Invalid IP address: %ip%").replace("%ip%", esc(rawIp))));
            return;
        }
        // Normalize to canonical form so "1.2.3.4", "::ffff:1.2.3.4" etc.
        // match the stored ban row regardless of input spelling.
        String ip = normalizeIp(rawIp);
        if (ip == null) {
            sender.sendMessage(render(
                    message("invalid-ip", "<red>Invalid IP address: %ip%").replace("%ip%", esc(rawIp))));
            return;
        }
        UUID staffUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
        String staffName = sender.getName();
        String finalIp = ip;

        // Captured once: a /plainbase reload racing the async hop below can
        // null plugin.getBanManager() mid-chain — a stale local reference
        // keeps the callback working instead of NPE-ing.
        BanManager manager = plugin.getBanManager();
        if (manager == null) {
            sender.sendMessage(render("<red>Moderation module is reloading, try again shortly."));
            return;
        }

        // R3 admin shield: an IP ban issued from console/by admin staff, or
        // covering a currently-online protected (exempt/admin) player, may only
        // be lifted by an admin. The record is read LIVE (not from the possibly
        // stale cache) in an async hop, then evaluated back on the region thread
        // (Bukkit online-player/permission APIs are not thread-safe). Same
        // "exempt" message as the ban side — no oracle.
        if (!isAdmin(sender)) {
            // Scheduler-rejection guard (disable race): runNow on a
            // disabled plugin throws — answer fail-closed via the sender
            // scheduler instead of leaving the sender hanging (same
            // pattern as IpBanCommand#checkOnlineIpOwners).
            try {
                Bukkit.getAsyncScheduler().runNow(plugin, asyncTask -> {
                List<IpBanRecord> liveRows = List.of();
                boolean liveOk = false;
                try {
                    liveRows = manager.queryActiveIpBansNow(finalIp);
                    liveOk = true;
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not live-check IP ban for unbanip shield '"
                            + finalIp + "': " + e.getMessage());
                }
                final List<IpBanRecord> shieldRows = liveRows;
                final boolean shieldRead = liveOk;
                if (!shieldRead) {
                    // Fail closed, but report the database error distinctly
                    // from an exempt rejection so staff do not misread an
                    // outage as target protection.
                    runOnSender(sender, () -> {
                        if (isGone(sender)) return;
                        sender.sendMessage(render(message("db-error", "<red>Database error, please try again later.")));
                    });
                    return;
                }
                if (shieldRows.isEmpty()) {
                    // Empty allow-list preserves the "not banned" result
                    // without revoking a ban inserted after the live read.
                    requestUnbanIp(manager, sender, finalIp, staffUuid, staffName, List.of());
                    return;
                }
                checkProtectedIpBans(shieldRows, finalIp, protectedBan -> runOnSender(sender, () -> {
                    if (isGone(sender)) return;
                    // Only a live, present record can authorize the shield
                    // decision. Stale cache data or a missing row cannot prove
                    // that the requested revoke is safe.
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
                    requestUnbanIp(manager, sender, finalIp, staffUuid, staffName, authorizedIds);
                }));
                });
            } catch (RuntimeException e) {
                plugin.getLogger().fine("Could not schedule unbanip shield check: " + e.getMessage());
                runOnSender(sender, () -> {
                    if (isGone(sender)) return;
                    sender.sendMessage(render(message("db-error", "<red>Database error, please try again later.")));
                });
            }
            return;
        }

        requestUnbanIp(manager, sender, finalIp, staffUuid, staffName, null);
    }

    private void requestUnbanIp(BanManager manager, CommandSender sender, String finalIp,
                                UUID staffUuid, String staffName, List<Integer> authorizedIds) {
        manager.unbanIpAsync(finalIp, staffUuid, staffName, authorizedIds, (unbanned, dbError) -> runOnSender(sender, () -> {
            if (isGone(sender)) return;
            if (dbError) {
                sender.sendMessage(render(
                        message("db-error", "<red>Database error, please try again later.")));
                return;
            }
            if (!unbanned) {
                sender.sendMessage(render(
                        message("ip-not-banned", "<red>%ip% is not currently banned.").replace("%ip%", esc(finalIp))));
                return;
            }

            sender.sendMessage(render(
                    message("unbanip-success", "<green>%ip% has been unbanned.").replace("%ip%", esc(finalIp))));

            broadcast(message("unbanip-broadcast", "").replace("%ip%", esc(finalIp)).replace("%staff%", esc(staffName)));
        }));
    }

    /**
     * Deliberately no tab-completion for the IP argument: suggesting stored
     * ban IPs/addresses would leak them to any sender with tab-complete
     * access (including non-admins who may run the command path up to the
     * shield check). The caller types the full IP instead.
     */
    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        return List.of();
    }
}
