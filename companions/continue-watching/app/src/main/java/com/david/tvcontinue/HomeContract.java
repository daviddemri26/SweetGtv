package com.david.tvcontinue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Values shared with the local home integration. No playback URI leaves the addon. */
final class HomeContract {
    static final String AUTHORITY = "com.david.tvcontinue.home";
    static final String HOME = "com.gathin.tvhome";
    static final String SHELF_CONTINUE = "continue", SHELF_YOUTUBE = "youtube";
    static final String[] COLUMNS = {"_id", "title", "subtitle", "owner", "progress", "art_uri"};
    static final String[] YOUTUBE_COLUMNS = {"_id", "title", "subtitle", "owner", "progress", "art_uri",
            "youtube_channel", "youtube_published_date", "youtube_duration_millis", "youtube_view_count", "youtube_live",
            "youtube_published_label"};
    static String[] columnsForShelf(String shelf) {
        if (!validShelf(shelf)) throw new IllegalArgumentException("Unsupported shelf");
        return SHELF_YOUTUBE.equals(shelf) ? YOUTUBE_COLUMNS : COLUMNS;
    }
    static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static boolean validKey(String key) { return key != null && key.matches("[0-9a-f]{64}"); }
    static boolean validShelf(String shelf) { return SHELF_CONTINUE.equals(shelf) || SHELF_YOUTUBE.equals(shelf); }
    static String keyFor(String shelf, String owner, String intent) {
        if (!validShelf(shelf)) throw new IllegalArgumentException("Unsupported shelf");
        return hash((SHELF_YOUTUBE.equals(shelf) ? "youtube\n" : "") + owner + '\n' + intent);
    }
    static int progress(String shelf, long position, long duration, int kind) {
        if (!validShelf(shelf)) throw new IllegalArgumentException("Unsupported shelf");
        return SHELF_YOUTUBE.equals(shelf) ? -1 : progress(position, duration, kind);
    }
    static int progress(long position, long duration, int kind) {
        return kind != 0 || position < 0 || duration <= 0 ? -1
                : (int) Math.max(0, Math.min(1000, position * 1000.0 / duration));
    }
    static String bounded(String text) { return text.length() > 512 ? text.substring(0, 512) : text; }
}
