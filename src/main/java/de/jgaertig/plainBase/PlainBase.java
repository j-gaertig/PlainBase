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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.Nullable;

public final class PlainBase extends JavaPlugin {

    public enum ModerationState { DISABLED, INITIALIZING, READY, FAILED }

    // ConcurrentHashMap: configs are read off-thread (BanManager refresh task,
    // RTPManager/TPAManager async callbacks, VanishManager/TeamManager scheduler
    // hops) while stopModules()/loadModuleConfig() mutate the map on the main
    // thread. A plain HashMap would risk visibility issues and
    // ConcurrentModificationExceptions during iteration (GlobalListener,
    // checkAllConfigVersions).
    private final Map<String, FileConfiguration> configs = new ConcurrentHashMap<>();
    // ConcurrentHashMap: read off-thread (checkAllConfigVersions on the region
    // thread, GlobalListener admin-join warnings) while onEnable() writes.
    // A plain HashMap would risk visibility issues across threads.
    // Values are Strings (not Doubles) so "1.10" stays distinct from "1.1":
    // a double would collapse both to 1.1 before the segment-wise compare.
    private final Map<String, String> latestVersions = new ConcurrentHashMap<>();
    /**
     * Central config lock: replaces all {@code synchronized(rootCfg)} /
     * {@code synchronized(config)} monitors for the root config read-modify
     * paths (toggle, suggest, GlobalListener, checkAllConfigVersions).
     * Synchronizing on the config instance itself is fragile (instance can be
     * swapped by reloadConfig()), a dedicated final monitor stays stable.
     */
    private final Object configLock = new Object();
    /** Per-file sequence counter to drop stale async writes (lost-update guard). */
    private final Map<String, AtomicLong> configSeq = new ConcurrentHashMap<>();
    /** Supersedes stale async reload continuations when multiple reloads overlap. */
    private final AtomicLong moduleReloadSeq = new AtomicLong();
    /** Per-file write locks so sync + async writers of the same file serialize. */
    private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();
    /** Pending async config writes, awaited with timeout in stopModules(). */
    private final List<CompletableFuture<Void>> pendingSaves =
            Collections.synchronizedList(new ArrayList<>());
    /**
     * Loop-breaker for global-thread hops (ensureGlobalThread/stopModules):
     * Bukkit.isPrimaryThread() does not reliably detect the Folia global
     * region thread, so a rescheduled call would otherwise re-detect
     * "off-thread" and reschedule forever. Set on the rescheduled task
     * before re-entering; a call that still looks off-thread with the guard
     * set proceeds with a warning instead of rescheduling again (max 1 hop).
     */
    private final ThreadLocal<Boolean> globalThreadHopGuard = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Object rootConfigReloadLock = new Object();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    // Volatile: read off-thread (VanishManager/TeamManager scheduler hops,
    // PlaceholderAPI expansion, Brigadier suggestions) while reloadModules()
    // swaps them on the global region thread.
    private volatile BroadcastManager broadcastManager;
    private volatile TPAManager tpaManager;
    private volatile RTPManager rtpManager;
    private volatile VanishManager vanishManager;
    /**
     * Vanish carry-over by value across reloads: stopModules() always drops
     * the manager instance and snapshots the vanished IDs here; setupVanish()
     * re-hides from this copy (take-and-clear). Never holds a live manager.
     */
    private volatile Set<java.util.UUID> carriedVanishedIds = Set.of();
    /**
     * BUG-1 carry-over: pre-vanish self states snapshotted alongside the IDs
     * so the fresh manager restores the ORIGINAL values instead of
     * snapshotting still-vanished flags on re-vanish. Take-and-clear with the IDs.
     */
    private volatile Map<java.util.UUID, VanishManager.PrevSelfState> carriedPrevSelfStates = Map.of();
    private volatile Set<java.util.UUID> carriedDirtyUnvanished = Set.of();
    private volatile Set<java.util.UUID> carriedDirtyVanished = Set.of();
    private volatile MenuManager menuManager;
    private volatile BanManager banManager;
    /** Kept registered across module reloads so moderation never has a listener gap. */
    private volatile ModerationListener moderationListener;
    private volatile ModerationState moderationState = ModerationState.DISABLED;
    /** Serializes reload transitions with the pre-login allow/deny snapshot. */
    private final Object moderationGateLock = new Object();
    private volatile CompletableFuture<Void> moderationSetupInFlight = CompletableFuture.completedFuture(null);
    /** Managers created by async moderation setup but not yet installed. */
    private final Object pendingModerationManagersLock = new Object();
    private final Set<BanManager> pendingModerationManagers =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean moderationShuttingDown;
    private volatile Map<String, FileConfiguration> preparedReloadConfigs = Map.of();
    private volatile Set<String> failedPreparedReloadConfigs = Set.of();
    private volatile TeamManager teamManager;
    private boolean placeholdersRegistered = false;
    private GlobalListener globalListener;
    // Kept so stopModules() can unregister the MenuListener explicitly LAST,
    // after all menus are confirmed closed (HandlerList.unregisterAll would
    // otherwise drop it together with every other listener).
    private MenuListener menuListener;

    private boolean commandsRegistered = false;

    /**
     * Permission nodes actually registered by THIS plugin instance (see
     * {@link #addPermissionIfMissing(Permission)}). {@link #removePermissions()}
     * only ever removes these: a node that already existed (other plugin,
     * permissions manager, plugin.yml) is foreign-owned and must survive our
     * onDisable.
     */
    private final Set<String> ownedPermissions = ConcurrentHashMap.newKeySet();

    /**
     * Central monitor for all root-config read-modify paths. Use this instead
     * of synchronizing on the config instance.
     */
    public Object getConfigLock() {
        return configLock;
    }

    private Object getFileLock(String fileName) {
        return fileLocks.computeIfAbsent(fileName, k -> new Object());
    }

    @Override
    public void onEnable() {
        synchronized (pendingModerationManagersLock) {
            moderationShuttingDown = false;
        }
        setupPermissions();

        saveDefaultConfig();
        // Initialize JavaPlugin's root-config field before commands become
        // available. Runtime reloads read it asynchronously below.
        reloadConfig();
        moderationState = isModuleEnabled("moderation")
                ? ModerationState.INITIALIZING : ModerationState.DISABLED;
        ensureModerationListener();

        latestVersions.put("config.yml", "1.7");
        latestVersions.put("spawn.yml", "1.2");
        latestVersions.put("joinitems.yml", "1.2");
        latestVersions.put("messages.yml", "1.1");
        latestVersions.put("teleport.yml", "1.0");
        latestVersions.put("vanish.yml", "1.1");
        latestVersions.put("menu.yml", "1.1");
        latestVersions.put("moderation.yml", "2.0");
        latestVersions.put("team.yml", "1.2");

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

        initializeModulesOnEnable();

        getLogger().info("Successfully Enabled! (Version " + getVersionSafe() + ")");
    }

    @Override
    public void onDisable() {
        // Bukkit API (resetAll, HandlerList, recalculatePermissions) must run
        // on the global region thread — same bounded hop as stopModules()
        // (global scheduler + 5s get, fallback best-effort). resetAll runs
        // only inside the hop, never off-thread before it.
        if (!isOnGlobalThread()) {
            runOnGlobalThreadBlocking("onDisable", this::onDisableOnGlobalThread);
            return;
        }
        onDisableOnGlobalThread();
    }

    private void onDisableOnGlobalThread() {
        moderationState = ModerationState.DISABLED;
        closePendingModerationManagers();
        // Always reveal vanished players on shutdown, even with
        // persist-on-rejoin=true (persist only covers rejoins, not shutdown —
        // otherwise players stay hidden with no manager left to unvanish them).
        if (vanishManager != null) {
            try {
                // Preserve the persisted vanished state before resetAll
                // invalidates queued saves and removes the in-memory entries.
                vanishManager.flushSync();
                vanishManager.resetAll();
            } catch (Exception e) {
                getLogger().fine("Failed to reset vanish state on disable: " + e.getMessage());
            }
            vanishManager = null;
        }

        stopModules();

        if (placeholdersRegistered) {
            try {
                PlainBaseExpansion.unregisterCached();
            } catch (Exception e) {
                getLogger().fine("Failed to unregister PlaceholderAPI expansion: " + e.getMessage());
            }
            placeholdersRegistered = false;
        }

        removePermissions();
        commandsRegistered = false;

        getLogger().info("Successfully Disabled! (Version " + getVersionSafe() + ")");
    }

