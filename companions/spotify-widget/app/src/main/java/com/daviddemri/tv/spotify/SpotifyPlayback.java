package com.daviddemri.tv.spotify;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.function.Consumer;

/** Controls only the session Spotify explicitly grants through its native media browser. */
final class SpotifyPlayback {
    static final String PACKAGE = "com.spotify.tv.android";

    static final class Snapshot {
        final String uri, title, subtitle, imageUrl;
        final Bitmap artwork;
        final boolean playing, buffering, paused, canPlay, canPause, canPrevious, canNext;
        final boolean installed, authorized, hasSession;

        Snapshot(String uri, String title, String subtitle, String imageUrl, Bitmap artwork, boolean playing, boolean buffering, boolean paused,
                 boolean canPlay, boolean canPause, boolean canPrevious, boolean canNext,
                 boolean installed, boolean authorized, boolean hasSession) {
            this.uri = uri;
            this.title = title;
            this.subtitle = subtitle;
            this.imageUrl = imageUrl;
            this.artwork = artwork;
            this.playing = playing;
            this.buffering = buffering;
            this.paused = paused;
            this.canPlay = canPlay;
            this.canPause = canPause;
            this.canPrevious = canPrevious;
            this.canNext = canNext;
            this.installed = installed;
            this.authorized = authorized;
            this.hasSession = hasSession;
        }
    }

    private final Context context;
    private final Consumer<Snapshot> listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaController controller;
    private MediaController.Callback callback;
    private boolean running;
    private Snapshot current;

    SpotifyPlayback(Context context, Consumer<Snapshot> listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        current = empty(isInstalled());
    }

    Snapshot snapshot() { return current; }

    /** A connected Spotify browser supplies its own session without notification access. */
    void setBrowserSession(MediaSession.Token token) {
        if (token != null && controller != null && token.equals(controller.getSessionToken())) {
            refresh();
            return;
        }
        detach();
        if (token != null) {
            try {
                MediaController candidate = new MediaController(context, token);
                String owner = candidate.getPackageName();
                if (PACKAGE.equals(owner)) controller = candidate;
            } catch (RuntimeException unavailable) {
                // A disconnected browser token may already be invalid.
            }
        }
        if (running) observe();
        refresh();
    }

    void start() {
        if (running) return;
        running = true;
        observe();
        refresh();
    }

    void stop() {
        running = false;
        detach();
        current = empty(current.installed);
    }

    void refresh() { if (running) publish(isInstalled()); }

    /** True means the request was sent; Spotify's callbacks confirm the resulting playback state. */
    boolean playPause() { return command(0); }
    boolean previous() { return command(1); }
    boolean next() { return command(2); }

    private boolean command(int action) {
        refresh();
        if (!running || controller == null) return false;
        try {
            MediaController.TransportControls controls = controller.getTransportControls();
            if (action == 1) {
                if (!current.canPrevious) return false;
                controls.skipToPrevious();
            } else if (action == 2) {
                if (!current.canNext) return false;
                controls.skipToNext();
            } else if (current.playing || current.buffering) {
                if (!current.canPause) return false;
                controls.pause();
            } else {
                if (!current.canPlay) return false;
                controls.play();
            }
            return true;
        } catch (RuntimeException unavailable) {
            detach();
            refresh();
            return false;
        }
    }

    /** Explicit fallback for login, library access, or a rejected native browser connection. */
    boolean openSpotify() {
        PackageManager packages = context.getPackageManager();
        try {
            Intent intent = packages.getLeanbackLaunchIntentForPackage(PACKAGE);
            if (intent == null) intent = packages.getLaunchIntentForPackage(PACKAGE);
            if (intent == null) return false;
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private boolean isInstalled() {
        try {
            return context.getPackageManager().getApplicationInfo(PACKAGE, 0).enabled;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return false;
        }
    }

    private void observe() {
        if (controller == null || callback != null) return;
        final MediaController attached = controller;
        callback = new MediaController.Callback() {
            private boolean active() { return running && controller == attached; }
            @Override public void onMetadataChanged(MediaMetadata metadata) {
                if (active()) publish(current.installed);
            }
            @Override public void onPlaybackStateChanged(PlaybackState state) {
                if (active()) publish(current.installed);
            }
            @Override public void onSessionDestroyed() {
                if (!active()) return;
                detach();
                publish(current.installed);
            }
        };
        try {
            attached.registerCallback(callback, main);
        } catch (RuntimeException unavailable) {
            detach();
        }
    }

    private void detach() {
        MediaController previous = controller;
        MediaController.Callback previousCallback = callback;
        controller = null;
        callback = null;
        if (previous != null && previousCallback != null) {
            try { previous.unregisterCallback(previousCallback); } catch (RuntimeException ignored) { }
        }
    }

    private Snapshot empty(boolean installed) {
        return new Snapshot("", "", "", "", null, false, false, false, false, false, false, false,
                installed, false, false);
    }

    private void publish(boolean installed) {
        Snapshot next = empty(installed);
        if (controller != null) {
            try {
                PlaybackState state = controller.getPlaybackState();
                MediaMetadata metadata = controller.getMetadata();
                MediaDescription description = metadata == null ? null : metadata.getDescription();
                String title = description == null ? "" : text(description.getTitle());
                String subtitle = description == null ? "" : text(description.getSubtitle());
                String uri = description == null ? "" : canonicalUri(description.getMediaId());
                if (uri.isEmpty() && metadata != null) uri = canonicalUri(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
                if (uri.isEmpty() && description != null && description.getMediaUri() != null) uri = canonicalUri(description.getMediaUri().toString());
                String imageUrl = "";
                Bitmap art = null;
                if (metadata != null) {
                    art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI}) {
                        String candidate = metadata.getString(key);
                        if (SpotifyApiPolicy.allowedImageUrl(candidate)) { imageUrl = candidate; break; }
                    }
                }
                if (art == null && description != null) art = description.getIconBitmap();
                long actions = state == null ? 0 : state.getActions();
                int value = state == null ? PlaybackState.STATE_NONE : state.getState();
                boolean playing = value == PlaybackState.STATE_PLAYING || value == PlaybackState.STATE_FAST_FORWARDING
                        || value == PlaybackState.STATE_REWINDING;
                boolean buffering = value == PlaybackState.STATE_BUFFERING || value == PlaybackState.STATE_CONNECTING;
                next = new Snapshot(uri, title, subtitle, imageUrl, art, playing, buffering, value == PlaybackState.STATE_PAUSED,
                        (actions & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) != 0,
                        (actions & (PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)) != 0,
                        (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0,
                        (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0,
                        installed, true, true);
            } catch (RuntimeException unavailable) {
                detach();
            }
        }
        current = next;
        if (running && listener != null) listener.accept(next);
    }

    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }

    private static String canonicalUri(String value) {
        if (SpotifyApiPolicy.validItemUri(value)) return value;
        if (value == null || value.length() > 2048) return "";
        try {
            Uri parsed = Uri.parse(value);
            if (!"https".equals(parsed.getScheme()) || !"open.spotify.com".equals(parsed.getHost()) || parsed.getUserInfo() != null) return "";
            java.util.List<String> path = parsed.getPathSegments();
            if (path.size() != 2) return "";
            String canonical = "spotify:" + path.get(0) + ":" + path.get(1);
            return SpotifyApiPolicy.validItemUri(canonical) ? canonical : "";
        } catch (RuntimeException invalid) { return ""; }
    }
}
