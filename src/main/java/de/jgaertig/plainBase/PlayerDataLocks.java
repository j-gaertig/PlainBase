package de.jgaertig.plainBase;

import java.util.UUID;

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
}
