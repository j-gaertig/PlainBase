package de.jgaertig.plainBase.moderation;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.storage.ModerationDatabase;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Ban/kick/IP-ban storage backed by a real database (SQLite by default,
 * MySQL opt-in for cross-server bans — see {@link ModerationDatabase}).
 * <p>
 * Two read paths, deliberately different in freshness guarantee:
 * <ul>
 *   <li><b>Cached reads</b> (getActiveBan/getBanCount/getActiveBans/...) — an
 *       in-memory snapshot refreshed at startup and on a periodic timer
 *       (storage.refresh-interval-seconds). Fast, safe to call from any
 *       thread, but on a MySQL/cross-server setup can be up to one refresh
 *       interval stale. Used by commands (banlist, baninfo) where that's fine.</li>
 *   <li><b>Live queries</b> (queryActiveBanNow/queryActiveIpBanNow) — hit the
 *       DB directly, no caching. Used by ModerationListener's login check,
 *       which is the one place staleness would actually matter (a ban issued
 *       on another server must block a login on THIS server immediately).
 *       Safe to call from AsyncPlayerPreLoginEvent because that event is
 *       already off the main thread — blocking JDBC I/O there is the
 *       intended use of that event, not a violation of the repo's
 *       "no sync IO in join events" rule (which targets the main-thread
 *       PlayerJoinEvent, not this async pre-login hook).</li>
 * </ul>
 * All WRITES (tryBanAsync/unbanPlayerAsync/recordKickAsync/tryBanIpAsync/
 * unbanIpAsync) run on Bukkit.getAsyncScheduler() and report back to the
 * caller via Bukkit.getGlobalRegionScheduler().run() — the same
 * async-then-region-hop pattern PlainBaseCommand uses for the Modrinth
 * update check — because a direct DB write from the command's own thread
 * would block the main/region thread on Paper/Folia.
 */
public class BanManager {

    private final PlainBase plugin;
    private final ModerationDatabase db;

    private final List<BanRecord> bansCache = new CopyOnWriteArrayList<>();
    private final List<KickRecord> kicksCache = new CopyOnWriteArrayList<>();
    private final List<IpBanRecord> ipBansCache = new CopyOnWriteArrayList<>();
    private final Map<UUID, List<BanRecord>> bansByUuid = new ConcurrentHashMap<>();
    private final Map<UUID, List<KickRecord>> kicksByUuid = new ConcurrentHashMap<>();

    // Guards check-then-act ban/unban mutations on THIS server instance so two
    // near-simultaneous /ban calls on the same target can't both pass the
    // "not already banned" check. Does NOT protect against a genuinely
    // simultaneous ban from a second server on a shared MySQL backend — that
    // is an accepted, documented limitation for this beta (last-write-wins).
    private final Object mutationLock = new Object();

    private ScheduledTask refreshTask;

    public BanManager(PlainBase plugin) throws SQLException {
        this.plugin = plugin;
        this.db = new ModerationDatabase(plugin);
        db.connect();
        refreshCacheBlocking();
        startPeriodicRefresh();
    }

    public void shutdown() {
        if (refreshTask != null) refreshTask.cancel();
        db.close();
    }

    // ---- Cache refresh ----

