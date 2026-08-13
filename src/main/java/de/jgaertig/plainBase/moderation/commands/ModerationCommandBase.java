package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * Shared helpers for the moderation commands (ban/tempban/unban/kick/banlist/baninfo):
 * module/permission/command-enabled checks (repo check-order convention), async
 * offline-player resolution and broadcast handling. Not a command itself.
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
