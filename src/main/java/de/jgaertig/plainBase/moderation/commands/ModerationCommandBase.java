package de.jgaertig.plainBase.moderation.commands;

import de.jgaertig.plainBase.PlainBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

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
 *
 * <p>Public (not package-private) on purpose: ModerationListener and BanManager
 * reuse {@link #normalizeIp}/{@link #isIpLike} so login tracking, login checks
 * and /banip all compare the same canonical IP form.
 */
public abstract class ModerationCommandBase {

    protected final PlainBase plugin;

    protected ModerationCommandBase(PlainBase plugin) {
        this.plugin = plugin;
    }

    /**
     * @return true if the command may proceed, false if it already sent a rejection message.
     */
    protected boolean checkPreconditions(CommandSender sender, String permissionNode, String commandKey) {
        if (!plugin.getConfig().getBoolean("modules.moderation", false)) {
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
     * Resolves the punishment reason: a missing or blank reason (omitted, only
     * whitespace, or an emptied {@code messages.default-reason} config value)
     * always falls back to the hardcoded default instead of storing/showing a
     * blank reason.
     */
    protected String defaultReason(String raw) {
        if (raw != null && !raw.isBlank()) return raw;
        String configured = message("default-reason", "No reason specified.");
        return configured == null || configured.isBlank() ? "No reason specified." : configured;
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
     * <p>
     * DNS-free literal parser: never touches {@code InetAddress.getByName()}
     * (which can block on DNS — forbidden on region threads). Only IPv4/IPv6
     * literals are accepted; hostnames are rejected. IPv4-mapped
     * {@code ::ffff:a.b.c.d} addresses are unmapped to plain IPv4, and
     * zone IDs ({@code %eth0}) are rejected.
     */
    public static String normalizeIp(String ip) {
        try {
            return normalizeIpLiteral(ip);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * True when the argument looks like a raw IP (IPv4/IPv6 characters only).
     * Pre-filter before {@link #normalizeIp}: distinguishes "looks like an IP"
     * from "looks like a player name". Must stay in sync with
     * IpBanCommand's gate.
     */
    public static boolean isIpLike(String arg) {
        if (arg == null) return false;
        if (!IP_LIKE.matcher(arg).matches()) return false;
        return arg.contains(".") || arg.contains(":");
    }

    private static final java.util.regex.Pattern IP_LIKE =
            java.util.regex.Pattern.compile("^[0-9a-fA-F.:\\[\\]]+$");

    static String normalizeIpLiteral(String ip) {
        if (ip == null) return null;
        String s = ip.trim();
        if (s.isEmpty()) return null;
        // Zone IDs (fe80::1%eth0) are never valid ban targets.
        if (s.contains("%")) return null;
        // Strip one pair of brackets around IPv6 literals ([::1]).
        if (s.length() > 2 && s.charAt(0) == '[' && s.charAt(s.length() - 1) == ']') {
            s = s.substring(1, s.length() - 1).trim();
            if (s.isEmpty() || s.contains("%")) return null;
        }
        if (!s.contains(".") && !s.contains(":")) return null;
        try {
            if (s.contains(":")) {
                byte[] v6 = parseIpv6Literal(s);
                if (v6 == null) return null;
                // Unmap ::ffff:0:0/96 (IPv4-mapped IPv6) to plain IPv4 so
                // "::ffff:1.2.3.4" and "1.2.3.4" compare equal everywhere.
                if (isIpv4Mapped(v6)) {
                    return (v6[12] & 0xFF) + "." + (v6[13] & 0xFF) + "."
                            + (v6[14] & 0xFF) + "." + (v6[15] & 0xFF);
                }
                return java.net.InetAddress.getByAddress(v6).getHostAddress();
            }
            byte[] v4 = parseIpv4Literal(s);
            if (v4 == null) return null;
            return java.net.InetAddress.getByAddress(v4).getHostAddress();
        } catch (java.net.UnknownHostException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] parseIpv4Literal(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return null;
            for (int j = 0; j < p.length(); j++) {
                if (!Character.isDigit(p.charAt(j))) return null;
            }
            try {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) return null;
                out[i] = (byte) v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    private static byte[] parseIpv6Literal(String s) {
        // Split off an embedded IPv4 tail (e.g. ::ffff:1.2.3.4 -> 2 hextets).
        String hextetPart = s;
        int[] tail = null;
        int lastColon = s.lastIndexOf(':');
        if (lastColon >= 0 && s.indexOf('.') > lastColon) {
            String v4tail = s.substring(lastColon + 1);
            byte[] v4 = parseIpv4Literal(v4tail);
            if (v4 == null) return null;
            tail = new int[]{((v4[0] & 0xFF) << 8) | (v4[1] & 0xFF), ((v4[2] & 0xFF) << 8) | (v4[3] & 0xFF)};
            hextetPart = s.substring(0, lastColon);
            // "::ffff:1.2.3.4" -> hextetPart "::ffff"; "1.2.3.4" alone never
            // reaches here (no colon).
            if (hextetPart.isEmpty()) return null;
        }
        int dbl = hextetPart.indexOf("::");
        if (dbl >= 0 && hextetPart.indexOf("::", dbl + 2) >= 0) return null; // at most one ::
        java.util.List<Integer> head = new java.util.ArrayList<>();
        java.util.List<Integer> tailGroups = new java.util.ArrayList<>();
        try {
            if (dbl >= 0) {
                String left = hextetPart.substring(0, dbl);
                String right = hextetPart.substring(dbl + 2);
                if (!left.isEmpty()) {
                    for (String g : left.split(":", -1)) {
                        if (g.isEmpty()) return null;
                        head.add(parseHextet(g));
                    }
                }
                if (!right.isEmpty()) {
                    for (String g : right.split(":", -1)) {
                        if (g.isEmpty()) return null;
                        tailGroups.add(parseHextet(g));
                    }
                }
            } else {
                if (hextetPart.isEmpty()) return null;
                for (String g : hextetPart.split(":", -1)) {
                    if (g.isEmpty()) return null;
                    head.add(parseHextet(g));
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        int tailLen = tail == null ? 0 : 2;
        int total = head.size() + tailGroups.size() + tailLen;
        if (dbl >= 0) {
            if (total > 8) return null;
            int zeros = 8 - total;
            int[] groups = new int[8];
            int idx = 0;
            for (int g : head) groups[idx++] = g;
            idx += zeros;
            for (int g : tailGroups) groups[idx++] = g;
            if (tail != null) {
                groups[idx++] = tail[0];
                groups[idx++] = tail[1];
            }
            return hextetsToBytes(groups);
        }
        if (total != 8) return null;
        int[] groups = new int[8];
        int idx = 0;
        for (int g : head) groups[idx++] = g;
        for (int g : tailGroups) groups[idx++] = g;
        if (tail != null) {
            groups[idx++] = tail[0];
            groups[idx++] = tail[1];
        }
        return hextetsToBytes(groups);
    }

    private static int parseHextet(String g) {
        if (g.isEmpty() || g.length() > 4) throw new NumberFormatException(g);
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) throw new NumberFormatException(g);
        }
        return Integer.parseInt(g, 16);
    }

    private static byte[] hextetsToBytes(int[] groups) {
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i * 2] = (byte) ((groups[i] >> 8) & 0xFF);
            out[i * 2 + 1] = (byte) (groups[i] & 0xFF);
        }
        return out;
    }

    private static boolean isIpv4Mapped(byte[] v6) {
        if (v6 == null || v6.length != 16) return false;
        for (int i = 0; i < 10; i++) {
            if (v6[i] != 0) return false;
        }
        return (v6[10] & 0xFF) == 0xFF && (v6[11] & 0xFF) == 0xFF;
    }

    /**
     * Online-player lookup that prefers an exact name match: Bukkit#getPlayer
     * does prefix matching ("Alex" also matches "Alexander"), which could ban
     * or kick the wrong player on a typo. Exact first, fuzzy only as
     * fallback — and the fuzzy fallback never resolves to a vanished player
     * invisible to the viewer (falls through to offline lookup instead, same
     * oracle protection as TeamManager#onlinePlayerExactFirst).
     */
    protected Player onlinePlayerExactFirst(String name, CommandSender viewer) {
        if (name == null) return null;
        Player exact;
        try {
            exact = Bukkit.getPlayerExact(name);
        } catch (Exception e) {
            return null;
        }
        if (exact != null) return exact;
        Player fuzzy;
        try {
            fuzzy = Bukkit.getPlayer(name);
        } catch (Exception e) {
            return null;
        }
        if (fuzzy == null) return null;
        if (viewer instanceof Player p) {
            try {
                var vanishManager = plugin.getVanishManager();
                if (vanishManager != null && vanishManager.isVanished(fuzzy) && !vanishManager.canSee(p, fuzzy)) {
                    return null;
                }
            } catch (Exception e) {
                return null;
            }
        }
        return fuzzy;
    }

    /**
     * Resolves a target by name: online players resolve instantly, Paper's cached
     * offline-player lookup resolves instantly too. Only an uncached, never-joined
     * name falls back to Bukkit#getOfflinePlayer(String), which can block on a
     * Mojang lookup — so that call always runs on the async scheduler, and the
     * callback is always dispatched back onto the main/region thread afterwards
     * (same pattern as PlainBaseCommand's Modrinth update check).
     * <p>
     * Phantom-UUID guard: a name with neither a profile name nor any playtime
     * resolves to null (→ "player-not-found" in every caller) instead of a
     * phantom UUID that would collect typo-bans. The hasPlayedBefore() check
     * runs INSIDE the async task above (never on the region thread), so no
     * disk I/O lands on the main thread.
     */
    protected void resolveTarget(CommandSender sender, String name, Consumer<OfflinePlayer> callback) {
        Player online = onlinePlayerExactFirst(name, sender);
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
            if (resolved.getName() == null && !resolved.hasPlayedBefore()) {
                resolved = null;
            }
            OfflinePlayer finalResolved = resolved;
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> callback.accept(finalResolved));
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
     * R3 unban shield: true when lifting this player ban requires an admin
     * sender — i.e. the banned target is currently protected (exempt/admin,
     * online-verifiable) or the ban itself was issued from the console or by
     * admin staff. A currently-offline target counts as protected (fail
     * closed): exempt status cannot be verified offline, mirroring the
     * ban-side offline rule in BanCommand/TempBanCommand. The active record is
     * read from the BanManager cache — locally issued bans are always cached
     * (mutations update it synchronously); a cross-server row not yet synced
     * is a documented staleness limitation, same as banlist/baninfo.
     */
    protected boolean isProtectedBan(de.jgaertig.plainBase.moderation.BanRecord record,
                                     OfflinePlayer target, CommandSender sender) {
        Player online = target.getPlayer();
        if (online != null) {
            if (isExempt(target, sender) || isProtectedTarget(online, sender)) return true;
        } else {
            return true;
        }
        if (record == null) return false;
        // Console-issued bans (null staff) were admin-issued by definition.
        if (record.staffUuid() == null) return true;
        // Staff-issued: protected when the staffer currently holds admin perms
        // (best-effort live check; offline staff cannot be verified).
        try {
            Player staff = Bukkit.getPlayer(record.staffUuid());
            if (staff != null && (staff.hasPermission("plainbase.admin")
                    || staff.hasPermission("plainbase.moderation.admin"))) return true;
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /**
     * R3 unban shield for IP bans: true when lifting this IP ban requires an
     * admin sender — console/admin-staff-issued, or a currently-online player
     * on that exact IP is protected (exempt/admin). Unlike the ban side there
     * is deliberately NO "must have a verifiable online owner" requirement:
     * unbanning punishes nobody, so unverifiable owners fail open here while
     * the admin-issued shield above stays the hard guard.
     */
    protected boolean isProtectedIpBan(de.jgaertig.plainBase.moderation.IpBanRecord record, String ip) {
        if (record == null || ip == null) return false;
        if (record.staffUuid() == null) return true;
        try {
            Player staff = Bukkit.getPlayer(record.staffUuid());
            if (staff != null && (staff.hasPermission("plainbase.admin")
                    || staff.hasPermission("plainbase.moderation.admin"))) return true;
        } catch (RuntimeException ignored) {
        }
        try {
            for (Player online : Bukkit.getOnlinePlayers()) {
                String onlineIp = normalizeIp(addressIpOf(online));
                if (ip.equals(onlineIp) && (online.hasPermission("plainbase.moderation.exempt")
                        || online.hasPermission("plainbase.moderation.admin")
                        || online.hasPermission("plainbase.admin"))) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /**
     * Null-safe extraction of a player's current IP for the shield checks
     * above (same dereference guards as IpBanCommand's private helper, which
     * cannot be reused from here).
     */
    private static String addressIpOf(Player player) {
        if (player == null || player.getAddress() == null) return null;
        try {
            java.net.InetAddress inner = player.getAddress().getAddress();
            return inner == null ? null : inner.getHostAddress();
        } catch (RuntimeException e) {
            return null;
        }
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
            if (!plugin.getConfig().getBoolean("modules.moderation", false)) return List.of();
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