    private void startPeriodicRefresh() {
        long seconds = Math.max(5, plugin.getModerationConfig().getLong("storage.refresh-interval-seconds", 30));
        refreshTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> refreshCacheBlocking(), seconds, seconds, TimeUnit.SECONDS);
    }

    /**
     * Blocking DB read — only call from an async context (constructor runs at
     * startup before players can connect, same as loadModuleConfig(); the
     * periodic task runs on Bukkit.getAsyncScheduler()).
     */
    private void refreshCacheBlocking() {
        try {
            List<BanRecord> bans = db.loadAllBans();
            List<KickRecord> kicks = db.loadAllKicks();
            List<IpBanRecord> ipBans = db.loadAllIpBans();

            Map<UUID, List<BanRecord>> newBansByUuid = new ConcurrentHashMap<>();
            for (BanRecord record : bans) {
                newBansByUuid.computeIfAbsent(record.uuid(), k -> new CopyOnWriteArrayList<>()).add(record);
            }
            Map<UUID, List<KickRecord>> newKicksByUuid = new ConcurrentHashMap<>();
            for (KickRecord record : kicks) {
                newKicksByUuid.computeIfAbsent(record.uuid(), k -> new CopyOnWriteArrayList<>()).add(record);
            }

            bansCache.clear();
            bansCache.addAll(bans);
            kicksCache.clear();
            kicksCache.addAll(kicks);
            ipBansCache.clear();
            ipBansCache.addAll(ipBans);
            bansByUuid.clear();
            bansByUuid.putAll(newBansByUuid);
            kicksByUuid.clear();
            kicksByUuid.putAll(newKicksByUuid);
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not refresh moderation cache: " + e.getMessage());
        }
    }

    // ---- Cached queries (thread-safe, safe to call from any thread) ----

    public Optional<BanRecord> getActiveBan(UUID uuid) {
        long now = System.currentTimeMillis();
        List<BanRecord> history = bansByUuid.get(uuid);
        if (history == null) return Optional.empty();

        BanRecord latestActive = null;
        for (BanRecord record : history) {
            if (record.isActive(now) && (latestActive == null || record.bannedAt() > latestActive.bannedAt())) {
                latestActive = record;
            }
        }
        return Optional.ofNullable(latestActive);
    }

    public List<BanRecord> getBanHistory(UUID uuid) {
        List<BanRecord> history = bansByUuid.get(uuid);
        return history == null ? List.of() : List.copyOf(history);
    }

    public List<KickRecord> getKickHistory(UUID uuid) {
        List<KickRecord> history = kicksByUuid.get(uuid);
        return history == null ? List.of() : List.copyOf(history);
    }

    public int getBanCount(UUID uuid) {
        return getBanHistory(uuid).size();
    }

    public int getKickCount(UUID uuid) {
        return getKickHistory(uuid).size();
    }

    public Optional<BanRecord> getLastBan(UUID uuid) {
        return getBanHistory(uuid).stream().max((a, b) -> Long.compare(a.bannedAt(), b.bannedAt()));
    }

    public List<BanRecord> getActiveBans() {
        long now = System.currentTimeMillis();
        List<BanRecord> active = new ArrayList<>();
        for (BanRecord record : bansCache) {
            if (record.isActive(now)) active.add(record);
        }
        active.sort((a, b) -> Long.compare(b.bannedAt(), a.bannedAt()));
        return active;
    }

    public List<IpBanRecord> getActiveIpBans() {
        long now = System.currentTimeMillis();
        List<IpBanRecord> active = new ArrayList<>();
        for (IpBanRecord record : ipBansCache) {
            if (record.isActive(now)) active.add(record);
        }
        active.sort((a, b) -> Long.compare(b.bannedAt(), a.bannedAt()));
        return active;
    }

    // ---- Live, uncached DB queries — used for login enforcement ----

    /**
     * Authoritative check, hits the DB directly (no cache). Only call from an
     * already-async context (AsyncPlayerPreLoginEvent, or your own async task).
     */
    public BanRecord queryActiveBanNow(UUID uuid) throws SQLException {
        return db.findActiveBan(uuid, System.currentTimeMillis());
    }

    public IpBanRecord queryActiveIpBanNow(String ip) throws SQLException {
        return db.findActiveIpBan(ip, System.currentTimeMillis());
    }

    /**
     * Records the player's current IP for later "/banip <name>" resolution.
     * Blocking DB write — only call from an already-async context.
     */
    public void trackPlayerIp(UUID uuid, String name, String ip) {
        try {
            db.trackPlayerIp(uuid, name, ip);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not track player IP for " + name + ": " + e.getMessage());
        }
    }

    public String findLastIpByName(String name) {
        try {
            return db.findLastIpByName(name);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not look up last IP for " + name + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Async mutations — always call back via the global region scheduler ----

    public void tryBanAsync(UUID uuid, String name, String reason, UUID staffUuid, String staffName, long durationMillis, Consumer<Optional<BanRecord>> callback) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            Optional<BanRecord> result;
            synchronized (mutationLock) {
                if (getActiveBan(uuid).isPresent()) {
                    result = Optional.empty();
                } else {
                    try {
                        BanRecord record = db.insertBan(uuid, name, reason, staffUuid, staffName, durationMillis);
                        bansCache.add(record);
                        bansByUuid.computeIfAbsent(uuid, k -> new CopyOnWriteArrayList<>()).add(record);
                        result = Optional.of(record);
                    } catch (SQLException e) {
                        plugin.getLogger().severe("Could not insert ban for " + name + ": " + e.getMessage());
                        result = Optional.empty();
                    }
                }
            }
            Optional<BanRecord> finalResult = result;
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> callback.accept(finalResult));
        });
    }

    public void unbanPlayerAsync(UUID uuid, UUID staffUuid, String staffName, Consumer<Boolean> callback) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            boolean success;
            synchronized (mutationLock) {
                Optional<BanRecord> active = getActiveBan(uuid);
                if (active.isEmpty()) {
                    success = false;
                } else {
                    BanRecord old = active.get();
                    long now = System.currentTimeMillis();
                    try {
                        db.revokeBan(old.id(), staffUuid, staffName, now);
                        BanRecord revoked = old.withRevoked(staffUuid, staffName, now);
                        replaceBanById(bansByUuid.get(uuid), old.id(), revoked);
                        replaceBanById(bansCache, old.id(), revoked);
                        success = true;
                    } catch (SQLException e) {
                        plugin.getLogger().severe("Could not revoke ban for " + uuid + ": " + e.getMessage());
                        success = false;
                    }
                }
            }
            boolean finalSuccess = success;
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> callback.accept(finalSuccess));
        });
    }

    public void recordKickAsync(UUID uuid, String name, String reason, UUID staffUuid, String staffName, Runnable onDone) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                KickRecord record = db.insertKick(uuid, name, reason, staffUuid, staffName);
                kicksCache.add(record);
                kicksByUuid.computeIfAbsent(uuid, k -> new CopyOnWriteArrayList<>()).add(record);
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not record kick for " + name + ": " + e.getMessage());
            }
            if (onDone != null) Bukkit.getGlobalRegionScheduler().run(plugin, t -> onDone.run());
        });
    }

    public void tryBanIpAsync(String ip, String reason, UUID staffUuid, String staffName, long durationMillis, Consumer<Optional<IpBanRecord>> callback) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            Optional<IpBanRecord> result;
            synchronized (mutationLock) {
                long now = System.currentTimeMillis();
                boolean alreadyBanned = ipBansCache.stream().anyMatch(r -> r.ip().equals(ip) && r.isActive(now));
                if (alreadyBanned) {
                    result = Optional.empty();
                } else {
                    try {
                        IpBanRecord record = db.insertIpBan(ip, reason, staffUuid, staffName, durationMillis);
                        ipBansCache.add(record);
                        result = Optional.of(record);
                    } catch (SQLException e) {
                        plugin.getLogger().severe("Could not insert IP ban for " + ip + ": " + e.getMessage());
                        result = Optional.empty();
                    }
                }
            }
            Optional<IpBanRecord> finalResult = result;
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> callback.accept(finalResult));
        });
    }

    public void unbanIpAsync(String ip, UUID staffUuid, String staffName, Consumer<Boolean> callback) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            boolean success;
            synchronized (mutationLock) {
                long now = System.currentTimeMillis();
                IpBanRecord old = ipBansCache.stream().filter(r -> r.ip().equals(ip) && r.isActive(now)).findFirst().orElse(null);
                if (old == null) {
                    success = false;
                } else {
                    try {
                        db.revokeIpBan(old.id(), staffUuid, staffName, now);
                        IpBanRecord revoked = old.withRevoked(staffUuid, staffName, now);
                        replaceIpBanById(ipBansCache, old.id(), revoked);
                        success = true;
                    } catch (SQLException e) {
                        plugin.getLogger().severe("Could not revoke IP ban for " + ip + ": " + e.getMessage());
                        success = false;
                    }
                }
            }
            boolean finalSuccess = success;
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> callback.accept(finalSuccess));
        });
    }

    private void replaceBanById(List<BanRecord> list, int id, BanRecord replacement) {
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id() == id) {
                list.set(i, replacement);
                return;
            }
        }
    }

    private void replaceIpBanById(List<IpBanRecord> list, int id, IpBanRecord replacement) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id() == id) {
                list.set(i, replacement);
                return;
            }
        }
    }
}
