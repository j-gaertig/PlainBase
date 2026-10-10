package de.jgaertig.plainBase.moderation.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanRecord;
import de.jgaertig.plainBase.moderation.commands.ModerationCommandBase;
import de.jgaertig.plainBase.moderation.IpBanRecord;
import de.jgaertig.plainBase.moderation.KickRecord;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC storage for the moderation module. Two dialects behind one API:
 * <ul>
 *   <li><b>sqlite</b> (default) — single file under {@code plugins/PlainBase/data/},
 *       zero extra setup, but local to this one server.</li>
 *   <li><b>mysql</b> — point every server in a network at the same database and
 *       bans/kicks/IP-bans are shared across all of them (the whole point of a
 *       real DB backend instead of a local YAML file).</li>
 * </ul>
 * All methods here do blocking JDBC I/O and must only ever be called from an
 * async context (Bukkit.getAsyncScheduler(), or an already-async event like
 * AsyncPlayerPreLoginEvent) — never from the main/region thread.
 */
public class ModerationDatabase {

    private final PlainBase plugin;
    private final boolean mysql;
    private HikariDataSource dataSource;

    public ModerationDatabase(PlainBase plugin) {
        this.plugin = plugin;
        // Snapshot with null-guard: a missing config fails safe to sqlite
        // (the default backend) instead of NPE-ing the constructor.
        FileConfiguration cfg = plugin.getModerationConfig();
        this.mysql = cfg != null && "mysql".equalsIgnoreCase(cfg.getString("storage.type", "sqlite"));
    }

