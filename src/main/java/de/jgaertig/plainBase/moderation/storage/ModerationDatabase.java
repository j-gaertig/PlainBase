package de.jgaertig.plainBase.moderation.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.jgaertig.plainBase.PlainBase;
import de.jgaertig.plainBase.moderation.BanRecord;
import de.jgaertig.plainBase.moderation.IpBanRecord;
import de.jgaertig.plainBase.moderation.KickRecord;

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
        this.mysql = "mysql".equalsIgnoreCase(plugin.getModerationConfig().getString("storage.type", "sqlite"));
    }

    /**
     * Opens the pool and creates tables if missing. Called once from
     * setupModeration() — synchronous, but happens at plugin/module startup
     * before any player can connect (same timing as loadModuleConfig()).
     */
    public void connect() throws SQLException {
        HikariConfig config = new HikariConfig();

        if (mysql) {
            String host = plugin.getModerationConfig().getString("storage.mysql.host", "localhost");
            int port = plugin.getModerationConfig().getInt("storage.mysql.port", 3306);
            String database = plugin.getModerationConfig().getString("storage.mysql.database", "plainbase");
            boolean useSsl = plugin.getModerationConfig().getBoolean("storage.mysql.useSSL", false);
            config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + useSsl + "&characterEncoding=utf8&autoReconnect=true");
            config.setUsername(plugin.getModerationConfig().getString("storage.mysql.username", "root"));
            config.setPassword(plugin.getModerationConfig().getString("storage.mysql.password", ""));
            config.setMaximumPoolSize(Math.max(2, plugin.getModerationConfig().getInt("storage.mysql.pool-size", 5)));
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        } else {
            File dataDir = new File(plugin.getDataFolder(), "data");
            dataDir.mkdirs();
            String fileName = plugin.getModerationConfig().getString("storage.sqlite.file", "moderation.db");
            File dbFile = new File(dataDir, fileName);
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            // SQLite has no real concurrent-writer story — a single pooled
            // connection avoids "database is locked" errors under load.
            config.setMaximumPoolSize(1);
            config.setDriverClassName("org.sqlite.JDBC");
        }

        config.setPoolName("PlainBase-Moderation");
        dataSource = new HikariDataSource(config);

        try (Connection conn = dataSource.getConnection()) {
            createTables(conn);
        }
    }

    public void close() {
        if (dataSource != null) dataSource.close();
    }

    private String prefix() {
        return mysql ? plugin.getModerationConfig().getString("storage.mysql.table-prefix", "pb_") : "pb_";
    }

    private void createTables(Connection conn) throws SQLException {
        String p = prefix();
        String autoInc = mysql ? "INT AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String varchar = mysql ? "VARCHAR(255)" : "TEXT";

        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "bans (" +
                    "id " + autoInc + ", " +
                    "uuid " + varchar + " NOT NULL, " +
                    "name " + varchar + ", " +
                    "reason " + varchar + ", " +
                    "staff_uuid " + varchar + ", " +
                    "staff_name " + varchar + ", " +
                    "banned_at BIGINT NOT NULL, " +
                    "duration BIGINT NOT NULL, " +
                    "revoked BOOLEAN NOT NULL DEFAULT 0, " +
                    "unbanned_by_uuid " + varchar + ", " +
                    "unbanned_by_name " + varchar + ", " +
                    "unbanned_at BIGINT NOT NULL DEFAULT 0)");
            createIndexIfMissing(st, p + "bans_uuid_idx", p + "bans", "uuid");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "kicks (" +
                    "id " + autoInc + ", " +
                    "uuid " + varchar + " NOT NULL, " +
                    "name " + varchar + ", " +
                    "reason " + varchar + ", " +
                    "staff_uuid " + varchar + ", " +
                    "staff_name " + varchar + ", " +
                    "kicked_at BIGINT NOT NULL)");
            createIndexIfMissing(st, p + "kicks_uuid_idx", p + "kicks", "uuid");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "ip_bans (" +
                    "id " + autoInc + ", " +
                    "ip " + varchar + " NOT NULL, " +
                    "reason " + varchar + ", " +
                    "staff_uuid " + varchar + ", " +
                    "staff_name " + varchar + ", " +
                    "banned_at BIGINT NOT NULL, " +
                    "duration BIGINT NOT NULL, " +
                    "revoked BOOLEAN NOT NULL DEFAULT 0, " +
                    "unbanned_by_uuid " + varchar + ", " +
                    "unbanned_by_name " + varchar + ", " +
                    "unbanned_at BIGINT NOT NULL DEFAULT 0)");
            createIndexIfMissing(st, p + "ip_bans_ip_idx", p + "ip_bans", "ip");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "player_ips (" +
                    "uuid " + varchar + " NOT NULL, " +
                    "name " + varchar + ", " +
                    "last_ip " + varchar + ", " +
                    "last_seen BIGINT NOT NULL, " +
                    "PRIMARY KEY (uuid))");
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
        String sql = "INSERT INTO " + prefix() + "bans (uuid, name, reason, staff_uuid, staff_name, banned_at, duration, revoked, unbanned_by_uuid, unbanned_by_name, unbanned_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 0, '', '', 0)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, reason);
            ps.setString(4, staffUuid == null ? "" : staffUuid.toString());
            ps.setString(5, staffName);
            ps.setLong(6, bannedAt);
            ps.setLong(7, duration);
            ps.executeUpdate();
            int id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                id = keys.next() ? keys.getInt(1) : -1;
            }
            return new BanRecord(id, uuid, name, reason, staffUuid, staffName, bannedAt, duration, false, null, "", 0L);
        }
    }

    public void revokeBan(int id, UUID staffUuid, String staffName, long unbannedAt) throws SQLException {
        String sql = "UPDATE " + prefix() + "bans SET revoked = 1, unbanned_by_uuid = ?, unbanned_by_name = ?, unbanned_at = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, staffUuid == null ? "" : staffUuid.toString());
            ps.setString(2, staffName);
            ps.setLong(3, unbannedAt);
            ps.setInt(4, id);
            ps.executeUpdate();
        }
    }

    public KickRecord insertKick(UUID uuid, String name, String reason, UUID staffUuid, String staffName) throws SQLException {
        long kickedAt = System.currentTimeMillis();
        String sql = "INSERT INTO " + prefix() + "kicks (uuid, name, reason, staff_uuid, staff_name, kicked_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, reason);
            ps.setString(4, staffUuid == null ? "" : staffUuid.toString());
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
        String sql = "INSERT INTO " + prefix() + "ip_bans (ip, reason, staff_uuid, staff_name, banned_at, duration, revoked, unbanned_by_uuid, unbanned_by_name, unbanned_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 0, '', '', 0)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, ip);
            ps.setString(2, reason);
            ps.setString(3, staffUuid == null ? "" : staffUuid.toString());
            ps.setString(4, staffName);
            ps.setLong(5, bannedAt);
            ps.setLong(6, duration);
            ps.executeUpdate();
            int id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                id = keys.next() ? keys.getInt(1) : -1;
            }
            return new IpBanRecord(id, ip, reason, staffUuid, staffName, bannedAt, duration, false, null, "", 0L);
        }
    }

    public void revokeIpBan(int id, UUID staffUuid, String staffName, long unbannedAt) throws SQLException {
        String sql = "UPDATE " + prefix() + "ip_bans SET revoked = 1, unbanned_by_uuid = ?, unbanned_by_name = ?, unbanned_at = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, staffUuid == null ? "" : staffUuid.toString());
            ps.setString(2, staffName);
            ps.setLong(3, unbannedAt);
            ps.setInt(4, id);
            ps.executeUpdate();
        }
    }

    public void trackPlayerIp(UUID uuid, String name, String ip) throws SQLException {
        String sql = mysql
                ? "INSERT INTO " + prefix() + "player_ips (uuid, name, last_ip, last_seen) VALUES (?, ?, ?, ?) " +
                  "ON DUPLICATE KEY UPDATE name = ?, last_ip = ?, last_seen = ?"
                : "INSERT INTO " + prefix() + "player_ips (uuid, name, last_ip, last_seen) VALUES (?, ?, ?, ?) " +
                  "ON CONFLICT(uuid) DO UPDATE SET name = ?, last_ip = ?, last_seen = ?";
        long now = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, ip);
            ps.setLong(4, now);
            ps.setString(5, name);
            ps.setString(6, ip);
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }

    /**
     * @return the last known IP for a player name, or null if unknown/never seen
     */
    public String findLastIpByName(String name) throws SQLException {
        String sql = "SELECT last_ip FROM " + prefix() + "player_ips WHERE name = ? ORDER BY last_seen DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ---- Reads (used for the periodic local cache refresh AND for the
    // authoritative, always-fresh login check — see BanManager/ModerationListener) ----

    public List<BanRecord> loadAllBans() throws SQLException {
        List<BanRecord> result = new ArrayList<>();
        String sql = "SELECT id, uuid, name, reason, staff_uuid, staff_name, banned_at, duration, revoked, unbanned_by_uuid, unbanned_by_name, unbanned_at FROM " + prefix() + "bans";
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) result.add(mapBan(rs));
        }
        return result;
    }

    public List<KickRecord> loadAllKicks() throws SQLException {
        List<KickRecord> result = new ArrayList<>();
        String sql = "SELECT id, uuid, name, reason, staff_uuid, staff_name, kicked_at FROM " + prefix() + "kicks";
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new KickRecord(rs.getInt(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                        emptyToNull(rs.getString(5)) == null ? null : UUID.fromString(rs.getString(5)), rs.getString(6), rs.getLong(7)));
            }
        }
        return result;
    }

    public List<IpBanRecord> loadAllIpBans() throws SQLException {
        List<IpBanRecord> result = new ArrayList<>();
        String sql = "SELECT id, ip, reason, staff_uuid, staff_name, banned_at, duration, revoked, unbanned_by_uuid, unbanned_by_name, unbanned_at FROM " + prefix() + "ip_bans";
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) result.add(mapIpBan(rs));
        }
        return result;
    }

    /**
     * Authoritative, always-fresh check — queries the DB directly rather than
     * a local cache, so a ban issued on another server (MySQL backend) is
     * enforced immediately on THIS server's very next login attempt.
     */
    public BanRecord findActiveBan(UUID uuid, long now) throws SQLException {
        String sql = "SELECT id, uuid, name, reason, staff_uuid, staff_name, banned_at, duration, revoked, unbanned_by_uuid, unbanned_by_name, unbanned_at " +
                "FROM " + prefix() + "bans WHERE uuid = ? AND revoked = 0 AND (duration < 0 OR banned_at + duration > ?) ORDER BY banned_at DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapBan(rs) : null;
            }
        }
    }

    public IpBanRecord findActiveIpBan(String ip, long now) throws SQLException {
        String sql = "SELECT id, ip, reason, staff_uuid, staff_name, banned_at, duration, revoked, unbanned_by_uuid, unbanned_by_name, unbanned_at " +
                "FROM " + prefix() + "ip_bans WHERE ip = ? AND revoked = 0 AND (duration < 0 OR banned_at + duration > ?) ORDER BY banned_at DESC LIMIT 1";
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

    private String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
