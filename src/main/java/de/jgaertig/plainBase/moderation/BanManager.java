package de.jgaertig.plainBase.moderation;

import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.commands.ModerationCommandBase;
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

    // Volatile snapshot references: refreshCacheBlocking() builds fresh
    // collections and SWAPS the reference (no clear()+addAll() on the live
    // lists) so readers never observe a half-cleared cache and GC churn stays
    // low. Mutations take mutationLock just like the swap, so an add can never
    // be lost to a concurrent refresh.
    private volatile List<BanRecord> bansCache = new CopyOnWriteArrayList<>();
    private volatile List<KickRecord> kicksCache = new CopyOnWriteArrayList<>();
    private volatile List<IpBanRecord> ipBansCache = new CopyOnWriteArrayList<>();
    private volatile Map<UUID, List<BanRecord>> bansByUuid = new ConcurrentHashMap<>();
    private volatile Map<UUID, List<KickRecord>> kicksByUuid = new ConcurrentHashMap<>();

    // Guards check-then-act ban/unban mutations on THIS server instance so two
    // near-simultaneous /ban calls on the same target can't both pass the
    // "not already banned" check. Does NOT protect against a genuinely
    // simultaneous ban from a second server on a shared MySQL backend — that
    // is an accepted, documented limitation for this beta (last-write-wins).
    private final Object mutationLock = new Object();

    private ScheduledTask refreshTask;

    /**
     * Opens the pool (bounded, fail-fast connect — a broken/unreachable
     * database throws here and setupModeration() fail-opens). The initial
     * cache fill is NOT done here: history tables are unbounded, so the first
     * load runs on the async scheduler instead of blocking the calling (main)
     * thread at startup. Until it lands, cached getters simply return empty
     * (fail-open); the login path never reads the cache anyway (it uses live
     * DB queries), so enforcement is unaffected by the loading window.
     */
    public BanManager(PlainBase plugin) throws SQLException {
        this.plugin = plugin;
        this.db = new ModerationDatabase(plugin);
        db.connect();
        startPeriodicRefresh();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> refreshCacheBlocking());
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
     * Blocking DB read — only call from an async context (the initial fill and
     * the periodic task both run on Bukkit.getAsyncScheduler()).
     * <p>
     * Full-table loads are unbounded by nature (history is never deleted), so
     * they must never run on the calling thread at startup — see constructor.
     * A genuine SQLException keeps the previous snapshot (fail-open); a single
     * corrupt row is skipped loudly without discarding the rest.
     */
    private void refreshCacheBlocking() {
        List<BanRecord> bans;
        List<KickRecord> kicks;
        List<IpBanRecord> ipBans;
        try {
            bans = db.loadAllBans();
            kicks = db.loadAllKicks();
            ipBans = db.loadAllIpBans();
        } catch (SQLException | RuntimeException e) {
            // RuntimeException included: a single corrupt row (bad UUID,
            // unexpected null) must never kill the periodic refresh task.
            plugin.getLogger().severe("Could not refresh moderation cache: " + e.getMessage());
            return;
        }

        Map<UUID, List<BanRecord>> newBansByUuid = new ConcurrentHashMap<>();
        for (BanRecord record : bans) {
            newBansByUuid.computeIfAbsent(record.uuid(), k -> new CopyOnWriteArrayList<>()).add(record);
        }
        Map<UUID, List<KickRecord>> newKicksByUuid = new ConcurrentHashMap<>();
        for (KickRecord record : kicks) {
            newKicksByUuid.computeIfAbsent(record.uuid(), k -> new CopyOnWriteArrayList<>()).add(record);
        }

        // Serialized with all mutations via mutationLock: without this, a
        // periodic refresh racing a concurrent ban/unban could wipe the
        // freshly written entry (a swap racing an add would lose the add).
        synchronized (mutationLock) {
            bansCache = new CopyOnWriteArrayList<>(bans);
            kicksCache = new CopyOnWriteArrayList<>(kicks);
            ipBansCache = new CopyOnWriteArrayList<>(ipBans);
            bansByUuid = newBansByUuid;
            kicksByUuid = newKicksByUuid;
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
     * <p>
     * Defense-in-depth alongside ModerationListener (which already passes the
     * canonical form): the address is normalized AGAIN here, so only canonical
     * spellings ever reach the DB — a mismatched notation could otherwise
     * bypass a later IP-ban check that compares exact strings.
     */
    public void trackPlayerIp(UUID uuid, String name, String ip) {
        // Never persist a null, blank, literal-"unknown" or otherwise
        // unparseable address — it would later resolve via /banip <name> and
        // ban a bogus address. normalizeIp returns null for all of those.
        String canonical = ModerationCommandBase.normalizeIp(ip);
        if (canonical == null) return;
        try {
            db.trackPlayerIp(uuid, name, canonical);
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

    /**
     * Strict variant of {@link #findLastIpByName} for callers that must tell a
     * genuine "never seen" (null) apart from a database failure (throws), so a
     * DB hiccup reports a database error instead of a misleading "ip-not-found".
     */
    public String findLastIpByNameStrict(String name) throws SQLException {
        return db.findLastIpByName(name);
    }

    // ---- Async mutations — always call back via the global region scheduler ----

    public void tryBanAsync(UUID uuid, String name, String reason, UUID staffUuid, String staffName, long durationMillis, Consumer<Optional<BanRecord>> callback) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            Optional<BanRecord> result;
            synchronized (mutationLock) {
                // Authoritative live check instead of cache-only: two racing
                // /ban calls (or a ban from another server on shared MySQL)
                // must not both pass the "not already banned" test.
                // On DB failure fall back to the cache so a hiccup can't
                // produce duplicate rows.
                BanRecord live = null;
                boolean liveOk = false;
                try {
                    live = db.findActiveBan(uuid, System.currentTimeMillis());
                    liveOk = true;
                } catch (SQLException | RuntimeException e) {
                    plugin.getLogger().warning("Could not live-check ban for " + name + ", falling back to cache: " + e.getMessage());
                }
                BanRecord effective = live != null ? live : (liveOk ? null : getActiveBan(uuid).orElse(null));
                if (effective != null) {
                    if (live != null) cacheLiveBan(live);
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
                long now = System.currentTimeMillis();
                try {
                    // Only an ACTIVE ban can be unbanned: revokeBan() matches
                    // every unrevoked row (revoked = 0) regardless of expiry, so
                    // without this guard an already-expired tempban would still
                    // revoke rows and report "unbanned". No active ban means the
                    // caller reports "not banned" (covers expired and absent).
                    if (!hasActiveBanNow(uuid, now)) {
                        success = false;
                    } else {
                        // Revoke-by-key revokes ALL unrevoked rows for this uuid;
                        // success is decided on the row count (0 = nothing to unban).
                        int revoked = db.revokeBan(uuid, staffUuid, staffName, now);
                        if (revoked > 0) {
                            revokeAllBansInCache(uuid, staffUuid, staffName, now);
                            success = true;
                        } else {
                            success = false;
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("Could not revoke ban for " + uuid + ": " + e.getMessage());
                    success = false;
                }
            }
            boolean finalSuccess = success;
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> callback.accept(finalSuccess));
        });
    }

    public void recordKickAsync(UUID uuid, String name, String reason, UUID staffUuid, String staffName, Runnable onDone) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            // Same mutationLock as every other mutation and the snapshot swap
            // in refreshCacheBlocking: without this, a periodic refresh racing
            // this add could swap in a snapshot taken before the insert and
            // lose the kick from the cache until the next refresh.
            // Note: banlist/baninfo read the cache and can therefore stay stale
            // until the next refresh; the DB is the source of truth (the login
            // path uses live queries, never the cache).
            synchronized (mutationLock) {
                try {
                    KickRecord record = db.insertKick(uuid, name, reason, staffUuid, staffName);
                    kicksCache.add(record);
                    kicksByUuid.computeIfAbsent(uuid, k -> new CopyOnWriteArrayList<>()).add(record);
                } catch (SQLException | RuntimeException e) {
                    plugin.getLogger().severe("Could not record kick for " + name + ": " + e.getMessage());
                }
            }
            if (onDone != null) Bukkit.getGlobalRegionScheduler().run(plugin, t -> onDone.run());
        });
    }

    public void tryBanIpAsync(String ip, String reason, UUID staffUuid, String staffName, long durationMillis, Consumer<Optional<IpBanRecord>> callback) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            Optional<IpBanRecord> result;
            synchronized (mutationLock) {
                // Live check first (same reasoning as tryBanAsync); cache is
                // only the fallback when the DB itself is unreachable.
                long now = System.currentTimeMillis();
                IpBanRecord live = null;
                boolean liveOk = false;
                try {
                    live = db.findActiveIpBan(ip, now);
                    liveOk = true;
                } catch (SQLException | RuntimeException e) {
                    plugin.getLogger().warning("Could not live-check IP ban for " + ip + ", falling back to cache: " + e.getMessage());
                }
                boolean alreadyBanned = live != null
                        || (!liveOk && ipBansCache.stream().anyMatch(r -> r.ip().equals(ip) && r.isActive(now)));
                if (alreadyBanned) {
                    if (live != null) cacheLiveIpBan(live);
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
                try {
                    // Same active-only guard as unbanPlayerAsync: an expired
                    // temp-IP-ban must report "not banned", not "unbanned".
                    if (!hasActiveIpBanNow(ip, now)) {
                        success = false;
                    } else {
                        // Same revoke-by-key pattern as unbanPlayerAsync: all
                        // unrevoked rows for this IP, row count decides success.
                        int revoked = db.revokeIpBan(ip, staffUuid, staffName, now);
                        if (revoked > 0) {
                            revokeAllIpBansInCache(ip, staffUuid, staffName, now);
                            success = true;
                        } else {
                            success = false;
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("Could not revoke IP ban for " + ip + ": " + e.getMessage());
                    success = false;
                }
            }
            boolean finalSuccess = success;
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> callback.accept(finalSuccess));
        });
    }

    /**
     * Live active check with cache fallback for the unban guards: on a DB
     * failure the (possibly stale) cache decides instead of a hiccup silently
     * reporting "not banned" or revoking blindly.
     */
    private boolean hasActiveBanNow(UUID uuid, long now) {
        try {
            return db.findActiveBan(uuid, now) != null;
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().warning("Could not live-check ban for " + uuid + ", falling back to cache: " + e.getMessage());
            return getActiveBan(uuid).isPresent();
        }
    }

    private boolean hasActiveIpBanNow(String ip, long now) {
        try {
            return db.findActiveIpBan(ip, now) != null;
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().warning("Could not live-check IP ban for " + ip + ", falling back to cache: " + e.getMessage());
            return getActiveIpBans().stream().anyMatch(r -> r.ip() != null && r.ip().equals(ip) && r.isActive(now));
        }
    }

    /**
     * Marks every unrevoked cached ban for the uuid as revoked (mirrors the
     * revoke-by-key UPDATE, which touches all rows, not just one id).
     * Must be called while holding {@code mutationLock}.
     */
    private void revokeAllBansInCache(UUID uuid, UUID staffUuid, String staffName, long now) {
        List<BanRecord> history = bansByUuid.get(uuid);
        if (history != null) {
            for (int i = 0; i < history.size(); i++) {
                BanRecord r = history.get(i);
                if (!r.revoked()) history.set(i, r.withRevoked(staffUuid, staffName, now));
            }
        }
        for (int i = 0; i < bansCache.size(); i++) {
            BanRecord r = bansCache.get(i);
            if (!r.revoked() && r.uuid().equals(uuid)) bansCache.set(i, r.withRevoked(staffUuid, staffName, now));
        }
    }

    /**
     * Marks every unrevoked cached IP ban for the ip as revoked.
     * Must be called while holding {@code mutationLock}.
     */
    private void revokeAllIpBansInCache(String ip, UUID staffUuid, String staffName, long now) {
        for (int i = 0; i < ipBansCache.size(); i++) {
            IpBanRecord r = ipBansCache.get(i);
            if (!r.revoked() && r.ip() != null && r.ip().equals(ip)) ipBansCache.set(i, r.withRevoked(staffUuid, staffName, now));
        }
    }

    /**
     * Keeps the in-memory snapshot consistent when a live DB check found a row
     * the cache doesn't have yet (e.g. banned from another server on shared
     * MySQL). Must be called while holding {@code mutationLock}.
     */
    private void cacheLiveBan(BanRecord live) {
        boolean known = bansCache.stream().anyMatch(r -> r.id() == live.id());
        if (!known) {
            bansCache.add(live);
            bansByUuid.computeIfAbsent(live.uuid(), k -> new CopyOnWriteArrayList<>()).add(live);
        }
    }

    private void cacheLiveIpBan(IpBanRecord live) {
        boolean known = ipBansCache.stream().anyMatch(r -> r.id() == live.id());
        if (!known) ipBansCache.add(live);
    }
}
