package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Shared helpers for the moderation commands (ban/tempban/unban/kick/banip/unbanip/
 * banlist/baninfo): module/permission/command-enabled checks (repo check-order
 * convention), async offline-player resolution, Folia-safe entity kicks, and
 * broadcast handling. Not a command itself.
 */
abstract class ModerationCommandBase {

    protected final PlainBase plugin;

    protected ModerationCommandBase(PlainBase plugin) {
        this.plugin = plugin;
    }

    /**
     * @return true if the command may proceed, false if it already sent a rejection message.
     */
    protected boolean checkPreconditions(CommandSender sender, String permissionNode, String commandKey) {
        if (!plugin.getConfig().getBoolean("modules.moderation", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module is currently disabled."));
            return false;
        }

        if (!sender.hasPermission("plainbase.admin") && !sender.hasPermission("plainbase.moderation.admin") && !sender.hasPermission(permissionNode)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return false;
        }

        // Both null briefly during /plainbase reload (stopModules() clears the
        // manager, setupModeration() re-creates it right after) — reject
        // cleanly instead of NPE-ing on a null BanManager/config.
        if (plugin.getModerationConfig() == null || plugin.getBanManager() == null) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Moderation module is reloading, try again shortly."));
            return false;
        }

        if (!plugin.getModerationConfig().getBoolean("commands." + commandKey + ".enabled", true)) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This command has been disabled."));
            return false;
        }

        return true;
    }

    protected String message(String key, String fallback) {
        return plugin.getModerationConfig().getString("messages." + key, fallback);
    }

    /**
     * Escapes a user-controlled value (player name, reason, staff name, IP)
     * so it can be safely substituted into a MiniMessage template via
     * String#replace BEFORE deserialization — without this, a reason like
     * {@code <click:run_command:...>} would inject clickable/hoverable tags
     * into every ban message shown to staff and players.
     */
    protected String esc(String s) {
        return plugin.getMiniMessage().escapeTags(Objects.toString(s, ""));
    }

    protected boolean isAdmin(CommandSender sender) {
        return sender.hasPermission("plainbase.admin") || sender.hasPermission("plainbase.moderation.admin");
    }

    /**
     * True when the online target is protected from punishment by a non-admin
     * sender: covers exempt players AND admins (an admin target must never be
     * bannable by a plain moderator). Admin senders bypass this entirely.
     */
    protected boolean isProtectedTarget(Player target, CommandSender sender) {
        if (isAdmin(sender)) return false;
        return target.hasPermission("plainbase.moderation.exempt")
                || target.hasPermission("plainbase.moderation.admin")
                || target.hasPermission("plainbase.admin");
    }

    /**
     * Offline players expose no permission API, so exempt/admin status cannot
     * be verified for them — documented limitation. Log for audit purposes so
     * admins can review offline punishments afterwards.
     */
    protected void warnOfflineExemptUnchecked(String targetName) {
        plugin.getLogger().warning("Punishing offline player '" + targetName
                + "' without exempt/admin check (cannot be verified while offline).");
    }

    /**
     * Normalizes an IP string (v4/v6, with/without brackets or leading zeros)
     * to its canonical host-address form so stored, cached and checked values
     * always compare equal. Returns null when the input is not a valid IP.
     */
    protected static String normalizeIp(String ip) {
        try {
            return InetAddress.getByName(ip).getHostAddress();
        } catch (UnknownHostException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Resolves a target by name: online players resolve instantly, Paper's cached
     * offline-player lookup resolves instantly too. Only an uncached, never-joined
     * name falls back to Bukkit#getOfflinePlayer(String), which can block on a
     * Mojang lookup — so that call always runs on the async scheduler, and the
     * callback is always dispatched back onto the main/region thread afterwards
     * (same pattern as PlainBaseCommand's Modrinth update check).
     */
    protected void resolveTarget(String name, Consumer<OfflinePlayer> callback) {
        Player online = Bukkit.getPlayer(name);
        if (online != null) {
            callback.accept(online);
            return;
        }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            callback.accept(cached);
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            OfflinePlayer resolved = Bukkit.getOfflinePlayer(name);
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> callback.accept(resolved));
        });
    }

    protected String displayName(OfflinePlayer player, String fallback) {
        String n = player.getName();
        return n != null ? n : fallback;
    }

    protected boolean isExempt(OfflinePlayer target, CommandSender sender) {
        if (sender.hasPermission("plainbase.admin") || sender.hasPermission("plainbase.moderation.admin")) return false;
        Player online = target.getPlayer();
        // Offline exempt-players can't be permission-checked reliably without the
        // player object (no permissions plugin lookup for pure OfflinePlayer) —
        // documented limitation, matches the rest of the module's offline-ban scope.
        return online != null && online.hasPermission("plainbase.moderation.exempt");
    }

    /**
     * Kicks an online player Folia-safely: Player#kick() mutates the target's
     * connection/entity state, which on Folia must happen on THAT player's own
     * region thread — not necessarily the thread the command executed on
     * (which is the SENDER's region). Matches VanishManager's
     * target.getScheduler().run(plugin, t -> ..., null) pattern.
     */
    protected void kickSafely(Player target, Component message) {
        target.getScheduler().run(plugin, t -> {
            if (target.isOnline()) target.kick(message);
        }, null);
    }

    protected void broadcast(String message) {
        if (message == null || message.isEmpty()) return;
        if (!plugin.getModerationConfig().getBoolean("broadcast.enabled", true)) return;

        boolean staffOnly = plugin.getModerationConfig().getBoolean("broadcast.staff-only", false);
        Component component = plugin.getMiniMessage().deserialize(message);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (staffOnly && !p.hasPermission("plainbase.moderation.notify")
                    && !p.hasPermission("plainbase.moderation.admin") && !p.hasPermission("plainbase.admin")) {
                continue;
            }
            p.sendMessage(component);
        }
        Bukkit.getConsoleSender().sendMessage(component);
    }
}
