package de.jgaertig.plainBase;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared per-UUID file locks for {@code data/playerdata/<uuid>.yml}.
 * <p>
 * TPAManager (key {@code tpauto}) and VanishManager (key {@code vanished})
 * share the same physical file and both do load-merge-save cycles. Without a
 * common lock a concurrent vanish-save and tpauto-save can interleave: both
 * read the same old content, then each overwrites the other's key.
 * <p>
 * Fixed-size stripe array (not one lock object per UUID in a map, which would
 * grow without bound). Collisions only reduce concurrency, never correctness.
 * Static on purpose: the lock must hold across manager instances (reload
 * creates fresh managers while the old one's async saves are still in flight).
 */
public final class PlayerDataLocks {

    private static final Object[] STRIPES = init(64);
    private static final ConcurrentHashMap<UUID, TpAutoSaveState> TP_AUTO_SAVES = new ConcurrentHashMap<>();

    private static final class TpAutoSaveState {
        private long latestVersion;
        private int activeSaves;
    }

    private PlayerDataLocks() {
    }

    private static Object[] init(int size) {
        Object[] stripes = new Object[size];
        for (int i = 0; i < size; i++) stripes[i] = new Object();
        return stripes;
    }

    public static Object lockFor(UUID uuid) {
        int hash = uuid == null ? 0 : uuid.hashCode();
        return STRIPES[(hash & 0x7fffffff) % STRIPES.length];
    }

    /** Register a save generation shared by manager instances across reload. */
    public static long beginTpAutoSave(UUID uuid) {
        synchronized (lockFor(uuid)) {
            TpAutoSaveState state = TP_AUTO_SAVES.computeIfAbsent(uuid, ignored -> new TpAutoSaveState());
            state.latestVersion++;
            state.activeSaves++;
            return state.latestVersion;
        }
    }

    /** True only while this is the newest TP-Auto save for this UUID. */
    public static boolean isCurrentTpAutoSave(UUID uuid, long version) {
        synchronized (lockFor(uuid)) {
            TpAutoSaveState state = TP_AUTO_SAVES.get(uuid);
            return state != null && state.latestVersion == version;
        }
    }

    /** Release a registered save; discard per-player tracking when no task remains. */
    public static void finishTpAutoSave(UUID uuid) {
        synchronized (lockFor(uuid)) {
            TpAutoSaveState state = TP_AUTO_SAVES.get(uuid);
            if (state == null) return;
            if (--state.activeSaves <= 0) TP_AUTO_SAVES.remove(uuid, state);
        }
    }
}
