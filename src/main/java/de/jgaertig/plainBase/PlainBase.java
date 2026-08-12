package de.jgaertig.plainBase;

import de.jgaertig.plainBase.global.GlobalListener;
import de.jgaertig.plainBase.global.commands.PlainBaseCommand;
import de.jgaertig.plainBase.joinItems.JoinItemsListener;
import de.jgaertig.plainBase.messages.BroadcastManager;
import de.jgaertig.plainBase.messages.MessagesListener;
import de.jgaertig.plainBase.spawn.SpawnListener;
import de.jgaertig.plainBase.spawn.commands.*;
import de.jgaertig.plainBase.teleport.rtp.RTPManager;
import de.jgaertig.plainBase.teleport.rtp.commands.RTPCommand;
import de.jgaertig.plainBase.teleport.tpa.TPAManager;
import de.jgaertig.plainBase.teleport.TeleportListener;
import de.jgaertig.plainBase.teleport.tpa.commands.*;
import de.jgaertig.plainBase.menu.MenuListener;
import de.jgaertig.plainBase.menu.MenuManager;
import de.jgaertig.plainBase.menu.commands.MenuCommand;
import de.jgaertig.plainBase.placeholder.PlaceholderBridge;
import de.jgaertig.plainBase.placeholder.PlainBaseExpansion;
import de.jgaertig.plainBase.vanish.VanishListener;
import de.jgaertig.plainBase.vanish.VanishManager;
import de.jgaertig.plainBase.vanish.commands.VanishCommand;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
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
    private TPAManager tpaManager;
    private RTPManager rtpManager;
    private VanishManager vanishManager;
    private MenuManager menuManager;
    private boolean placeholdersRegistered = false;

    private boolean commandsRegistered = false;

    @Override
    public void onEnable() {
        setupPermissions();

        saveDefaultConfig();

        latestVersions.put("config.yml", 1.5);
        latestVersions.put("spawn.yml", 1.2);
        latestVersions.put("joinitems.yml", 1.1);
        latestVersions.put("messages.yml", 1.0);
        latestVersions.put("teleport.yml", 1.0);
        latestVersions.put("vanish.yml", 1.1);
        latestVersions.put("menu.yml", 1.0);

        registerPlaceholderExpansion();

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

    private void setupPermissions() {
        // General
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.admin", "PlainBase: Allows access to all permissions", PermissionDefault.OP)
        );

        // spawn module
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.spawn.admin", "PlainBase: Allows access to all permissions of the spawn module", PermissionDefault.OP)
        );

        getServer().getPluginManager().addPermission(
                new Permission("plainbase.spawn.spawn", "PlainBase: Allows access to /spawn", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.spawn.setspawn", "PlainBase: Allows access to /setspawn", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.spawn.disablespawn", "PlainBase: Allows access to /disablespawn", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.spawn.setfirstspawn", "PlainBase: Allows access to /setfirstspawn", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.spawn.disablefirstspawn", "PlainBase: Allows access to /disablefirstspawn", PermissionDefault.OP)
        );

        // teleport module
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.teleport.admin", "PlainBase: Allows access to all permissions of the teleport module", PermissionDefault.OP)
        );

        getServer().getPluginManager().addPermission(
                new Permission("plainbase.teleport.rtp.admin", "PlainBase: Allows access to all permissions of rtp of the teleport module", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.teleport.rtp.rtp", "PlainBase: Allows access to /rtp", PermissionDefault.OP)
        );

        getServer().getPluginManager().addPermission(
                new Permission("plainbase.teleport.tpa.admin", "PlainBase: Allows access to all permissions of tpa of the teleport module", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.teleport.tpa.tpa", "PlainBase: Allows access to /tpa", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.teleport.tpa.tpaccept", "PlainBase: Allows access to /tpaccept", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.teleport.tpa.tpdeny", "PlainBase: Allows access to /tpdeny", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.teleport.tpa.tpacancel", "PlainBase: Allows access to /tpacancel", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.teleport.tpa.tpahere", "PlainBase: Allows access to /tpahere", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.teleport.tpa.tpauto", "PlainBase: Allows access to /tpauto", PermissionDefault.OP)
        );

        // vanish module
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.vanish.admin", "PlainBase: Allows access to all permissions of the vanish module", PermissionDefault.OP)
        );

        getServer().getPluginManager().addPermission(
                new Permission("plainbase.vanish.vanish", "PlainBase: Allows access to /vanish", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.vanish.vanish.other", "PlainBase: Allows access to /vanish <player>", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.vanish.world", "PlainBase: Allows access to /vanish world", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.vanish.all", "PlainBase: Allows access to /vanish all", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.vanish.see", "PlainBase: Allows to see vanished players", PermissionDefault.OP)
        );

        // menu module
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.menu.admin", "PlainBase: Allows access to all permissions of the menu module", PermissionDefault.OP)
        );

        getServer().getPluginManager().addPermission(
                new Permission("plainbase.menu.new", "PlainBase: Allows access to /menu new", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.menu.delete", "PlainBase: Allows access to /menu delete", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.menu.open", "PlainBase: Allows access to /menu open", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.menu.list", "PlainBase: Allows access to /menu list", PermissionDefault.OP)
        );
    }

    public void reloadModules() {
        stopModules();
        reloadConfig();

        if (getConfig().getBoolean("modules.spawn", true)) setupSpawn();
        if (getConfig().getBoolean("modules.joinitems", true)) setupJoinItems();
        if (getConfig().getBoolean("modules.messages", true)) setupMessages();
        if (getConfig().getBoolean("modules.teleport", true)) setupTeleport();
        if (getConfig().getBoolean("modules.vanish", true)) setupVanish();
        if (getConfig().getBoolean("modules.menu", true)) setupMenu();
    }

    public void stopModules() {
        if (broadcastManager != null) {
            broadcastManager.stopBroadcasts();
        }

        // Reveal everyone when the vanish module is switched off, or when
        // persist-on-rejoin is disabled (reload must not keep anyone hidden).
        // A plain reload with persist enabled keeps vanished players hidden
        // and setupVanish() re-applies their state.
        if (vanishManager != null && (!getConfig().getBoolean("modules.vanish", true)
                || !getVanishConfig().getBoolean("vanish.persist-on-rejoin", true))) {
            vanishManager.resetAll();
        }

        menuManager = null;
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

    public FileConfiguration getTeleportConfig() {
        return configs.get("teleport.yml");
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

    public void setupTeleport() {
        loadModuleConfig("teleport.yml");

        tpaManager = new TPAManager(this);
        rtpManager = new RTPManager(this);

        getServer().getPluginManager().registerEvents(new TeleportListener(this), this);

        if (!commandsRegistered) {
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                var r = event.registrar();
                r.register("tpa", new TPACommand(this));
                r.register("tpaccept", new TPACCEPTCommand(this));
                r.register("tpahere", new TPAHERECommand(this));
                r.register("tpauto", new TPAUTOCommand(this));
                r.register("tpdeny", new TPDENYCommand(this));
                r.register("tpacancel", new TPACANCELCommand(this));

                r.register("rtp", new RTPCommand(this));
            });
        }
    }

    public void setupVanish() {
        loadModuleConfig("vanish.yml");

        vanishManager = new VanishManager(this);

        getServer().getPluginManager().registerEvents(new VanishListener(this), this);

        if (!commandsRegistered) {
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                var r = event.registrar();
                r.register("vanish", new VanishCommand(this));
            });
        }

        // Re-apply persisted vanish state for already-online players after a reload
        for (Player player : Bukkit.getOnlinePlayers()) {
            vanishManager.applyOnJoin(player);
        }
    }

    public void setupMenu() {
        loadModuleConfig("menu.yml");

        menuManager = new MenuManager(this);
        menuManager.reloadMenus();

        getServer().getPluginManager().registerEvents(new MenuListener(this), this);

        if (!commandsRegistered) {
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                var r = event.registrar();
                r.register("menu", new MenuCommand(this));
            });
        }
    }

    /**
     * Registers the %plainbase_*% PlaceholderAPI expansion when PlaceholderAPI
     * is present. Safe no-op otherwise (soft dependency).
     */
    private void registerPlaceholderExpansion() {
        if (placeholdersRegistered) return;
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) return;

        placeholdersRegistered = new PlainBaseExpansion(this).register();
        if (placeholdersRegistered) {
            getLogger().info("PlaceholderAPI detected — registered %plainbase_*% placeholders!");
        }
    }

    public TPAManager getTPAManager() {
        return tpaManager;
    }

    public RTPManager getRTPManager() {
        return rtpManager;
    }

    public VanishManager getVanishManager() {
        return vanishManager;
    }

    public FileConfiguration getVanishConfig() {
        return configs.get("vanish.yml");
    }

    public MenuManager getMenuManager() {
        return menuManager;
    }

    public FileConfiguration getMenuConfig() {
        return configs.get("menu.yml");
    }

    public void saveMenuConfig() {
        try {
            FileConfiguration config = getMenuConfig();
            if (config != null) {
                config.save(new File(getDataFolder(), "modules/menu.yml"));
            }
        } catch (IOException e) {
            getLogger().severe("Could not save menu.yml!");
        }
    }

    /**
     * Applies PlaceholderAPI placeholders to a string when PlaceholderAPI is
     * present. Also replaces %player% for backwards compatibility. Safe no-op
     * without PlaceholderAPI (soft dependency).
     */
    public String applyPlaceholders(Player player, String text) {
        return PlaceholderBridge.apply(player, text);
    }

    public MiniMessage getMiniMessage() {
        return miniMessage;
    }
}