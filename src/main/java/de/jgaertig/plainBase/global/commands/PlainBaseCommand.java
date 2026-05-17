package de.jgaertig.plainBase.global.commands;

import de.jgaertig.plainBase.PlainBase;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.stream.Stream;

public class PlainBaseCommand implements BasicCommand {

    private final PlainBase plugin;

    public PlainBaseCommand(PlainBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String @NotNull [] args) {
        CommandSender sender = stack.getSender();

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
                sender.sendMessage(plugin.getMiniMessage().deserialize("<italic><gray>Please restart the server for the changes to take effect."));
            } else {
                sender.sendMessage(plugin.getMiniMessage().deserialize("<red>This module does not exist!"));
            }
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("update")) {
            String serverVersion = Bukkit.getMinecraftVersion();
            sender.sendMessage(plugin.getMiniMessage().deserialize("<gray>Checking for updates for Minecraft " + serverVersion + "..."));


            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                String latestVersion = getLatestVersionFromModrinth("yfx0z1Sw", serverVersion);

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
        }
    }

    @Override
    public @NotNull List<String> suggest(@NotNull CommandSourceStack stack, @NotNull String @NonNull [] args) {
        if (args.length == 0) {
            return List.of("toggle", "update");
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return Stream.of("toggle", "update")
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
            String urlString = String.format(
                    "https://api.modrinth.com/v2/project/%s/version?game_versions=[\"%s\"]&loaders=[\"paper\"]",
                    projectId, gameVersion
            );

            java.net.URL url = new java.net.URL(urlString);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "j-gaertig/PlainBase/" + plugin.getPluginMeta().getVersion());

            if (conn.getResponseCode() == 200) {
                java.util.Scanner scanner = new java.util.Scanner(conn.getInputStream());
                StringBuilder builder = new StringBuilder();
                while (scanner.hasNextLine()) builder.append(scanner.nextLine());
                scanner.close();

                String response = builder.toString();

                if (response.equals("[]")) {
                    return "NOT_FOUND";
                }

                if (response.contains("\"version_number\":\"")) {
                    int start = response.indexOf("\"version_number\":\"") + 18;
                    int end = response.indexOf("\"", start);
                    return response.substring(start, end);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to check for updates: " + e.getMessage());
        }
        return null;
    }
}
