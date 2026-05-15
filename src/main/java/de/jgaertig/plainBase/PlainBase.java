package de.jgaertig.plainBase;

import de.jgaertig.plainBase.spawn.SpawnListener;
import de.jgaertig.plainBase.spawn.commands.DisableFirstSpawn;import de.jgaertig.plainBase.spawn.commands.DisableSpawn;
import de.jgaertig.plainBase.spawn.commands.SetFirstSpawn;
import de.jgaertig.plainBase.spawn.commands.SetSpawn;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class PlainBase extends JavaPlugin {

    private File spawnFile;
    private FileConfiguration spawnConfig;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Override
    public void onEnable() {

        saveDefaultConfig();

        if (getConfig().getBoolean("modules.spawn", true)) {
            createSpawnConfig();
            setupSpawn();
        }

        getLogger().info("[PlainBase] Successfully Enabled!");
    }


    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }


    private void createSpawnConfig() {
        spawnFile = new File(getDataFolder(), "spawn.yml");
        if (!spawnFile.exists()) {
            spawnFile.getParentFile().mkdirs();
            saveResource("spawn.yml", false);
        }
        spawnConfig = YamlConfiguration.loadConfiguration(spawnFile);
    }

    public FileConfiguration getSpawnConfig() {
        return spawnConfig;
    }

    public void saveSpawnConfig() {
        try {
            spawnConfig.save(spawnFile);
        } catch (IOException e) {
            getLogger().severe("Konnte spawn.yml nicht speichern!");
        }
    }


    private void setupSpawn() {
        getCommand("setspawn").setExecutor(new SetSpawn(this));
        getCommand("setfirstspawn").setExecutor(new SetFirstSpawn(this));

        getCommand("disablespawn").setExecutor(new DisableSpawn(this));
        getCommand("disablefirstspawn").setExecutor(new DisableFirstSpawn(this));

        getServer().getPluginManager().registerEvents(new SpawnListener(this), this);
    }


    public MiniMessage getMiniMessage() {
        return miniMessage;
    }
}
