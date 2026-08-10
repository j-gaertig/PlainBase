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
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Stream;

public class PlainBaseCommand implements BasicCommand {

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
            String path = "modules." + moduleName;

            if (plugin.getConfig().contains(path)) {
                boolean currentStatus = plugin.getConfig().getBoolean(path);
                boolean newStatus = !currentStatus;

                plugin.getConfig().set(path, newStatus);
                plugin.saveConfig();

                String statusColor = newStatus ? "<green>enabled" : "<red>disabled";
                sender.sendMessage(plugin.getMiniMessage().deserialize(
                        "<gray>The module <yellow>" + moduleName + "</yellow> has been " + statusColor + "<gray>."
                ));
                plugin.reloadModules();
            } else {
                sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module does not exist!"));
            }
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadModules();
            sender.sendMessage(plugin.getMiniMessage().deserialize("<green>Config reloaded and modules updated!"));
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("update")) {
            String serverVersion = Bukkit.getMinecraftVersion();
            sender.sendMessage(plugin.getMiniMessage().deserialize("<gray>Checking for updates for Minecraft " + serverVersion + "..."));

            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                String latestVersion = getLatestVersionFromModrinth("yfx0z1Sw", serverVersion);

                // Send result on the global region scheduler (main thread) - thread-safe on Paper and Folia
                Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> {
                    if (latestVersion == null) {
                        sender.sendMessage(plugin.getMiniMessage().deserialize("<red>Could not reach Modrinth. Please try again later."));
                        return;
                    }

                    if (latestVersion.equals("NOT_FOUND")) {
                        sender.sendMessage(plugin.getMiniMessage().deserialize("<yellow>No compatible version found for Minecraft " + serverVersion + "."));
                        return;
                    }

                    String currentVersion = plugin.getPluginMeta().getVersion();
                    if (currentVersion.equalsIgnoreCase(latestVersion)) {
                        sender.sendMessage(plugin.getMiniMessage().deserialize("<green>You are running the latest version! (" + currentVersion + ")"));
                    } else {
                        sender.sendMessage(plugin.getMiniMessage().deserialize(
                                "<yellow>A new version is available: <bold>" + latestVersion + "</bold>\n" +
                                        "<gray>Download here: <click:open_url:'https://modrinth.com/plugin/plainbase'><underlined><blue>modrinth.com/plugin/plainbase</blue></underlined></click>"
                        ));
                    }
                });
            });
        }
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NonNull [] args) {
        if (args.length == 0) {
            return List.of("toggle", "update", "reload");
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return Stream.of("toggle", "update", "reload")
                    .filter(s -> s.startsWith(input))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            ConfigurationSection modules = plugin.getConfig().getConfigurationSection("modules");
            if (modules != null) {
                String input = args[1].toLowerCase();
                return modules.getKeys(false).stream()
                        .filter(s -> s.startsWith(input))
                        .toList();
            }
        }
        return List.of();
    }

    private String getLatestVersionFromModrinth(String projectId, String gameVersion) {
        try {
            String encodedVersion = URLEncoder.encode(gameVersion, StandardCharsets.UTF_8);
            String urlString = "https://api.modrinth.com/v2/project/" + projectId
                    + "/version?game_versions=%5B%22" + encodedVersion
                    + "%22%5D&loaders=%5B%22paper%22%5D";

            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "j-gaertig/PlainBase/" + plugin.getPluginMeta().getVersion());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                try (Scanner scanner = new Scanner(conn.getInputStream())) {
                    StringBuilder builder = new StringBuilder();
                    while (scanner.hasNextLine()) builder.append(scanner.nextLine());

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
                plugin.getLogger().warning("Modrinth API responded with HTTP " + conn.getResponseCode());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to check for updates: " + e.getMessage());
        }
        return null;
    }
}