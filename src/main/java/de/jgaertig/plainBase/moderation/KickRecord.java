package de.jgaertig.plainBase.moderation;

import java.util.UUID;

/**
 * A single kick entry, kept as history for {@code /baninfo}. staffUuid is
 * null when the kick was issued from the console.
 */
public record KickRecord(
        int id,
        UUID uuid,
        String name,
        String reason,
        UUID staffUuid,
        String staffName,
        long kickedAt
) {
}
