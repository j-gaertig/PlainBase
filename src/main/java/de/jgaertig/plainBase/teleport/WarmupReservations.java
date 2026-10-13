package de.jgaertig.plainBase.teleport;

import org.bukkit.Location;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared atomic ownership for TPA and RTP warmups across manager instances. */
public final class WarmupReservations {
    private static final Map<UUID, Object> OWNERS = new HashMap<>();
    private static final Map<UUID, ExpectedTeleport> EXPECTED_TELEPORTS = new HashMap<>();

    private record ExpectedTeleport(Object identity, UUID worldId, double x, double y, double z) {}

    private WarmupReservations() {}

    public static synchronized boolean reserve(UUID playerId, Object identity) {
        if (playerId == null || identity == null || OWNERS.containsKey(playerId)) return false;
        OWNERS.put(playerId, identity);
        return true;
    }

    /** Atomically reserves both the teleporter and the player anchoring a TPA. */
    public static synchronized boolean reservePair(UUID playerId, UUID anchorId, Object identity) {
        if (playerId == null || anchorId == null || identity == null || playerId.equals(anchorId)
                || OWNERS.containsKey(playerId) || OWNERS.containsKey(anchorId)) return false;
        OWNERS.put(playerId, identity);
        OWNERS.put(anchorId, identity);
        return true;
    }

    public static synchronized boolean isReserved(UUID playerId) {
        return playerId != null && OWNERS.containsKey(playerId);
    }

    public static synchronized boolean isOwner(UUID playerId, Object identity) {
        return playerId != null && identity != null && OWNERS.get(playerId) == identity;
    }

    /** Marks the exact destination of this warmup's pending teleportAsync call. */
    public static synchronized boolean expectTeleport(UUID playerId, Object identity, Location destination) {
        if (playerId == null || identity == null || destination == null || destination.getWorld() == null
                || OWNERS.get(playerId) != identity) return false;
        EXPECTED_TELEPORTS.put(playerId, new ExpectedTeleport(identity, destination.getWorld().getUID(),
                destination.getX(), destination.getY(), destination.getZ()));
        return true;
    }

    /** True only for the owned warmup's own teleport event; leaves the marker until completion. */
    public static synchronized boolean isExpectedTeleport(UUID playerId, Location destination) {
        if (playerId == null || destination == null || destination.getWorld() == null) return false;
        ExpectedTeleport expected = EXPECTED_TELEPORTS.get(playerId);
        return expected != null && OWNERS.get(playerId) == expected.identity()
                && expected.worldId().equals(destination.getWorld().getUID())
                && Math.abs(expected.x() - destination.getX()) <= 1.0E-5
                && Math.abs(expected.y() - destination.getY()) <= 1.0E-5
                && Math.abs(expected.z() - destination.getZ()) <= 1.0E-5;
    }

    /** World-change events do not carry a destination, so compare the player's new world. */
    public static synchronized boolean isExpectedWorldChange(UUID playerId, UUID worldId) {
        if (playerId == null || worldId == null) return false;
        ExpectedTeleport expected = EXPECTED_TELEPORTS.get(playerId);
        return expected != null && OWNERS.get(playerId) == expected.identity()
                && expected.worldId().equals(worldId);
    }

    /** Once teleportAsync has been initiated, cancellation cannot reliably stop it. */
    public static synchronized boolean isTeleportInFlight(UUID playerId, Object identity) {
        if (playerId == null || identity == null || OWNERS.get(playerId) != identity) return false;
        ExpectedTeleport expected = EXPECTED_TELEPORTS.get(playerId);
        return expected != null && expected.identity() == identity;
    }

    public static synchronized void release(UUID playerId, Object identity) {
        if (playerId == null || identity == null || OWNERS.get(playerId) != identity) return;
        OWNERS.remove(playerId);
        ExpectedTeleport expected = EXPECTED_TELEPORTS.get(playerId);
        if (expected != null && expected.identity() == identity) EXPECTED_TELEPORTS.remove(playerId);
    }

    /** Releases every role held by this operation, including a TPA anchor. */
    public static synchronized void releaseIdentity(Object identity) {
        if (identity == null) return;
        OWNERS.entrySet().removeIf(entry -> {
            if (entry.getValue() != identity) return false;
            EXPECTED_TELEPORTS.remove(entry.getKey());
            return true;
        });
    }
}
