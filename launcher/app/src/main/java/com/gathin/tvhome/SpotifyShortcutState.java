package com.gathin.tvhome;

import java.util.regex.Pattern;

/** Last confirmed local title snapshot, retained briefly while its companion reconnects. */
final class SpotifyShortcutState {
    static final long MAX_AGE_MS = 45_000;
    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private String title = "", artist = "";
    private long updatedAt = -1, expiresAt;

    boolean accept(String song, String performer, boolean playing, long observedAt, long now) {
        if (observedAt < 0 || now < 0) return false;
        long age = now - observedAt;
        if (age < -1000 || age > MAX_AGE_MS || observedAt < updatedAt) return false;
        updatedAt = observedAt;
        expiresAt = observedAt + MAX_AGE_MS;
        title = playing ? clean(song) : "";
        artist = title.isEmpty() ? "" : clean(performer);
        return true;
    }

    void expire(long now) {
        if (remaining(now) > 0) return;
        title = artist = "";
        expiresAt = 0;
    }

    long remaining(long now) { return Math.max(0, expiresAt - now); }
    String title() { return title; }
    String artist() { return artist; }

    private static String clean(String value) {
        if (value == null || value.length() > 2048) return "";
        String text = SPACES.matcher(CONTROLS.matcher(value).replaceAll(" ")).replaceAll(" ").trim();
        return text.codePointCount(0, text.length()) > 256
                ? text.substring(0, text.offsetByCodePoints(0, 256)) : text;
    }
}
