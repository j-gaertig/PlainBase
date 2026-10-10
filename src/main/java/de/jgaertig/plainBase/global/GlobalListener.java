package de.jgaertig.plainBase.global;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class GlobalListener implements Listener {

    private final PlainBase plugin;

    public GlobalListener(PlainBase plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onAdminJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().hasPermission("plainbase.admin")) return;

        // String comparison (mirrors PlainBase.compareVersions semantics): a
        // double comparison would treat 1.10 as equal to 1.1, and even
        // String.valueOf(getDouble(...)) collapses a stored "1.10" to "1.1"
        // before the compare. Versions are therefore read via get() and
        // toString() (tolerates numeric unquoted YAML and quoted strings),
        // and the latest map holds Strings (Map<String, String>).
        // Central configLock guards the ROOT config only (toggle/suggest may
        // mutate it while this reads). Module configs are guarded per-instance
        // via synchronized(config): their async snapshots use the same monitor
        // (see PlainBase.saveModuleConfigAsync). Snapshot everything under the
        // lock(s), send outside.
        final String currentMain;
        final String latestMain;
        final java.util.Map<String, String> currentByFile = new java.util.HashMap<>();
        final java.util.Map<String, String> latestByFile;
        synchronized (plugin.getConfigLock()) {
            currentMain = readVersionString(plugin.getConfig().get("version"));
            latestMain = plugin.getLatestVersions().getOrDefault("config.yml", "0");
            plugin.getConfigs().forEach((name, config) -> {
                String current;
                if (config == null) {
                    current = "0";
                } else {
                    synchronized (config) {
                        current = readVersionString(config.get("version"));
                    }
                }
                currentByFile.put(name, current);
            });
            latestByFile = plugin.getLatestVersions();
        }

        if (compareVersions(currentMain, latestMain) < 0) {
            sendWarning(event, "config.yml", currentMain, latestMain);
        }

        currentByFile.forEach((name, current) -> {
            String latest = latestByFile.getOrDefault(name, "0");

            if (compareVersions(current, latest) < 0) {
                sendWarning(event, "modules/" + name, current, latest);
            }
        });
    }

    private void sendWarning(PlayerJoinEvent event, String name, String current, String latest) {
        String safeName = plugin.getMiniMessage().escapeTags(name);
        String safeCurrent = plugin.getMiniMessage().escapeTags(current);
        String safeLatest = plugin.getMiniMessage().escapeTags(latest);
        event.getPlayer().sendMessage(plugin.getMiniMessage().deserialize(
                "<red><bold>[PlainBase]</bold> Your <yellow>" + safeName + "</yellow> is outdated! " +
                        "<gray>(v" + safeCurrent + " < v" + safeLatest + ")"
        ));
    }

    /**
     * Local segment-wise compare (same semantics as PlainBase.compareVersions,
     * kept local so no PlainBase signature changes): "1.10" &gt; "1.9".
     * Non-numeric segments count as 0, missing segments count as 0.
     */
    private static int compareVersions(String a, String b) {
        String left = a == null ? "0" : a;
        String right = b == null ? "0" : b;
        String[] pa = left.split("\\.", -1);
        String[] pb = right.split("\\.", -1);
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = parseSegment(i < pa.length ? pa[i] : "0");
            int nb = parseSegment(i < pb.length ? pb[i] : "0");
            if (na != nb) return Integer.compare(na, nb);
        }
        return 0;
    }

    private static int parseSegment(String segment) {
        try {
            return Integer.parseInt(segment.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Reads a "version" value as string, tolerating numeric (unquoted YAML)
     * and quoted-string forms. Mirrors PlainBase.readVersionString: a
     * getDouble() read would collapse "1.10" to 1.1 before comparing.
     */
    private static String readVersionString(Object value) {
        if (value == null) return "0";
        String text = value.toString().trim();
        return text.isEmpty() ? "0" : text;
    }
}