    /**
     * Opens the pool and creates tables if missing. Called once from
     * setupModeration() — synchronous, but happens at plugin/module startup
     * before any player can connect (same timing as loadModuleConfig()).
     */
    public void connect() throws SQLException {
        HikariConfig config = new HikariConfig();

        // Snapshot with null-guard: a /plainbase reload racing startup can
        // leave the config briefly null — fail with a clear error (the caller
        // fail-opens) instead of NPE-ing mid-connect.
        FileConfiguration modCfg = plugin.getModerationConfig();
        if (modCfg == null) {
            throw new SQLException("Moderation config is not loaded.");
        }

        if (mysql) {
            String host = modCfg.getString("storage.mysql.host", "localhost");
            int port = modCfg.getInt("storage.mysql.port", 3306);
            String database = modCfg.getString("storage.mysql.database", "plainbase");
            boolean useSsl = modCfg.getBoolean("storage.mysql.useSSL", false);
            if (host == null || !(host.matches("[A-Za-z0-9._-]+") || host.matches("\\[[0-9a-fA-F:.]+\\]"))) {
                plugin.getLogger().warning("Invalid storage.mysql.host '" + host + "', falling back to localhost");
                host = "localhost";
            }
            if (database == null || !database.matches("[A-Za-z0-9_$]+")) {
                plugin.getLogger().warning("Invalid storage.mysql.database '" + database + "', falling back to plainbase");
                database = "plainbase";
            }
            if (port < 1 || port > 65535) {
                plugin.getLogger().warning("Invalid storage.mysql.port '" + port + "', falling back to 3306");
                port = 3306;
            }
            config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + useSsl + "&characterEncoding=utf8mb4"
                    + "&connectTimeout=5000&socketTimeout=10000");
            config.setUsername(modCfg.getString("storage.mysql.username", "root"));
            config.setPassword(modCfg.getString("storage.mysql.password", ""));
            config.setMaximumPoolSize(Math.min(10, Math.max(2, modCfg.getInt("storage.mysql.pool-size", 5))));
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            // Statement-cache tunables are Connector/J (MySQL) only — setting
            // them globally is a no-op on SQLite and only misleading there.
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        } else {
            File dataDir = new File(plugin.getDataFolder(), "data");
            if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
                throw new SQLException("Could not create moderation data directory: " + dataDir.getAbsolutePath());
            }
            String fileName = modCfg.getString("storage.sqlite.file", "moderation.db");
            if (fileName == null || !fileName.matches("[A-Za-z0-9_.-]+\\.db")) {
                plugin.getLogger().warning("Invalid storage.sqlite.file '" + fileName + "', falling back to moderation.db");
                fileName = "moderation.db";
            }
            File dbFile = new File(dataDir, fileName);
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            // Small pool instead of a single connection: WAL mode (enabled
            // below) allows concurrent readers, writes stay short and are
            // serialized app-side via BanManager's mutationLock, and
            // busy_timeout (see connectionInitSql) absorbs transient locks.
            config.setMaximumPoolSize(3);
            // Per-CONNECTION init: busy_timeout is a per-connection PRAGMA, so
            // setting it once on an arbitrary pooled connection is lost as soon
            // as the pool recycles that connection — init SQL re-applies it to
            // EVERY new connection for the pool's whole lifetime.
            config.setConnectionInitSql("PRAGMA busy_timeout=5000");
            config.setDriverClassName("org.sqlite.JDBC");
        }

        config.setPoolName("PlainBase-Moderation");
        // Bound all blocking setup I/O: BanManager is constructed on the main
        // thread, so an unreachable MySQL host must fail fast instead of hanging
        // startup (Hikari defaults to a 30s connection timeout). The short
        // connection timeout ALSO protects the login path: every
        // queryActive*Now check runs with this budget and fail-opens
        // (allows the login) on timeout instead of stalling logins. No new
        // feature, just a fail-fast budget for connect and login checks.
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(3000);
        config.setInitializationFailTimeout(5000);
        if (mysql) {
            // Pool hardening: recycle connections before typical MySQL wait_timeout
            // (8h) / NAT timeouts, probe idle ones, cap idle retention.
            config.setMaxLifetime(280000);
            config.setKeepaliveTime(30000);
            config.setIdleTimeout(60000);
        } else {
            // SQLite connections are cheap local file handles — never recycle
            // them on a timer (0 = disabled), so WAL state and PRAGMAs survive
            // indefinitely and no recycled connection can lose its settings.
            config.setMaxLifetime(0);
            config.setKeepaliveTime(0);
            config.setIdleTimeout(0);
        }
        dataSource = new HikariDataSource(config);

        try {
            try (Connection conn = dataSource.getConnection()) {
                if (!mysql) {
                    // WAL + busy timeout reduce "database is locked" errors under
                    // concurrent load. Best-effort: failures must not break setup.
                    try (Statement st = conn.createStatement()) {
                        st.execute("PRAGMA journal_mode=WAL");
                        st.execute("PRAGMA busy_timeout=5000");
                    } catch (SQLException ignored) {
                    }
                }
                createTables(conn);
                migrateTextColumns(conn);
                normalizeUuidCase(conn);
                normalizeIpSpellings(conn);
            }
        } catch (SQLException | RuntimeException e) {
            // createTables()/getConnection() failed AFTER the pool was opened —
            // close it again instead of leaking threads and connections on
            // every failed (re)load. setupModeration() fail-opens on the throw.
            try {
                dataSource.close();
            } catch (Exception closeEx) {
                plugin.getLogger().warning("Could not close moderation pool after failed connect: " + closeEx.getMessage());
            }
            dataSource = null;
            throw e;
        }
    }

    public void close() {
        HikariDataSource ds = dataSource;
        dataSource = null;
        if (ds != null) {
            try {
                ds.close();
            } catch (Exception e) {
                plugin.getLogger().fine("Could not close moderation pool: " + e.getMessage());
            }
        }
    }

    private String prefix() {
        // Snapshot with null-guard: fails safe to the default prefix instead
        // of NPE-ing SQL construction mid-reload.
        FileConfiguration modCfg = plugin.getModerationConfig();
        String p = mysql && modCfg != null ? modCfg.getString("storage.mysql.table-prefix", "pb_") : "pb_";
        if (p == null) p = "pb_";
        // Table prefix is concatenated into SQL — never allow anything that
        // could break out of the identifier (SQL injection via config).
        if (!p.matches("[A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid moderation table prefix: " + p);
        }
        return p;
    }

    private void createTables(Connection conn) throws SQLException {
        String p = prefix();
        String autoInc = mysql ? "INT AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        // Dialect-drift fix: free-text columns (names, reasons, staff names)
        // are TEXT on both dialects. VARCHAR(255) on MySQL silently truncates
        // (or rejects, depending on sql_mode) long reasons while SQLite TEXT
        // never does — identical DDL removes that drift. Columns that carry an
        // index or serve as exact-match lookup keys (ip, last_ip,
        // player_ips.name) stay VARCHAR(255) on MySQL: MySQL cannot index a
        // TEXT column without a prefix length, and player names (max 16 chars)
        // plus literal IPs never come close to 255 chars anyway.
        String textCol = "TEXT";
        String keyCol = mysql ? "VARCHAR(255)" : "TEXT";
        // UUID columns: fixed CHAR(36) ascii_bin on fresh MySQL installs so
        // UUID lookups compare case-sensitively and stay index-friendly.
        // (CREATE TABLE IF NOT EXISTS never alters existing tables — no
        // migration, no schema break for current installs.)
        String uuidCol = mysql ? "CHAR(36) CHARACTER SET ascii COLLATE ascii_bin" : "TEXT";

        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "bans (" +
                    "id " + autoInc + ", " +
                    "uuid " + uuidCol + " NOT NULL, " +
                    "name " + textCol + ", " +
                    "reason " + textCol + ", " +
                    "staff_uuid " + uuidCol + ", " +
                    "staff_name " + textCol + ", " +
                    "banned_at BIGINT NOT NULL, " +
                    "duration BIGINT NOT NULL, " +
                    "revoked BOOLEAN NOT NULL DEFAULT 0, " +
                    "unbanned_by_uuid " + uuidCol + ", " +
                    "unbanned_by_name " + textCol + ", " +
                    "unbanned_at BIGINT NOT NULL DEFAULT 0)");
            createIndexIfMissing(st, p + "bans_uuid_idx", p + "bans", "uuid");
            createIndexIfMissing(st, p + "bans_uuid_revoked_at_idx", p + "bans", "uuid, revoked, banned_at");
            createPartialUniqueIndex(st, p + "bans_uuid_active_uidx", p + "bans", "uuid");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "kicks (" +
                    "id " + autoInc + ", " +
                    "uuid " + uuidCol + " NOT NULL, " +
                    "name " + textCol + ", " +
                    "reason " + textCol + ", " +
                    "staff_uuid " + uuidCol + ", " +
                    "staff_name " + textCol + ", " +
                    "kicked_at BIGINT NOT NULL)");
            createIndexIfMissing(st, p + "kicks_uuid_idx", p + "kicks", "uuid");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "ip_bans (" +
                    "id " + autoInc + ", " +
                    "ip " + keyCol + " NOT NULL, " +
                    "reason " + textCol + ", " +
                    "staff_uuid " + uuidCol + ", " +
                    "staff_name " + textCol + ", " +
                    "banned_at BIGINT NOT NULL, " +
                    "duration BIGINT NOT NULL, " +
                    "revoked BOOLEAN NOT NULL DEFAULT 0, " +
                    "unbanned_by_uuid " + uuidCol + ", " +
                    "unbanned_by_name " + textCol + ", " +
                    "unbanned_at BIGINT NOT NULL DEFAULT 0)");
            createIndexIfMissing(st, p + "ip_bans_ip_idx", p + "ip_bans", "ip");
            createIndexIfMissing(st, p + "ip_bans_ip_revoked_at_idx", p + "ip_bans", "ip, revoked, banned_at");
            createPartialUniqueIndex(st, p + "ip_bans_ip_active_uidx", p + "ip_bans", "ip");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "player_ips (" +
                    "uuid " + uuidCol + " NOT NULL, " +
                    "name " + keyCol + ", " +
                    "last_ip " + keyCol + ", " +
                    "last_seen BIGINT NOT NULL, " +
                    "PRIMARY KEY (uuid))");
            createIndexIfMissing(st, p + "player_ips_name_idx", p + "player_ips", "name");
            // findLastIpByName() filters on LOWER(name), which a plain index on
            // name cannot serve. SQLite gets a matching expression index;
            // MySQL has no portable functional-index syntax across the server
            // versions this plugin supports, so there the lookup stays a scan
            // over a tiny table (one row per player) — deliberately left
            // as-is, documented here instead of a risky DDL change.
            if (!mysql) {
                try {
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS " + p + "player_ips_name_lower_idx ON " + p + "player_ips(LOWER(name))");
                } catch (SQLException e) {
                    plugin.getLogger().warning("Could not create index " + p + "player_ips_name_lower_idx: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Migrates pre-existing MySQL tables from VARCHAR(255) to TEXT for the
     * free-text columns (CREATE TABLE IF NOT EXISTS never alters existing
     * tables). Best-effort and idempotent: one ALTER TABLE per table with
     * multiple MODIFY COLUMN clauses (3 statements total instead of one per
     * column), failures are logged loudly but never break setup — a table
     * that keeps VARCHAR(255) simply stays subject to the insert-time
     * {@link #clip255} guard on key columns, while overlong TEXT values would
     * fail loudly on that one legacy table instead of silently truncating.
     */
    private void migrateTextColumns(Connection conn) {
        if (!mysql) return;
        String p;
        try {
            p = prefix();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not migrate text columns: " + e.getMessage());
            return;
        }
        // One ALTER per table, multiple MODIFYs each — fewer round-trips on
        // the setup (main) thread than one ALTER per column.
        String[][] tables = {
                {p + "bans", "name", "reason", "staff_name", "unbanned_by_name"},
                {p + "kicks", "name", "reason", "staff_name"},
                {p + "ip_bans", "reason", "staff_name", "unbanned_by_name"},
        };
        for (String[] table : tables) {
            StringBuilder sql = new StringBuilder("ALTER TABLE " + table[0]);
            for (int i = 1; i < table.length; i++) {
                sql.append(i == 1 ? " MODIFY COLUMN " : ", MODIFY COLUMN ").append(table[i]).append(" TEXT");
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(sql.toString());
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().warning("Could not migrate " + table[0] + " text columns to TEXT: " + e.getMessage());
            }
        }
    }

    /**
     * Insert-time guard for the columns that deliberately stay VARCHAR(255)
     * on MySQL (indexed lookup keys): clips with a loud warning instead of
     * letting MySQL silently truncate (or reject, depending on sql_mode).
     * Real values for these columns (player names, literal IPs) never come
     * close to 255 chars, so a clip always indicates corrupt input.
     */
    private String clip255(String value, String field) {
        if (value != null && value.length() > 255) {
            plugin.getLogger().warning("Truncating overlong moderation " + field
                    + " (" + value.length() + " chars) to 255 chars.");
            return value.substring(0, 255);
        }
        return value;
    }

    /**
     * One-time, idempotent case normalization: all WRITES already store
     * lowercase UUIDs, but legacy rows may contain mixed case — and reads must
     * use an exact {@code uuid = ?} comparison so the index is used (wrapping
     * the column in {@code LOWER()} disables the index on every login check).
     * Each UPDATE only touches rows that actually differ (no-op otherwise) and
     * failures are logged, never fatal: setup must not break over legacy data.
     * Rows created from now on are already lowercase, so this converges to a
     * no-op on the second start.
     */
    private void normalizeUuidCase(Connection conn) {
        String p = prefix();
        String[] statements = {
                "UPDATE " + p + "bans SET uuid = lower(uuid) WHERE uuid != lower(uuid)",
                "UPDATE " + p + "bans SET staff_uuid = lower(staff_uuid) WHERE staff_uuid IS NOT NULL AND staff_uuid != lower(staff_uuid)",
                "UPDATE " + p + "bans SET unbanned_by_uuid = lower(unbanned_by_uuid) WHERE unbanned_by_uuid IS NOT NULL AND unbanned_by_uuid != lower(unbanned_by_uuid)",
                "UPDATE " + p + "kicks SET uuid = lower(uuid) WHERE uuid != lower(uuid)",
                "UPDATE " + p + "kicks SET staff_uuid = lower(staff_uuid) WHERE staff_uuid IS NOT NULL AND staff_uuid != lower(staff_uuid)",
                "UPDATE " + p + "ip_bans SET staff_uuid = lower(staff_uuid) WHERE staff_uuid IS NOT NULL AND staff_uuid != lower(staff_uuid)",
                "UPDATE " + p + "ip_bans SET unbanned_by_uuid = lower(unbanned_by_uuid) WHERE unbanned_by_uuid IS NOT NULL AND unbanned_by_uuid != lower(unbanned_by_uuid)",
                "UPDATE " + p + "player_ips SET uuid = lower(uuid) WHERE uuid != lower(uuid)",
        };
        for (String sql : statements) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(sql);
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not normalize UUID case (" + sql + "): " + e.getMessage());
            }
        }
    }

    /**
     * One-time, idempotent IP normalization: all WRITES already store the
     * canonical form (see ModerationCommandBase#normalizeIp), but legacy rows
     * may use other spellings ("::ffff:1.2.3.4", leading zeros, "[::1]"
     * brackets). Reads use an exact {@code ip = ?} comparison, so such rows
     * would otherwise never match. Each UPDATE only touches rows that actually
     * differ (no-op otherwise) and failures are logged, never fatal. Only
     * normalizes, no new tables. Rows created from now on are already
     * canonical, so this converges to a no-op on the second start.
     */
    private void normalizeIpSpellings(Connection conn) {
        String p;
        try {
            p = prefix();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not normalize IP spellings: " + e.getMessage());
            return;
        }
        // ip_bans: id -> canonical ip for rows that differ.
        try (PreparedStatement sel = conn.prepareStatement("SELECT id, ip FROM " + p + "ip_bans");
             ResultSet rs = sel.executeQuery()) {
            List<int[]> ids = new ArrayList<>();
            List<String> canonicals = new ArrayList<>();
            while (rs.next()) {
                int id = rs.getInt(1);
                String stored = rs.getString(2);
                if (stored == null || stored.isBlank()) continue;
                String canonical;
                try {
                    canonical = ModerationCommandBase.normalizeIp(stored);
                } catch (RuntimeException e) {
                    continue;
                }
                if (canonical == null || canonical.equals(stored)) continue;
                ids.add(new int[]{id});
                canonicals.add(canonical);
            }
            try (PreparedStatement upd = conn.prepareStatement("UPDATE " + p + "ip_bans SET ip = ? WHERE id = ?")) {
                for (int i = 0; i < ids.size(); i++) {
                    try {
                        upd.setString(1, canonicals.get(i));
                        upd.setInt(2, ids.get(i)[0]);
                        upd.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().warning("Could not normalize IP ban id " + ids.get(i)[0] + ": " + e.getMessage());
                    }
                }
            }
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().warning("Could not normalize IP ban spellings: " + e.getMessage());
        }
        // player_ips: uuid -> canonical last_ip for rows that differ.
        try (PreparedStatement sel = conn.prepareStatement("SELECT uuid, last_ip FROM " + p + "player_ips");
             ResultSet rs = sel.executeQuery()) {
            List<String> uuids = new ArrayList<>();
            List<String> canonicals = new ArrayList<>();
            while (rs.next()) {
                String uuid = rs.getString(1);
                String stored = rs.getString(2);
                if (stored == null || stored.isBlank()) continue;
                String canonical;
                try {
                    canonical = ModerationCommandBase.normalizeIp(stored);
                } catch (RuntimeException e) {
                    continue;
                }
                if (canonical == null || canonical.equals(stored)) continue;
                uuids.add(uuid);
                canonicals.add(canonical);
            }
            try (PreparedStatement upd = conn.prepareStatement("UPDATE " + p + "player_ips SET last_ip = ? WHERE uuid = ?")) {
                for (int i = 0; i < uuids.size(); i++) {
                    try {
                        upd.setString(1, canonicals.get(i));
                        upd.setString(2, uuids.get(i));
                        upd.executeUpdate();
                    } catch (SQLException e) {
                        plugin.getLogger().warning("Could not normalize player IP for " + uuids.get(i) + ": " + e.getMessage());
                    }
                }
            }
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().warning("Could not normalize player IP spellings: " + e.getMessage());
        }
    }

    /**
     * prefix() validates the configured table prefix and throws
     * IllegalArgumentException on misuse. Async callers only catch
     * SQLException, so this converts the validation failure into a
     * SQLException the existing fail-open paths already handle.
     */
    private String prefixChecked() throws SQLException {
        try {
            return prefix();
        } catch (RuntimeException e) {
            throw new SQLException("Invalid moderation table prefix: " + e.getMessage(), e);
        }
    }

    /**
     * Partial uniqueness: at most one unrevoked row per uuid/ip. SQLite
     * supports partial indexes natively; MySQL has no partial-index support
     * (portably), so there this is best-effort only — the application-level
     * live check inside BanManager's mutationLock plus revoke-by-key (row
     * count decides success) prevent duplicates on a single server, while a
     * genuinely simultaneous cross-server race on shared MySQL stays
     * last-write-wins (documented limitation; a SELECT ... FOR UPDATE
     * transaction around check-then-insert would be the full fix and is left
     * out deliberately to avoid a schema/locking behaviour change here).
     */
    private void createPartialUniqueIndex(Statement st, String indexName, String table, String column) {
        if (mysql) return; // no partial-index support — best-effort no-op (see javadoc)
        try {
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS " + indexName + " ON " + table + "(" + column + ") WHERE revoked = 0");
        } catch (SQLException e) {
            // Pre-existing duplicate active rows (legacy data) would violate
            // the index — never break setup over it, just log loudly.
            plugin.getLogger().warning("Could not create partial unique index " + indexName + ": " + e.getMessage());
        }
    }

    /**
     * SQLite supports "CREATE INDEX IF NOT EXISTS" directly; MySQL does not
     * (portably, across the version range this plugin supports), so on MySQL
     * we issue a plain CREATE INDEX and swallow the "index already exists"
     * error (MySQL error code 1061) on repeated calls (e.g. every plugin
     * enable/reload re-runs createTables()).
     */
    private void createIndexIfMissing(Statement st, String indexName, String table, String column) throws SQLException {
        if (!mysql) {
            st.executeUpdate("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + table + "(" + column + ")");
            return;
        }
        try {
            st.executeUpdate("CREATE INDEX " + indexName + " ON " + table + "(" + column + ")");
        } catch (SQLException e) {
            if (e.getErrorCode() != 1061) throw e; // 1061 = ER_DUP_KEYNAME, i.e. index already exists
        }
    }

    // ---- Writes ----

    public BanRecord insertBan(UUID uuid, String name, String reason, UUID staffUuid, String staffName, long duration) throws SQLException {
        long bannedAt = System.currentTimeMillis();
        String sql = "INSERT INTO " + prefixChecked() + "bans (uuid, name, reason, staff_uuid, staff_name, banned_at, duration, revoked, unbanned_by_uuid, unbanned_by_name, unbanned_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 0, NULL, NULL, 0)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString().toLowerCase(java.util.Locale.ROOT));
            ps.setString(2, name);
            ps.setString(3, reason);
            ps.setString(4, staffUuid == null ? null : staffUuid.toString().toLowerCase(java.util.Locale.ROOT));
            ps.setString(5, staffName);
            ps.setLong(6, bannedAt);
            ps.setLong(7, duration);
            ps.executeUpdate();
            int id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                id = keys.next() ? keys.getInt(1) : -1;
            }
            return new BanRecord(id, uuid, name, reason, staffUuid, staffName, bannedAt, duration, false, null, null, 0L);
        }
    }

    /**
     * Revokes ALL unrevoked rows for the uuid (not just one id) so legacy
     * duplicate active rows can never survive an /unban.
     * <p>
     * MySQL note: without partial-unique-index support this check-then-act
     * is best-effort under a genuinely simultaneous cross-server race
     * (single-server callers serialize via BanManager's mutationLock); a
     * {@code SELECT ... FOR UPDATE} transaction would be the full fix.
     *
     * @return number of rows revoked; unban success is decided on this count
     */
    public int revokeBan(UUID uuid, UUID staffUuid, String staffName, long unbannedAt) throws SQLException {
        String sql = "UPDATE " + prefixChecked() + "bans SET revoked = 1, unbanned_by_uuid = ?, unbanned_by_name = ?, unbanned_at = ? WHERE uuid = ? AND revoked = 0";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, staffUuid == null ? null : staffUuid.toString().toLowerCase(java.util.Locale.ROOT));
            ps.setString(2, staffName);
            ps.setLong(3, unbannedAt);
            ps.setString(4, uuid.toString().toLowerCase(java.util.Locale.ROOT));
            return ps.executeUpdate();
        }
    }

    public KickRecord insertKick(UUID uuid, String name, String reason, UUID staffUuid, String staffName) throws SQLException {
        long kickedAt = System.currentTimeMillis();
        String sql = "INSERT INTO " + prefixChecked() + "kicks (uuid, name, reason, staff_uuid, staff_name, kicked_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString().toLowerCase(java.util.Locale.ROOT));
            ps.setString(2, name);
            ps.setString(3, reason);
            ps.setString(4, staffUuid == null ? null : staffUuid.toString().toLowerCase(java.util.Locale.ROOT));
            ps.setString(5, staffName);
            ps.setLong(6, kickedAt);
            ps.executeUpdate();
            int id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                id = keys.next() ? keys.getInt(1) : -1;
            }
            return new KickRecord(id, uuid, name, reason, staffUuid, staffName, kickedAt);
        }
    }

    public IpBanRecord insertIpBan(String ip, String reason, UUID staffUuid, String staffName, long duration) throws SQLException {
        long bannedAt = System.currentTimeMillis();
        String sql = "INSERT INTO " + prefixChecked() + "ip_bans (ip, reason, staff_uuid, staff_name, banned_at, duration, revoked, unbanned_by_uuid, unbanned_by_name, unbanned_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 0, NULL, NULL, 0)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, clip255(ip, "ip_ban.ip"));
            ps.setString(2, reason);
            ps.setString(3, staffUuid == null ? null : staffUuid.toString().toLowerCase(java.util.Locale.ROOT));
            ps.setString(4, staffName);
            ps.setLong(5, bannedAt);
            ps.setLong(6, duration);
            ps.executeUpdate();
            int id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                id = keys.next() ? keys.getInt(1) : -1;
            }
            return new IpBanRecord(id, ip, reason, staffUuid, staffName, bannedAt, duration, false, null, null, 0L);
        }
    }

    /**
     * Revokes ALL unrevoked rows for the IP (not just one id); success is
     * decided on the returned row count. MySQL cross-server race caveat: see
     * {@link #revokeBan}.
     *
     * @return number of rows revoked
     */
    public int revokeIpBan(String ip, UUID staffUuid, String staffName, long unbannedAt) throws SQLException {
        String sql = "UPDATE " + prefixChecked() + "ip_bans SET revoked = 1, unbanned_by_uuid = ?, unbanned_by_name = ?, unbanned_at = ? WHERE ip = ? AND revoked = 0";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, staffUuid == null ? null : staffUuid.toString().toLowerCase(java.util.Locale.ROOT));
            ps.setString(2, staffName);
            ps.setLong(3, unbannedAt);
            ps.setString(4, ip);
            return ps.executeUpdate();
        }
    }

    public void trackPlayerIp(UUID uuid, String name, String ip) throws SQLException {
        String tablePrefix = prefixChecked();
        String sql = mysql
                ? "INSERT INTO " + tablePrefix + "player_ips (uuid, name, last_ip, last_seen) VALUES (?, ?, ?, ?) " +
                  "ON DUPLICATE KEY UPDATE name = ?, last_ip = ?, last_seen = ?"
                : "INSERT INTO " + tablePrefix + "player_ips (uuid, name, last_ip, last_seen) VALUES (?, ?, ?, ?) " +
                  "ON CONFLICT(uuid) DO UPDATE SET name = ?, last_ip = ?, last_seen = ?";
        long now = System.currentTimeMillis();
        String clippedName = clip255(name, "player_ips.name");
        String clippedIp = clip255(ip, "player_ips.last_ip");
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString().toLowerCase(java.util.Locale.ROOT));
            ps.setString(2, clippedName);
            ps.setString(3, clippedIp);
            ps.setLong(4, now);
            ps.setString(5, clippedName);
            ps.setString(6, clippedIp);
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }

    /**
     * @return the last known IP for a player name, or null if unknown/never seen
     */
    public String findLastIpByName(String name) throws SQLException {
        String sql = "SELECT last_ip FROM " + prefixChecked() + "player_ips WHERE LOWER(name) = LOWER(?) ORDER BY last_seen DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ---- Reads (used for the periodic local cache refresh AND for the
    // authoritative, always-fresh login check — see BanManager/ModerationListener) ----

    /**
     * Cache window for the history loads below: history tables are unbounded
     * (rows are never deleted), so the periodic refresh only loads active or
     * recent rows instead of the full history. There is no
     * {@code expires_at}/{@code unbanned} column to filter on — the only
     * state columns that exist are {@code revoked}, {@code banned_at},
     * {@code duration} and {@code unbanned_at}, so the filter is built from
     * those (matches the live findActive* queries' semantics). Ban/kick
     * totals derived from the cache therefore cover this window; the database
     * itself keeps every row forever.
     */
    private static final long HISTORY_WINDOW_MILLIS = 90L * 24 * 60 * 60 * 1000;

    public List<BanRecord> loadAllBans() throws SQLException {
        List<BanRecord> result = new ArrayList<>();
        String tablePrefix = prefixChecked();
        long cutoff = System.currentTimeMillis() - HISTORY_WINDOW_MILLIS;
        String sql = "SELECT id, uuid, name, reason, staff_uuid, staff_name, banned_at, duration, revoked, unbanned_by_uuid, unbanned_by_name, unbanned_at FROM "
                + tablePrefix + "bans WHERE revoked = 0 OR banned_at > ? OR unbanned_at > ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            ps.setLong(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        result.add(mapBan(rs));
                    } catch (RuntimeException e) {
                        // A single corrupt row (bad UUID, unexpected null) must never
                        // discard the whole refresh — skip it loudly, keep the rest.
                        // Genuine SQLExceptions still propagate and keep the old cache.
                        plugin.getLogger().warning("Skipping corrupt row in " + tablePrefix + "bans: " + e.getMessage());
                    }
                }
            }
        }
        return result;
    }

    public List<KickRecord> loadAllKicks() throws SQLException {
        List<KickRecord> result = new ArrayList<>();
        String tablePrefix = prefixChecked();
        long cutoff = System.currentTimeMillis() - HISTORY_WINDOW_MILLIS;
        String sql = "SELECT id, uuid, name, reason, staff_uuid, staff_name, kicked_at FROM "
                + tablePrefix + "kicks WHERE kicked_at > ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        result.add(mapKick(rs));
                    } catch (RuntimeException e) {
                        // Same row-skip policy as loadAllBans (see above).
                        plugin.getLogger().warning("Skipping corrupt row in " + tablePrefix + "kicks: " + e.getMessage());
                    }
                }
            }
        }
        return result;
    }

    public List<IpBanRecord> loadAllIpBans() throws SQLException {
        List<IpBanRecord> result = new ArrayList<>();
        String tablePrefix = prefixChecked();
        long cutoff = System.currentTimeMillis() - HISTORY_WINDOW_MILLIS;
        String sql = "SELECT id, ip, reason, staff_uuid, staff_name, banned_at, duration, revoked, unbanned_by_uuid, unbanned_by_name, unbanned_at FROM "
                + tablePrefix + "ip_bans WHERE revoked = 0 OR banned_at > ? OR unbanned_at > ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            ps.setLong(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        IpBanRecord record = mapIpBan(rs);
                        if (record.ip() == null || record.ip().isBlank()) {
                            plugin.getLogger().warning("Skipping " + tablePrefix + "ip_bans row with missing IP.");
                            continue;
                        }
                        result.add(record);
                    } catch (RuntimeException e) {
                        // Same row-skip policy as loadAllBans (see above).
                        plugin.getLogger().warning("Skipping corrupt row in " + tablePrefix + "ip_bans: " + e.getMessage());
                    }
                }
            }
        }
        return result;
    }

    /**
     * Authoritative, always-fresh check — queries the DB directly rather than
     * a local cache, so a ban issued on another server (MySQL backend) is
     * enforced immediately on THIS server's very next login attempt.
     */
    public BanRecord findActiveBan(UUID uuid, long now) throws SQLException {
        // Overflow-free expiry check: (? - banned_at < duration) never adds
        // two large longs (banned_at + duration could wrap for huge tempbans).
        // Exact uuid = ? comparison (no LOWER() on the column): all writes
        // store lowercase UUIDs and connect() normalizes legacy rows, so the
        // index on uuid is actually used on every login check.
        String sql = "SELECT id, uuid, name, reason, staff_uuid, staff_name, banned_at, duration, revoked, unbanned_by_uuid, unbanned_by_name, unbanned_at " +
                "FROM " + prefixChecked() + "bans WHERE uuid = ? AND revoked = 0 AND (duration < 0 OR (? - banned_at < duration)) ORDER BY banned_at DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString().toLowerCase(java.util.Locale.ROOT));
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapBan(rs) : null;
            }
        }
    }

    public IpBanRecord findActiveIpBan(String ip, long now) throws SQLException {
        // Overflow-free expiry check (same reasoning as findActiveBan).
        String sql = "SELECT id, ip, reason, staff_uuid, staff_name, banned_at, duration, revoked, unbanned_by_uuid, unbanned_by_name, unbanned_at " +
                "FROM " + prefixChecked() + "ip_bans WHERE ip = ? AND revoked = 0 AND (duration < 0 OR (? - banned_at < duration)) ORDER BY banned_at DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapIpBan(rs) : null;
            }
        }
    }

    private BanRecord mapBan(ResultSet rs) throws SQLException {
        return new BanRecord(
                rs.getInt(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                nullableUuid(rs.getString(5)), rs.getString(6), rs.getLong(7), rs.getLong(8),
                rs.getBoolean(9), nullableUuid(rs.getString(10)), rs.getString(11), rs.getLong(12)
        );
    }

    private KickRecord mapKick(ResultSet rs) throws SQLException {
        return new KickRecord(
                rs.getInt(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                nullableUuid(rs.getString(5)), rs.getString(6), rs.getLong(7)
        );
    }

    private IpBanRecord mapIpBan(ResultSet rs) throws SQLException {
        return new IpBanRecord(
                rs.getInt(1), rs.getString(2), rs.getString(3),
                nullableUuid(rs.getString(4)), rs.getString(5), rs.getLong(6), rs.getLong(7),
                rs.getBoolean(8), nullableUuid(rs.getString(9)), rs.getString(10), rs.getLong(11)
        );
    }

    private UUID nullableUuid(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
