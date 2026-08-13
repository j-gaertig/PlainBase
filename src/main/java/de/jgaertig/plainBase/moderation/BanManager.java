package de.jgaertig.plainBase.moderation;

import de.jgaertig.plainBase.PlainBase;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds every ban/kick record (active + historic) in memory, keyed by UUID
 * so a name change never evades a ban. Reads/writes go to
 * {@code plugins/PlainBase/data/bans.yml} and {@code data/kicks.yml}.
 * <p>
 * Loading happens once, synchronously, during {@code setupModeration()} —
 * i.e. at plugin/module startup, before any player can connect. That is the
 * same timing as {@code loadModuleConfig()} and is NOT a join-event read.
 * All later mutations are persisted via {@code Bukkit.getAsyncScheduler()},
 * matching {@code TPAManager#savePlayerData}. Lookups
 * (getActiveBan/getBanCount/...) are pure in-memory map reads and are safe
 * to call from {@code AsyncPlayerPreLoginEvent} (which runs off the main
 * thread) because {@link ConcurrentHashMap} and
 * {@link CopyOnWriteArrayList} are used throughout.
 */
public class BanManager {

    private final PlainBase plugin;

    private final List<BanRecord> bans = new CopyOnWriteArrayList<>();
    private final List<KickRecord> kicks = new CopyOnWriteArrayList<>();
    private final Map<UUID, List<BanRecord>> bansByUuid = new ConcurrentHashMap<>();
    private final Map<UUID, List<KickRecord>> kicksByUuid = new ConcurrentHashMap<>();

    private final AtomicInteger nextBanId = new AtomicInteger(1);
    private final AtomicInteger nextKickId = new AtomicInteger(1);

    // Serializes the actual file writes so two save calls in quick succession
    // (e.g. ban immediately followed by unban) can never interleave their
    // writes to the same bans.yml/kicks.yml on different async-scheduler threads.
    private final Object bansFileLock = new Object();
    private final Object kicksFileLock = new Object();

    public BanManager(PlainBase plugin) {
        this.plugin = plugin;
        load();
    }

    // ---- Queries (thread-safe, safe to call async) ----

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
        for (BanRecord record : bans) {
            if (record.isActive(now)) active.add(record);
        }
        active.sort((a, b) -> Long.compare(b.bannedAt(), a.bannedAt()));
        return active;
    }

    // ---- Mutations ----

    public BanRecord banPlayer(UUID uuid, String name, String reason, UUID staffUuid, String staffName, long durationMillis) {
        BanRecord record = new BanRecord(
                nextBanId.getAndIncrement(), uuid, name, reason, staffUuid, staffName,
                System.currentTimeMillis(), durationMillis, false, null, "", 0L
        );
        bans.add(record);
        bansByUuid.computeIfAbsent(uuid, k -> new CopyOnWriteArrayList<>()).add(record);
        saveBans();
        return record;
    }

    /**
     * @return true if an active ban was found and revoked, false if the player wasn't banned
     */
    public boolean unbanPlayer(UUID uuid, UUID staffUuid, String staffName) {
        Optional<BanRecord> active = getActiveBan(uuid);
        if (active.isEmpty()) return false;

        BanRecord old = active.get();
        BanRecord revoked = old.withRevoked(staffUuid, staffName, System.currentTimeMillis());

        List<BanRecord> history = bansByUuid.get(uuid);
        int idx = history.indexOf(old);
        if (idx >= 0) history.set(idx, revoked);

        int allIdx = bans.indexOf(old);
        if (allIdx >= 0) bans.set(allIdx, revoked);

        saveBans();
        return true;
    }

    public KickRecord recordKick(UUID uuid, String name, String reason, UUID staffUuid, String staffName) {
        KickRecord record = new KickRecord(nextKickId.getAndIncrement(), uuid, name, reason, staffUuid, staffName, System.currentTimeMillis());
        kicks.add(record);
        kicksByUuid.computeIfAbsent(uuid, k -> new CopyOnWriteArrayList<>()).add(record);
        saveKicks();
        return record;
    }

    // ---- Persistence ----

    private File bansFile() {
        return new File(plugin.getDataFolder(), "data/bans.yml");
    }

    private File kicksFile() {
        return new File(plugin.getDataFolder(), "data/kicks.yml");
    }

    private void load() {
        File bf = bansFile();
        if (bf.exists()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(bf);
            nextBanId.set(config.getInt("next-id", 1));
            ConfigurationSection section = config.getConfigurationSection("bans");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    try {
                        BanRecord record = readBan(section, key);
                        bans.add(record);
                        bansByUuid.computeIfAbsent(record.uuid(), k -> new CopyOnWriteArrayList<>()).add(record);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Could not read ban entry " + key + ": " + e.getMessage());
                    }
                }
            }
        }

        File kf = kicksFile();
        if (kf.exists()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(kf);
            nextKickId.set(config.getInt("next-id", 1));
            ConfigurationSection section = config.getConfigurationSection("kicks");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    try {
                        KickRecord record = readKick(section, key);
                        kicks.add(record);
                        kicksByUuid.computeIfAbsent(record.uuid(), k -> new CopyOnWriteArrayList<>()).add(record);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Could not read kick entry " + key + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    private BanRecord readBan(ConfigurationSection section, String key) {
        String p = key + ".";
        return new BanRecord(
                Integer.parseInt(key),
                UUID.fromString(section.getString(p + "uuid")),
                section.getString(p + "name", "?"),
                section.getString(p + "reason", "No reason specified."),
                parseUuidOrNull(section.getString(p + "staff-uuid")),
                section.getString(p + "staff-name", "Console"),
                section.getLong(p + "banned-at"),
                section.getLong(p + "duration", -1L),
                section.getBoolean(p + "revoked", false),
                parseUuidOrNull(section.getString(p + "unbanned-by-uuid")),
                section.getString(p + "unbanned-by-name", ""),
                section.getLong(p + "unbanned-at", 0L)
        );
    }

    private KickRecord readKick(ConfigurationSection section, String key) {
        String p = key + ".";
        return new KickRecord(
                Integer.parseInt(key),
                UUID.fromString(section.getString(p + "uuid")),
                section.getString(p + "name", "?"),
                section.getString(p + "reason", "No reason specified."),
                parseUuidOrNull(section.getString(p + "staff-uuid")),
                section.getString(p + "staff-name", "Console"),
                section.getLong(p + "kicked-at")
        );
    }

    private UUID parseUuidOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Persisted off the main thread (Bukkit.getAsyncScheduler()), same pattern as
     * TPAManager#savePlayerData — never blocks the caller's thread with file IO.
     */
    private void saveBans() {
        // Snapshot is taken INSIDE the async task (at execution time), not
        // before scheduling: multiple ban/unban calls in quick succession
        // each schedule their own save, and async tasks are not guaranteed
        // to finish in submission order. Reading the CopyOnWriteArrayList
        // fresh when the task actually runs means every write reflects
        // whatever is truly current at that moment (mutations always happen
        // synchronously on the calling thread before scheduling), so a
        // late-finishing older task can never clobber a newer one's data.
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            synchronized (bansFileLock) {
                List<BanRecord> snapshot = List.copyOf(bans);
                YamlConfiguration config = new YamlConfiguration();
                config.set("next-id", nextBanId.get());
                for (BanRecord record : snapshot) {
                    String p = "bans." + record.id() + ".";
                    config.set(p + "uuid", record.uuid().toString());
                    config.set(p + "name", record.name());
                    config.set(p + "reason", record.reason());
                    config.set(p + "staff-uuid", record.staffUuid() == null ? "" : record.staffUuid().toString());
                    config.set(p + "staff-name", record.staffName());
                    config.set(p + "banned-at", record.bannedAt());
                    config.set(p + "duration", record.duration());
                    config.set(p + "revoked", record.revoked());
                    config.set(p + "unbanned-by-uuid", record.unbannedByUuid() == null ? "" : record.unbannedByUuid().toString());
                    config.set(p + "unbanned-by-name", record.unbannedByName());
                    config.set(p + "unbanned-at", record.unbannedAt());
                }
                try {
                    File file = bansFile();
                    file.getParentFile().mkdirs();
                    config.save(file);
                } catch (IOException e) {
                    plugin.getLogger().severe("Could not save bans.yml: " + e.getMessage());
                }
            }
        });
    }

    private void saveKicks() {
        // Same execution-time-snapshot reasoning as saveBans() above.
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            synchronized (kicksFileLock) {
                List<KickRecord> snapshot = List.copyOf(kicks);
                YamlConfiguration config = new YamlConfiguration();
                config.set("next-id", nextKickId.get());
                for (KickRecord record : snapshot) {
                    String p = "kicks." + record.id() + ".";
                    config.set(p + "uuid", record.uuid().toString());
                    config.set(p + "name", record.name());
                    config.set(p + "reason", record.reason());
                    config.set(p + "staff-uuid", record.staffUuid() == null ? "" : record.staffUuid().toString());
                    config.set(p + "staff-name", record.staffName());
                    config.set(p + "kicked-at", record.kickedAt());
                }
                try {
                    File file = kicksFile();
                    file.getParentFile().mkdirs();
                    config.save(file);
                } catch (IOException e) {
                    plugin.getLogger().severe("Could not save kicks.yml: " + e.getMessage());
                }
            }
        });
    }
}
