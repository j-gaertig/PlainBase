package de.jgaertig.plainBase.moderation;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.commands.ModerationCommandBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Blocks logins for banned players/IPs. This event runs OFF the main thread
 * (see AsyncPlayerPreLoginEvent javadoc) — deliberately doing a BLOCKING,
 * uncached DB read here (BanManager#queryActiveBanNow/queryActiveIpBanNow) so
 * a ban issued on another server sharing the same MySQL backend is enforced
 * on THIS server's very next login attempt, not just after the next
 * periodic cache refresh. This is the documented, intended use of this
 * event (LiteBans and friends do the exact same thing) — it is NOT the
 * "no sync IO in join events" rule, which targets the main-thread
 * PlayerJoinEvent, not this already-async pre-login hook.
 */
public class ModerationListener implements Listener {

    private final PlainBase plugin;

    public ModerationListener(PlainBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!plugin.getConfig().getBoolean("modules.moderation", false)) return;

        BanManager manager = plugin.getBanManager();
        if (manager == null) return;

        // Fail-open: during /plainbase reload (stopModules() clears configs,
        // setupModeration() re-loads them right after) getModerationConfig()
        // can briefly be null — and this event runs async, so the reload can
        // land mid-check. A missing config must allow the login, never NPE.
        FileConfiguration modConfig = plugin.getModerationConfig();
        if (modConfig == null) {
            plugin.getLogger().warning("Moderation config unavailable during pre-login for "
                    + Objects.toString(event.getName(), "?") + " — allowing login (fail-open).");
            return;
        }

        // getAddress()/getHostAddress() can be null on some proxies/edge cases.
        // Never persist the literal "unknown" (or null/blank) in the DB — it
        // would later resolve via /banip <name> and ban a bogus address. Skip
        // tracking AND the IP-ban check entirely when there is no real address;
        // a missing address must never NPE into a fail-open bypass either.
        //
        // The address is normalized ONCE to canonical form (same
        // ModerationCommandBase#normalizeIp /banip uses, so "::ffff:1.2.3.4",
        // leading zeros and "[::1]" brackets all compare equal): only the
        // canonical form is tracked and checked. Raw getHostAddress() spellings
        // would otherwise bypass an IP ban via mere notation. Legacy rows
        // stored pre-normalization may still use other spellings — they are
        // matched opportunistically wherever cheap (see BanInfoCommand), but
        // all NEW rows are canonical, so the mismatch window closes itself.
        // normalizeIp returns null for null/blank/"unknown"/unparseable input.
        String rawIp = event.getAddress() == null ? null : event.getAddress().getHostAddress();
        String ip = ModerationCommandBase.normalizeIp(rawIp);

        // Always record the IP (even for a player we're about to reject) so
        // staff can /banip a name later even if this exact login is denied —
        // but only when there is a real address to record.
        if (ip != null) {
            try {
                manager.trackPlayerIp(event.getUniqueId(), event.getName(), ip);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not track player IP for "
                        + Objects.toString(event.getName(), "?") + ": " + e.getMessage());
            }
        }

        // Snapshot every config value this check needs up front: a reload can
        // mutate the config mid-check (this event runs async), so locals keep
        // one consistent decision instead of half-old/half-new behavior.
        // Separate guards: an IP ban must still be enforced when the UUID ban
        // is disabled (and vice versa) — never return early on one flag.
        boolean banEnabled = modConfig.getBoolean("ban.enabled", true);
        boolean ipBanEnabled = modConfig.getBoolean("ip-ban.enabled", true);
        if (!banEnabled && !ipBanEnabled) return;

        try {
            if (banEnabled) {
                BanRecord ban = manager.queryActiveBanNow(event.getUniqueId());
                if (ban != null) {
                    // Fail-closed: disallow FIRST with a plain fallback, then try
                    // the configured screen. If message building throws, the deny
                    // stays — a builder bug can never let a banned player in.
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, Component.text("You are banned."));
                    try {
                        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banKickMessage(ban, modConfig));
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Could not build ban screen, keeping fallback: " + e.getMessage());
                    }
                    return;
                }
            }

            if (ipBanEnabled && ip != null) {
                IpBanRecord ipBan = manager.queryActiveIpBanNow(ip);
                if (ipBan != null) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, Component.text("Your IP address is banned."));
                    try {
                        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, ipBanKickMessage(ipBan, modConfig));
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Could not build IP-ban screen, keeping fallback: " + e.getMessage());
                    }
                }
            }
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().warning("Could not check ban status for " + Objects.toString(event.getName(), "?") + ": " + e.getMessage());
            // Fail open: a DB hiccup must never lock every player out of the server.
            // (NPEs can no longer bypass bans: all nullable ban/template fields
            // are handled via Objects.toString below, so this path only triggers
            // on genuine DB/runtime failures.)
        }
    }

    /**
     * Post-login safety net for the ban-during-prelogin race: tryBanAsync()
     * writes the row asynchronously while this player's AsyncPlayerPreLoginEvent
     * may already have passed (offlinePlayer.getPlayer() is still null then, so
     * the command callback cannot kick). A cheap LIVE re-check here — off the
     * join thread, never a full cache scan per join — catches exactly that
     * window and kicks. The next login is additionally covered by the pre-login
     * live DB check.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("modules.moderation", false)) return;

        BanManager manager = plugin.getBanManager();
        if (manager == null) return;

        FileConfiguration modConfig = plugin.getModerationConfig();
        if (modConfig == null) return;
        // Config snapshot on the MAIN/join thread BEFORE hopping async: the
        // reload-mutable config object is never touched off-thread — every
        // boolean/string this check needs is copied into locals here.
        // Separate guards (see onPreLogin): either check can run while the
        // other is disabled.
        boolean banEnabled = modConfig.getBoolean("ban.enabled", true);
        boolean ipBanEnabled = modConfig.getBoolean("ip-ban.enabled", true);
        if (!banEnabled && !ipBanEnabled) return;
        final boolean banEnabledAsync = banEnabled;
        final boolean ipBanEnabledAsync = ipBanEnabled;
        String banTemplate = Objects.toString(
                modConfig.getString("messages.ban-screen", "<red>You are banned."), "<red>You are banned.");
        String tempbanTemplate = Objects.toString(
                modConfig.getString("messages.tempban-screen", "<red>You are banned."), "<red>You are banned.");
        String ipbanTemplate = Objects.toString(
                modConfig.getString("messages.ipban-screen", "<red>Your IP address is banned."),
                "<red>Your IP address is banned.");

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String rawIp = player.getAddress() == null ? null : player.getAddress().getAddress() == null
                ? null : player.getAddress().getAddress().getHostAddress();
        String ip = ModerationCommandBase.normalizeIp(rawIp);
        // Name/IP captured sync into locals: player.getName() must never run
        // off-thread (the async task below) on Folia.
        final String playerName = player.getName();
        final String ipAsync = ip;

        // Live re-check off the join thread (blocking JDBC must never run on
        // it, and no full cache scan per join). Pre-login already covered the
        // normal case, so a DB failure here fails open with a warning — except
        // the UUID check, which cheaply falls back to the cache.
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            BanRecord ban = null;
            if (banEnabledAsync) {
                try {
                    ban = manager.queryActiveBanNow(uuid);
                } catch (SQLException | RuntimeException e) {
                    plugin.getLogger().warning("Could not re-check ban status on join for "
                            + Objects.toString(playerName, "?") + ": " + e.getMessage());
                    ban = manager.getActiveBan(uuid).orElse(null);
                }
            }

            IpBanRecord ipBan = null;
            if (ban == null && ipBanEnabledAsync && ipAsync != null) {
                try {
                    ipBan = manager.queryActiveIpBanNow(ipAsync);
                } catch (SQLException | RuntimeException e) {
                    plugin.getLogger().warning("Could not re-check IP ban status on join for "
                            + Objects.toString(playerName, "?") + ": " + e.getMessage());
                }
            }
            if (ban == null && ipBan == null) return;

            // Message building in its own try/catch, fail-closed: a broken
            // template must still kick, just with a plain fallback screen.
            Component screen;
            try {
                screen = ban != null
                        ? renderBanScreen(ban, ban.isPermanent() ? banTemplate : tempbanTemplate)
                        : renderIpBanScreen(ipBan, ipbanTemplate);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not build join kick screen, using fallback: " + e.getMessage());
                screen = Component.text("You are banned.");
            }
            kickSafely(player, screen);
        });
    }

    /**
     * Folia-safe kick (same pattern as ModerationCommandBase#kickSafely, which
     * lives in the commands package and is not visible here): the kick must run
     * on the target player's own region thread.
     */
    private void kickSafely(Player target, Component message) {
        target.getScheduler().run(plugin, t -> {
            if (target.isOnline()) target.kick(message);
        }, null);
    }

    private Component banKickMessage(BanRecord ban, FileConfiguration modConfig) {
        // The config is passed in (it was already null-checked by the caller);
        // a /plainbase reload racing the async pre-login event can still null
        // it between check and use, so a null here falls back to a plain
        // screen instead of NPE-ing. Fail-open keeps the login allowed only
        // via onPreLogin's guard; here denial was already decided, so a
        // fallback message is correct.
        String template = modConfig == null ? "<red>You are banned." : Objects.toString(
                modConfig.getString(
                        ban.isPermanent() ? "messages.ban-screen" : "messages.tempban-screen",
                        "<red>You are banned."),
                "<red>You are banned.");
        return renderBanScreen(ban, template);
    }

    /**
     * Builds the ban screen from an already-snapshotted template (see onJoin):
     * touches no config, so it is safe to call off-thread.
     */
    private Component renderBanScreen(BanRecord ban, String template) {
        long now = System.currentTimeMillis();
        String safeTemplate = Objects.toString(template, "<red>You are banned.");

        // Escape user-controlled values BEFORE substitution so a reason like
        // "<click:run_command:...>" can never inject MiniMessage tags.
        String reason = plugin.getMiniMessage().escapeTags(Objects.toString(ban.reason(), ""));
        String staff = plugin.getMiniMessage().escapeTags(Objects.toString(ban.staffName(), ""));
        String text = safeTemplate
                .replace("%reason%", reason)
                .replace("%staff%", staff)
                .replace("%remaining%", DurationParser.format(ban.remainingMillis(now)));

        return kickMessage(text);
    }

    private Component ipBanKickMessage(IpBanRecord ban, FileConfiguration modConfig) {
        String template = modConfig == null ? "<red>Your IP address is banned." : Objects.toString(
                modConfig.getString("messages.ipban-screen", "<red>Your IP address is banned."),
                "<red>Your IP address is banned.");
        return renderIpBanScreen(ban, template);
    }

    /**
     * Builds the IP-ban screen from an already-snapshotted template (see
     * onJoin): touches no config, so it is safe to call off-thread.
     */
    private Component renderIpBanScreen(IpBanRecord ban, String template) {
        long now = System.currentTimeMillis();
        String safeTemplate = Objects.toString(template, "<red>Your IP address is banned.");

        String reason = plugin.getMiniMessage().escapeTags(Objects.toString(ban.reason(), ""));
        String staff = plugin.getMiniMessage().escapeTags(Objects.toString(ban.staffName(), ""));
        String text = safeTemplate
                .replace("%reason%", reason)
                .replace("%staff%", staff)
                .replace("%remaining%", DurationParser.format(ban.remainingMillis(now)));

        return kickMessage(text);
    }

    /**
     * MiniMessage with a plain-text fallback: a broken admin template must
     * never turn a deny into an exception (which would fail open and let a
     * banned player in).
     */
    private Component kickMessage(String text) {
        try {
            return plugin.getMiniMessage().deserialize(text);
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid ban-screen MiniMessage, using plain fallback: " + e.getMessage());
            return Component.text(stripTags(text));
        }
    }

    private static String stripTags(String text) {
        return text == null ? "You are banned." : text.replaceAll("<[^>]*>", "");
    }
}
