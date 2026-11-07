package com.gathin.tvhome;

import java.util.Objects;

/** A visible poster stays usable until its replacement has actually loaded. */
final class RetainedArtwork<T> {
    private String source, displayedSource;
    private T value;
    private boolean stale;

    void source(String next) {
        source = next;
        if (next == null) release();
    }

    T value() { return value; }

    boolean needsLoad() {
        return source != null && (stale || value == null || !Objects.equals(source, displayedSource));
    }

    void invalidate() { stale = true; }

    void accept(String requestedSource, T replacement) {
        // A failed or superseded request must never blank the last good poster.
        if (replacement == null || source == null || !source.equals(requestedSource)) return;
        value = replacement;
        displayedSource = source;
        stale = false;
    }

    void release() {
        value = null;
        displayedSource = null;
        stale = false;
    }
}
