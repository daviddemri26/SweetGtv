package com.david.tvcontinue;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** Optional public preview fields. Missing data never becomes an invented zero or publication date. */
final class YouTubeMetadata {
    static final YouTubeMetadata EMPTY = new YouTubeMetadata("", "", "", -1, -1, -1);
    private static final long INTERACTION_TYPE_VIEWS = 0;
    private static final Pattern RECENCY = Pattern.compile("(?:(?:streamed|premiered) )?"
            + "(?:[0-9]{1,6}|a|an) (?:seconds?|minutes?|hours?|days?|weeks?|months?|years?) ago"
            + "|(?:diffusé(?:e)? (?:en direct )?|première )?il y a (?:[0-9]{1,6}|un|une) "
            + "(?:secondes?|minutes?|heures?|jours?|semaines?|mois|ans?|années?)"
            + "|just now|yesterday|today|à l['’]instant|hier|aujourd['’]hui",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COUNT = Pattern.compile("[0-9][0-9 .,]*[kmb]? "
            + "(?:views?|vues?|subscribers?|abonnés?|listeners?|auditeurs?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    final String channel, releaseDate, publishedLabel;
    final long durationMillis, viewCount, live;

    private YouTubeMetadata(String channel, String releaseDate, String publishedLabel,
                            long durationMillis, long viewCount, long live) {
        this.channel = channel;
        this.releaseDate = releaseDate;
        this.publishedLabel = publishedLabel;
        this.durationMillis = durationMillis;
        this.viewCount = viewCount;
        this.live = live;
    }

    static YouTubeMetadata from(String author, String releaseDate, String duration,
                                String interactionType, String interactionCount, String live) {
        return from(author, releaseDate, duration, interactionType, interactionCount, live, "");
    }

    static YouTubeMetadata from(String author, String releaseDate, String duration,
                                String interactionType, String interactionCount, String live, String shortDescription) {
        long millis = nonNegative(duration);
        long type = nonNegative(interactionType);
        long liveValue = nonNegative(live);
        long views = type == INTERACTION_TYPE_VIEWS ? nonNegative(interactionCount) : -1;
        String channel = cleaned(author, 128);
        String published = "";
        String description = shortDescription == null || shortDescription.length() > 2048
                ? "" : cleaned(shortDescription, 256);
        int separator = Math.max(description.lastIndexOf(" • "), description.lastIndexOf(" · "));
        if (separator > 0) {
            String left = description.substring(0, separator).trim();
            String right = description.substring(separator + 3).trim();
            if (RECENCY.matcher(right).matches()) {
                published = cleaned(right, 64);
                if (channel.isEmpty() && plausibleChannel(left)) channel = cleaned(left, 128);
            }
        } else if (channel.isEmpty() && (millis > 0 && views >= 0 || liveValue == 1)
                && plausibleChannel(description)) channel = cleaned(description, 128);
        return new YouTubeMetadata(channel, fullDate(releaseDate), published, millis > 0 ? millis : -1,
                views,
                liveValue == 0 || liveValue == 1 ? liveValue : -1);
    }

    private static String cleaned(String value, int maximum) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", " ")
                .replaceAll("[\\p{Z}\\s]+", " ").trim();
        int count = cleaned.codePointCount(0, cleaned.length());
        return count > maximum ? cleaned.substring(0, cleaned.offsetByCodePoints(0, maximum)) : cleaned;
    }

    private static boolean plausibleChannel(String value) {
        return !value.isEmpty() && value.codePointCount(0, value.length()) <= 128
                && !value.contains(" • ") && !value.contains(" · ") && !value.contains("://")
                && !value.matches("[0-9 .,]+") && !RECENCY.matcher(value).matches()
                && !COUNT.matcher(value).matches()
                && !value.matches("(?i:live|en direct|upcoming|à venir|mix|mixes|personalized mix for you|mix personnalisé pour vous)")
                && !value.matches("(?i:.*(?: and more| et plus| et d['’]autres))");
    }

    /** Only complete, valid dates are exported; a year alone must not turn into January 1. */
    private static String fullDate(String value) {
        if (value == null) return "";
        String date = value.trim();
        if (date.length() > 64) return "";
        try {
            if (date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                LocalDate parsed = LocalDate.parse(date);
                return parsed.getYear() > 0 ? parsed.toString() : "";
            }
            if (date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.+")) {
                LocalDate parsed = OffsetDateTime.parse(date).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
                return parsed.getYear() > 0 ? parsed.toString() : "";
            }
        } catch (DateTimeParseException invalid) { return ""; }
        return "";
    }

    private static long nonNegative(String value) {
        if (value == null) return -1;
        String number = value.trim();
        if (!number.matches("[0-9]{1,19}")) return -1;
        try { return Long.parseLong(number); }
        catch (NumberFormatException invalid) { return -1; }
    }
}
