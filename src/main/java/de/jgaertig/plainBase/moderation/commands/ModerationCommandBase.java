package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
        // getModerationConfig() is briefly null during /plainbase reload
        // (stopModules() clears configs, setupModeration() re-loads them) —
        // async callbacks (baninfo IP lookup, banip/ban/unban results) that
        // re-read the config after the hop would NPE without this guard.
        try {
            FileConfiguration cfg = plugin.getModerationConfig();
            if (cfg == null) return fallback;
            String value = cfg.getString("messages." + key, fallback);
            return value != null ? value : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * MiniMessage with a plain-text fallback: a broken admin template must
     * never break an async result callback with an exception.
     */
    protected Component render(String raw) {
        String text = raw == null ? "" : raw;
        try {
            return plugin.getMiniMessage().deserialize(text);
        } catch (Exception e) {
            return Component.text(text);
        }
    }

    /**
     * True when a player sender is no longer around to receive a delayed
     * async answer (logged off during the DB hop). Console/command blocks
     * are never "gone".
     */
    protected static boolean isGone(CommandSender sender) {
        return sender instanceof Player player && !player.isOnline();
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
        // Same reload race as message(): a null config keeps defaults
        // (broadcasts on, staff-only off) instead of NPE-ing the callback.
        FileConfiguration cfg = plugin.getModerationConfig();
        if (cfg != null && !cfg.getBoolean("broadcast.enabled", true)) return;

        boolean staffOnly = cfg != null && cfg.getBoolean("broadcast.staff-only", false);
        Component component = render(message);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (staffOnly && !p.hasPermission("plainbase.moderation.notify")
                    && !p.hasPermission("plainbase.moderation.admin") && !p.hasPermission("plainbase.admin")) {
                continue;
            }
            p.sendMessage(component);
        }
        Bukkit.getConsoleSender().sendMessage(component);
    }

    /**
     * Silent permission gate for tab-completion: mirrors
     * {@link #checkPreconditions}' permission logic without sending messages.
     */
    protected boolean hasSuggestPermission(CommandSender sender, String permissionNode) {
        try {
            return sender.hasPermission("plainbase.admin")
                    || sender.hasPermission("plainbase.moderation.admin")
                    || sender.hasPermission(permissionNode);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Vanish-aware visibility for tab-completion: a player sender must not be
     * offered names they cannot see. Delegates to VanishManager.canSee() when
     * the vanish module is active; when the manager is null (module off) no
     * filtering is applied. Console/non-player senders always see everyone.
     */
    protected boolean isSuggestVisible(CommandSender sender, Player target) {
        if (target == null) return false;
        if (!(sender instanceof Player viewer)) return true;
        try {
            if (plugin.getVanishManager() != null) {
                return plugin.getVanishManager().canSee(viewer, target);
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * Gated online-player name suggestions: empty when the sender lacks the
     * command permission, the module is disabled, or the manager is reloading.
     * Otherwise vanished players invisible to the sender are filtered out.
     */
    protected List<String> suggestOnlinePlayers(CommandSender sender, String input, String permissionNode) {
        if (!hasSuggestPermission(sender, permissionNode)) return List.of();
        try {
            if (!plugin.getConfig().getBoolean("modules.moderation", true)) return List.of();
        } catch (Exception e) {
            return List.of();
        }
        if (plugin.getModerationConfig() == null || plugin.getBanManager() == null) return List.of();
        String prefix = input == null ? "" : input.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .filter(target -> isSuggestVisible(sender, target))
                .map(Player::getName)
                .filter(n -> n != null && n.toLowerCase(Locale.ROOT).startsWith(prefix))
                .collect(Collectors.toList());
    }
}
