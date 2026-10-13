package de.jgaertig.plainBase.global.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Stream;

public class PlainBaseCommand implements BasicCommand {

    /** Modrinth project id for PlainBase update checks. */
    private static final String MODRINTH_PROJECT_ID = "yfx0z1Sw";
    /** Max Modrinth response body kept in memory (OOM guard). */
    private static final int MODRINTH_MAX_BYTES = 256 * 1024;

    private final PlainBase plugin;
    private final AtomicBoolean toggleInFlight = new AtomicBoolean();

    public PlainBaseCommand(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

        if (!sender.hasPermission("plainbase.admin")) {
            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>No permission!"));
            return;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("toggle")) {
            if (!toggleInFlight.compareAndSet(false, true)) {
                sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>A module toggle is already being saved. Try again shortly."));
                return;
            }
            String moduleName = args[1];
            String safeModule = plugin.getMiniMessage().escapeTags(moduleName);

            if (moduleName.isEmpty()) {
                sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module does not exist!"));
                toggleInFlight.set(false);
                return;
            }

            // Folia: only validated above; config write, save and module
            // reload run on the global region thread, success message in callback.
            try {
                Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                    if (!plugin.isEnabled()) {
                        toggleInFlight.set(false);
                        return;
                    }
                    try {
                        // G3 whitelist: only exact top-level keys under "modules"
                        // may be toggled. The old contains("modules."+name) check
                        // also accepted nested paths (e.g. "spawn.enabled" or
                        // "team.commands.invite.enabled"), letting a typo write a
                        // stray key. Reject anything with a dot and anything not
                        // in the live key set.
                        // F3: toggle and suggest() race on the live YamlConfiguration
                        // (suggest may run off-thread while this mutates). All
                        // reads/mutations go through the central configLock (not
                        // the config instance, which reloadConfig() may swap).
                        // Single critical section: whitelist check + read-modify
                        // under one lock, snapshot newStatus; the async save
                        // below snapshots under the same lock and the reload is
                        // chained after the write completed, so the toggled
                        // value is never wiped by a premature disk re-read.
                        String actualKey = null;
                        boolean newStatus = false;
                        boolean found;
                        synchronized (plugin.getConfigLock()) {
                            // The config reference is fetched INSIDE the lock:
                            // reloadConfig() may swap the instance at any time,
                            // so a reference taken before the lock can be stale.
                            org.bukkit.configuration.file.FileConfiguration rootCfg = plugin.getConfig();
                            if (rootCfg == null) {
                                sendToSender(sender, "<red>Could not toggle module!");
                                toggleInFlight.set(false);
                                return;
                            }
                            try {
                                ConfigurationSection sec = rootCfg.getConfigurationSection("modules");
                                if (sec != null && !moduleName.contains(".") && !moduleName.contains(" ")) {
                                    for (String k : sec.getKeys(false)) {
                                        if (k.equalsIgnoreCase(moduleName)) {
                                            actualKey = k;
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                            found = (actualKey != null);
                            if (found) {
                                String path = "modules." + actualKey;
                                newStatus = !rootCfg.getBoolean(path);
                                rootCfg.set(path, newStatus);
                            }
                        }
                        if (!found) {
                            sendToSender(sender, "<red>This module does not exist!");
                            toggleInFlight.set(false);
                            return;
                        }
                        // NOTE: saveConfigAsync() rewrites config.yml WITHOUT comments
                        // (Bukkit YamlConfiguration strips them) — the toggle
                        // itself is unaffected, but hand-written comments are
                        // lost. The success message below says so.
                        // Folia: no sync disk I/O on the global region thread —
                        // the reload (which re-reads config.yml from disk) is
                        // chained after the write completed, then the message.
                        final boolean appliedStatus = newStatus;
                        final boolean previousStatus = !appliedStatus;
                        final String appliedKey = actualKey;
                        plugin.saveConfigAsync().whenComplete((v, saveError) -> {
                            if (saveError != null) {
                                plugin.getLogger().warning("Async save of config.yml failed: " + saveError.getMessage());
                                try {
                                    Bukkit.getGlobalRegionScheduler().run(plugin, rollbackTask -> {
                                        synchronized (plugin.getConfigLock()) {
                                            org.bukkit.configuration.file.FileConfiguration current = plugin.getConfig();
                                            String path = "modules." + appliedKey;
                                            if (current != null && current.getBoolean(path) == appliedStatus) {
                                                current.set(path, previousStatus);
                                            }
                                        }
                                        sendToSender(sender, "<red>Could not save config.yml; the module toggle was reverted.");
                                        toggleInFlight.set(false);
                                    });
                                } catch (Exception rollbackScheduleError) {
                                    plugin.getLogger().warning("Could not schedule in-memory toggle rollback: "
                                            + rollbackScheduleError.getMessage());
                                    sendToSender(sender, "<red>Could not save config.yml; module state may need a reload.");
                                    toggleInFlight.set(false);
                                }
                                return;
                            }
                            try {
                                Bukkit.getGlobalRegionScheduler().run(plugin, reloadTask -> {
                                    if (!plugin.isEnabled()) {
                                        toggleInFlight.set(false);
                                        return;
                                    }
                                    plugin.reloadModulesAsync().whenComplete((ignored, reloadError) -> {
                                        if (reloadError != null) {
                                            plugin.getLogger().log(Level.WARNING,
                                                    "Failed to reload after toggle of " + moduleName, reloadError);
                                            sendToSender(sender, "<red>Could not toggle module!");
                                            toggleInFlight.set(false);
                                            return;
                                        }
                                        String statusColor = appliedStatus ? "<green>enabled" : "<red>disabled";
                                        sendToSender(sender, "<gray>The module <yellow>" + safeModule
                                                + "</yellow> has been " + statusColor + "<gray>."
                                                + " <dark_gray>(Note: saving removed comments from config.yml.)");
                                        toggleInFlight.set(false);
                                    });
                                });
                            } catch (Exception e) {
                                plugin.getLogger().warning("Failed to schedule reload after toggle: " + e.getMessage());
                                try {
                                    sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Could not toggle module!"));
                                } catch (Exception ignored) {
                                }
                                toggleInFlight.set(false);
                            }
                        });
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to toggle module " + moduleName, e);
                        sendToSender(sender, "<red>Could not toggle module!");
                        toggleInFlight.set(false);
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to schedule module toggle: " + e.getMessage());
                sendToSender(sender, "<red>Could not toggle module!");
                toggleInFlight.set(false);
            }
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadModulesAsync().whenComplete((ignored, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to reload modules", error);
                    sendToSender(sender, "<red>Could not reload modules!");
                } else {
                    sendToSender(sender, "<green>Config reloaded and modules updated!");
                }
            });
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("update")) {
            final String serverVersionRaw = Bukkit.getMinecraftVersion();
            final String serverVersion = serverVersionRaw == null ? "unknown" : serverVersionRaw;
            String safeVersion = plugin.getMiniMessage().escapeTags(serverVersion);
            sendToSender(sender, "<gray>Checking for updates for Minecraft " + safeVersion + "...");

            try {
                Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                String latestVersion = getLatestVersionFromModrinth(MODRINTH_PROJECT_ID, serverVersion);

                // P2: the plugin may have been disabled while the Modrinth
                // request was in flight — scheduling on a disabled plugin
                // throws, so guard before the second hop. The hop itself
                // re-checks isEnabled (disable between schedule and run).
                if (!plugin.isEnabled()) return;
                // Send result on the global region scheduler (main thread) - thread-safe on Paper and Folia
                try {
                    Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> {
                        if (!plugin.isEnabled()) return;
                    if (latestVersion == null) {
                        sendToSender(sender, "<red>Could not reach Modrinth. Please try again later.");
                        return;
                    }

                    if (latestVersion.equals("NOT_FOUND")) {
                        sendToSender(sender, "<yellow>No compatible version found for Minecraft " + safeVersion + ".");
                        return;
                    }

                    String currentVersion = plugin.getPluginMeta().getVersion();
                    if (currentVersion == null) currentVersion = "unknown";
                    String latestSafe = latestVersion == null ? "unknown" : latestVersion;
                    String safeLatest = plugin.getMiniMessage().escapeTags(latestSafe);
                    String safeCurrent = plugin.getMiniMessage().escapeTags(currentVersion);
                    if (compareVersions(currentVersion, latestVersion) >= 0) {
                        sendToSender(sender, "<green>You are running the latest version! (" + safeCurrent + ")");
                    } else {
                        sendToSender(sender, "<yellow>A new version is available: <bold>" + safeLatest + "</bold>\n" +
                                "<gray>Download here: <click:open_url:'https://modrinth.com/plugin/plainbase'><underlined><blue>modrinth.com/plugin/plainbase</blue></underlined></click>");
                    }
                    });
                } catch (Exception e) {
                    plugin.getLogger().fine("Failed to deliver update result (plugin disabling?): " + e.getMessage());
                }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to schedule update check: " + e.getMessage());
                sendToSender(sender, "<red>Could not check for updates!");
            }
            return;
        }

        sender.sendMessage(plugin.getMiniMessage().deserialize(
                "<gray>Usage: <yellow>/plainbase <toggle <module>|reload|update>"
        ));
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NonNull [] args) {
        // Module names and subcommands are admin-only — never leak them to unauthorized senders.
        try {
            if (!stack.getSender().hasPermission("plainbase.admin")) return List.of();
        } catch (Exception e) {
            return List.of();
        }
        if (args.length == 0) {
            return List.of("toggle", "update", "reload");
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("toggle", "update", "reload")
                    .filter(s -> s.startsWith(input))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            // F3: suggestions may run off-thread while toggle/reload mutates
            // the config on the global thread. Snapshot the key set under the
            // central configLock (toggle uses the same monitor), then filter
            // outside the lock.
            java.util.Set<String> snapshot;
            synchronized (plugin.getConfigLock()) {
                // Fetched inside the lock (see toggle above): the instance
                // may be swapped by reloadConfig() at any time.
                org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
                if (cfg == null) return List.of();
                try {
                    ConfigurationSection sec = cfg.getConfigurationSection("modules");
                    snapshot = (sec == null) ? Set.of() : Set.copyOf(sec.getKeys(false));
                } catch (Exception ignored) {
                    snapshot = Set.of();
                }
            }
            String input = args[1].toLowerCase(Locale.ROOT);
            return snapshot.stream()
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(input))
                    .toList();
        }
        return List.of();
    }

    private String getLatestVersionFromModrinth(String projectId, String gameVersion) {
        HttpURLConnection conn = null;
        try {
            String encodedVersion = URLEncoder.encode(gameVersion, StandardCharsets.UTF_8);
            String urlString = "https://api.modrinth.com/v2/project/" + projectId
                    + "/version?game_versions=%5B%22" + encodedVersion
                    + "%22%5D&loaders=%5B%22paper%22%5D";

            URL url = URI.create(urlString).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "j-gaertig/PlainBase/" + plugin.getPluginMeta().getVersion());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8)) {
                    StringBuilder builder = new StringBuilder();
                    int bytes = 0;
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine();
                        bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                        if (bytes > MODRINTH_MAX_BYTES) {
                            plugin.getLogger().warning("Modrinth response exceeded 256KB, aborting update check.");
                            return null;
                        }
                        builder.append(line);
                    }

                    JsonElement parsed = JsonParser.parseString(builder.toString());
                    if (!parsed.isJsonArray()) {
                        plugin.getLogger().warning("Modrinth API returned unexpected response, aborting update check.");
                        return null;
                    }
                    JsonArray versions = parsed.getAsJsonArray();
                    if (versions.isEmpty()) {
                        return "NOT_FOUND";
                    }

                    // Update channel follows the running version: pre-release
                    // builds ("-Beta"/"-Alpha") also accept "beta" entries so
                    // beta users are offered updates; stable builds only ever
                    // see "release" entries.
                    boolean acceptBeta = isPreRelease(plugin.getPluginMeta().getVersion());
                    for (JsonElement element : versions) {
                        if (!element.isJsonObject()) continue;
                        var obj = element.getAsJsonObject();
                        if (!obj.has("version_number")) continue;
                        String type = obj.has("version_type") && !obj.get("version_type").isJsonNull()
                                ? obj.get("version_type").getAsString() : "";
                        if ("release".equalsIgnoreCase(type)) {
                            return obj.get("version_number").getAsString();
                        }
                        if (acceptBeta && "beta".equalsIgnoreCase(type)) {
                            return obj.get("version_number").getAsString();
                        }
                    }
                    return "NOT_FOUND";
                }
            } else {
                plugin.getLogger().warning("Modrinth API responded with HTTP " + responseCode);
                // Drain + close the error stream so the connection can be reused.
                // Bounded read (OOM guard): never buffer an unbounded error body.
                try (InputStream err = conn.getErrorStream()) {
                    if (err != null) err.readNBytes(8 * 1024);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to check for updates: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    /**
     * Segment-wise version compare ("1.10" &gt; "1.9"): a plain
     * equalsIgnoreCase would miss equivalent-but-differently-cased versions
     * and a double compare would collapse 1.10 to 1.1. A dash-separated
     * pre-release suffix ("0.3.0-Beta") is stripped from the numeric core
     * and compared afterwards: same core means pre-release &lt; release, two
     * pre-releases compare their tags case-insensitively. Non-numeric
     * segments count as 0, missing segments count as 0. Kept identical in
     * PlainBase/GlobalListener.
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

    /**
     * True when the running version is a pre-release ("-Beta"/"-Alpha"):
     * the update check then also accepts "beta" Modrinth entries, otherwise
     * only "release" entries are offered.
     */
    private static boolean isPreRelease(String version) {
        if (version == null) return false;
        String lower = version.toLowerCase(Locale.ROOT);
        return lower.contains("-alpha") || lower.contains("-beta");
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

    private void sendToSender(CommandSender sender, String miniMessage) {
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                if (!plugin.isEnabled()) return;
                net.kyori.adventure.text.Component component = plugin.getMiniMessage().deserialize(miniMessage);
                if (sender instanceof Player player) {
                    try {
                        player.getScheduler().run(plugin, entityTask -> {
                            if (player.isOnline()) player.sendMessage(component);
                        }, null);
                    } catch (Exception e) {
                        plugin.getLogger().fine("Could not schedule command response for player: " + e.getMessage());
                    }
                } else {
                    sender.sendMessage(component);
                }
            });
        } catch (Exception e) {
            plugin.getLogger().fine("Could not schedule command response: " + e.getMessage());
        }
    }
}
