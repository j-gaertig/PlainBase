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
        // be lifted by an admin (checked against the stored IpBanRecord via
        // BanManager). Same "exempt" message as the ban side — no oracle.
        if (!isAdmin(sender)) {
            try {
                java.util.Optional<de.jgaertig.plainBase.moderation.IpBanRecord> active = manager.getActiveIpBans().stream()
                        .filter(r -> r != null && finalIp.equals(r.ip()))
                        .findFirst();
                if (active.isPresent() && isProtectedIpBan(active.get(), finalIp)) {
                    sender.sendMessage(render(
                            message("exempt", "<red>You cannot punish this player.")));
                    return;
                }
            } catch (RuntimeException e) {
                plugin.getLogger().fine("Failed unbanip shield check for '" + finalIp + "': " + e.getMessage());
            }
        }

        manager.unbanIpAsync(finalIp, staffUuid, staffName, (unbanned, dbError) -> {
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
        });
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
