package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.net.InetAddress;
import java.sql.SQLException;
import java.util.function.BiConsumer;
import org.bukkit.OfflinePlayer;

/**
 * /banip <ip-or-player> [reason] — bans a raw IP address, or resolves a
 * player name (online first, then their last-known IP from the database) to
 * an IP. IP bans are checked independently of UUID bans on every login.
 */
public class IpBanCommand extends ModerationCommandBase implements BasicCommand {

    public IpBanCommand(PlainBase plugin) {
        super(plugin);
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!checkPreconditions(sender, "plainbase.moderation.banip", "banip")) return;

        // Captured once (manager + config): a /plainbase reload racing the
        // async hops below can null either mid-chain — stale locals keep the
        // callback working instead of NPE-ing (same pattern as KickCommand).
        BanManager manager = plugin.getBanManager();
        FileConfiguration moderationConfig = plugin.getModerationConfig();
        if (manager == null || moderationConfig == null) {
            sender.sendMessage(render("<red>Moderation module is reloading, try again shortly."));
            return;
        }

        if (!moderationConfig.getBoolean("ip-ban.enabled", true)) {
            sender.sendMessage(render("<red>IP banning is currently disabled."));
            return;
        }

        if (args.length < 1) {
            sender.sendMessage(render("<yellow>Usage: <gray>/banip <ip|player> [reason]"));
            return;
        }

        String target = args[0];
        String reason = defaultReason(args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : null);

        UUID staffUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
        String staffName = sender.getName();
        String selfIp = (sender instanceof Player self) ? normalizeIp(addressIp(self)) : null;
        boolean adminSender = isAdmin(sender);

        resolveIp(sender, target, (ip, dbError) -> runOnSender(sender, () -> {
            if (isGone(sender)) return;
            if (manager == null || moderationConfig == null) {
                sender.sendMessage(render("<red>Moderation module is reloading, try again shortly."));
                return;
            }
            if (dbError) {
                sender.sendMessage(render(
                        message("db-error", "<red>Database error, please try again later.")));
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

            // Self-IP warning: captured on the sender's entity thread before
            // resolveIp can hop through async/global schedulers.
            if (selfIp != null && ip.equals(selfIp)) {
                sender.sendMessage(render(
                        message("self-ip-warning", "<yellow>Warning: this is your own IP address — you will lock yourself out.")));
            }

            // Exempt/admin checks inspect each owner's IP and permissions on
            // that player's Folia region. Non-admins still need at least one
            // online, verifiable owner and may not target a protected one.
            if (adminSender) {
                applyIpBan(manager, sender, ip, reason, staffUuid, staffName);
            } else {
                checkOnlineIpOwners(sender, ip, (foundOwner, protectedOwner) -> {
                    if (isGone(sender)) return;
                    if (protectedOwner || !foundOwner) {
                    sender.sendMessage(render(
                            message("exempt", "<red>You cannot punish this player.")));
                        return;
                    }
                    applyIpBan(manager, sender, ip, reason, staffUuid, staffName);
                });
            }
        }));
    }

