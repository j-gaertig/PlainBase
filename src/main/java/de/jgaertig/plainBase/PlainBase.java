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
import de.jgaertig.plainBase.moderation.BanManager;
import de.jgaertig.plainBase.moderation.ModerationListener;
import de.jgaertig.plainBase.moderation.commands.*;
import de.jgaertig.plainBase.placeholder.PlaceholderBridge;
import de.jgaertig.plainBase.placeholder.PlainBaseExpansion;
import de.jgaertig.plainBase.team.TeamListener;
import de.jgaertig.plainBase.team.TeamManager;
import de.jgaertig.plainBase.team.commands.TeamCommand;
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
    private BanManager banManager;
    private TeamManager teamManager;
    private boolean placeholdersRegistered = false;

    private boolean commandsRegistered = false;

    @Override
    public void onEnable() {
        setupPermissions();

        saveDefaultConfig();

        latestVersions.put("config.yml", 1.6);
        latestVersions.put("spawn.yml", 1.2);
        latestVersions.put("joinitems.yml", 1.2);
        latestVersions.put("messages.yml", 1.1);
        latestVersions.put("teleport.yml", 1.0);
        latestVersions.put("vanish.yml", 1.1);
        latestVersions.put("menu.yml", 1.1);
        latestVersions.put("moderation.yml", 2.0);
        latestVersions.put("team.yml", 1.0);

        registerPlaceholderExpansion();

        // Register commands unconditionally, independent of which modules are
        // enabled at startup: the command implementations themselves guard on
        // their module being enabled. This way /vanish and /menu still work
        // when a module is enabled later via /plainbase toggle or config reload.
        if (!commandsRegistered) {
            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                var r = event.registrar();
                r.register("plainbase", new PlainBaseCommand(this));
                r.register("vanish", new VanishCommand(this));
                r.register("menu", new MenuCommand(this));
                r.register("team", new TeamCommand(this));

                r.register("ban", new BanCommand(this));
                r.register("tempban", new TempBanCommand(this));
                r.register("unban", new UnbanCommand(this));
                r.register("kick", new KickCommand(this));
                r.register("banlist", new BanListCommand(this));
                r.register("baninfo", new BanInfoCommand(this));
                r.register("banip", new IpBanCommand(this));
                r.register("unbanip", new UnbanIpCommand(this));
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

        // moderation module
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.moderation.admin", "PlainBase: Allows access to all permissions of the moderation module", PermissionDefault.OP)
        );

        getServer().getPluginManager().addPermission(
                new Permission("plainbase.moderation.ban", "PlainBase: Allows access to /ban", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.moderation.tempban", "PlainBase: Allows access to /tempban", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.moderation.unban", "PlainBase: Allows access to /unban", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.moderation.kick", "PlainBase: Allows access to /kick", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.moderation.banlist", "PlainBase: Allows access to /banlist", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.moderation.baninfo", "PlainBase: Allows access to /baninfo", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.moderation.notify", "PlainBase: Allows seeing ban/kick broadcasts when broadcast.staff-only is enabled", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.moderation.exempt", "PlainBase: Makes a player immune to /ban and /kick by non-admins", PermissionDefault.FALSE)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.moderation.banip", "PlainBase: Allows access to /banip", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.moderation.unbanip", "PlainBase: Allows access to /unbanip", PermissionDefault.OP)
        );

        // team module
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.team.admin", "PlainBase: Bypass — acts as team-admin on any team regardless of membership", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.team.invite", "PlainBase: Allows access to /team <team> invite", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.team.add", "PlainBase: Allows access to /team <team> add", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.team.kick", "PlainBase: Allows access to /team <team> kick", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.team.setrole", "PlainBase: Allows access to /team <team> setrole", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.team.request", "PlainBase: Allows access to /team <team> request", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.team.accept", "PlainBase: Allows access to /team accept", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.team.deny", "PlainBase: Allows access to /team deny (and /team <team> deny-request)", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.team.leave", "PlainBase: Allows access to /team leave", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.team.list", "PlainBase: Allows access to /team list", PermissionDefault.OP)
        );
        getServer().getPluginManager().addPermission(
                new Permission("plainbase.team.info", "PlainBase: Allows access to /team <team> info", PermissionDefault.OP)
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
        if (getConfig().getBoolean("modules.moderation", true)) setupModeration();
        if (getConfig().getBoolean("modules.team", true)) setupTeam();
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

        // Close any open menu inventories before the listeners are
        // unregistered: an open menu whose clicks are no longer cancelled
        // would let players take items out of the GUI (duplication/exploit).
        if (menuManager != null) {
            menuManager.closeAllMenus();
        }
        menuManager = null;

        // Cancels the periodic cache-refresh task and closes the JDBC
        // connection pool cleanly instead of just dropping the reference.
        if (banManager != null) {
            banManager.shutdown();
        }
        banManager = null;

        // Unregisters the mirrored vanilla scoreboard teams so a disabled/reloaded
        // team module doesn't leave stale "pb_<id>" teams around.
        if (teamManager != null) {
            teamManager.shutdown();
        }
        teamManager = null;

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
    }

    public void setupModeration() {
        loadModuleConfig("moderation.yml");

        try {
            banManager = new BanManager(this);
        } catch (java.sql.SQLException e) {
            getLogger().severe("Could not connect the moderation database (storage.type=" +
                    getModerationConfig().getString("storage.type", "sqlite") + "): " + e.getMessage());
            getLogger().severe("The moderation module is disabled until this is fixed and /plainbase reload is run.");
            banManager = null;
            return;
        }

        getServer().getPluginManager().registerEvents(new ModerationListener(this), this);
    }

    public void setupTeam() {
        loadModuleConfig("team.yml");

        teamManager = new TeamManager(this);

        getServer().getPluginManager().registerEvents(new TeamListener(this), this);

        // Re-sync scoreboard entries for already-online players after a reload
        // (their teams were just re-loaded from disk into a fresh TeamManager).
        // No invite reminders here — they don't need to be re-notified on reload.
        for (Player player : Bukkit.getOnlinePlayers()) {
            teamManager.resyncScoreboard(player);
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

    public BanManager getBanManager() {
        return banManager;
    }

    public FileConfiguration getModerationConfig() {
        return configs.get("moderation.yml");
    }

    public TeamManager getTeamManager() {
        return teamManager;
    }

    public FileConfiguration getTeamConfig() {
        return configs.get("team.yml");
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