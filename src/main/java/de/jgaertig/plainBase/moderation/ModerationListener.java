package de.jgaertig.plainBase.moderation;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.sql.SQLException;
import java.util.Objects;

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
        if (!plugin.getConfig().getBoolean("modules.moderation", true)) return;

        BanManager manager = plugin.getBanManager();
        if (manager == null) return;

        // getAddress() can be null on some proxies/edge cases — never let a
        // null address NPE into a fail-open bypass; skip only the IP-ban check.
        String ip = event.getAddress() == null ? "unknown" : event.getAddress().getHostAddress();

        // Always record the IP (even for a player we're about to reject) so
        // staff can /banip a name later even if this exact login is denied.
        manager.trackPlayerIp(event.getUniqueId(), event.getName(), ip);

        if (!plugin.getModerationConfig().getBoolean("ban.enabled", true)) return;

        try {
            BanRecord ban = manager.queryActiveBanNow(event.getUniqueId());
            if (ban != null) {
                disallowForBan(event, ban);
                return;
            }

            if (!"unknown".equals(ip) && plugin.getModerationConfig().getBoolean("ip-ban.enabled", true)) {
                IpBanRecord ipBan = manager.queryActiveIpBanNow(ip);
                if (ipBan != null) {
                    disallowForIpBan(event, ipBan);
                }
            }
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().severe("Could not check ban status for " + Objects.toString(event.getName(), "?") + ": " + e.getMessage());
            // Fail open: a DB hiccup must never lock every player out of the server.
            // (NPEs can no longer bypass bans: all nullable ban/template fields
            // are handled via Objects.toString below, so this path only triggers
            // on genuine DB/runtime failures.)
        }
    }

    private void disallowForBan(AsyncPlayerPreLoginEvent event, BanRecord ban) {
        long now = System.currentTimeMillis();
        String template = Objects.toString(
                plugin.getModerationConfig().getString(
                        ban.isPermanent() ? "messages.ban-screen" : "messages.tempban-screen",
                        "<red>You are banned."),
                "<red>You are banned.");

        // Escape user-controlled values BEFORE substitution so a reason like
        // "<click:run_command:...>" can never inject MiniMessage tags.
        String reason = plugin.getMiniMessage().escapeTags(Objects.toString(ban.reason(), ""));
        String staff = plugin.getMiniMessage().escapeTags(Objects.toString(ban.staffName(), ""));
        String text = template
                .replace("%reason%", reason)
                .replace("%staff%", staff)
                .replace("%remaining%", DurationParser.format(ban.remainingMillis(now)));

        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, plugin.getMiniMessage().deserialize(text));
    }

    private void disallowForIpBan(AsyncPlayerPreLoginEvent event, IpBanRecord ban) {
        long now = System.currentTimeMillis();
        String template = Objects.toString(
                plugin.getModerationConfig().getString("messages.ipban-screen", "<red>Your IP address is banned."),
                "<red>Your IP address is banned.");

        String reason = plugin.getMiniMessage().escapeTags(Objects.toString(ban.reason(), ""));
        String staff = plugin.getMiniMessage().escapeTags(Objects.toString(ban.staffName(), ""));
        String text = template
                .replace("%reason%", reason)
                .replace("%staff%", staff)
                .replace("%remaining%", DurationParser.format(ban.remainingMillis(now)));

        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, plugin.getMiniMessage().deserialize(text));
    }
}