    private void applyIpBan(BanManager manager, CommandSender sender, String ip, String reason,
                            UUID staffUuid, String staffName) {
            manager.tryBanIpAsync(ip, reason, staffUuid, staffName, -1L, (result, banDbError) -> runOnSender(sender, () -> {
                if (isGone(sender)) return;
                if (banDbError) {
                    sender.sendMessage(render(
                            message("db-error", "<red>Database error, please try again later.")));
                    return;
                }
                if (result.isEmpty()) {
                    sender.sendMessage(render(
                            message("ip-already-banned", "<red>%ip% is already banned.").replace("%ip%", esc(ip))));
                    return;
                }

                // Kick any currently-online player connecting from this IP.
                // Canonical comparison (see above): raw spellings included.
                Component kickMessage = render(
                        message("ipban-screen", "<red>Your IP address is banned.\n<gray>Reason: %reason%")
                                .replace("%reason%", esc(reason)).replace("%staff%", esc(staffName))
                                .replace("%remaining%", "permanent"));
                try {
                    Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                        for (Player online : List.copyOf(Bukkit.getOnlinePlayers())) {
                            scheduleIpKickCheck(online, ip, kickMessage);
                        }
                    });
                } catch (RuntimeException e) {
                    plugin.getLogger().fine("Could not snapshot online players for IP-ban kicks: " + e.getMessage());
                }

                sender.sendMessage(render(
                        message("banip-success", "<green>%ip% has been banned. <gray>(%reason%)")
                                .replace("%ip%", esc(ip)).replace("%reason%", esc(reason))));

                broadcast(message("banip-broadcast", "")
                        .replace("%ip%", esc(ip)).replace("%staff%", esc(staffName)).replace("%reason%", esc(reason)));
            }));
    }

    private void checkOnlineIpOwners(CommandSender sender, String ip, BiConsumer<Boolean, Boolean> callback) {
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> checkOnlineIpOwnersOnSnapshot(sender, ip, callback));
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Could not schedule /banip owner snapshot: " + e.getMessage());
            runOnSender(sender, () -> callback.accept(false, true));
        }
    }

    private void checkOnlineIpOwnersOnSnapshot(CommandSender sender, String ip, BiConsumer<Boolean, Boolean> callback) {
        List<Player> onlinePlayers = List.copyOf(Bukkit.getOnlinePlayers());
        if (onlinePlayers.isEmpty()) {
            runOnSender(sender, () -> callback.accept(false, false));
            return;
        }
        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(onlinePlayers.size());
        java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean protectedOwner = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable finish = () -> {
            if (remaining.decrementAndGet() == 0) {
                try {
                    Bukkit.getGlobalRegionScheduler().run(plugin,
                            task -> runOnSender(sender, () -> callback.accept(found.get(), protectedOwner.get())));
                } catch (RuntimeException e) {
                    plugin.getLogger().fine("Could not complete /banip owner check: " + e.getMessage());
                    // Disable race: the global scheduler is gone, but the
                    // answer must still reach the sender — route it via the
                    // sender scheduler instead of leaving them unanswered.
                    runOnSender(sender, () -> callback.accept(found.get(), protectedOwner.get()));
                }
            }
        };
        for (Player player : onlinePlayers) {
            try {
                player.getScheduler().run(plugin, task -> {
                    try {
                        String onlineIp = normalizeIp(addressIp(player));
                        if (onlineIp == null) {
                            // An address we cannot read could be the target IP.
                            protectedOwner.set(true);
                        } else if (ip.equals(onlineIp)) {
                            found.set(true);
                            if (player.hasPermission("plainbase.moderation.exempt")
                                    || player.hasPermission("plainbase.moderation.admin")
                                    || player.hasPermission("plainbase.admin")) protectedOwner.set(true);
                        }
                    } catch (RuntimeException e) {
                        // An online owner whose status cannot be checked is
                        // treated as protected for this non-admin operation.
                        protectedOwner.set(true);
                    } finally {
                        finish.run();
                    }
                }, () -> {
                    protectedOwner.set(true);
                    finish.run();
                });
            } catch (RuntimeException e) {
                protectedOwner.set(true);
                finish.run();
            }
        }
    }

    private void scheduleIpKickCheck(Player player, String ip, net.kyori.adventure.text.Component message) {
        try {
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline() && ip.equals(normalizeIp(addressIp(player)))) kickSafely(player, message);
            }, null);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Could not schedule IP-ban kick check: " + e.getMessage());
        }
    }

    /**
     * If the argument already looks like an IP, uses it directly. Otherwise
     * treats it as a player name: tries the online player's current address
     * first, then falls back to the database's last-known IP for that name
     * (async — the DB lookup can block, same pattern as offline name resolution).
     * <p>
     * The boolean handed to the callback is true when the DB lookup itself
     * failed: the caller must then report a database error, never a misleading
     * "ip-not-found" (a SQLException resolving to null would otherwise claim
     * the player simply never joined).
     */
    private void resolveIp(CommandSender sender, String arg, BiConsumer<String, Boolean> callback) {
        if (isIpLike(arg)) {
            // Normalize ("::ffff:1.2.3.4", leading zeros, ...) to canonical
            // form so stored, cached and checked values always compare equal.
            callback.accept(normalizeIp(arg), false);
            return;
        }

        // Exact name first: Bukkit#getPlayer does prefix matching and could
        // resolve (and ban the IP of) the wrong player on a typo.
        Player online = onlinePlayerExactFirst(arg, sender);
        if (online != null) {
            Player exact = online;
            Runnable storedFallback = () -> runOnSender(sender, () -> resolveStoredIp(sender, arg, callback));
            try {
                exact.getScheduler().run(plugin, task -> {
                    String onlineIp = normalizeIp(addressIp(exact));
                    if (onlineIp != null) callback.accept(onlineIp, false);
                    else storedFallback.run();
                }, storedFallback);
            } catch (RuntimeException e) {
                storedFallback.run();
            }
            return;
        }

        resolveStoredIp(sender, arg, callback);
    }

    private void resolveStoredIp(CommandSender sender, String arg, BiConsumer<String, Boolean> callback) {
        // Offline: resolve the exact profile first (phantom-guard via
        // resolveTarget), then look up the last IP by UUID (stable across
        // name changes). The name-based lookup is only the fallback when no
        // UUID is known (uncached/never-joined name resolving to null).
        resolveTarget(sender, arg, (OfflinePlayer offlinePlayer) -> {
            final UUID targetUuid = offlinePlayer == null ? null : offlinePlayer.getUniqueId();
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                // Reload race: the manager can be gone by the time this async
                // task runs — resolve to "not found" instead of NPE-ing.
                BanManager manager = plugin.getBanManager();
                String lastIp = null;
                boolean dbError = false;
                if (manager == null) {
                    lastIp = null;
                } else if (targetUuid != null) {
                    try {
                        lastIp = manager.findLastIpByUuidStrict(targetUuid);
                    } catch (SQLException | RuntimeException e) {
                        plugin.getLogger().warning("Could not look up last IP for " + arg + ": " + e.getMessage());
                        dbError = true;
                    }
                } else {
                    try {
                        lastIp = manager.findLastIpByNameStrict(arg);
                    } catch (SQLException | RuntimeException e) {
                        plugin.getLogger().warning("Could not look up last IP for " + arg + ": " + e.getMessage());
                        dbError = true;
                    }
                }
                // Stored IPs were recorded via getHostAddress() already, but
                // normalize defensively so legacy rows still match. A missing,
                // blank, literal-"unknown" (legacy rows from before the listener
                // skipped null addresses) or otherwise unparseable value resolves
                // to null so the caller takes the ip-not-found path — it must
                // NEVER be passed on as a bannable address.
                String resolved = (!dbError && isUsableStoredIp(lastIp)) ? normalizeIp(lastIp) : null;
                boolean finalDbError = dbError;
                Bukkit.getGlobalRegionScheduler().run(plugin, t -> callback.accept(resolved, finalDbError));
            });
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

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        if (args.length <= 1) {
            String input = args.length == 0 ? "" : args[0];
            return suggestOnlinePlayers(stack.getSender(), input, "plainbase.moderation.banip");
        }
        return List.of();
    }
}
