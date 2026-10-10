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
import java.util.stream.Stream;

public class PlainBaseCommand implements BasicCommand {

    /** Modrinth project id for PlainBase update checks. */
    private static final String MODRINTH_PROJECT_ID = "yfx0z1Sw";
    /** Max Modrinth response body kept in memory (OOM guard). */
    private static final int MODRINTH_MAX_BYTES = 256 * 1024;

    private final PlainBase plugin;

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
            String moduleName = args[1];
            String safeModule = plugin.getMiniMessage().escapeTags(moduleName);

            if (moduleName.isEmpty()) {
                sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module does not exist!"));
                return;
            }

            // Folia: only validated above; config write, save and module
            // reload run on the global region thread, success message in callback.
            try {
                Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                    if (!plugin.isEnabled()) return;
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
                        // under one lock, snapshot newStatus; saveConfig() runs
                        // after the lock but on the same global thread, so no
                        // interleaved toggle can slip between modify and save.
                        org.bukkit.configuration.file.FileConfiguration rootCfg = plugin.getConfig();
                        if (rootCfg == null) {
                            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Could not toggle module!"));
                            return;
                        }
                        String actualKey = null;
                        boolean newStatus = false;
                        boolean found;
                        synchronized (plugin.getConfigLock()) {
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
                            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module does not exist!"));
                            return;
                        }
                        plugin.saveConfig();
                        plugin.reloadModules();
                        if (sender instanceof Player player && !player.isOnline()) return;
                        String statusColor = newStatus ? "<green>enabled" : "<red>disabled";
                        sender.sendMessage(plugin.getMiniMessage().deserialize(
                                "<gray>The module <yellow>" + safeModule + "</yellow> has been " + statusColor + "<gray>."
                        ));
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to toggle module " + moduleName + ": " + e.getMessage());
                        try {
                            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Could not toggle module!"));
                        } catch (Exception ignored) {
                        }
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to schedule module toggle: " + e.getMessage());
                sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Could not toggle module!"));
            }
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            // Folia: reloadModules() touches Bukkit state — hop to the global
            // region thread, success message in callback.
            try {
                Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                    if (!plugin.isEnabled()) return;
                    try {
                        plugin.reloadModules();
                        if (sender instanceof Player player && !player.isOnline()) return;
                        sender.sendMessage(plugin.getMiniMessage().deserialize("<green>Config reloaded and modules updated!"));
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to reload modules: " + e.getMessage());
                        try {
                            sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Could not reload modules!"));
                        } catch (Exception ignored) {
                        }
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to schedule reload: " + e.getMessage());
                sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Could not reload modules!"));
            }
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("update")) {
            String serverVersion = Bukkit.getMinecraftVersion();
            String safeVersion = plugin.getMiniMessage().escapeTags(serverVersion);
            sender.sendMessage(plugin.getMiniMessage().deserialize("<gray>Checking for updates for Minecraft " + safeVersion + "..."));

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
                        if (sender instanceof Player player && !player.isOnline()) {
                            return;
                        }
                    if (latestVersion == null) {
                        sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Could not reach Modrinth. Please try again later."));
                        return;
                    }

                    if (latestVersion.equals("NOT_FOUND")) {
                        sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>No compatible version found for Minecraft " + safeVersion + "."));
                        return;
                    }

                    String currentVersion = plugin.getPluginMeta().getVersion();
                    String safeLatest = plugin.getMiniMessage().escapeTags(latestVersion);
                    String safeCurrent = plugin.getMiniMessage().escapeTags(currentVersion);
                    if (compareVersions(currentVersion, latestVersion) >= 0) {
                        sender.sendMessage(plugin.getMiniMessage().deserialize("<green>You are running the latest version! (" + safeCurrent + ")"));
                    } else {
                        sender.sendMessage(plugin.getMiniMessage().deserialize(
                                "<yellow>A new version is available: <bold>" + safeLatest + "</bold>\n" +
                                        "<gray>Download here: <click:open_url:'https://modrinth.com/plugin/plainbase'><underlined><blue>modrinth.com/plugin/plainbase</blue></underlined></click>"
                        ));
                    }
                    });
                } catch (Exception e) {
                    plugin.getLogger().fine("Failed to deliver update result (plugin disabling?): " + e.getMessage());
                }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to schedule update check: " + e.getMessage());
                try {
                    sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Could not check for updates!"));
                } catch (Exception ignored) {
                }
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
            org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
            if (cfg == null) return List.of();
            java.util.Set<String> snapshot;
            synchronized (plugin.getConfigLock()) {
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

                    JsonArray versions = JsonParser.parseString(builder.toString()).getAsJsonArray();
                    if (versions.isEmpty()) {
                        return "NOT_FOUND";
                    }

                    JsonElement latest = versions.get(0);
                    if (latest.isJsonObject() && latest.getAsJsonObject().has("version_number")) {
                        return latest.getAsJsonObject().get("version_number").getAsString();
                    }
                }
            } else {
                plugin.getLogger().warning("Modrinth API responded with HTTP " + responseCode);
                // Drain + close the error stream so the connection can be reused.
                try (InputStream err = conn.getErrorStream()) {
                    if (err != null) err.readAllBytes();
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
     * and a double compare would collapse 1.10 to 1.1. Non-numeric segments
     * count as 0, missing segments count as 0.
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