    /**
     * P2: getPluginMeta() must never break enable/disable logging (e.g. mock
     * or half-torn-down server during tests/shutdown). Never throws.
     */
    private String getVersionSafe() {
        try {
            String version = getPluginMeta().getVersion();
            return version != null ? version : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Inventory of every permission node registered in
     * {@link #setupPermissions()} — reference documentation only. Deliberately
     * NOT used by {@link #removePermissions()}: that method must remove
     * strictly the nodes this instance created (tracked in
     * {@link #ownedPermissions}) so a server /reload or plugin re-enable
     * never leaks stale registrations (Bukkit keeps permissions after
     * onDisable unless removed) without ever deleting a foreign same-named
     * node. Kept so the full node list stays greppable in one place.
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
        // Bukkit API (getOnlinePlayers/recalculatePermissions) must run on
        // the global region thread — same bounded hop as stopModules().
        if (!isOnGlobalThread()) {
            runOnGlobalThreadBlocking("removePermissions", this::removePermissions);
            return;
        }
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
        // Bukkit Permissibles cache effective permissions: refresh online
        // players so removed nodes no longer resolve until re-registered.
        try {
            for (Player player : getServer().getOnlinePlayers()) {
                try {
                    player.recalculatePermissions();
                } catch (Exception e) {
                    getLogger().fine("Could not recalculate permissions for " + player.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            getLogger().fine("Could not recalculate permissions after removal: " + e.getMessage());
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
     * Thread-guard: these lifecycle/config paths must run on the global region
     * (or primary) thread. Off-thread callers are warned and rescheduled
     * instead of touching Bukkit state. Build-neutral via
     * {@link org.bukkit.Bukkit#isPrimaryThread()}, with a loop-breaker (max 1
     * reschedule): where isPrimaryThread() misses the Folia global region
     * thread, the rescheduled call proceeds with a warning instead of
     * rescheduling forever.
     */
    private boolean ensureGlobalThread(String what, Runnable reschedule) {
        if (isOnGlobalThread()) {
            if (Boolean.TRUE.equals(globalThreadHopGuard.get())) {
                getLogger().warning(what + " rescheduled call still looks off-thread, continuing anyway (loop-breaker).");
            }
            return true;
        }
        getLogger().warning(what + " called off-thread, rescheduling on the global region thread.");
        try {
            Bukkit.getGlobalRegionScheduler().run(this, t -> {
                if (!isEnabled()) return;
                globalThreadHopGuard.set(Boolean.TRUE);
                try {
                    reschedule.run();
                } catch (Exception e) {
                    getLogger().warning("Rescheduled " + what + " failed: " + e.getMessage());
                } finally {
                    globalThreadHopGuard.remove();
                }
            });
        } catch (Exception e) {
            getLogger().warning("Could not reschedule " + what + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * Build-neutral global-thread check: true when Bukkit.isPrimaryThread()
     * says so, when it cannot be determined (fail-open, previous behavior), or
     * when this thread already runs a rescheduled hop (loop-breaker for the
     * Folia global region thread).
     */
    private boolean isOnGlobalThread() {
        try {
            if (Bukkit.isPrimaryThread()) return true;
        } catch (Exception ignored) {
            return true;
        }
        return Boolean.TRUE.equals(globalThreadHopGuard.get());
    }

    /**
     * Blocking global-thread hop for disable/shutdown paths (same pattern as
     * {@link #stopModules(boolean)}): runs {@code work} on the global region
     * thread and waits at most 5s. If the scheduler rejects the hop (server
     * disabling), runs best-effort on the calling thread instead of dropping
     * the work. Never throws.
     */
    private void runOnGlobalThreadBlocking(String what, Runnable work) {
        if (isOnGlobalThread()) {
            try {
                work.run();
            } catch (Exception e) {
                getLogger().warning("Rescheduled " + what + " failed: " + e.getMessage());
            }
            return;
        }
        getLogger().warning(what + " called off-thread, rescheduling on the global region thread.");
        final CompletableFuture<Void> hopped = new CompletableFuture<>();
        try {
            Bukkit.getGlobalRegionScheduler().run(this, t -> {
                globalThreadHopGuard.set(Boolean.TRUE);
                try {
                    work.run();
                } catch (Exception e) {
                    getLogger().warning("Rescheduled " + what + " failed: " + e.getMessage());
                } finally {
                    globalThreadHopGuard.remove();
                    hopped.complete(null);
                }
            });
        } catch (Exception e) {
            getLogger().warning("Could not reschedule " + what + ", running best-effort off-thread: " + e.getMessage());
            try {
                work.run();
            } catch (Exception ex) {
                getLogger().warning("Best-effort " + what + " failed: " + ex.getMessage());
            }
            return;
        }
        try {
            hopped.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            getLogger().warning("Timed out waiting for " + what + " on the global region thread.");
        } catch (Exception e) {
            getLogger().fine("Interrupted waiting for " + what + ": " + e.getMessage());
        }
    }

    /**
     * Must run on the global region thread: unregisters listeners and touches
     * Bukkit state. Reload configuration reads run on the async scheduler.
     */
    public void reloadModules() {
        reloadModulesAsync();
    }

    /**
     * Installs startup listeners before onEnable returns. Runtime reloads use
     * async config preparation; the first enable keeps its synchronous setup
     * ordering so joins cannot arrive before module listeners are registered.
     */
    private void initializeModulesOnEnable() {
        moderationState = isModuleEnabled("moderation")
                ? ModerationState.INITIALIZING : ModerationState.DISABLED;
        if (!isOnGlobalThread()) {
            reloadModulesAsync(false);
            return;
        }
        moduleReloadSeq.incrementAndGet();
        stopModules(false);
        finishModuleReload().whenComplete((ignored, error) -> {
            if (error != null) {
                getLogger().log(java.util.logging.Level.WARNING,
                        "Module setup did not fully complete during enable", error);
            }
        });
    }

    /**
     * Reloads module configuration after async flushes from the previous
     * generation settle. Bukkit state transitions stay on the global region
     * thread, while that thread never waits for file I/O.
     */
    public CompletableFuture<Void> reloadModulesAsync() {
        return reloadModulesAsync(true);
    }

    private CompletableFuture<Void> reloadModulesAsync(boolean reloadRootConfig) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        final long reloadId;
        final ModerationState previousModerationState;
        // Close the moderation login gate at the reload request boundary,
        // before the async root-config read is even scheduled. Previously a
        // FAILED setup remained fail-open until the global-thread continuation
        // ran after reloadConfig(), leaving a window in which banned players
        // could pass AsyncPlayerPreLoginEvent. The persistent listener checks
        // while the root config itself may still be stale, covering reloads
        // that enable moderation as well as those already enabled.
        synchronized (moderationGateLock) {
            previousModerationState = moderationState;
            reloadId = moduleReloadSeq.incrementAndGet();
            // If moderation is currently disabled, keep logins unaffected
            // until the newly read root config actually enables it. If it is
            // enabled, close the gate before any asynchronous reload work.
            if (isModuleEnabled("moderation")) moderationState = ModerationState.INITIALIZING;
        }
        Runnable begin = () -> {
            try {
                if (!isEnabled() || moduleReloadSeq.get() != reloadId) {
                    result.complete(null);
                    return;
                }
                synchronized (moderationGateLock) {
                    moderationState = isModuleEnabled("moderation")
                            ? ModerationState.INITIALIZING : ModerationState.DISABLED;
                }
                stopModules(false);
                List<String> moduleFiles = enabledModuleConfigFiles();
                CompletableFuture<?>[] writes;
                synchronized (pendingSaves) {
                    writes = pendingSaves.toArray(CompletableFuture[]::new);
                }
                CompletableFuture.allOf(writes).whenComplete((ignored, flushError) -> {
                    prepareReloadConfigsAsync(moduleFiles).whenComplete((prepared, prepareError) -> {
                        try {
                            Bukkit.getGlobalRegionScheduler().run(this, task -> {
                                if (!isEnabled() || moduleReloadSeq.get() != reloadId) {
                                    result.complete(null);
                                    return;
                                }
                                if (prepareError == null && prepared != null) {
                                    preparedReloadConfigs = prepared.loaded();
                                    failedPreparedReloadConfigs = prepared.failed();
                                } else {
                                    preparedReloadConfigs = Map.of();
                                    failedPreparedReloadConfigs = Set.copyOf(moduleFiles);
                                    getLogger().warning("Could not prepare module configs during reload: "
                                            + (prepareError == null ? "unknown error" : prepareError.getMessage()));
                                }
                                globalThreadHopGuard.set(Boolean.TRUE);
                                try {
                                    finishModuleReload().whenComplete((setupIgnored, setupError) -> {
                                        if (setupError == null) result.complete(null);
                                        else result.completeExceptionally(setupError);
                                    });
                                } catch (Exception e) {
                                    getLogger().log(java.util.logging.Level.WARNING,
                                            "Failed to set up modules after reload", e);
                                    result.completeExceptionally(e);
                                } finally {
                                    globalThreadHopGuard.remove();
                                    preparedReloadConfigs = Map.of();
                                    failedPreparedReloadConfigs = Set.of();
                                }
                            });
                        } catch (Exception e) {
                            getLogger().warning("Could not schedule module setup after reload: " + e.getMessage());
                            result.completeExceptionally(e);
                        }
                    });
                });
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        };
        Runnable scheduleBeginOnGlobal = () -> {
            if (moduleReloadSeq.get() != reloadId) {
                result.complete(null);
                return;
            }
            if (isOnGlobalThread()) {
                globalThreadHopGuard.set(Boolean.TRUE);
                try {
                    begin.run();
                } finally {
                    globalThreadHopGuard.remove();
                }
                return;
            }
            try {
                Bukkit.getGlobalRegionScheduler().run(this, globalTask -> {
                    globalThreadHopGuard.set(Boolean.TRUE);
                    try {
                        begin.run();
                    } finally {
                        globalThreadHopGuard.remove();
                    }
                });
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        };
        if (!reloadRootConfig) {
            scheduleBeginOnGlobal.run();
        } else {
            try {
                // Root config reload reads YAML from disk, so do it before the
                // global-thread state transition. The shared root lock protects
                // PlainBase readers while reloadConfig() swaps the backing object.
                Bukkit.getAsyncScheduler().runNow(this, task -> {
                    try {
                        synchronized (moderationGateLock) {
                            synchronized (rootConfigReloadLock) {
                                if (moduleReloadSeq.get() == reloadId) reloadConfigForGeneration(reloadId);
                            }
                            if (moduleReloadSeq.get() == reloadId) {
                                moderationState = isModuleEnabled("moderation")
                                        ? ModerationState.INITIALIZING : ModerationState.DISABLED;
                            }
                        }
                    } catch (Exception e) {
                        getLogger().warning("Failed to reload config.yml, keeping previous config: " + e.getMessage());
                    }
                    scheduleBeginOnGlobal.run();
                });
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        }
        result.whenComplete((ignored, error) -> {
            if (error != null) settleModerationAfterReloadFailure(reloadId, previousModerationState);
        });
        return result;
    }

    private void settleModerationAfterReloadFailure(long reloadId, ModerationState previousState) {
        synchronized (moderationGateLock) {
            if (moduleReloadSeq.get() != reloadId || moderationState != ModerationState.INITIALIZING) return;
            if (!isEnabled() || !isModuleEnabled("moderation")) {
                moderationState = ModerationState.DISABLED;
            } else if (previousState == ModerationState.READY && banManager != null) {
                // Setup never reached the point of retiring the old manager,
                // so keep its working login checks after scheduling failure.
                moderationState = ModerationState.READY;
            } else {
                // Preserve fail-open behavior when no usable manager exists;
                // a scheduler rejection must not strand the listener in the
                // temporary fail-closed INITIALIZING state forever.
                moderationState = ModerationState.FAILED;
            }
        }
    }

    private List<String> enabledModuleConfigFiles() {
        Map<String, String> moduleFiles = Map.of(
                "spawn", "spawn.yml",
                "joinitems", "joinitems.yml",
                "messages", "messages.yml",
                "teleport", "teleport.yml",
                "vanish", "vanish.yml",
                "menu", "menu.yml",
                "moderation", "moderation.yml",
                "team", "team.yml");
        List<String> enabled = new ArrayList<>();
        moduleFiles.forEach((module, file) -> {
            if (isModuleEnabled(module)) enabled.add(file);
        });
        return List.copyOf(enabled);
    }

    private record PreparedModuleConfigs(Map<String, FileConfiguration> loaded, Set<String> failed) {}

    private CompletableFuture<PreparedModuleConfigs> prepareReloadConfigsAsync(List<String> moduleFiles) {
        CompletableFuture<PreparedModuleConfigs> prepared = new CompletableFuture<>();
        trackPendingSave(prepared.thenApply(ignored -> null));
        try {
            Bukkit.getAsyncScheduler().runNow(this, task -> {
                try {
                    Map<String, FileConfiguration> loaded = new HashMap<>();
                    Set<String> failed = new java.util.HashSet<>();
                    for (String file : moduleFiles) {
                        FileConfiguration config = loadModuleConfigFromDisk(file);
                        if (config == null) failed.add(file);
                        else loaded.put(file, config);
                    }
                    prepared.complete(new PreparedModuleConfigs(Map.copyOf(loaded), Set.copyOf(failed)));
                } catch (Exception e) {
                    prepared.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            prepared.completeExceptionally(e);
        }
        return prepared;
    }

    private CompletableFuture<Void> finishModuleReload() {
        // Each module is guarded so one broken module disables only itself.
        if (isModuleEnabled("spawn")) runModuleSetup("spawn", this::setupSpawn);
        if (isModuleEnabled("joinitems")) runModuleSetup("joinitems", this::setupJoinItems);
        if (isModuleEnabled("messages")) runModuleSetup("messages", this::setupMessages);
        if (isModuleEnabled("teleport")) runModuleSetup("teleport", this::setupTeleport);
        if (isModuleEnabled("vanish")) runModuleSetup("vanish", this::setupVanish);
        if (isModuleEnabled("menu")) runModuleSetup("menu", this::setupMenu);
        if (isModuleEnabled("moderation")) runModuleSetup("moderation", this::setupModeration);
        if (isModuleEnabled("team")) runModuleSetup("team", this::setupTeam);

        ensureGlobalListenerSafe();
        try {
            registerPlaceholderExpansion();
        } catch (Exception e) {
            getLogger().fine("Failed to register PlaceholderAPI expansion on reload: " + e.getMessage());
        }
        checkAllConfigVersionsSafe();
        return isModuleEnabled("moderation")
                ? moderationSetupInFlight
                : CompletableFuture.completedFuture(null);
    }

    /**
     * F6: tail-calls of reloadModules() must never kill the whole enable or
     * leave later modules unstarted when they throw (a broken listener
     * registration or a corrupt version value). Logged and skipped instead.
     */
    private void ensureGlobalListenerSafe() {
        try {
            ensureGlobalListener();
        } catch (Exception e) {
            getLogger().warning("Failed to register global listener: " + e.getMessage());
        }
    }

    /**
     * F6: see {@link #ensureGlobalListenerSafe()}.
     */
    private void checkAllConfigVersionsSafe() {
        try {
            checkAllConfigVersions();
        } catch (Exception e) {
            getLogger().warning("Failed to check config versions: " + e.getMessage());
        }
    }

    /**
     * Module flag with a missing-key warning: getBoolean(key, false) silently
     * assumes disabled when the key is absent (e.g. outdated config.yml after
     * an update), which hides misconfiguration. Logging only, no migration.
     */
    private boolean isModuleEnabled(String moduleName) {
        // Root-config read under the central configLock (same monitor as
        // toggle/suggest/GlobalListener/checkAllConfigVersions): the instance
        // is fetched INSIDE the lock since reloadConfig() may swap it.
        synchronized (configLock) {
            FileConfiguration rootCfg = getConfig();
            if (rootCfg == null || !rootCfg.contains("modules." + moduleName)) {
                getLogger().warning("Missing config key 'modules." + moduleName
                        + "' in config.yml, assuming disabled. Add it or regenerate config.yml.");
                return false;
            }
            return rootCfg.getBoolean("modules." + moduleName, false);
        }
    }

    /**
     * Runs one module setup in isolation: a throwing module is logged and
     * skipped, later modules still start. Never throws.
     */
    private void runModuleSetup(String moduleName, Runnable setup) {
        try {
            setup.run();
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Failed to enable the " + moduleName + " module", e);
        }
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

    /**
     * Tracks an async persistence future so {@link #awaitPendingSaves(boolean)} can
     * await it during reload/disable. Used by VanishManager/TPAManager
     * per-player saves (which historically ran untracked and could be lost to
     * a cancelled async task on shutdown). Never throws.
     */
    public void trackPendingSave(CompletableFuture<Void> future) {
        if (future == null) return;
        try {
            synchronized (pendingSaves) {
                pendingSaves.removeIf(CompletableFuture::isDone);
                pendingSaves.add(future);
            }
        } catch (Exception e) {
            getLogger().fine("Failed to track pending async save: " + e.getMessage());
        }
    }

    /**
     * Flush barrier for pending async config writes tracked in
     * {@link #pendingSaves}. Completed futures are removed. On timeout the
     * snapshot entries are abandoned (removeAll) so they can never leak
     * across reloads, and the abandon is logged. Never throws.
     *
     * @param block {@code true} on the disable path: waits bounded (5s) for
     *              the writes. {@code false} on the reload path: never stalls
     *              the global region thread — attaches an async cleanup and
     *              returns immediately while the writes finish in the
     *              background.
     */
    private void awaitPendingSaves(boolean block) {
        CompletableFuture<?>[] snapshot;
        synchronized (pendingSaves) {
            pendingSaves.removeIf(CompletableFuture::isDone);
            if (pendingSaves.isEmpty()) return;
            snapshot = pendingSaves.toArray(new CompletableFuture[0]);
        }
        if (!block) {
            CompletableFuture.allOf(snapshot).orTimeout(5, TimeUnit.SECONDS).whenComplete((v, t) -> {
                if (t != null && !isCancellation(t)) {
                    getLogger().warning("Async config writes failed during reload: " + t.getMessage());
                }
                synchronized (pendingSaves) {
                    pendingSaves.removeAll(java.util.Arrays.asList(snapshot));
                }
            });
            return;
        }
        try {
            CompletableFuture.allOf(snapshot).get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            getLogger().warning("Timed out waiting for " + snapshot.length
                    + " async config writes, abandoning them: " + e.getMessage());
            synchronized (pendingSaves) {
                pendingSaves.removeAll(java.util.Arrays.asList(snapshot));
            }
        } catch (Exception e) {
            getLogger().fine("Pending async config writes did not finish in time: " + e.getMessage());
        } finally {
            synchronized (pendingSaves) {
                pendingSaves.removeIf(CompletableFuture::isDone);
            }
        }
    }

    private static boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.util.concurrent.CancellationException) return true;
            current = current.getCause();
        }
        return false;
    }

    /**
     * Disable-path entry point: blocks (bounded) on pending async writes and
     * flushes module configs synchronously. Reload callers must use
     * {@link #stopModules(boolean)} with {@code false} instead so the global
     * region thread never stalls on disk I/O.
     */
    public void stopModules() {
        stopModules(true);
    }

    /**
     * @param block {@code true} on the disable path (bounded blocking wait +
     *              synchronous config flush), {@code false} on the reload path
     *              (async continuation, no disk I/O on the calling thread).
     */
    public void stopModules(boolean block) {
        // Off-thread hop (bounded, deadlock-free): the HandlerList/Bukkit
        // state below must run on the global region thread — never continue
        // off-thread after a warning. Reschedules once and waits at most 5s
        // (same budget as awaitPendingSaves), then returns. The hop sets the
        // loop-breaker guard so the re-entrant call proceeds even where
        // Bukkit.isPrimaryThread() misses the Folia global thread (no
        // self-wait deadlock: the re-entrant call never blocks). While
        // disabling the scheduler may reject the hop — then abort instead of
        // running Bukkit API off-thread.
        if (!isOnGlobalThread()) {
            getLogger().warning("stopModules called off-thread, rescheduling on the global region thread.");
            if (!block && !isEnabled()) {
                getLogger().warning("stopModules skipped: plugin is disabling, cannot hop threads safely.");
                return;
            }
            final CompletableFuture<Void> hopped = new CompletableFuture<>();
            try {
                Bukkit.getGlobalRegionScheduler().run(this, t -> {
                    if (!block && !isEnabled()) {
                        hopped.complete(null);
                        return;
                    }
                    globalThreadHopGuard.set(Boolean.TRUE);
                    try {
                        stopModules(block);
                    } catch (Exception e) {
                        getLogger().warning("Rescheduled stopModules failed: " + e.getMessage());
                    } finally {
                        globalThreadHopGuard.remove();
                        hopped.complete(null);
                    }
                });
            } catch (Exception e) {
                getLogger().warning("Could not reschedule stopModules, aborting off-thread run: " + e.getMessage());
                return;
            }
            try {
                hopped.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                getLogger().warning("Timed out waiting for stopModules on the global region thread.");
            } catch (Exception e) {
                getLogger().fine("Interrupted waiting for stopModules: " + e.getMessage());
            }
            return;
        }
        // Flush pending async writes first: async tasks may be cancelled on
        // disable/reload and their changes would be lost. Disable path waits
        // bounded; reload path continues async without blocking the global
        // region thread.
        awaitPendingSaves(block);
        VanishManager reloadFlushManager = null;
        FileConfiguration reloadVanishSnapshot = null;
        // Synchronous vanish flush on the disable path only: per-player
        // vanish saves run async and may have been cancelled on shutdown —
        // rewrite the authoritative in-memory vanish state directly so a kill
        // right after /vanish cannot lose it. Runs before the resetAll
        // handling below. Reload path (block==false): never stall the global
        // region thread on per-player file I/O — flush async and track the
        // future so a later disable still awaits it (bounded) via
        // awaitPendingSaves(true); the already-tracked per-player saves keep
        // running in the background meanwhile (Folia never cancels tasks on
        // reload, only on disable).
        if (vanishManager != null) {
            if (block) {
                // Bump the vanish-flush sequence so older async reload flushes
                // (scheduled before this sync flush) are treated as stale.
                configSeq.computeIfAbsent("vanish-flush", k -> new AtomicLong()).incrementAndGet();
                try {
                    vanishManager.flushSync();
                } catch (Exception e) {
                    getLogger().warning("Failed to flush vanish state on shutdown: " + e.getMessage());
                }
            } else {
                reloadFlushManager = vanishManager;
                // Snapshot BEFORE configs.clear() below: the async task runs
                // after clear() and must not read the live map (would see
                // null and fall back to the wrong persist default).
                reloadVanishSnapshot = getVanishConfig();
                if (reloadVanishSnapshot == null) {
                    getLogger().fine("Skipping async vanish flush on reload: vanish config not loaded.");
                }
            }
        }
        // Disable path: synchronous flush (shutdown runs off the tick path,
        // sync disk I/O is allowed there). Reload path: async variants so the
        // global region thread never blocks on disk I/O (the sync methods are
        // shutdown/fallback-only per their Javadoc).
        // Only spawn.yml and menu.yml are flushed here by design — everything
        // else stays async and may be dropped on a hard kill: vanish
        // per-player files (VanishManager), tpauto flags
        // (data/playerdata/<uuid>.yml via TPAManager) and the moderation
        // cache/database (BanManager.shutdown() closes the pool; unwritten
        // cache entries are lost).
        if (block) {
            try {
                saveSpawnConfig();
            } catch (Exception e) {
                getLogger().warning("Failed to flush spawn.yml on shutdown: " + e.getMessage());
            }
            try {
                saveMenuConfig();
            } catch (Exception e) {
                getLogger().warning("Failed to flush menu.yml on shutdown: " + e.getMessage());
            }
        } else {
            // Null-guarded: a disabled module has no config loaded — the
            // async writer would only log a "not loaded" warning for it.
            if (getSpawnConfig() != null) {
                try {
                    saveSpawnConfigAsync();
                } catch (Exception e) {
                    getLogger().warning("Failed to schedule async flush of spawn.yml on reload: " + e.getMessage());
                }
            }
            if (getMenuConfig() != null) {
                try {
                    saveMenuConfigAsync();
                } catch (Exception e) {
                    getLogger().warning("Failed to schedule async flush of menu.yml on reload: " + e.getMessage());
                }
            }
        }

        // Cache the vanish config BEFORE configs.clear() below: after clearing,
        // getVanishConfig() returns null and the persist check would NPE.
        // vanishEnabled reads the NEW main config: reloadModules() calls
        // reloadConfig() before stopModules() (F1), so a toggle-off already
        // sees modules.vanish=false here. onDisable() has no prior reload and
        // correctly sees the current flags.
        FileConfiguration vanishConfig = getVanishConfig();
        // Root-config read under the central configLock (same monitor as
        // toggle/suggest/GlobalListener/checkAllConfigVersions/isModuleEnabled):
        // reloadModules() reloads the root config just before this, and a
        // concurrent toggle must never interleave with this snapshot.
        // Lock order stays configLock-only here (no nested config monitor).
        boolean vanishEnabled;
        synchronized (configLock) {
            FileConfiguration rootCfg = getConfig();
            vanishEnabled = rootCfg != null && rootCfg.getBoolean("modules.vanish", false);
        }
        boolean persist = true;
        if (vanishConfig != null) {
            synchronized (vanishConfig) {
                persist = vanishConfig.getBoolean("vanish.persist-on-rejoin", true);
            }
        }

        if (broadcastManager != null) {
            try {
                broadcastManager.stopBroadcasts();
            } catch (Exception e) {
                getLogger().fine("Failed to stop broadcasts: " + e.getMessage());
            }
            broadcastManager = null;
        }

        // Reveal everyone when the vanish module is switched off, or when
        // persist-on-rejoin is disabled (reload must not keep anyone hidden).
        // A plain reload with persist enabled keeps vanished players hidden
        // and setupVanish() re-applies their state from the copied ID set.
        // F1: a throwing resetAll must not abort the rest of stopModules().
        // The manager reference is always dropped below (even when the module
        // stays enabled) — otherwise the stale manager survives the reload;
        // carry-over is by value via carriedVanishedIds, never a live instance.
        if (!block && vanishManager != null) {
            // Fence callbacks synchronously before reset/carry-over. Any
            // transition already inside the manager lock finishes first;
            // later callbacks cannot race the reset or the copied state.
            vanishManager.beginPersistenceFinalization();
        }
        if (vanishManager != null && (!vanishEnabled || !persist)) {
            try {
                vanishManager.prepareForRevealPersistence();
                vanishManager.resetAll(block);
            } catch (Exception e) {
                getLogger().warning("Failed to reset vanish state: " + e.getMessage());
            }
        }
        if (vanishManager != null) {
            try {
                carriedVanishedIds = Set.copyOf(vanishManager.getVanishedPlayers());
            } catch (Exception e) {
                getLogger().warning("Failed to snapshot vanish state, vanished players may be revealed: " + e.getMessage());
                carriedVanishedIds = Set.of();
            }
            // BUG-1: carry pre-vanish self states by value alongside the IDs.
            try {
                carriedPrevSelfStates = Map.copyOf(vanishManager.copyPrevSelfStates());
            } catch (Exception e) {
                getLogger().fine("Failed to snapshot vanish self states: " + e.getMessage());
                carriedPrevSelfStates = Map.of();
            }
            try {
                VanishManager.DirtyState dirtyState = vanishManager.copyDirtyState();
                Set<java.util.UUID> dirtyFalse = new java.util.HashSet<>(carriedDirtyUnvanished);
                dirtyFalse.addAll(dirtyState.unvanished());
                Set<java.util.UUID> dirtyTrue = new java.util.HashSet<>(carriedDirtyVanished);
                dirtyTrue.addAll(dirtyState.vanished());
                // The latest live transition owns the obligation if an earlier
                // overlapping reload left an opposite carried obligation.
                dirtyFalse.removeAll(dirtyState.vanished());
                dirtyTrue.removeAll(dirtyState.unvanished());
                carriedDirtyUnvanished = Set.copyOf(dirtyFalse);
                carriedDirtyVanished = Set.copyOf(dirtyTrue);
            } catch (Exception e) {
                getLogger().fine("Failed to snapshot pending vanish persistence state: " + e.getMessage());
            }
            if (!vanishEnabled || !persist) {
                // resetAll() has already revealed this manager's live state.
                // Do not retain carry-over from an earlier overlapping reload.
                carriedVanishedIds = Set.of();
                carriedPrevSelfStates = Map.of();
                carriedDirtyVanished = Set.of();
            }
            vanishManager = null;
        } else if (block || !vanishEnabled || !persist) {
            revealCarriedVanishState(block);
            carriedVanishedIds = Set.of();
            carriedPrevSelfStates = Map.of();
            carriedDirtyVanished = Set.of();
        }

        // Start disk work only after the old manager is fenced, reset if
        // required, and copied into the carry-over snapshots. The tracked
        // future keeps reloadModulesAsync() from installing the next manager
        // before this flush completes.
        if (!block && reloadFlushManager != null && reloadVanishSnapshot != null) {
            scheduleVanishReloadFlush(reloadFlushManager, reloadVanishSnapshot);
        }

        // Close any open menu inventories before the listeners are
        // unregistered: an open menu whose clicks are no longer cancelled
        // would let players take items out of the GUI (duplication/exploit).
        // Ordered close with a bounded wait, so the deferred entity-thread
        // closes are confirmed finished before the MenuListener is gone; the
        // MenuListener itself is unregistered explicitly LAST below.
        // Each step is isolated: a throwing manager must never abort the rest.
        if (menuManager != null) {
            try {
                if (block) {
                    // Disable path only: bounded synchronous wait (1000ms —
                    // the entity-thread closes usually land within a few ms,
                    // the rest is abandoned + logged, never stalling shutdown
                    // indefinitely). Kept sequential with awaitPendingSaves()
                    // above by design: total bounded shutdown budget ~= 5s
                    // saves + 1s menus, both abandoned with a warning instead
                    // of blocking forever.
                    menuManager.closeAllMenusSyncAwait(1000);
                } else {
                    // Reload path: never stall the global region thread with
                    // a .get() wait. Best-effort sync close first, then the
                    // deferred fire-and-forget entity-thread closes (Folia:
                    // inventory access is entity-thread-only, the sync pass
                    // throws there and the deferred pass covers it).
                    menuManager.closeAllMenusSyncBestEffort();
                    menuManager.closeAllMenus();
                }
            } catch (Exception e) {
                getLogger().warning("Failed to close menus: " + e.getMessage());
            }
        }
        menuManager = null;

        // Cancels the periodic cache-refresh task and closes the JDBC
        // connection pool cleanly instead of just dropping the reference.
        // Null-guarded: getModerationConfig() may already be null when the
        // module never started (BanManager.startPeriodicRefresh/shutdown must
        // tolerate a null config the same way).
        if (banManager != null) {
            try {
                banManager.shutdown(block);
            } catch (Exception e) {
                getLogger().warning("Failed to shut down ban manager: " + e.getMessage());
            }
        }
        banManager = null;

        // Unregisters the mirrored vanilla scoreboard teams so a disabled/reloaded
        // team module doesn't leave stale "pb_<id>" teams around.
        if (teamManager != null) {
            try {
                teamManager.shutdown();
            } catch (Exception e) {
                getLogger().warning("Failed to shut down team manager: " + e.getMessage());
            }
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

        // Scheduler-task note (no new API introduced): Folia cancels plugin
        // tasks automatically only on plugin disable — never on
        // reloadModules()/module toggle. Every manager with repeating or
        // long-lived tasks is therefore cancelled explicitly above
        // (stopBroadcasts, cancelAll x2, BanManager/TeamManager shutdown(),
        // open menus closed). What remains are bounded one-shot delayed/async
        // command callbacks (spawn teleports, join-item gives, async
        // moderation lookups), which expire on their own short timeout
        // instead of needing a central registry.
        // Vanish config is cached locally above, so unregistering first is safe.
        try {
            unregisterModuleListeners();
        } catch (Exception e) {
            getLogger().fine("Failed to unregister listeners: " + e.getMessage());
        }
        // MenuListener explicitly LAST: unregisterAll above already dropped it
        // together with the rest, but a concurrently re-registered instance
        // (menu recreated between close and unregister) must not survive with
        // an open-GUI exploit window — this second pass is a no-op otherwise.
        if (menuListener != null) {
            try {
                org.bukkit.event.HandlerList.unregisterAll(menuListener);
            } catch (Exception e) {
                getLogger().fine("Failed to unregister menu listener: " + e.getMessage());
            }
            menuListener = null;
        }
        // ConcurrentHashMap: clear() is safe against concurrent off-thread
        // reads (BanManager refresh, scheduler callbacks). Iterate a snapshot
        // copy anywhere we traverse the map so a concurrent clear()/put()
        // can never throw ConcurrentModificationException mid-iteration.
        try {
            configs.clear();
            preparedReloadConfigs = Map.of();
            failedPreparedReloadConfigs = Set.of();
        } catch (Exception e) {
            getLogger().fine("Failed to clear config cache: " + e.getMessage());
        }
        // The unregister above also removed the global listener: drop the
        // reference so reloadModules() re-registers it via ensureGlobalListener().
        globalListener = null;
    }

    /**
     * Unregister every module listener except the moderation pre-login guard.
     * That listener must remain live while module configs and the moderation
     * database are being rebuilt asynchronously; otherwise banned players can
     * connect in the reload window.
     */
    private void unregisterModuleListeners() {
        ModerationListener persistentModerationListener = moderationListener;
        if (persistentModerationListener == null) {
            org.bukkit.event.HandlerList.unregisterAll(this);
            return;
        }

        Set<org.bukkit.event.Listener> listeners = Collections.newSetFromMap(new IdentityHashMap<>());
        for (org.bukkit.plugin.RegisteredListener registered
                : org.bukkit.event.HandlerList.getRegisteredListeners(this)) {
            org.bukkit.event.Listener listener = registered.getListener();
            if (listener != persistentModerationListener && listeners.add(listener)) {
                org.bukkit.event.HandlerList.unregisterAll(listener);
            }
        }
    }

    /** Registers the persistent moderation guard once, before any async setup. */
    private void ensureModerationListener() {
        if (moderationListener != null && isModerationListenerRegistered(moderationListener)) return;
        synchronized (this) {
            ModerationListener listener = moderationListener;
            if (listener == null) {
                listener = new ModerationListener(this);
                moderationListener = listener;
            }
            if (!isModerationListenerRegistered(listener)) {
                getServer().getPluginManager().registerEvents(listener, this);
            }
        }
    }

    private boolean isModerationListenerRegistered(ModerationListener listener) {
        for (org.bukkit.plugin.RegisteredListener registered
                : org.bukkit.event.HandlerList.getRegisteredListeners(this)) {
            if (registered.getListener() == listener) return true;
        }
        return false;
    }

    private void scheduleVanishReloadFlush(VanishManager manager, FileConfiguration snapshot) {
        // Lost-update guard like spawn/menu (configSeq + fileLock, keyed
        // "vanish-flush"): only the newest snapshot flushes. flushSync()
        // additionally serializes updates per UUID.
        final long sequence = configSeq.computeIfAbsent("vanish-flush", k -> new AtomicLong()).incrementAndGet();
        final Object flushLock = getFileLock("vanish-flush");
        final CompletableFuture<Void> flushFuture = new CompletableFuture<>();
        trackPendingSave(flushFuture);
        try {
            Bukkit.getAsyncScheduler().runNow(this, task -> {
                try {
                    AtomicLong seq = configSeq.get("vanish-flush");
                    if (seq != null && seq.get() != sequence) return;
                    synchronized (flushLock) {
                        AtomicLong inner = configSeq.get("vanish-flush");
                        if (inner != null && inner.get() != sequence) return;
                        manager.flushSync(snapshot);
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to flush vanish state on reload: " + e.getMessage());
                } finally {
                    flushFuture.complete(null);
                }
            });
        } catch (Exception e) {
            getLogger().warning("Could not schedule async vanish flush on reload: " + e.getMessage());
            flushFuture.complete(null);
        }
    }

    /** Reveals carry-over after an overlapping reload disabled vanish while no manager exists. */
    private void revealCarriedVanishState(boolean block) {
        Set<java.util.UUID> ids = carriedVanishedIds;
        Map<java.util.UUID, VanishManager.PrevSelfState> states = carriedPrevSelfStates;
        Set<java.util.UUID> clearIds = new java.util.HashSet<>(ids);
        clearIds.addAll(carriedDirtyVanished);
        clearIds.addAll(carriedDirtyUnvanished);
        FileConfiguration cfg = getVanishConfig();
        boolean persist = cfg == null || cfg.getBoolean("vanish.persist-on-rejoin", true);
        // Supersede any earlier reload flush and serialize this reveal behind
        // one that already entered its per-file critical section.
        configSeq.computeIfAbsent("vanish-flush", k -> new AtomicLong()).incrementAndGet();
        final Object flushLock = getFileLock("vanish-flush");
        VanishManager persistence = new VanishManager(this);
        Runnable clearFiles = () -> {
            synchronized (flushLock) {
                Set<java.util.UUID> failed = new java.util.HashSet<>();
                for (java.util.UUID uuid : clearIds) {
                    if (uuid == null) continue;
                    try {
                        if (!persistence.persistVanishValue(uuid, persist ? false : null)) failed.add(uuid);
                    } catch (Exception e) {
                        failed.add(uuid);
                        getLogger().fine("Could not clear carried vanish state for " + uuid + ": " + e.getMessage());
                    }
                }
                carriedDirtyUnvanished = Set.copyOf(failed);
            }
        };
        if (block) {
            clearFiles.run();
        } else {
            CompletableFuture<Void> pending = new CompletableFuture<>();
            trackPendingSave(pending);
            try {
                Bukkit.getAsyncScheduler().runNow(this, task -> {
                    try {
                        clearFiles.run();
                    } finally {
                        pending.complete(null);
                    }
                });
            } catch (Exception e) {
                getLogger().warning("Could not schedule carried vanish cleanup: " + e.getMessage());
                pending.completeExceptionally(e);
            }
        }
        // Failed false-write obligations remain for the next manager to retry.
        for (java.util.UUID uuid : ids) {
            if (uuid == null) continue;
            Player target = Bukkit.getPlayer(uuid);
            if (target == null || !target.isOnline()) continue;
            VanishManager.PrevSelfState previous = states.get(uuid);
            try {
                target.getScheduler().run(this, task -> {
                    if (!target.isOnline()) return;
                    try {
                        target.setInvisible(previous != null && previous.invisible());
                        target.setCollidable(previous == null || previous.collidable());
                        target.setSilent(previous != null && previous.silent());
                    } catch (Exception ignored) {
                    }
                }, null);
            } catch (Exception ignored) {
            }
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.equals(target)) continue;
                try {
                    viewer.getScheduler().run(this, task -> {
                        if (!viewer.isOnline() || !target.isOnline()) return;
                        try {
                            viewer.showEntity(this, target);
                            try {
                                viewer.listPlayer(target);
                            } catch (Exception ignored) {
                            }
                        } catch (Exception ignored) {
                        }
                    }, null);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Reloads the root config and merges jar defaults for missing keys only
     * (module-config analogue: {@link #mergeMissingDefaults}): an outdated
     * config.yml keeps working after an update instead of silently assuming
     * defaults. In-memory only — like the module merge, missing keys persist
     * on the next saveConfig() (no comment-stripping rewrite here). Never
     * throws: a corrupt config.yml keeps the previous in-memory config
     * (Bukkit leaves the old instance in place when the re-read fails).
     */
    @Override
    public void reloadConfig() {
        reloadConfigForGeneration(-1L);
    }

    /**
     * Reloads the root config while fencing asynchronous module reloads by
     * generation. Disk I/O and YAML parsing happen without holding configLock;
     * the generation must therefore be checked again at the final in-memory
     * swap so an older, slower read cannot overwrite a newer reload or toggle.
     * A negative generation is used by the public Bukkit override when no
     * module-reload generation owns the call.
     */
    private void reloadConfigForGeneration(long expectedGeneration) {
        // Snapshot under the root-config lock, but do all file access and YAML
        // parsing outside it so global-region readers never wait on disk I/O.
        final String snapshot;
        synchronized (configLock) {
            try {
                FileConfiguration cur = readRawConfigNoReload();
                snapshot = cur == null ? null : cur.saveToString();
            } catch (Exception e) {
                // NOTE: must NOT call getConfig() here; on first startup that
                // can dispatch back to this override and recurse.
                getLogger().fine("Could not snapshot config.yml before reload: " + e.getMessage());
                return;
            }
        }

        FileConfiguration prepared = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(new File(getDataFolder(), "config.yml")), StandardCharsets.UTF_8)) {
            prepared.load(reader);
        } catch (Exception e) {
            getLogger().warning("Failed to reload config.yml, keeping previous config: " + e.getMessage());
            return;
        }

        boolean wiped = !prepared.contains("modules") || !prepared.contains("version");
        if (wiped) {
            getLogger().severe("config.yml appears corrupt (missing version/modules after reload), restored previous in-memory config. Fix or regenerate config.yml!");
            if (snapshot != null && !snapshot.isBlank()) {
                try {
                    prepared.loadFromString(snapshot);
                } catch (Exception restoreEx) {
                    getLogger().warning("Failed to restore previous config.yml snapshot: " + restoreEx.getMessage());
                    return;
                }
            }
        }
        try {
            mergeMissingRootDefaults(prepared);
        } catch (Exception e) {
            getLogger().warning("Failed to merge defaults for config.yml: " + e.getMessage());
        }

        synchronized (configLock) {
            if (expectedGeneration >= 0 && moduleReloadSeq.get() != expectedGeneration) return;
            if (!replaceRawConfigNoReload(prepared)) {
                getLogger().warning("Could not replace the in-memory config.yml after reload.");
            }
        }
    }

    /**
     * Reads JavaPlugin's in-memory config field WITHOUT triggering a reload.
     * getConfig() must never be called from reloadConfig(): when the field is
     * still null (first start) it dispatches back to this override -&gt; infinite
     * recursion / StackOverflow. Returns null when no config is loaded yet or
     * on reflection failure (callers then skip the wiped-check).
     */
    private @Nullable FileConfiguration readRawConfigNoReload() {
        for (String name : new String[]{"config", "newConfig"}) {
            try {
                java.lang.reflect.Field f = JavaPlugin.class.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(this);
                if (v instanceof FileConfiguration fc) return fc;
                // Field exists but is still null (first start): try the next
                // candidate instead of giving up (e.g. "config" null -> "newConfig").
                continue;
            } catch (NoSuchFieldException e) {
                // Try next candidate name.
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private boolean replaceRawConfigNoReload(FileConfiguration config) {
        boolean replaced = false;
        for (Class<?> type = JavaPlugin.class; type != null; type = type.getSuperclass()) {
            for (String name : new String[]{"config", "newConfig"}) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(name);
                    if (!FileConfiguration.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    field.set(this, config);
                    replaced = true;
                } catch (NoSuchFieldException ignored) {
                } catch (Exception e) {
                    getLogger().fine("Could not replace raw config field " + name + ": " + e.getMessage());
                }
            }
        }
        return replaced;
    }

    private void mergeMissingRootDefaults(FileConfiguration target) {
        try (InputStream in = getResource("config.yml")) {
            if (in == null) return;
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                FileConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
                target.setDefaults(defaults);
                for (String key : defaults.getKeys(true)) {
                    if (!target.isSet(key)) {
                        target.set(key, defaults.get(key));
                    }
                }
            }
        } catch (Exception e) {
            getLogger().fine("Could not merge defaults for config.yml: " + e.getMessage());
        }
    }

    public @Nullable FileConfiguration loadModuleConfig(String fileName) {
        if (failedPreparedReloadConfigs.contains(fileName)) return null;
        FileConfiguration prepared = preparedReloadConfigs.get(fileName);
        if (prepared != null) {
            configs.put(fileName, prepared);
            return prepared;
        }
        FileConfiguration loaded = loadModuleConfigFromDisk(fileName);
        if (loaded != null) configs.put(fileName, loaded);
        return loaded;
    }

    /** Reads and merges one module YAML file. Reload calls this on the async scheduler. */
    private @Nullable FileConfiguration loadModuleConfigFromDisk(String fileName) {
        File file = new File(getDataFolder(), "modules/" + fileName);
        if (!file.exists()) {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                getLogger().warning("Could not create directory: " + parent);
            }
            // saveResource() throws IllegalArgumentException (not IOException)
            // when the default resource is missing from the jar — a broken
            // build must disable only this module, not kill the whole reload.
            try {
                saveResource("modules/" + fileName, false);
            } catch (IllegalArgumentException e) {
                getLogger().severe("Missing default resource for " + fileName + "! The module stays disabled: " + e.getMessage());
                return null;
            }
        }

        // YamlConfiguration.loadConfiguration() reports malformed YAML via
        // unchecked exceptions — catch broadly so one corrupt module config
        // disables only its own module (callers already handle null).
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(reader);
            mergeMissingDefaults(config, fileName);
            return config;
        } catch (Exception e) {
            getLogger().warning("Could not load " + fileName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Merges jar defaults into the loaded config, missing keys only: existing
     * user values are never overwritten. Uses setDefaults() as fallback for
     * getters plus an explicit copy of absent keys so the next save persists
     * them. Never throws.
     */
    private void mergeMissingDefaults(FileConfiguration config, String fileName) {
        try (InputStream in = getResource("modules/" + fileName)) {
            if (in == null) return;
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                FileConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
                config.setDefaults(defaults);
                for (String key : defaults.getKeys(true)) {
                    if (!config.isSet(key)) {
                        config.set(key, defaults.get(key));
                    }
                }
            }
        } catch (Exception e) {
            getLogger().fine("Could not merge defaults for " + fileName + ": " + e.getMessage());
        }
    }

    /**
     * Snapshot of the loaded module configs (unmodifiable map copy).
     * P2: the map is a copy, but the values are live
     * {@link FileConfiguration} instances — do not mutate them; guard reads
     * with {@code synchronized(config)} (module configs) or
     * {@code synchronized(configLock)} for the root config (see
     * {@link #getConfigLock()}), since {@link #reloadConfig()} may swap
     * instances at any time.
     */
    public Map<String, FileConfiguration> getConfigs() {
        return Collections.unmodifiableMap(new HashMap<>(configs));
    }

    public Map<String, String> getLatestVersions() {
        return Collections.unmodifiableMap(new HashMap<>(latestVersions));
    }

    public @Nullable FileConfiguration getSpawnConfig() {
        return configs.get("spawn.yml");
    }

    public @Nullable FileConfiguration getJoinItemsConfig() {
        return configs.get("joinitems.yml");
    }

    public @Nullable FileConfiguration getMessagesConfig() {
        return configs.get("messages.yml");
    }

    public @Nullable FileConfiguration getTeleportConfig() {
        return configs.get("teleport.yml");
    }

    private void checkAllConfigVersions() {
        // Central lock guards the ROOT config only (toggle/suggest mutate it
        // while this reads). Module configs are guarded per-instance via
        // synchronized(config): their snapshots/mutations use the same monitor
        // (see saveModuleConfigAsync), YamlConfiguration is not thread-safe.
        // Snapshot everything under the lock(s), log outside so slow I/O
        // (logger handlers) never holds configLock.
        final String currentMain;
        final String configLatest;
        final java.util.Map<String, String> currentByFile = new java.util.HashMap<>();
        final java.util.Map<String, String> latestByFile = new java.util.HashMap<>();
        synchronized (configLock) {
            currentMain = readVersionString(getConfig());
            configLatest = versionToString(latestVersions.get("config.yml"));

            // Snapshot copy: a concurrent stopModules() -> clear() on another
            // thread must never break this traversal.
            new HashMap<>(configs).forEach((name, config) -> {
                if (config == null) {
                    currentByFile.put(name, "0");
                    return;
                }
                String current;
                synchronized (config) {
                    current = readVersionString(config);
                }
                currentByFile.put(name, current);
            });
            latestByFile.putAll(latestVersions);
        }

        if (configLatest != null) {
            checkVersion("config.yml", currentMain, configLatest);
        }
        currentByFile.forEach((name, current) -> {
            String latest = latestByFile.get(name);
            if (latest != null) {
                checkVersion("modules/" + name, current, latest);
            }
        });
    }

    /**
     * Compares dotted version strings segment by segment ("1.10" &gt; "1.9"):
     * a double comparison would wrongly treat 1.10 as equal to 1.1.
     * A dash-separated pre-release suffix ("0.3.0-Beta", "1.0-Alpha") is
     * stripped from the numeric core and compared afterwards: same core
     * means pre-release &lt; release, two pre-releases compare their tags
     * case-insensitively ("alpha" &lt; "beta"). Non-numeric segments count
     * as 0 (numeric prefix when trailing junk remains), missing segments
     * count as 0. Returns negative if a &lt; b, zero if equal, positive if
     * a &gt; b. Kept identical in PlainBaseCommand/GlobalListener.
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
            int na = parseVersionSegment(i < pa.length ? pa[i] : "0");
            int nb = parseVersionSegment(i < pb.length ? pb[i] : "0");
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

    private static int parseVersionSegment(String segment) {
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

    /**
     * Normalizes a stored latest version for comparison. Null (unknown file)
     * stays null so the caller skips the check. Takes a String (F2): the map
     * holds version strings so "1.10" never collapses to "1.1" via double.
     */
    private static String versionToString(String version) {
        return version;
    }

    /**
     * Reads the "version" value of a config as a string, tolerating both
     * numeric (unquoted YAML, e.g. {@code version: 1.7}) and quoted string
     * forms (e.g. {@code version: "1.10"} — required to distinguish 1.10
     * from 1.1, which YAML would otherwise parse to the same double).
     */
    private static String readVersionString(FileConfiguration config) {
        if (config == null) return "0";
        Object value = config.get("version");
        if (value == null) return "0";
        String text = value.toString().trim();
        return text.isEmpty() ? "0" : text;
    }

    private void checkVersion(String fileName, String currentV, String latestV) {
        if (compareVersions(currentV, latestV) < 0) {
            getLogger().warning("!!! OUTDATED CONFIG: " + fileName + " !!!");
            getLogger().warning("Your version: " + currentV + " | Required: " + latestV);
            getLogger().warning("Please check GitHub for the latest version and update your file.");
        }
    }

    public void setupSpawn() {
        if (!ensureGlobalThread("setupSpawn", this::setupSpawn)) return;
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
        // Per-file lock: serializes with the async writer of the same file.
        synchronized (getFileLock("spawn.yml")) {
            // Bump the sequence so older async snapshots (taken before this
            // sync flush) are treated as stale and dropped by the async writer.
            configSeq.computeIfAbsent("spawn.yml", k -> new AtomicLong()).incrementAndGet();
            synchronized (config) {
                try {
                    config.save(new File(getDataFolder(), "modules/spawn.yml"));
                } catch (IOException e) {
                    getLogger().log(java.util.logging.Level.SEVERE, "Could not save spawn.yml!", e);
                }
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
        if (!ensureGlobalThread("setupJoinItems", this::setupJoinItems)) return;
        FileConfiguration joinItemsConfig = loadModuleConfig("joinitems.yml");
        if (joinItemsConfig == null) {
            getLogger().severe("Could not load joinitems.yml! The joinitems module stays disabled until this is fixed.");
            return;
        }
        getServer().getPluginManager().registerEvents(new JoinItemsListener(this), this);
    }

    public void setupMessages() {
        if (!ensureGlobalThread("setupMessages", this::setupMessages)) return;
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
        if (!ensureGlobalThread("setupTeleport", this::setupTeleport)) return;
        FileConfiguration teleportConfig = loadModuleConfig("teleport.yml");
        if (teleportConfig == null) {
            getLogger().severe("Could not load teleport.yml! The teleport module stays disabled until this is fixed.");
            return;
        }

        tpaManager = new TPAManager(this);
        rtpManager = new RTPManager(this);

        getServer().getPluginManager().registerEvents(new TeleportListener(this), this);

        // Join events do not fire for players who are already online during
        // a module reload. Register the listener first so players joining
        // around this snapshot are handled either by the snapshot or by the
        // join event, then restore through each player's Folia scheduler.
        tpaManager.loadOnlinePlayerData();
    }

    public void setupVanish() {
        if (!ensureGlobalThread("setupVanish", this::setupVanish)) return;
        // Carry over the vanished IDs snapshotted by stopModules() (by value —
        // the old manager instance is already gone): the fresh manager must
        // know them again so nobody is revealed by the reload and nobody stays
        // hidden without backing state (no vanish leak window until the async
        // per-player persist load catches up). Offline players are covered by
        // their persisted player-data files via applyOnJoin().
        // No bulk copyFrom by design: restoreVanishState() re-applies via
        // vanish() so hide effects are preserved (resetAll did not run here).
        // Take-and-clear: a later setupVanish() without an intermediate
        // stopModules() must never re-hide a stale set.
        Set<java.util.UUID> previousVanished = carriedVanishedIds;
        carriedVanishedIds = Set.of();
        Set<java.util.UUID> previousDirtyUnvanished = carriedDirtyUnvanished;
        Set<java.util.UUID> previousDirtyVanished = carriedDirtyVanished;
        // BUG-1: take the carried pre-vanish self states alongside the IDs.
        Map<java.util.UUID, VanishManager.PrevSelfState> previousSelfStates = carriedPrevSelfStates;
        carriedPrevSelfStates = Map.of();

        FileConfiguration vanishCfg = loadModuleConfig("vanish.yml");
        if (vanishCfg == null) {
            Set<java.util.UUID> retryFalseWrites = new java.util.HashSet<>(previousDirtyUnvanished);
            retryFalseWrites.addAll(previousDirtyVanished);
            retryFalseWrites.addAll(previousVanished);
            carriedDirtyVanished = Set.of();
            getLogger().severe("Could not load vanish.yml! The vanish module stays disabled until this is fixed.");
            // Error path: no manager exists to resetAll with (stopModules()
            // always drops it), so reveal the carried players best-effort —
            // nobody may stay hidden without a backing manager. Mirrors
            // VanishManager.unvanish()'s scheduled show plus the no-stored-state
            // fallback of its self-state reset; per-player guarded, never throws.
            // Folia: entity visibility/state is entity-thread-only — every call
            // hops via the viewer/player scheduler (same pattern as
            // VanishManager.showTo/resetSelfState), never direct on this thread.
            VanishManager persistence = new VanishManager(this);
            // Player-data load/merge/write is disk I/O. Keep it off the
            // global region thread and make reload setup wait for completion.
            CompletableFuture<Void> falseWrites = new CompletableFuture<>();
            trackPendingSave(falseWrites);
            try {
                Bukkit.getAsyncScheduler().runNow(this, task -> {
                    Set<java.util.UUID> failed = new java.util.HashSet<>();
                    try {
                        for (java.util.UUID uuid : retryFalseWrites) {
                            if (uuid == null) continue;
                            try {
                                if (!persistence.persistVanishValue(uuid, false)) failed.add(uuid);
                            } catch (Exception e) {
                                failed.add(uuid);
                                getLogger().fine("Could not clear carried vanish state for " + uuid + ": " + e.getMessage());
                            }
                        }
                    } finally {
                        carriedDirtyUnvanished = Set.copyOf(failed);
                        falseWrites.complete(null);
                    }
                });
            } catch (Exception e) {
                getLogger().warning("Could not schedule carried vanish state cleanup: " + e.getMessage());
                carriedDirtyUnvanished = Set.copyOf(retryFalseWrites);
                falseWrites.completeExceptionally(e);
            }
            for (java.util.UUID uuid : retryFalseWrites) {
                try {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) continue;
                    final Player target = player;
                    for (Player viewer : Bukkit.getOnlinePlayers()) {
                        if (viewer.equals(target)) continue;
                        final Player v = viewer;
                        try {
                            v.getScheduler().run(this, t -> {
                                if (!v.isOnline() || !target.isOnline()) return;
                                try {
                                    v.showEntity(this, target);
                                    try {
                                        v.listPlayer(target);
                                    } catch (Exception ignored) {
                                    }
                                } catch (Exception ignored) {
                                }
                            }, null);
                        } catch (Exception ignored) {
                        }
                    }
                    // P2: restore the carried pre-vanish self state instead of
                    // hardcoding defaults — a third-party silent/collidable flag
                    // must survive the reveal. Fallback to defaults only when no
                    // snapshot exists for this player.
                    final VanishManager.PrevSelfState carried =
                            previousSelfStates != null ? previousSelfStates.get(uuid) : null;
                    try {
                        target.getScheduler().run(this, t -> {
                            if (!target.isOnline()) return;
                            boolean restoreInvisible = carried != null ? carried.invisible() : false;
                            boolean restoreCollidable = carried != null ? carried.collidable() : true;
                            boolean restoreSilent = carried != null ? carried.silent() : false;
                            try {
                                target.setInvisible(restoreInvisible);
                            } catch (Exception ignored) {
                            }
                            try {
                                target.setCollidable(restoreCollidable);
                            } catch (Exception ignored) {
                            }
                            try {
                                target.setSilent(restoreSilent);
                            } catch (Exception ignored) {
                            }
                        }, null);
                    } catch (Exception ignored) {
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to reveal vanished player " + uuid + " without vanish config: " + e.getMessage());
                }
            }
            carriedVanishedIds = Set.of();
            carriedPrevSelfStates = Map.of();
            carriedDirtyVanished = Set.of();
            return;
        }

        vanishManager = new VanishManager(this);
        vanishManager.restorePrevSelfStates(previousSelfStates);
        vanishManager.restoreDirtyUnvanished(previousDirtyUnvanished);
        vanishManager.restoreDirtyVanished(previousDirtyVanished);
        carriedDirtyUnvanished = Set.of();
        carriedDirtyVanished = Set.of();
        Set<java.util.UUID> restoredVanished = new java.util.HashSet<>(previousVanished);
        restoredVanished.addAll(previousDirtyVanished);
        restoredVanished.removeAll(previousDirtyUnvanished);
        restoreVanishState(restoredVanished, previousSelfStates, previousDirtyVanished);

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
     * <p>
     * BUG-1: restores the carried pre-vanish self states BEFORE re-vanishing
     * so applySelfState's putIfAbsent keeps the original values instead of
     * snapshotting the still-vanished flags.
     */
    private void restoreVanishState(Set<java.util.UUID> previousVanished,
                                    Map<java.util.UUID, VanishManager.PrevSelfState> previousSelfStates,
                                    Set<java.util.UUID> dirtyVanished) {
        if (vanishManager == null) return;
        try {
            vanishManager.restorePrevSelfStates(previousSelfStates);
        } catch (Exception e) {
            getLogger().fine("Failed to restore vanish self states: " + e.getMessage());
        }
        if (previousVanished == null || previousVanished.isEmpty()) return;
        for (java.util.UUID uuid : previousVanished) {
            try {
                if (vanishManager.isVanished(uuid)) continue;
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    if (dirtyVanished != null && dirtyVanished.contains(uuid)) {
                        vanishManager.restoreVanishedPlayer(player);
                    } else {
                        vanishManager.vanish(player);
                    }
                }
            } catch (Exception e) {
                getLogger().fine("Failed to restore vanish state for " + uuid + ": " + e.getMessage());
            }
        }
    }

    public void setupMenu() {
        if (!ensureGlobalThread("setupMenu", this::setupMenu)) return;
        FileConfiguration menuCfg = loadModuleConfig("menu.yml");
        if (menuCfg == null) {
            getLogger().severe("Could not load menu.yml! The menu module stays disabled until this is fixed.");
            return;
        }

        menuManager = new MenuManager(this);
        menuManager.reloadMenus();

        menuListener = new MenuListener(this);
        getServer().getPluginManager().registerEvents(menuListener, this);
    }

    public void setupModeration() {
        if (!ensureGlobalThread("setupModeration", this::setupModeration)) return;
        final long setupGeneration = moduleReloadSeq.get();
        FileConfiguration moderationCfg = loadModuleConfig("moderation.yml");
        if (moderationCfg == null) {
            getLogger().severe("Could not load moderation.yml! The moderation module stays disabled until this is fixed.");
            moderationSetupInFlight = CompletableFuture.completedFuture(null);
            synchronized (moderationGateLock) {
                if (moduleReloadSeq.get() == setupGeneration) {
                    moderationState = ModerationState.FAILED;
                }
            }
            return;
        }
        ensureModerationListener();
        final CompletableFuture<Void> setup = new CompletableFuture<>();
        moderationSetupInFlight = setup;
        try {
            Bukkit.getAsyncScheduler().runNow(this, task -> {
                BanManager created = null;
                Throwable failure = null;
                try {
                    created = new BanManager(this);
                } catch (Throwable e) {
                    failure = e;
                }
                final BanManager completedManager = created;
                final Throwable setupFailure = failure;
                if (completedManager != null && !registerPendingModerationManager(completedManager)) {
                    completedManager.shutdown(true);
                    setup.complete(null);
                    return;
                }
                try {
                    Bukkit.getGlobalRegionScheduler().run(this, globalTask -> {
                        boolean retireManager = false;
                        Throwable callbackError = null;
                        synchronized (moderationGateLock) {
                            if (!isEnabled() || moduleReloadSeq.get() != setupGeneration
                                    || !isModuleEnabled("moderation")) {
                                retireManager = completedManager != null;
                            } else if (setupFailure != null) {
                                FileConfiguration moderationConfig = getModerationConfig();
                                String storageType = moderationConfig != null
                                        ? moderationConfig.getString("storage.type", "sqlite") : "<unknown>";
                                getLogger().log(java.util.logging.Level.SEVERE,
                                        "Could not connect the moderation database (storage.type=" + storageType + ")",
                                        setupFailure);
                                getLogger().severe("The moderation module is disabled until this is fixed and /plainbase reload is run.");
                                banManager = null;
                                moderationState = ModerationState.FAILED;
                            } else {
                                try {
                                    if (completedManager != null
                                            && !installPendingModerationManager(completedManager)) {
                                        retireManager = true;
                                    } else {
                                        moderationState = ModerationState.READY;
                                    }
                                } catch (Throwable e) {
                                    retireManager = completedManager != null;
                                    banManager = null;
                                    moderationState = ModerationState.FAILED;
                                    callbackError = e;
                                }
                            }
                        }
                        if (retireManager) retirePendingModerationManager(completedManager, true);
                        if (callbackError == null) setup.complete(null);
                        else setup.completeExceptionally(callbackError);
                    });
                } catch (Exception scheduleError) {
                    retirePendingModerationManager(completedManager, true);
                    synchronized (moderationGateLock) {
                        if (moduleReloadSeq.get() == setupGeneration) moderationState = ModerationState.FAILED;
                    }
                    setup.completeExceptionally(scheduleError);
                }
            });
        } catch (Exception scheduleError) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not schedule moderation database setup", scheduleError);
            synchronized (moderationGateLock) {
                if (moduleReloadSeq.get() == setupGeneration) moderationState = ModerationState.FAILED;
            }
            setup.completeExceptionally(scheduleError);
        }
    }

    private boolean registerPendingModerationManager(BanManager manager) {
        synchronized (pendingModerationManagersLock) {
            if (moderationShuttingDown) return false;
            pendingModerationManagers.add(manager);
            return true;
        }
    }

    /**
     * Atomically transfers a completed setup manager into the live slot.
     * Disable closes pending managers under the same lock, so there is never
     * an unowned pool between async construction and the live field.
     */
    private boolean installPendingModerationManager(BanManager manager) {
        synchronized (pendingModerationManagersLock) {
            if (moderationShuttingDown) return false;
            pendingModerationManagers.remove(manager);
            banManager = manager;
            return true;
        }
    }

    private void retirePendingModerationManager(BanManager manager, boolean force) {
        if (manager == null) return;
        synchronized (pendingModerationManagersLock) {
            pendingModerationManagers.remove(manager);
            manager.shutdown(force);
        }
    }

    /** Closes pools whose global-thread install callbacks may be cancelled by disable. */
    private void closePendingModerationManagers() {
        List<BanManager> pending;
        synchronized (pendingModerationManagersLock) {
            moderationShuttingDown = true;
            pending = new ArrayList<>(pendingModerationManagers);
            pendingModerationManagers.clear();
        }
        for (BanManager manager : pending) {
            manager.shutdown(true);
        }
    }

    public void setupTeam() {
        if (!ensureGlobalThread("setupTeam", this::setupTeam)) return;
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

        // BUG-7: cache the instance only after register()==true — a merely
        // constructed instance must never become lastInstance.
        PlainBaseExpansion expansion = new PlainBaseExpansion(this);
        boolean ok = false;
        try {
            ok = expansion.register();
        } catch (Exception e) {
            getLogger().fine("Failed to register PlaceholderAPI expansion: " + e.getMessage());
            ok = false;
        }
        if (ok) {
            try {
                PlainBaseExpansion.setLastInstance(expansion);
            } catch (Exception e) {
                getLogger().fine("Failed to cache PlaceholderAPI expansion: " + e.getMessage());
            }
        }
        placeholdersRegistered = ok;
        if (placeholdersRegistered) {
            getLogger().info("PlaceholderAPI detected — registered %plainbase_*% placeholders!");
        }
    }

    public @Nullable TPAManager getTPAManager() {
        return tpaManager;
    }

    public @Nullable RTPManager getRTPManager() {
        return rtpManager;
    }

    public @Nullable VanishManager getVanishManager() {
        return vanishManager;
    }

    public @Nullable FileConfiguration getVanishConfig() {
        return configs.get("vanish.yml");
    }

    public @Nullable MenuManager getMenuManager() {
        return menuManager;
    }

    public @Nullable FileConfiguration getMenuConfig() {
        return configs.get("menu.yml");
    }

    public @Nullable BanManager getBanManager() {
        return banManager;
    }

    public ModerationState getModerationState() {
        return moderationState;
    }

    public Object getModerationGateLock() {
        return moderationGateLock;
    }

    public @Nullable FileConfiguration getModerationConfig() {
        return configs.get("moderation.yml");
    }

    public @Nullable TeamManager getTeamManager() {
        return teamManager;
    }

    public @Nullable FileConfiguration getTeamConfig() {
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
        // Per-file lock: serializes with the async writer of the same file.
        synchronized (getFileLock("menu.yml")) {
            // Bump the sequence so older async snapshots (taken before this
            // sync flush) are treated as stale and dropped by the async writer.
            configSeq.computeIfAbsent("menu.yml", k -> new AtomicLong()).incrementAndGet();
            synchronized (config) {
                try {
                    config.save(new File(getDataFolder(), "modules/menu.yml"));
                } catch (IOException e) {
                    getLogger().log(java.util.logging.Level.SEVERE, "Could not save menu.yml!", e);
                }
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
                getLogger().log(java.util.logging.Level.SEVERE, "Could not save " + fileName + "!", e);
                return;
            }
        }
        // Lost-update guard: each snapshot gets a sequence number, stale
        // writers (an older snapshot finishing after a newer one) are dropped.
        final long mySeq = configSeq.computeIfAbsent(fileName, k -> new AtomicLong()).incrementAndGet();
        final File target = new File(getDataFolder(), "modules/" + fileName);
        final Object fileLock = getFileLock(fileName);
        final CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (pendingSaves) {
            pendingSaves.removeIf(CompletableFuture::isDone);
            pendingSaves.add(future);
        }
        Runnable write = () -> {
            try {
                AtomicLong seq = configSeq.get(fileName);
                if (seq != null && seq.get() != mySeq) {
                    return;
                }
                // Same per-file monitor as the sync writers above.
                synchronized (fileLock) {
                    if (seq != null && seq.get() != mySeq) {
                        return;
                    }
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                        getLogger().warning("Could not create directory: " + parent);
                        return;
                    }
                    // Atomic publish via temp file: readers never see a
                    // half-written config, even on crash mid-write.
                    java.nio.file.Path tmp = target.toPath().resolveSibling(target.getName() + ".tmp");
                    Files.writeString(tmp, data, StandardCharsets.UTF_8);
                    try {
                        Files.move(tmp, target.toPath(),
                                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (Exception e) {
                getLogger().log(java.util.logging.Level.SEVERE, "Could not save " + fileName + "!", e);
            } finally {
                future.complete(null);
            }
        };
        try {
            Bukkit.getAsyncScheduler().runNow(this, task -> write.run());
        } catch (Exception e) {
            // The scheduler rejected the task (plugin disabling / server
            // shutdown): never fall back to synchronous disk I/O here — this
            // runs on a Folia region thread and blocking it stalls the tick.
            // The in-memory config is still flushed synchronously by
            // stopModules(), so warning is enough. Never throws.
            getLogger().warning("Could not schedule async save of " + fileName
                    + ", changes will be flushed on shutdown: " + e.getMessage());
            future.complete(null);
        }
    }

    /**
     * Folia-safe persist for the root config changed from commands
     * (/plainbase toggle): snapshots in memory under configLock (no disk I/O
     * on the calling thread) and writes the bytes on the async scheduler.
     * Same guards as {@link #saveModuleConfigAsync}: sequence guard (a stale
     * snapshot never overwrites a newer one) and atomic temp-file publish (a
     * failed write never leaves a half-written config.yml behind), keyed
     * "config.yml". Tracked in pendingSaves so the disable path still awaits
     * it (bounded). Never throws; the future completes when the write settled
     * (callers hop back to the global thread for reload/messages to keep
     * modify-save-reload ordering). Comment stripping is unchanged Bukkit
     * behavior (saveToString), only the thread is moved.
     */
    public CompletableFuture<Void> saveConfigAsync() {
        final String data;
        synchronized (configLock) {
            FileConfiguration rootCfg = getConfig();
            if (rootCfg == null) {
                getLogger().warning("Cannot save config.yml: config is not loaded.");
                return CompletableFuture.failedFuture(
                        new IllegalStateException("config.yml is not loaded"));
            }
            try {
                data = rootCfg.saveToString();
            } catch (Exception e) {
                getLogger().log(java.util.logging.Level.SEVERE, "Could not save config.yml!", e);
                return CompletableFuture.failedFuture(e);
            }
        }
        final long mySeq = configSeq.computeIfAbsent("config.yml", k -> new AtomicLong()).incrementAndGet();
        final File target = new File(getDataFolder(), "config.yml");
        final Object fileLock = getFileLock("config.yml");
        final CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (pendingSaves) {
            pendingSaves.removeIf(CompletableFuture::isDone);
            pendingSaves.add(future);
        }
        Runnable write = () -> {
            try {
                AtomicLong seq = configSeq.get("config.yml");
                if (seq != null && seq.get() != mySeq) {
                    future.completeExceptionally(new java.util.concurrent.CancellationException(
                            "A newer config save superseded this write."));
                    return;
                }
                synchronized (fileLock) {
                    if (seq != null && seq.get() != mySeq) {
                        future.completeExceptionally(new java.util.concurrent.CancellationException(
                                "A newer config save superseded this write."));
                        return;
                    }
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                        throw new IOException("Could not create directory: " + parent);
                    }
                    java.nio.file.Path tmp = target.toPath().resolveSibling(target.getName() + ".tmp");
                    Files.writeString(tmp, data, StandardCharsets.UTF_8);
                    try {
                        Files.move(tmp, target.toPath(),
                                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (Exception e) {
                getLogger().log(java.util.logging.Level.SEVERE, "Could not save config.yml!", e);
                future.completeExceptionally(e);
            } finally {
                // Preserve failure state for callers (notably /plainbase
                // toggle); a failed or superseded write must never look like
                // a successful persistence operation.
                if (!future.isDone()) future.complete(null);
            }
        };
        try {
            Bukkit.getAsyncScheduler().runNow(this, task -> write.run());
        } catch (Exception e) {
            getLogger().warning("Could not schedule async save of config.yml"
                    + ", changes will be flushed on shutdown: " + e.getMessage());
            future.completeExceptionally(e);
        }
        return future;
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
