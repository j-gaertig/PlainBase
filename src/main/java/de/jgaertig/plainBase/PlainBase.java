package de.jgaertig.plainBase;

import de.jgaertig.plainBase.global.GlobalListener;
import de.jgaertig.plainBase.global.commands.PlainBaseCommand;
import de.jgaertig.plainBase.joinItems.JoinItemsListener;
import de.jgaertig.plainBase.messages.BroadcastManager;
import de.jgaertig.plainBase.messages.MessagesListener;
import de.jgaertig.plainBase.spawn.SpawnListener;
import de.jgaertig.plainBase.spawn.commands.*;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class PlainBase extends JavaPlugin {

    private final Map<String, FileConfiguration> configs = new HashMap<>();
    private final Map<String, Double> latestVersions = new HashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private BroadcastManager broadcastManager;

    private boolean commandsRegistered = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        latestVersions.put("config.yml", 1.2);
        latestVersions.put("spawn.yml", 1.2);
        latestVersions.put("joinitems.yml", 1.1);
        latestVersions.put("messages.yml", 1.0);

        if (!commandsRegistered) {
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                var r = event.registrar();
                r.register("plainbase", new PlainBaseCommand(this));
            });
        }

        reloadModules();

        getServer().getPluginManager().registerEvents(new GlobalListener(this), this);
        checkAllConfigVersions();

        commandsRegistered = true;

        getLogger().info("Successfully Enabled!");
    }

    @Override
    public void onDisable() {
        stopModules();

        getLogger().info("Successfully Disabled!");
    }

    public void reloadModules() {
        stopModules();
        reloadConfig();

        if (getConfig().getBoolean("modules.spawn", true)) setupSpawn();
        if (getConfig().getBoolean("modules.joinitems", true)) setupJoinItems();
        if (getConfig().getBoolean("modules.messages", true)) setupMessages();
    }

    public void stopModules() {
        if (broadcastManager != null) {
            broadcastManager.stopBroadcasts();
        }

        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    public FileConfiguration loadModuleConfig(String fileName) {
        File file = new File(getDataFolder(), "modules/" + fileName);
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            saveResource("modules/" + fileName, false);
        }

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(reader);
            configs.put(fileName, config);
            return config;
        } catch (IOException e) {
            getLogger().severe("Could not load " + fileName + "!");
            return null;
        }
    }

    public Map<String, FileConfiguration> getConfigs() {
        return configs;
    }

    public Map<String, Double> getLatestVersions() {
        return latestVersions;
    }

    public FileConfiguration getSpawnConfig() {
        return configs.get("spawn.yml");
    }

    public FileConfiguration getJoinItemsConfig() {
        return configs.get("joinitems.yml");
    }

    public FileConfiguration getMessagesConfig() {
        return configs.get("messages.yml");
    }

    private void checkAllConfigVersions() {
        Double configLatest = latestVersions.get("config.yml");
        if (configLatest != null) {
            checkVersion("config.yml", getConfig().getDouble("version", 0.0), configLatest);
        }

        configs.forEach((name, config) -> {
            Double latest = latestVersions.get(name);
            if (latest != null) {
                checkVersion("modules/" + name, config.getDouble("version", 0.0), latest);
            }
        });
    }

    private void checkVersion(String fileName, double currentV, double latestV) {
        if (currentV < latestV) {
            getLogger().warning("!!! OUTDATED CONFIG: " + fileName + " !!!");
            getLogger().warning("Your version: " + currentV + " | Required: " + latestV);
            getLogger().warning("Please check GitHub for the latest version and update your file.");
        }
    }

    public void setupSpawn() {
        loadModuleConfig("spawn.yml");
        getServer().getPluginManager().registerEvents(new SpawnListener(this), this);

        if (!commandsRegistered) {
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                var r = event.registrar();
                r.register("spawn", new Spawn(this));
                r.register("setspawn", new SetSpawn(this));
                r.register("setfirstspawn", new SetFirstSpawn(this));
                r.register("disablespawn", new DisableSpawn(this));
                r.register("disablefirstspawn", new DisableFirstSpawn(this));
            });
        }
    }

    public void saveSpawnConfig() {
        try {
            FileConfiguration config = getSpawnConfig();
            if (config != null) {
                config.save(new File(getDataFolder(), "modules/spawn.yml"));
            }
        } catch (IOException e) {
            getLogger().severe("Could not save spawn.yml!");
        }
    }

    public void setupJoinItems() {
        loadModuleConfig("joinitems.yml");
        getServer().getPluginManager().registerEvents(new JoinItemsListener(this), this);
    }

    public void setupMessages() {
        loadModuleConfig("messages.yml");
        getServer().getPluginManager().registerEvents(new MessagesListener(this), this);

        broadcastManager = new BroadcastManager(this);
        broadcastManager.startBroadcasts();
    }

    public MiniMessage getMiniMessage() {
        return miniMessage;
    }
}