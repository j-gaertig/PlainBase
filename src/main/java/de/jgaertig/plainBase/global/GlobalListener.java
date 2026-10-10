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
        // The getConfigs()/getLatestVersions() copies are taken OUTSIDE the
        // lock: both already return defensive copies, so holding the central
        // monitor while copying is unnecessary.
        final java.util.Map<String, org.bukkit.configuration.file.FileConfiguration> configsSnapshot =
                plugin.getConfigs();
        final java.util.Map<String, String> latestSnapshot = plugin.getLatestVersions();
        final String currentMain;
        final String latestMain;
        final java.util.Map<String, String> currentByFile = new java.util.HashMap<>();
        synchronized (plugin.getConfigLock()) {
            org.bukkit.configuration.file.FileConfiguration rootCfg = plugin.getConfig();
            currentMain = (rootCfg == null) ? "0" : readVersionString(rootCfg.get("version"));
            latestMain = latestSnapshot.getOrDefault("config.yml", "0");
            configsSnapshot.forEach((name, config) -> {
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
        }

        // Spam guard: one chat line per outdated file (up to 10) becomes a
        // single summary line — details stay in the console log
        // (PlainBase.checkAllConfigVersions logs each file on reload).
        java.util.List<String> outdated = new java.util.ArrayList<>();
        if (compareVersions(currentMain, latestMain) < 0) {
            outdated.add("config.yml");
        }
        currentByFile.forEach((name, current) -> {
            String latest = latestSnapshot.getOrDefault(name, "0");
            if (compareVersions(current, latest) < 0) {
                outdated.add("modules/" + name);
            }
        });
        if (outdated.isEmpty()) return;
        int count = outdated.size();
        event.getPlayer().sendMessage(plugin.getMiniMessage().deserialize(
                "<red><bold>[PlainBase]</bold></red> <yellow>" + count
                        + (count == 1 ? " config is" : " configs are")
                        + " outdated!</yellow> <gray>See console for details.</gray>"
        ));
    }

    /**
     * Local segment-wise compare (same semantics as PlainBase.compareVersions,
     * kept local so no PlainBase signature changes): "1.10" &gt; "1.9".
     * A dash-separated pre-release suffix ("0.3.0-Beta") is stripped from the
     * numeric core and compared afterwards: same core means pre-release &lt;
     * release, two pre-releases compare their tags case-insensitively.
     * Non-numeric segments count as 0, missing segments count as 0.
     */
    private static int compareVersions(String a, String b) {
        String left = a == null ? "0" : a.trim();
        String right = b == null ? "0" : b.trim();
        String preL = preReleaseOf(left);
        String preR = preReleaseOf(right);
        String[] pa = coreOf(left).split("\\.", -1);
        String[] pb = coreOf(right).split("\\.", -1);
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = parseSegment(i < pa.length ? pa[i] : "0");
            int nb = parseSegment(i < pb.length ? pb[i] : "0");
            if (na != nb) return Integer.compare(na, nb);
        }
        boolean hasPreL = preL != null;
        boolean hasPreR = preR != null;
        if (hasPreL != hasPreR) return hasPreL ? -1 : 1;
        if (hasPreL) {
            int cmp = preL.compareToIgnoreCase(preR);
            if (cmp != 0) return Integer.compare(cmp, 0);
        }
        return 0;
    }

    /** Numeric core of a version: everything before the first '-' or '+'. */
    private static String coreOf(String version) {
        String core = version;
        int dash = core.indexOf('-');
        if (dash >= 0) core = core.substring(0, dash);
        int plus = core.indexOf('+');
        if (plus >= 0) core = core.substring(0, plus);
        core = core.trim();
        return core.isEmpty() ? "0" : core;
    }

    /** Pre-release tag of a version ("0.3.0-Beta" -&gt; "Beta"), or null. */
    private static String preReleaseOf(String version) {
        int dash = version.indexOf('-');
        if (dash < 0) return null;
        String pre = version.substring(dash + 1);
        int plus = pre.indexOf('+');
        if (plus >= 0) pre = pre.substring(0, plus);
        pre = pre.trim();
        return pre.isEmpty() ? null : pre;
    }

    private static int parseSegment(String segment) {
        String s = segment == null ? "" : segment.trim();
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            // Tolerate trailing junk ("3rc1" -> 3): compare the leading
            // numeric prefix instead of collapsing the whole segment to 0.
            int end = 0;
            while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
            if (end == 0) return 0;
            try {
                return Integer.parseInt(s.substring(0, end));
            } catch (NumberFormatException e2) {
                return 0;
            }
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
