package de.jgaertig.plainBase;

import de.jgaertig.plainBase.global.GlobalListener;
import de.jgaertig.plainBase.global.commands.PlainBaseCommand;
import de.jgaertig.plainBase.joinItems.JoinItemsListener;
import de.jgaertig.plainBase.spawn.SpawnListener;
import de.jgaertig.plainBase.spawn.commands.*;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class PlainBase extends JavaPlugin {

    private File spawnFile;
    private FileConfiguration spawnConfig;
    private File joinItemsFile;
    private FileConfiguration joinItemsConfig;
    private File joinMessagesFile;
    private FileConfiguration joinMessagesConfig;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final double LATEST_CONFIG_V = 1.2;
    private final double LATEST_SPAWN_V = 1.1;
    private final double LATEST_JOINITEMS_V = 1.1;
    private final double LATEST_JOINMESSAGES_V = 1.0;

    public double getLatestConfigV() { return LATEST_CONFIG_V; }
    public double getLatestSpawnV() { return LATEST_SPAWN_V; }
    public double getLatestJoinItemsV() { return LATEST_JOINITEMS_V; }
    public double getLatestJoinMessagesV() { return LATEST_JOINMESSAGES_V; }

    @Override
    public void onEnable() {

        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new GlobalListener(this), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var commands = event.registrar();
            commands.register("plainbase", "", new PlainBaseCommand(this));

        });

        if (getConfig().getBoolean("modules.spawn", true)) {
            setupSpawn();
        }
        if (getConfig().getBoolean("modules.joinitems", true)) {
            setupJoinItems();
        }
        if (getConfig().getBoolean("modules.joinmessages", true)) {
            setupJoinMessages();
        }

        checkAllConfigVersions();

        getLogger().info("Successfully Enabled!");
    }


    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    private void checkAllConfigVersions() {
        checkVersion("config.yml", getConfig().getDouble("version", 0.0), LATEST_CONFIG_V);

        checkVersion("spawn.yml", getSpawnConfig().getDouble("version", 0.0), LATEST_SPAWN_V);

        checkVersion("joinitems.yml", getJoinItemsConfig().getDouble("version", 0.0), LATEST_JOINITEMS_V);

        checkVersion("joinmessages.yml", getJoinMessagesConfig().getDouble("version", 0.0), LATEST_JOINMESSAGES_V);
    }

    private void checkVersion(String fileName, double currentV, double latestV) {
        if (currentV < latestV) {
            getLogger().warning("!!! OUTDATED CONFIG: " + fileName + " !!!");
            getLogger().warning("Your version: " + currentV + " | Required: " + latestV);
            getLogger().warning("Please check GitHub for the latest version and update your file.");
        }
    }

    public void setupSpawn() {
        // create Spawn Config
        spawnFile = new File(getDataFolder(), "spawn.yml");
        if (!spawnFile.exists()) {
            spawnFile.getParentFile().mkdirs();
            saveResource("spawn.yml", false);
        }
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(
                new java.io.FileInputStream(spawnFile), java.nio.charset.StandardCharsets.UTF_8)) {
            spawnConfig = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            getLogger().severe("Could not load spawn.yml in UTF-8!");
        }


        // register Listeners
        getServer().getPluginManager().registerEvents(new SpawnListener(this), this);


        // register Commands
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var commands = event.registrar();

            commands.register("spawn", "Teleport to spawn", new Spawn(this));
            commands.register("setspawn", "Set the spawn location", new SetSpawn(this));
            commands.register("setfirstspawn", "Set the first spawn", new SetFirstSpawn(this));
            commands.register("disablespawn", "Disable spawn", new DisableSpawn(this));
            commands.register("disablefirstspawn", "Disable first spawn", new DisableFirstSpawn(this));
        });
    }

    public FileConfiguration getSpawnConfig() {
        return spawnConfig;
    }

    public void saveSpawnConfig() {
        try {
            spawnConfig.save(spawnFile);
        } catch (IOException e) {
            getLogger().severe("Could not save spawn.yml in UTF-8!");
        }
    }


    public void setupJoinItems() {
        // create JoinItems Config
        joinItemsFile = new File(getDataFolder(), "joinitems.yml");
        if (!joinItemsFile.exists()) {
            joinItemsFile.getParentFile().mkdirs();
            saveResource("joinitems.yml", false);
        }

        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(
                new java.io.FileInputStream(joinItemsFile), java.nio.charset.StandardCharsets.UTF_8)) {
            joinItemsConfig = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            getLogger().severe("Could not load joinitems.yml in UTF-8!");
        }

        // register Listeners
        getServer().getPluginManager().registerEvents(new JoinItemsListener(this), this);
    }

    public FileConfiguration getJoinItemsConfig() {
        return joinItemsConfig;
    }


    public void setupJoinMessages() {
        // create Join Messages Config
        joinMessagesFile = new File(getDataFolder(), "joinmessages.yml");
        if (!joinMessagesFile.exists()) {
            joinMessagesFile.getParentFile().mkdirs();
            saveResource("joinmessages.yml", false);
        }
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(
                new java.io.FileInputStream(joinMessagesFile), java.nio.charset.StandardCharsets.UTF_8)) {
            joinMessagesConfig = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            getLogger().severe("Could not load joinmessages.yml in UTF-8!");
        }


        // register Listeners


        // register Commands
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var commands = event.registrar();

        });
    }

    public FileConfiguration getJoinMessagesConfig() {
        return joinMessagesConfig;
    }


    public MiniMessage getMiniMessage() {
        return miniMessage;
    }
}
