package de.jgaertig.plainBase.moderation;

import java.util.UUID;

/**
 * A single ban entry, kept forever (even after unban / expiry) as history.
 * Stored keyed by the player's UUID so a name change can never evade a ban.
 * {@code staffUuid} is null when the ban was issued from the console.
 */
public record BanRecord(
        int id,
        UUID uuid,
        String name,
        String reason,
        UUID staffUuid,
        String staffName,
        long bannedAt,
        long duration,       // millis, -1 = permanent
        boolean revoked,
        UUID unbannedByUuid, // nullable
        String unbannedByName,
        long unbannedAt
) {

    public boolean isPermanent() {
        return duration < 0;
    }

    /**
     * Saturating expiry timestamp (overflow-free): permanent bans map to
     * {@link Long#MAX_VALUE} so no caller ever adds {@code bannedAt + duration}
     * directly.
     */
    public long expiryMillis() {
        if (isPermanent()) return Long.MAX_VALUE;
        if (duration > Long.MAX_VALUE - bannedAt) return Long.MAX_VALUE;
        return bannedAt + duration;
    }

    public boolean isActive(long now) {
        if (revoked) return false;
        if (isPermanent()) return true;
        return now < expiryMillis();
    }

    /**
     * Remaining ban duration in millis, or -1 if permanent. 0 if already expired.
     * Saturates at {@link Long#MAX_VALUE} for far-future expiries instead of
     * overflowing.
     */
    public long remainingMillis(long now) {
        if (isPermanent()) return -1;
        long expiry = expiryMillis();
        if (expiry == Long.MAX_VALUE) return Long.MAX_VALUE - now;
        return Math.max(0, expiry - now);
    }

    public BanRecord withRevoked(UUID byUuid, String byName, long at) {
        return new BanRecord(id, uuid, name, reason, staffUuid, staffName, bannedAt, duration, true, byUuid, byName, at);
    }
}
