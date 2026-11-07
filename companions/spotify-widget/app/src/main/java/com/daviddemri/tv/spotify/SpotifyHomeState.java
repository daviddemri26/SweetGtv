package com.daviddemri.tv.spotify;

import java.util.regex.Pattern;

/** Bounded public labels from the current Spotify API session, with no stale playback claim. */
final class SpotifyHomeState {
    static final long REMOTE_MAX_AGE_MS = 45_000;
    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private Snapshot current = Snapshot.EMPTY;
    private boolean webKnown;
    private long webAt;

    static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot("", "", false);
        final String title, artist;
        final boolean playing;
        Snapshot(String title, String artist, boolean playing) {
            this.title = playing ? cleanTitle(title) : "";
            this.playing = playing && !this.title.isEmpty();
            this.artist = this.playing ? cleanTitle(artist) : "";
        }
    }

    void update(String title, String artist, boolean playing, long now) {
        current = playing ? new Snapshot(title, artist, true) : Snapshot.EMPTY;
        webKnown = true;
        webAt = now;
    }

    void clear() { current = Snapshot.EMPTY; webKnown = false; webAt = 0; }

    boolean known() { return webKnown; }

    Snapshot snapshot(long now) {
        boolean fresh = webKnown && now >= webAt && now - webAt < REMOTE_MAX_AGE_MS;
        return fresh ? current : Snapshot.EMPTY;
    }

    static String cleanTitle(String value) {
        if (value == null) return "";
        String clean = SPACE.matcher(CONTROLS.matcher(value).replaceAll(" ")).replaceAll(" ").trim();
        if (clean.codePointCount(0, clean.length()) > 256)
            clean = clean.substring(0, clean.offsetByCodePoints(0, 256));
        return clean;
    }
}
