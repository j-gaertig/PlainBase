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

        // String comparison (mirrors PlainBase.compareVersions): a double
        // comparison would treat 1.10 as equal to 1.1. Doubles are read from
        // the version map (type unchanged: Map<String, Double>) and converted
        // via String.valueOf for the segment-wise compare below.
        String currentMain = String.valueOf(plugin.getConfig().getDouble("version", 0.0));
        String latestMain = String.valueOf(plugin.getLatestVersions().getOrDefault("config.yml", 0.0));

        if (compareVersions(currentMain, latestMain) < 0) {
            sendWarning(event, "config.yml", currentMain, latestMain);
        }

        plugin.getConfigs().forEach((name, config) -> {
            String current = String.valueOf(config.getDouble("version", 0.0));
            String latest = String.valueOf(plugin.getLatestVersions().getOrDefault(name, 0.0));

            if (compareVersions(current, latest) < 0) {
                sendWarning(event, "modules/" + name, current, latest);
            }
        });
    }

    private void sendWarning(PlayerJoinEvent event, String name, String current, String latest) {
        event.getPlayer().sendMessage(plugin.getMiniMessage().deserialize(
                "<red><bold>[PlainBase]</bold> Your <yellow>" + name + "</yellow> is outdated! " +
                        "<gray>(v" + current + " < v" + latest + ")"
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
}