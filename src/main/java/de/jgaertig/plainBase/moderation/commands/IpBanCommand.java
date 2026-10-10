package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.net.InetAddress;

/**
 * /banip <ip-or-player> [reason] — bans a raw IP address, or resolves a
 * player name (online first, then their last-known IP from the database) to
 * an IP. IP bans are checked independently of UUID bans on every login.
 */
public class IpBanCommand extends ModerationCommandBase implements BasicCommand {

    // Deliberately permissive (IPv4 + IPv6) — good enough to distinguish
    // "this looks like a raw IP" from "this looks like a player name".
    private static final Pattern IP_LIKE = Pattern.compile("^[0-9a-fA-F.:]+$");

    public IpBanCommand(PlainBase plugin) {
        super(plugin);
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!checkPreconditions(sender, "plainbase.moderation.banip", "banip")) return;

        if (!plugin.getModerationConfig().getBoolean("ip-ban.enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>IP banning is currently disabled."));
            return;
        }

        if (args.length < 1) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/banip <ip|player> [reason]"));
            return;
        }

        String target = args[0];
        String reason = args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : message("default-reason", "No reason specified.");

        UUID staffUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
        String staffName = sender.getName();

        // Captured once: a /plainbase reload racing the async hops below can
        // null plugin.getBanManager() mid-chain — a stale local reference
        // keeps the callback working instead of NPE-ing.
        BanManager manager = plugin.getBanManager();

        resolveIp(target, ip -> {
            if (isGone(sender)) return;
            if (manager == null) {
                sender.sendMessage(render("<red>Moderation module is reloading, try again shortly."));
                return;
            }
            if (ip == null) {
                if (isIpLike(target)) {
                    sender.sendMessage(render(
                            message("invalid-ip", "<red>Invalid IP address: %ip%").replace("%ip%", esc(target))));
                } else {
                    sender.sendMessage(render(
                            message("ip-not-found", "<red>Could not resolve an IP for %player%.").replace("%player%", esc(target))));
                }
                return;
            }

            // Self-IP warning: banning your own address locks YOU out on next login.
            String selfIp = (sender instanceof Player self) ? addressIp(self) : null;
            if (selfIp != null && ip.equals(selfIp)) {
                sender.sendMessage(render(
                        message("self-ip-warning", "<yellow>Warning: this is your own IP address — you will lock yourself out.")));
            }

            // Exempt/admin check for online players currently on this IP.
            // Offline owners of the IP cannot be permission-checked (documented
            // limitation) — logged for audit when the sender is no admin.
            if (!isAdmin(sender)) {
                boolean protectedOwner = false;
                for (Player online : Bukkit.getOnlinePlayers()) {
                    String onlineIp = addressIp(online);
                    if (onlineIp != null
                            && ip.equals(onlineIp)
                            && isProtectedTarget(online, sender)) {
                        protectedOwner = true;
                        break;
                    }
                }
                if (protectedOwner) {
                    sender.sendMessage(render(
                            message("exempt", "<red>You cannot punish this player.")));
                    return;
                }
                plugin.getLogger().warning("IP ban on " + ip + " by " + sender.getName()
                        + " without full exempt/admin check (offline owners cannot be verified).");
            }

            manager.tryBanIpAsync(ip, reason, staffUuid, staffName, -1L, result -> {
                if (isGone(sender)) return;
                if (result.isEmpty()) {
                    sender.sendMessage(render(
                            message("ip-already-banned", "<red>%ip% is already banned.").replace("%ip%", esc(ip))));
                    return;
                }

                // Kick any currently-online player connecting from this IP.
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (ip.equals(addressIp(online))) {
                        kickSafely(online, render(
                                message("ipban-screen", "<red>Your IP address is banned.\n<gray>Reason: %reason%")
                                        .replace("%reason%", esc(reason)).replace("%staff%", esc(staffName))
                                        .replace("%remaining%", "permanent")));
                    }
                }

                sender.sendMessage(render(
                        message("banip-success", "<green>%ip% has been banned. <gray>(%reason%)")
                                .replace("%ip%", esc(ip)).replace("%reason%", esc(reason))));

                broadcast(message("banip-broadcast", "")
                        .replace("%ip%", esc(ip)).replace("%staff%", esc(staffName)).replace("%reason%", esc(reason)));
            });
        });
    }

    /**
     * If the argument already looks like an IP, uses it directly. Otherwise
     * treats it as a player name: tries the online player's current address
     * first, then falls back to the database's last-known IP for that name
     * (async — the DB lookup can block, same pattern as offline name resolution).
     */
    private void resolveIp(String arg, java.util.function.Consumer<String> callback) {
        if (isIpLike(arg)) {
            // Normalize ("::ffff:1.2.3.4", leading zeros, ...) to canonical
            // form so stored, cached and checked values always compare equal.
            callback.accept(normalizeIp(arg));
            return;
        }

        Player online = Bukkit.getPlayer(arg);
        if (online != null) {
            String onlineIp = addressIp(online);
            if (onlineIp != null) {
                callback.accept(onlineIp);
                return;
            }
            // Online but the address is currently unavailable (e.g. unresolved)
            // — fall through to the DB lookup instead of NPE-ing or resolving
            // garbage.
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            // Reload race: the manager can be gone by the time this async
            // task runs — resolve to "not found" instead of NPE-ing.
            BanManager manager = plugin.getBanManager();
            String lastIp = manager == null ? null : manager.findLastIpByName(arg);
            // Stored IPs were recorded via getHostAddress() already, but
            // normalize defensively so legacy rows still match. A missing,
            // blank, literal-"unknown" (legacy rows from before the listener
            // skipped null addresses) or otherwise unparseable value resolves
            // to null so the caller takes the ip-not-found path — it must
            // NEVER be passed on as a bannable address.
            String resolved = isUsableStoredIp(lastIp) ? normalizeIp(lastIp) : null;
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> callback.accept(resolved));
        });
    }

    /**
     * Null-safe extraction of a player's current IP. Both levels can be null:
     * Player#getAddress() (no address yet) and InetSocketAddress#getAddress()
     * (unresolved address) — dereferencing either blindly NPEs.
     */
    private static String addressIp(Player player) {
        if (player.getAddress() == null) return null;
        try {
            InetAddress inner = player.getAddress().getAddress();
            return inner == null ? null : inner.getHostAddress();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isUsableStoredIp(String stored) {
        return stored != null && !stored.isBlank() && !"unknown".equalsIgnoreCase(stored.trim());
    }

    private static boolean isIpLike(String arg) {
        return IP_LIKE.matcher(arg).matches() && (arg.contains(".") || arg.contains(":"));
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        if (args.length <= 1) {
            String input = args.length == 0 ? "" : args[0];
            return suggestOnlinePlayers(stack.getSender(), input, "plainbase.moderation.banip");
        }
        return List.of();
    }
}
