package de.jgaertig.plainBase.moderation;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.sql.SQLException;

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

        String ip = event.getAddress().getHostAddress();

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

            if (plugin.getModerationConfig().getBoolean("ip-ban.enabled", true)) {
                IpBanRecord ipBan = manager.queryActiveIpBanNow(ip);
                if (ipBan != null) {
                    disallowForIpBan(event, ipBan);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not check ban status for " + event.getName() + ": " + e.getMessage());
            // Fail open: a DB hiccup must never lock every player out of the server.
        }
    }

    private void disallowForBan(AsyncPlayerPreLoginEvent event, BanRecord ban) {
        long now = System.currentTimeMillis();
        String template = ban.isPermanent()
                ? plugin.getModerationConfig().getString("messages.ban-screen", "<red>You are banned.")
                : plugin.getModerationConfig().getString("messages.tempban-screen", "<red>You are banned.");

        String text = template
                .replace("%reason%", ban.reason())
                .replace("%staff%", ban.staffName())
                .replace("%remaining%", DurationParser.format(ban.remainingMillis(now)));

        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, plugin.getMiniMessage().deserialize(text));
    }

    private void disallowForIpBan(AsyncPlayerPreLoginEvent event, IpBanRecord ban) {
        long now = System.currentTimeMillis();
        String template = plugin.getModerationConfig().getString("messages.ipban-screen", "<red>Your IP address is banned.");

        String text = template
                .replace("%reason%", ban.reason())
                .replace("%staff%", ban.staffName())
                .replace("%remaining%", DurationParser.format(ban.remainingMillis(now)));

        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, plugin.getMiniMessage().deserialize(text));
    }
}
