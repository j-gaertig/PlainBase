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

        resolveTarget(sender, targetName, offlinePlayer -> {
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
                Bukkit.getAsyncScheduler().runNow(plugin, asyncTask -> {
                    BanRecord live = null;
                    boolean liveOk = false;
                    try {
                        live = manager.queryActiveBanNow(targetUuid);
                        liveOk = true;
                    } catch (Exception e) {
                        plugin.getLogger().warning("Could not live-check ban for unban shield '"
                                + targetName + "', falling back to cache: " + e.getMessage());
                    }
                    if (!liveOk) {
                        try {
                            live = manager.getActiveBan(targetUuid).orElse(null);
                        } catch (RuntimeException cacheEx) {
                            plugin.getLogger().fine("Failed unban shield cache fallback for '"
                                    + targetName + "': " + cacheEx.getMessage());
                            live = null;
                        }
                    }
                    final BanRecord shieldRecord = live;
                    Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                        if (isGone(sender)) return;
                        // Only a present record can be protected: no record
                        // means "not banned" (or DB-down with empty cache, in
                        // which case unbanPlayerAsync below reports db-error
                        // fail-closed with a hint instead of a misleading
                        // "exempt").
                        if (shieldRecord != null) {
                            try {
                                if (isProtectedBan(shieldRecord, offlinePlayer, sender)) {
                                    sender.sendMessage(render(
                                            message("exempt", "<red>You cannot punish this player.")));
                                    return;
                                }
                            } catch (RuntimeException e) {
                                plugin.getLogger().fine("Failed unban shield check for '" + targetName + "': " + e.getMessage());
                                sender.sendMessage(render(
                                        message("exempt", "<red>You cannot punish this player.")));
                                return;
                            }
                        }
                        requestUnban(manager, sender, targetUuid, displayNameFinal, staffUuid, staffName);
                    });
                });
                return;
            }

            requestUnban(manager, sender, targetUuid, displayNameFinal, staffUuid, staffName);
        });
    }

    private void requestUnban(BanManager manager, CommandSender sender, UUID targetUuid,
                              String name, UUID staffUuid, String staffName) {
        manager.unbanPlayerAsync(targetUuid, staffUuid, staffName, (unbanned, dbError) -> {
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
            });
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
