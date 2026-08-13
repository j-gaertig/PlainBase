package de.jgaertig.plainBase.moderation;

import java.util.UUID;

/**
 * An IP ban entry — mirrors {@link BanRecord} but is keyed by the raw IP
 * address string instead of a player UUID. Login is blocked if either the
 * player's UUID OR their connecting IP has an active ban.
 */
public record IpBanRecord(
        int id,
        String ip,
        String reason,
        UUID staffUuid,
        String staffName,
        long bannedAt,
        long duration,       // millis, -1 = permanent
        boolean revoked,
        UUID unbannedByUuid,
        String unbannedByName,
        long unbannedAt
) {

    public boolean isPermanent() {
        return duration < 0;
    }

    public boolean isActive(long now) {
        if (revoked) return false;
        return isPermanent() || now < bannedAt + duration;
    }

    public long remainingMillis(long now) {
        if (isPermanent()) return -1;
        return Math.max(0, (bannedAt + duration) - now);
    }

    public IpBanRecord withRevoked(UUID byUuid, String byName, long at) {
        return new IpBanRecord(id, ip, reason, staffUuid, staffName, bannedAt, duration, true, byUuid, byName, at);
    }
}
