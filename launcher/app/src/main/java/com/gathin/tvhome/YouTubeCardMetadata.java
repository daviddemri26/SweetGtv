package com.gathin.tvhome;

import java.text.NumberFormat;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Optional public video details. Missing values never become zero views or an invented date. */
final class YouTubeCardMetadata {
    static final YouTubeCardMetadata EMPTY = new YouTubeCardMetadata("", "", -1, -1, false);
    final String channel, publishedDate, publishedLabel;
    final long durationMillis, viewCount;
    final boolean live;

    YouTubeCardMetadata(String channel, String publishedDate, long durationMillis, long viewCount, boolean live) {
        this(channel, publishedDate, "", durationMillis, viewCount, live);
    }

    YouTubeCardMetadata(String channel, String publishedDate, String publishedLabel,
                        long durationMillis, long viewCount, boolean live) {
        this.channel = clean(channel, 120);
        this.publishedDate = clean(publishedDate, 40);
        this.publishedLabel = clean(publishedLabel, 64);
        this.durationMillis = durationMillis;
        this.viewCount = viewCount;
        this.live = live;
    }

    String[] lines(Locale locale) {
        String date = publication(publishedDate, locale);
        // Relative publication text comes verbatim from YouTube; it is never turned into a guessed date.
        String details = join(date.isEmpty() ? publishedLabel : date, views(viewCount, locale));
        if (channel.isEmpty()) return details.isEmpty() ? new String[0] : new String[]{details};
        return details.isEmpty() ? new String[]{channel} : new String[]{channel, details};
    }

    String badge() { return live ? "LIVE" : duration(durationMillis); }

    String accessibility(Locale locale) {
        String[] lines = lines(locale);
        String text = lines.length == 0 ? "" : lines.length == 1 ? lines[0] : lines[0] + ", " + lines[1];
        String badge = badge();
        if (!badge.isEmpty()) text = join(text, live ? "Live" : "Duration " + badge);
        return text;
    }

    static String publication(String raw, Locale locale) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            // Preserve the precision published by the provider: a year is never made into January 1.
            if (raw.matches("[0-9]{4}") && Year.parse(raw).getValue() > 0) return raw;
            if (raw.matches("[0-9]{4}-[0-9]{2}")) {
                YearMonth date = YearMonth.parse(raw);
                return date.getYear() > 0 ? date.format(DateTimeFormatter.ofPattern("MMM uuuu", locale)) : "";
            }
            if (raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                LocalDate date = LocalDate.parse(raw);
                return date.getYear() > 0 ? date.format(DateTimeFormatter.ofPattern("d MMM uuuu", locale)) : "";
            }
            if (raw.endsWith("Z") && raw.indexOf('T') > 0) {
                LocalDate date = Instant.parse(raw).atZone(ZoneOffset.UTC).toLocalDate();
                return date.getYear() > 0 ? date.format(DateTimeFormatter.ofPattern("d MMM uuuu", locale)) : "";
            }
        } catch (DateTimeParseException ignored) { /* An invalid or non-calendar value stays hidden. */ }
        return "";
    }

    static String duration(long millis) {
        if (millis <= 0 || millis > 1000L * 60 * 60 * 1000) return "";
        long seconds = millis / 1000;
        if (seconds == 0) return "";
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    static String views(long count, Locale locale) {
        if (count < 0 || count > 1_000_000_000_000_000L) return "";
        long divisor = count >= 1_000_000_000_000L ? 1_000_000_000_000L
                : count >= 1_000_000_000L ? 1_000_000_000L : count >= 1_000_000L ? 1_000_000L
                : count >= 1_000L ? 1_000L : 1;
        String suffix = divisor == 1_000_000_000_000L ? "T" : divisor == 1_000_000_000L ? "B"
                : divisor == 1_000_000L ? "M" : divisor == 1_000L ? "K" : "";
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(false);
        format.setRoundingMode(RoundingMode.DOWN);
        format.setMaximumFractionDigits(divisor == 1 ? 0 : 1);
        format.setMinimumFractionDigits(0);
        String label = "fr".equals(locale.getLanguage()) ? count == 1 ? " vue" : " vues" : count == 1 ? " view" : " views";
        return format.format((double) count / divisor) + suffix + label;
    }

    private static String join(String first, String second) {
        return first.isEmpty() ? second : second.isEmpty() ? first : first + " · " + second;
    }

    private static String clean(String value, int limit) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", " ").trim();
        int count = clean.codePointCount(0, clean.length());
        return count > limit ? clean.substring(0, clean.offsetByCodePoints(0, limit)) : clean;
    }
}
