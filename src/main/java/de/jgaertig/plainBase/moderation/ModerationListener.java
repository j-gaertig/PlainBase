package de.jgaertig.plainBase.moderation;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Optional;

/**
 * Blocks logins for banned players. This event runs OFF the main thread
 * (see AsyncPlayerPreLoginEvent javadoc) — only touches thread-safe,
 * already-in-memory data (BanManager's ConcurrentHashMap) and the pure
 * MiniMessage#deserialize() call, never Bukkit main-thread-only APIs.
 */
public class ModerationListener implements Listener {

    private final PlainBase plugin;

    public ModerationListener(PlainBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!plugin.getConfig().getBoolean("modules.moderation", true)) return;
        if (!plugin.getModerationConfig().getBoolean("ban.enabled", true)) return;

        BanManager manager = plugin.getBanManager();
        if (manager == null) return;

        Optional<BanRecord> activeBan = manager.getActiveBan(event.getUniqueId());
        if (activeBan.isEmpty()) return;

        BanRecord ban = activeBan.get();
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
}
