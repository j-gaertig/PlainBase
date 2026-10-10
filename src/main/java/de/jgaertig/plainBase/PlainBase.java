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
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PlainBase extends JavaPlugin {

    // ConcurrentHashMap: configs are read off-thread (BanManager refresh task,
    // RTPManager/TPAManager async callbacks, VanishManager/TeamManager scheduler
    // hops) while stopModules()/loadModuleConfig() mutate the map on the main
    // thread. A plain HashMap would risk visibility issues and
    // ConcurrentModificationExceptions during iteration (GlobalListener,
    // checkAllConfigVersions).
    private final Map<String, FileConfiguration> configs = new ConcurrentHashMap<>();
    private final Map<String, Double> latestVersions = new HashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    // Volatile: read off-thread (VanishManager/TeamManager scheduler hops,
    // PlaceholderAPI expansion, Brigadier suggestions) while reloadModules()
    // swaps them on the global region thread.
    private volatile BroadcastManager broadcastManager;
    private volatile TPAManager tpaManager;
    private volatile RTPManager rtpManager;
    private volatile VanishManager vanishManager;
    private volatile MenuManager menuManager;
    private volatile BanManager banManager;
    private volatile TeamManager teamManager;
    private boolean placeholdersRegistered = false;
    private GlobalListener globalListener;

    private boolean commandsRegistered = false;

    /**
     * Permission nodes actually registered by THIS plugin instance (see
     * {@link #addPermissionIfMissing(Permission)}). {@link #removePermissions()}
     * only ever removes these: a node that already existed (other plugin,
     * permissions manager, plugin.yml) is foreign-owned and must survive our
     * onDisable.
     */
    private final Set<String> ownedPermissions = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        setupPermissions();

        saveDefaultConfig();

        latestVersions.put("config.yml", 1.7);
        latestVersions.put("spawn.yml", 1.2);
        latestVersions.put("joinitems.yml", 1.2);
        latestVersions.put("messages.yml", 1.1);
        latestVersions.put("teleport.yml", 1.0);
        latestVersions.put("vanish.yml", 1.1);
        latestVersions.put("menu.yml", 1.1);
        latestVersions.put("moderation.yml", 2.0);
        latestVersions.put("team.yml", 1.2);

        registerPlaceholderExpansion();

        // Register ALL commands unconditionally at startup, independent of which
        // modules are enabled: the command implementations themselves guard on
        // their module being enabled. This way /vanish, /menu, /spawn, /tpa etc.
        // still work when a module is enabled later via /plainbase toggle or reload.
        // setupSpawn()/setupTeleport() only register listeners and managers.
        // Guarded: a blind re-register would throw on the duplicate command
        // literals. The flag is set immediately after registering (before
        // reloadModules(), which must never be able to re-arm it by throwing),
        // so a second onEnable() on the same instance can never register
        // twice. A fresh instance (the normal reload path) starts with false
        // via the field initializer.
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

                r.register("spawn", new Spawn(this));
                r.register("setspawn", new SetSpawn(this));
                r.register("setfirstspawn", new SetFirstSpawn(this));
                r.register("disablespawn", new DisableSpawn(this));
                r.register("disablefirstspawn", new DisableFirstSpawn(this));

                r.register("tpa", new TPACommand(this));
                r.register("tpaccept", new TPACCEPTCommand(this));
                r.register("tpahere", new TPAHERECommand(this));
                r.register("tpauto", new TPAUTOCommand(this));
                r.register("tpdeny", new TPDENYCommand(this));
                r.register("tpacancel", new TPACANCELCommand(this));

                r.register("rtp", new RTPCommand(this));
            });
            commandsRegistered = true;
        }

        reloadModules();

        getLogger().info("Successfully Enabled!");
    }

    @Override
    public void onDisable() {
        // Always reveal vanished players on shutdown, even with
        // persist-on-rejoin=true (persist only covers rejoins, not shutdown —
        // otherwise players stay hidden with no manager left to unvanish them).
        if (vanishManager != null) {
            try {
                vanishManager.resetAll();
            } catch (Exception e) {
                getLogger().fine("Failed to reset vanish state on disable: " + e.getMessage());
            }
            vanishManager = null;
        }

        stopModules();

        if (placeholdersRegistered) {
            try {
                new PlainBaseExpansion(this).unregister();
            } catch (Exception e) {
                getLogger().fine("Failed to unregister PlaceholderAPI expansion: " + e.getMessage());
            }
            placeholdersRegistered = false;
        }

        removePermissions();

        getLogger().info("Successfully Disabled!");
    }

    /**
     * Every permission node registered in {@link #setupPermissions()}.
     * Used by {@link #removePermissions()} so a server /reload or plugin
     * re-enable never leaks stale permission registrations into the next
     * lifecycle (Bukkit keeps permissions after onDisable unless removed).
     */
    private static final List<String> ALL_PERMISSIONS = List.of(
            "plainbase.admin",
            "plainbase.spawn.admin",
            "plainbase.spawn.spawn",
            "plainbase.spawn.setspawn",
            "plainbase.spawn.disablespawn",
            "plainbase.spawn.setfirstspawn",
            "plainbase.spawn.disablefirstspawn",
            "plainbase.teleport.admin",
            "plainbase.teleport.rtp.admin",
            "plainbase.teleport.rtp.rtp",
            "plainbase.teleport.tpa.admin",
            "plainbase.teleport.tpa.tpa",
            "plainbase.teleport.tpa.tpaccept",
            "plainbase.teleport.tpa.tpdeny",
            "plainbase.teleport.tpa.tpacancel",
            "plainbase.teleport.tpa.tpahere",
            "plainbase.teleport.tpa.tpauto",
            "plainbase.vanish.admin",
            "plainbase.vanish.vanish",
            "plainbase.vanish.vanish.other",
            "plainbase.vanish.world",
            "plainbase.vanish.all",
            "plainbase.vanish.see",
            "plainbase.menu.admin",
            "plainbase.menu.new",
            "plainbase.menu.delete",
            "plainbase.menu.open",
            "plainbase.menu.list",
            "plainbase.moderation.admin",
            "plainbase.moderation.ban",
            "plainbase.moderation.tempban",
            "plainbase.moderation.unban",
            "plainbase.moderation.kick",
            "plainbase.moderation.banlist",
            "plainbase.moderation.baninfo",
            "plainbase.moderation.notify",
            "plainbase.moderation.exempt",
            "plainbase.moderation.banip",
            "plainbase.moderation.unbanip",
            "plainbase.team.admin",
            "plainbase.team.invite",
            "plainbase.team.add",
            "plainbase.team.kick",
            "plainbase.team.setrole",
            "plainbase.team.request",
            "plainbase.team.accept",
            "plainbase.team.deny",
            "plainbase.team.reject",
            "plainbase.team.leave",
            "plainbase.team.list",
            "plainbase.team.info",
            "plainbase.team.invites",
            "plainbase.team.requests"
    );

    /**
     * Idempotent registration: a node that already exists (second onEnable
     * without a prior onDisable, or a permission defined by another
     * plugin/permissions manager) is left alone AND stays unowned — we only
     * ever remove nodes we created ourselves (see {@link #removePermissions()}).
     * A lost registration race is therefore also safe: the loser simply does
     * not claim ownership.
     */
    private void addPermissionIfMissing(Permission permission) {
        try {
            if (getServer().getPluginManager().getPermission(permission.getName()) != null) {
                return;
            }
            getServer().getPluginManager().addPermission(permission);
            ownedPermissions.add(permission.getName());
        } catch (IllegalArgumentException alreadyRegistered) {
            // Raced with another registrar — the node exists now, but we did
            // not create it, so it stays unowned and is never removed by us.
            getLogger().fine("Permission already registered: " + permission.getName());
        }
    }

    /**
     * Removes only the nodes this instance actually registered (tracked in
     * {@link #ownedPermissions}), so a server /reload or plugin re-enable
     * starts from a clean slate without ever deleting a foreign same-named
     * node: Bukkit permissions have no owner field, so a name collision with
     * another plugin is otherwise indistinguishable — and the other plugin's
     * node would silently vanish on our onDisable. Nodes we skipped because
     * they already existed are deliberately left untouched.
     * Deliberately NOT called from stopModules(): a /plainbase reload or
     * module toggle must keep the nodes while managers are rebuilt —
     * setupPermissions() only runs in onEnable().
     */
    private void removePermissions() {
        for (String name : Set.copyOf(ownedPermissions)) {
            try {
                if (getServer().getPluginManager().getPermission(name) == null) {
                    ownedPermissions.remove(name);
                    continue;
                }
                getServer().getPluginManager().removePermission(name);
                ownedPermissions.remove(name);
            } catch (Exception e) {
                getLogger().fine("Could not remove permission " + name + ": " + e.getMessage());
            }
        }
    }

    private void setupPermissions() {
        // General
        addPermissionIfMissing(
                new Permission("plainbase.admin", "PlainBase: Allows access to all permissions", PermissionDefault.OP)
        );

        // spawn module
        addPermissionIfMissing(
                new Permission("plainbase.spawn.admin", "PlainBase: Allows access to all permissions of the spawn module", PermissionDefault.OP)
        );

        addPermissionIfMissing(
                new Permission("plainbase.spawn.spawn", "PlainBase: Allows access to /spawn", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.spawn.setspawn", "PlainBase: Allows access to /setspawn", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.spawn.disablespawn", "PlainBase: Allows access to /disablespawn", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.spawn.setfirstspawn", "PlainBase: Allows access to /setfirstspawn", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.spawn.disablefirstspawn", "PlainBase: Allows access to /disablefirstspawn", PermissionDefault.OP)
        );

        // teleport module
        addPermissionIfMissing(
                new Permission("plainbase.teleport.admin", "PlainBase: Allows access to all permissions of the teleport module", PermissionDefault.OP)
        );

        addPermissionIfMissing(
                new Permission("plainbase.teleport.rtp.admin", "PlainBase: Allows access to all permissions of rtp of the teleport module", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.teleport.rtp.rtp", "PlainBase: Allows access to /rtp", PermissionDefault.TRUE)
        );

        addPermissionIfMissing(
                new Permission("plainbase.teleport.tpa.admin", "PlainBase: Allows access to all permissions of tpa of the teleport module", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.teleport.tpa.tpa", "PlainBase: Allows access to /tpa", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.teleport.tpa.tpaccept", "PlainBase: Allows access to /tpaccept", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.teleport.tpa.tpdeny", "PlainBase: Allows access to /tpdeny", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.teleport.tpa.tpacancel", "PlainBase: Allows access to /tpacancel", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.teleport.tpa.tpahere", "PlainBase: Allows access to /tpahere", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.teleport.tpa.tpauto", "PlainBase: Allows access to /tpauto", PermissionDefault.TRUE)
        );

        // vanish module
        addPermissionIfMissing(
                new Permission("plainbase.vanish.admin", "PlainBase: Allows access to all permissions of the vanish module", PermissionDefault.OP)
        );

        addPermissionIfMissing(
                new Permission("plainbase.vanish.vanish", "PlainBase: Allows access to /vanish", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.vanish.vanish.other", "PlainBase: Allows access to /vanish <player>", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.vanish.world", "PlainBase: Allows access to /vanish world", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.vanish.all", "PlainBase: Allows access to /vanish all", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.vanish.see", "PlainBase: Allows to see vanished players", PermissionDefault.OP)
        );

        // menu module
        addPermissionIfMissing(
                new Permission("plainbase.menu.admin", "PlainBase: Allows access to all permissions of the menu module", PermissionDefault.OP)
        );

        addPermissionIfMissing(
                new Permission("plainbase.menu.new", "PlainBase: Allows access to /menu new", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.menu.delete", "PlainBase: Allows access to /menu delete", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.menu.open", "PlainBase: Allows access to /menu open", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.menu.list", "PlainBase: Allows access to /menu list", PermissionDefault.TRUE)
        );

        // moderation module
        addPermissionIfMissing(
                new Permission("plainbase.moderation.admin", "PlainBase: Allows access to all permissions of the moderation module", PermissionDefault.OP)
        );

        addPermissionIfMissing(
                new Permission("plainbase.moderation.ban", "PlainBase: Allows access to /ban", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.moderation.tempban", "PlainBase: Allows access to /tempban", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.moderation.unban", "PlainBase: Allows access to /unban", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.moderation.kick", "PlainBase: Allows access to /kick", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.moderation.banlist", "PlainBase: Allows access to /banlist", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.moderation.baninfo", "PlainBase: Allows access to /baninfo", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.moderation.notify", "PlainBase: Allows seeing ban/kick broadcasts when broadcast.staff-only is enabled", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.moderation.exempt", "PlainBase: Makes a player immune to /ban and /kick by non-admins", PermissionDefault.FALSE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.moderation.banip", "PlainBase: Allows access to /banip", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.moderation.unbanip", "PlainBase: Allows access to /unbanip", PermissionDefault.OP)
        );

        // team module
        addPermissionIfMissing(
                new Permission("plainbase.team.admin", "PlainBase: Bypass — acts as team-admin on any team regardless of membership", PermissionDefault.OP)
        );
        addPermissionIfMissing(
                new Permission("plainbase.team.invite", "PlainBase: Allows access to /team <team> invite (team admins only)", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.team.add", "PlainBase: Allows access to /team <team> add (team admins only)", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.team.kick", "PlainBase: Allows access to /team <team> kick (team admins only)", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.team.setrole", "PlainBase: Allows access to /team <team> setrole (team admins only)", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.team.request", "PlainBase: Allows access to /team <team> request", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.team.accept", "PlainBase: Allows access to /team accept", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.team.deny", "PlainBase: Allows access to /team deny", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.team.reject", "PlainBase: Allows access to /team reject (team admins only)", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.team.leave", "PlainBase: Allows access to /team leave", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.team.list", "PlainBase: Allows access to /team list", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.team.info", "PlainBase: Allows access to /team info", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.team.invites", "PlainBase: Allows access to /team invites (list your own pending invites)", PermissionDefault.TRUE)
        );
        addPermissionIfMissing(
                new Permission("plainbase.team.requests", "PlainBase: Allows access to /team requests (team admins only)", PermissionDefault.TRUE)
        );
    }

    /**
     * Must run on the global region thread: unregisters listeners, touches
     * Bukkit state and does config disk I/O. Command call sites hop via
     * Bukkit.getGlobalRegionScheduler() before calling this.
     */
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

        ensureGlobalListener();

        // Warning-only version check, also on every /plainbase reload/toggle:
        // an outdated module config after an update must be noticed even when
        // the server was never restarted. (onEnable() reaches this via
        // reloadModules(), so no separate call there.)
        checkAllConfigVersions();
    }

    /**
     * Registers the global listener exactly once. After {@link #stopModules()}
     * unregisters all listeners, the reference is cleared so the next
     * {@link #reloadModules()} re-registers it (e.g. after /plainbase reload).
     */
    private void ensureGlobalListener() {
        if (globalListener == null) {
            globalListener = new GlobalListener(this);
            getServer().getPluginManager().registerEvents(globalListener, this);
        }
    }

    public void stopModules() {
        // Synchronously flush pending async config writes first: async tasks
        // may be cancelled on disable/reload and their changes would be lost.
        try {
            saveSpawnConfig();
        } catch (Exception e) {
            getLogger().fine("Failed to flush spawn.yml on shutdown: " + e.getMessage());
        }
        try {
            saveMenuConfig();
        } catch (Exception e) {
            getLogger().fine("Failed to flush menu.yml on shutdown: " + e.getMessage());
        }

        // Cache the vanish config BEFORE configs.clear() below: after clearing,
        // getVanishConfig() returns null and the persist check would NPE.
        FileConfiguration vanishConfig = getVanishConfig();
        boolean vanishEnabled = getConfig().getBoolean("modules.vanish", true);
        boolean persist = vanishConfig != null && vanishConfig.getBoolean("vanish.persist-on-rejoin", true);

        if (broadcastManager != null) {
            broadcastManager.stopBroadcasts();
            broadcastManager = null;
        }

        // Reveal everyone when the vanish module is switched off, or when
        // persist-on-rejoin is disabled (reload must not keep anyone hidden).
        // A plain reload with persist enabled keeps vanished players hidden
        // and setupVanish() re-applies their state.
        if (vanishManager != null && (!vanishEnabled || !persist)) {
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

        // Pending TPA/RTP warmups, searches and request timeouts must not
        // survive a reload or module toggle-off.
        if (tpaManager != null) {
            try {
                tpaManager.cancelAll();
            } catch (Exception e) {
                getLogger().warning("Failed to cancel TPA tasks during shutdown: " + e.getMessage());
            }
        }
        tpaManager = null;
        if (rtpManager != null) {
            try {
                rtpManager.cancelAll();
            } catch (Exception e) {
                getLogger().warning("Failed to cancel RTP tasks during shutdown: " + e.getMessage());
            }
        }
        rtpManager = null;

        // Vanish config is cached locally above, so unregistering first is safe.
        org.bukkit.event.HandlerList.unregisterAll(this);
        // ConcurrentHashMap: clear() is safe against concurrent off-thread
        // reads (BanManager refresh, scheduler callbacks). Iterate a snapshot
        // copy anywhere we traverse the map so a concurrent clear()/put()
        // can never throw ConcurrentModificationException mid-iteration.
        configs.clear();
        // The unregister above also removed the global listener: drop the
        // reference so reloadModules() re-registers it via ensureGlobalListener().
        globalListener = null;
    }

    public FileConfiguration loadModuleConfig(String fileName) {
        File file = new File(getDataFolder(), "modules/" + fileName);
        if (!file.exists()) {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                getLogger().warning("Could not create directory: " + parent);
            }
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

        // Snapshot copy: a concurrent stopModules() -> clear() on another
        // thread must never break this traversal.
        new HashMap<>(configs).forEach((name, config) -> {
            Double latest = latestVersions.get(name);
            if (latest != null && config != null) {
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
        FileConfiguration spawnConfig = loadModuleConfig("spawn.yml");
        if (spawnConfig == null) {
            getLogger().severe("Could not load spawn.yml! The spawn module stays disabled until this is fixed.");
            return;
        }
        getServer().getPluginManager().registerEvents(new SpawnListener(this), this);
    }

    /**
     * Synchronous persist. Only for shutdown/fallback paths — never call this
     * from a command or region thread (it blocks on disk I/O). That path must
     * use {@link #saveSpawnConfigAsync()} instead. Never throws.
     */
    public void saveSpawnConfig() {
        FileConfiguration config = getSpawnConfig();
        if (config == null) return;
        synchronized (config) {
            try {
                config.save(new File(getDataFolder(), "modules/spawn.yml"));
            } catch (IOException e) {
                getLogger().severe("Could not save spawn.yml!");
            }
        }
    }

    /**
     * Folia-safe persist for command/region threads (/setspawn and friends):
     * snapshots the config in memory (no disk I/O on the calling thread) and
     * writes the bytes on the async scheduler. Failures are logged; the caller
     * keeps its optimistic success message (a write failure here is a broken
     * disk, not a user error). Never throws.
     */
    public void saveSpawnConfigAsync() {
        saveModuleConfigAsync("spawn.yml", getSpawnConfig());
    }

    public void setupJoinItems() {
        FileConfiguration joinItemsConfig = loadModuleConfig("joinitems.yml");
        if (joinItemsConfig == null) {
            getLogger().severe("Could not load joinitems.yml! The joinitems module stays disabled until this is fixed.");
            return;
        }
        getServer().getPluginManager().registerEvents(new JoinItemsListener(this), this);
    }

    public void setupMessages() {
        FileConfiguration messagesConfig = loadModuleConfig("messages.yml");
        if (messagesConfig == null) {
            getLogger().severe("Could not load messages.yml! The messages module stays disabled until this is fixed.");
            return;
        }
        getServer().getPluginManager().registerEvents(new MessagesListener(this), this);

        broadcastManager = new BroadcastManager(this);
        broadcastManager.startBroadcasts();
    }

    public void setupTeleport() {
        FileConfiguration teleportConfig = loadModuleConfig("teleport.yml");
        if (teleportConfig == null) {
            getLogger().severe("Could not load teleport.yml! The teleport module stays disabled until this is fixed.");
            return;
        }

        tpaManager = new TPAManager(this);
        rtpManager = new RTPManager(this);

        getServer().getPluginManager().registerEvents(new TeleportListener(this), this);
    }

    public void setupVanish() {
        // Carry over the old in-memory vanish set across reloads: stopModules()
        // keeps vanished players hidden when persist-on-rejoin is enabled, so
        // the fresh manager must know them again (no vanish leak window until
        // the async per-player persist load catches up). Offline players are
        // covered by their persisted player-data files via applyOnJoin().
        // No bulk copyFrom by design: restoreVanishState() re-applies via
        // vanish() so hide effects are preserved (resetAll did not run here).
        Set<java.util.UUID> previousVanished = vanishManager != null
                ? vanishManager.getVanishedPlayers() : Set.of();

        FileConfiguration vanishCfg = loadModuleConfig("vanish.yml");
        if (vanishCfg == null) {
            getLogger().severe("Could not load vanish.yml! The vanish module stays disabled until this is fixed.");
            return;
        }

        vanishManager = new VanishManager(this);
        restoreVanishState(previousVanished);

        getServer().getPluginManager().registerEvents(new VanishListener(this), this);

        // Re-apply persisted vanish state for already-online players after a reload
        for (Player player : Bukkit.getOnlinePlayers()) {
            vanishManager.applyOnJoin(player);
        }
    }

    /**
     * Re-applies a previously captured vanish set to the fresh manager using
     * only the existing VanishManager API (getVanishedPlayers/vanish). Online
     * players are re-vanished directly; offline ones re-vanish on join via
     * their persisted files. Never throws.
     */
    private void restoreVanishState(Set<java.util.UUID> previousVanished) {
        if (previousVanished == null || previousVanished.isEmpty() || vanishManager == null) return;
        for (java.util.UUID uuid : previousVanished) {
            try {
                if (vanishManager.isVanished(uuid)) continue;
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    vanishManager.vanish(player);
                }
            } catch (Exception e) {
                getLogger().fine("Failed to restore vanish state for " + uuid + ": " + e.getMessage());
            }
        }
    }

    public void setupMenu() {
        FileConfiguration menuCfg = loadModuleConfig("menu.yml");
        if (menuCfg == null) {
            getLogger().severe("Could not load menu.yml! The menu module stays disabled until this is fixed.");
            return;
        }

        menuManager = new MenuManager(this);
        menuManager.reloadMenus();

        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
    }

    public void setupModeration() {
        FileConfiguration moderationCfg = loadModuleConfig("moderation.yml");
        if (moderationCfg == null) {
            getLogger().severe("Could not load moderation.yml! The moderation module stays disabled until this is fixed.");
            return;
        }

        try {
            banManager = new BanManager(this);
        } catch (Exception e) {
            FileConfiguration moderationConfig = getModerationConfig();
            String storageType = moderationConfig != null ? moderationConfig.getString("storage.type", "sqlite") : "<unknown>";
            getLogger().severe("Could not connect the moderation database (storage.type=" +
                    storageType + "): " + e.getMessage());
            getLogger().severe("The moderation module is disabled until this is fixed and /plainbase reload is run.");
            banManager = null;
            return;
        }

        getServer().getPluginManager().registerEvents(new ModerationListener(this), this);
    }

    public void setupTeam() {
        FileConfiguration teamCfg = loadModuleConfig("team.yml");
        if (teamCfg == null) {
            getLogger().severe("Could not load team.yml! The team module stays disabled until this is fixed.");
            return;
        }

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

    /**
     * Synchronous persist. Only for shutdown/fallback paths — never call this
     * from a command or region thread (it blocks on disk I/O). That path must
     * use {@link #saveMenuConfigAsync()} instead. Never throws.
     */
    public void saveMenuConfig() {
        FileConfiguration config = getMenuConfig();
        if (config == null) return;
        synchronized (config) {
            try {
                config.save(new File(getDataFolder(), "modules/menu.yml"));
            } catch (IOException e) {
                getLogger().severe("Could not save menu.yml!");
            }
        }
    }

    /**
     * Folia-safe persist for command/region threads (/menu new|delete):
     * snapshots the config in memory (no disk I/O on the calling thread) and
     * writes the bytes on the async scheduler. Failures are logged; the caller
     * keeps its optimistic success message (a write failure here is a broken
     * disk, not a user error). Never throws.
     */
    public void saveMenuConfigAsync() {
        saveModuleConfigAsync("menu.yml", getMenuConfig());
    }

    /**
     * Shared async writer for small module configs changed from commands.
     * {@code FileConfiguration#saveToString()} is memory-only, so the calling
     * (region) thread never touches the disk; the actual byte write runs
     * async. Synchronized on the config because two players on different
     * Folia regions could run the command concurrently. If the scheduler
     * rejects the task (shutdown), falls back to a best-effort synchronous
     * write. Never throws to the caller.
     */
    private void saveModuleConfigAsync(String fileName, FileConfiguration config) {
        if (config == null) {
            getLogger().warning("Cannot save " + fileName + ": config is not loaded.");
            return;
        }
        final String data;
        synchronized (config) {
            try {
                data = config.saveToString();
            } catch (Exception e) {
                getLogger().severe("Could not save " + fileName + "!");
                return;
            }
        }
        final File target = new File(getDataFolder(), "modules/" + fileName);
        Runnable write = () -> {
            try {
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    getLogger().warning("Could not create directory: " + parent);
                    return;
                }
                Files.writeString(target.toPath(), data, StandardCharsets.UTF_8);
            } catch (Exception e) {
                getLogger().severe("Could not save " + fileName + "!");
            }
        };
        try {
            Bukkit.getAsyncScheduler().runNow(this, task -> write.run());
        } catch (Exception e) {
            try {
                write.run();
            } catch (Exception ignored) {
            }
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