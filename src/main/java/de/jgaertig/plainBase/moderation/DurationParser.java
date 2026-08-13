package de.jgaertig.plainBase.moderation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses LiteBans-style duration strings ("1d12h30m", "7d", "2h") into
 * millis, and formats millis back into a short human-readable string.
 * "permanent" / "perm" / "-1" mean an unlimited ban (returns -1).
 */
public final class DurationParser {

    private static final Pattern TOKEN = Pattern.compile("(\\d+)\\s*(y|mo|w|d|h|m|s)", Pattern.CASE_INSENSITIVE);

    private DurationParser() {
    }

    /**
     * @return millis for the given duration, or -1 for a permanent/unlimited ban
     * @throws IllegalArgumentException if the string cannot be parsed
     */
    public static long parse(String input) {
        if (input == null) throw new IllegalArgumentException("Duration is null");
        String trimmed = input.trim().toLowerCase();

        if (trimmed.equals("permanent") || trimmed.equals("perm") || trimmed.equals("-1") || trimmed.equals("forever")) {
            return -1;
        }

        Matcher matcher = TOKEN.matcher(trimmed);
        long totalMillis = 0;
        int matchedChars = 0;

        while (matcher.find()) {
            matchedChars += matcher.group().length();
            long amount = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2).toLowerCase();
            totalMillis += amount * unitMillis(unit);
        }

        // Reject input that isn't fully made of recognized "<number><unit>" tokens
        // (this also rejects an empty string, whitespace-only input, etc.).
        if (matchedChars != trimmed.replaceAll("\\s+", "").length() || totalMillis <= 0) {
            throw new IllegalArgumentException("Could not parse duration: " + input);
        }

        return totalMillis;
    }

    private static long unitMillis(String unit) {
        return switch (unit) {
            case "s" -> 1000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            case "w" -> 604_800_000L;
            case "mo" -> 2_592_000_000L; // 30 days
            case "y" -> 31_536_000_000L; // 365 days
            default -> throw new IllegalArgumentException("Unknown duration unit: " + unit);
        };
    }

    /**
     * Formats millis into a short human-readable duration, e.g. "1d 2h 3m".
     * Returns "permanent" for -1, "expired" for 0.
     */
    public static String format(long millis) {
        if (millis < 0) return "permanent";
        if (millis == 0) return "expired";

        long seconds = millis / 1000;
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (days == 0 && hours == 0) sb.append(seconds).append("s ");

        return sb.toString().trim();
    }
}
