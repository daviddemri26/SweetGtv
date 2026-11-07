package com.david.tvcontinue;

import java.util.Locale;
import java.util.Set;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Collections;

/** Pure rules shared by the reader and tested without an Android device. */
final class EntryPolicy {
    static final String STREMIO = "com.stremio.one";
    static final long MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000;
    static final Set<String> SOURCES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.google.android.youtube.tv", "com.apple.atve.androidtv.appletv",
            "com.peacocktv.peacockandroid", "com.spotify.tv.android",
            "com.disney.disneyplus", STREMIO)));

    static boolean isResumeChannel(String owner, String title) {
        if (!STREMIO.equals(owner) || title == null) return false;
        String value = title.trim().toLowerCase(Locale.ROOT);
        return value.equals("continue watching") || value.equals("continuer à regarder")
                || value.equals("reprendre la lecture") || value.equals("reprendre");
    }

    static boolean include(String owner, String title, String intent, int kind,
                           long lastEngagement, long position, long duration, long now) {
        if (!SOURCES.contains(owner) || title == null || title.trim().isEmpty()
                || intent == null || intent.trim().isEmpty()) return false;
        // Watchlist (3) and unknown kinds are not a Continue watching row.
        if (kind < 0 || kind > 2) return false;
        if (lastEngagement > 0 && now - lastEngagement > MAX_AGE_MS) return false;
        // A completed item of kind CONTINUE is stale. NEXT/NEW may refer to the next episode.
        return kind != 0 || duration <= 0 || position < duration * .99;
    }

    static boolean safeLink(String owner, String componentOwner, String intentOwner,
                            String action, String scheme, boolean hasData) {
        if (!SOURCES.contains(owner)) return false;
        if (componentOwner != null && !owner.equals(componentOwner)) return false;
        if (intentOwner != null && !owner.equals(intentOwner)) return false;
        if (action != null && !action.equals("android.intent.action.VIEW")
                && !action.equals("android.intent.action.MAIN")) return false;
        if (hasData && (scheme == null || Arrays.asList("javascript", "file", "content", "data")
                .contains(scheme.toLowerCase(Locale.ROOT)))) return false;
        return hasData || componentOwner != null;
    }
}
