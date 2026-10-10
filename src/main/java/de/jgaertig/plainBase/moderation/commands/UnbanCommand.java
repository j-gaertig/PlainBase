package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanManager;
import de.jgaertig.plainBase.moderation.BanRecord;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
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

        if (args.length < 1) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>Usage: <gray>/unban <player>"));
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
                sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Moderation module is reloading, try again shortly."));
                return;
            }
            if (offlinePlayer == null) {
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("player-not-found", "<red>Could not resolve player: %player%").replace("%player%", esc(targetName))));
                return;
            }

            String name = displayName(offlinePlayer, targetName);

            // R3 admin shield: a ban on a protected (exempt/admin) target, or
            // issued from console/by admin staff, may only be lifted by an
            // admin (checked against the stored BanRecord via BanManager).
            // Uses the same "exempt" message as the ban side — no oracle about
            // which of the two conditions matched.
            if (!isAdmin(sender)) {
                try {
                    if (isProtectedBan(manager.getActiveBan(offlinePlayer.getUniqueId()).orElse(null),
                            offlinePlayer, sender)) {
                        sender.sendMessage(plugin.getMiniMessage().deserialize(
                                message("exempt", "<red>You cannot punish this player.")));
                        return;
                    }
                } catch (RuntimeException e) {
                    plugin.getLogger().fine("Failed unban shield check for '" + targetName + "': " + e.getMessage());
                }
            }

            manager.unbanPlayerAsync(offlinePlayer.getUniqueId(), staffUuid, staffName, (unbanned, dbError) -> {
                if (isGone(sender)) return;
                if (dbError) {
                    sender.sendMessage(plugin.getMiniMessage().deserialize(
                            message("db-error", "<red>Database error, please try again later.")));
                    return;
                }
                if (!unbanned) {
                    sender.sendMessage(plugin.getMiniMessage().deserialize(
                            message("not-banned", "<red>%player% is not currently banned. <gray>(Name change? Bans are UUID-based.)").replace("%player%", esc(name))));
                    return;
                }

                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        message("unban-success", "<green>%player% has been unbanned.").replace("%player%", esc(name))));

                broadcast(message("unban-broadcast", "").replace("%player%", esc(name)).replace("%staff%", esc(staffName)));
            });
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
                for (BanRecord record : manager.getActiveBans()) {
                    String n = record == null ? null : record.name();
                    if (n != null && n.toLowerCase(Locale.ROOT).startsWith(prefix)) names.add(n);
                }
            } catch (RuntimeException e) {
                // A cache read must never break tab-completion.
            }
        }
        return List.copyOf(names);
    }
}
