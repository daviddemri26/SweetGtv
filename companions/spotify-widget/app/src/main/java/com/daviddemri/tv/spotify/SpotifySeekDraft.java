package com.daviddemri.tv.spotify;

/** Local seek preview. Repeated adjustments change this draft without sending playback commands. */
final class SpotifySeekDraft {
    private String uri = "", deviceId = "";
    private long initialPosition, position, duration;
    private boolean active;

    boolean begin(String uri, String deviceId, long progress, long duration) {
        cancel();
        if (blank(uri) || blank(deviceId) || duration <= 0) return false;
        this.uri = uri; this.deviceId = deviceId; this.duration = duration;
        initialPosition = position = Math.max(0, Math.min(progress, duration));
        active = true;
        return true;
    }

    boolean active() { return active; }
    String uri() { return uri; }
    String deviceId() { return deviceId; }
    long position() { return position; }
    long duration() { return duration; }
    boolean dirty() { return active && position != initialPosition; }

    long step(long delta) {
        if (!active) return 0;
        if (delta > 0 && delta > duration - position) position = duration;
        else if (delta < 0 && delta < -position) position = 0;
        else position += delta;
        return position;
    }

    long setFraction(int value, int max) {
        if (!active) return 0;
        if (max <= 0) return position;
        long fraction = Math.max(0, Math.min(value, max));
        // Each term is bounded by duration; the remainder product is below Integer.MAX_VALUE squared.
        position = duration / max * fraction + duration % max * fraction / max;
        return position;
    }

    boolean matches(String uri, String deviceId, long duration) {
        return active && this.uri.equals(uri) && this.deviceId.equals(deviceId) && this.duration == duration;
    }

    void cancel() {
        active = false; uri = ""; deviceId = "";
        initialPosition = position = duration = 0;
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
