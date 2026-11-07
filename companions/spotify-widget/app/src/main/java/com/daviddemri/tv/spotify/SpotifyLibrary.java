package com.daviddemri.tv.spotify;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.os.Handler;
import android.os.Looper;
import android.service.media.MediaBrowserService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Live Spotify catalog. Explicit selections use the same Connect destination as the transport buttons. */
final class SpotifyLibrary {
    private static final long CATALOG_TTL_MS = 60_000, CURRENT_TTL_MS = 5_000, BROWSER_TIMEOUT_MS = 8_000;
    private static final int MAX_PLAYLISTS = 48, MAX_RECENT = 48, MAX_EPISODES = 100;
    private static final String RECENT_ENDPOINT = "https://api.spotify.com/v1/me/player/recently-played?limit=48";
    private static final String PLAYLIST_ENDPOINT = "https://api.spotify.com/v1/me/playlists?limit=48";

    static final class Item {
        final String id, title, subtitle, imageUrl, type;
        final long progressMillis, durationMillis;
        final Bitmap artwork = null;
        final boolean playable = true, browsable = false;
        Item(String id, String title, String subtitle, String imageUrl, String type, long progress, long duration) {
            this.id = id; this.title = title; this.subtitle = subtitle; this.imageUrl = imageUrl; this.type = type;
            progressMillis = progress; durationMillis = duration;
        }
    }

    static final class NowPlaying {
        static final NowPlaying EMPTY = new NowPlaying("", "", "", "", "", "", false, false, 0, 0, 0);
        final String uri, title, subtitle, imageUrl, deviceName, deviceId;
        final boolean playing, isTv;
        final long progressMillis, durationMillis, updatedAt;
        NowPlaying(String uri, String title, String subtitle, String imageUrl, String deviceName, String deviceId,
                   boolean playing, boolean isTv, long progress, long duration, long updatedAt) {
            this.uri = uri; this.title = title; this.subtitle = subtitle; this.imageUrl = imageUrl;
            this.deviceName = deviceName; this.deviceId = deviceId; this.playing = playing; this.isTv = isTv;
            progressMillis = progress; durationMillis = duration; this.updatedAt = updatedAt;
        }
    }

    static final class State {
        final boolean loading, canGoBack = false, available, failed, connected, cached;
        final String title, parentId, syncedAt, error;
        final long lastUpdated;
        final List<Item> items;
        final NowPlaying current;
        State(String category, List<Item> items, boolean connected, boolean loading, String error,
              long updated, NowPlaying current) {
            parentId = category;
            title = "playlists".equals(category) ? "Playlists" : "resume".equals(category) ? "Episodes to resume" : "Recent listening";
            this.items = items; this.connected = connected; this.loading = loading; this.error = error;
            available = !items.isEmpty(); failed = !error.isEmpty(); cached = available && failed;
            lastUpdated = updated;
            syncedAt = updated == 0 ? "" : java.time.Instant.ofEpochMilli(updated).toString();
            this.current = current;
        }

        boolean sameSource(String category, List<Item> items, boolean connected, boolean loading, String error,
                           long updated, NowPlaying current) {
            return parentId.equals(category) && this.items == items && this.connected == connected
                    && this.loading == loading && this.error.equals(error) && lastUpdated == updated
                    && this.current == current;
        }
    }

    private final Context context;
    private final Consumer<State> listener;
    private final Consumer<MediaSession.Token> tokenListener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SpotifyAuthStore auth;
    private final SpotifyApi api;
    private final Map<String, List<Item>> categories = new HashMap<>();
    private final Map<String, Long> updated = new HashMap<>();
    private final Map<String, Long> attempted = new HashMap<>();
    private final Map<String, String> errors = new HashMap<>();
    private ExecutorService worker;
    private Future<?> pending;
    private boolean running, connecting, catalogLoading;
    private int generation, nativeGeneration;
    private long authRevision = -1, currentAttempted;
    private String category = "recent", currentError = "";
    private NowPlaying nowPlaying = NowPlaying.EMPTY;
    private MediaBrowser browser;
    private SpotifyConnect connect;
    private Runnable browserTimeout;
    private State state;
    private final Runnable tick = () -> { if (running) refresh(false); };

    SpotifyLibrary(Context context, Consumer<State> listener, Consumer<MediaSession.Token> tokenListener) {
        this.context = context.getApplicationContext(); this.listener = listener; this.tokenListener = tokenListener;
        auth = new SpotifyAuthStore(this.context); api = new SpotifyApi(auth); rebuildState();
    }
    State snapshot() { return state; }

    void resume() {
        if (!running) { running = true; ++generation; }
        if (browser == null || (!browser.isConnected() && !connecting)) connectNative();
        refresh(false);
    }

    void pause() {
        running = false; ++generation;
        main.removeCallbacks(tick);
        api.cancel();
        if (pending != null) pending.cancel(true);
        pending = null;
        if (worker != null) worker.shutdownNow();
        worker = null;
        catalogLoading = false;
        disconnectNative();
    }

    void browseRoot() { browse("recent"); }
    void browse(String next) {
        if (!validCategory(next)) return;
        category = next;
        publish();
        if (running) refresh(false);
    }
    boolean back() { return false; }
    void refresh() { refresh(true); }
    boolean isConnected() { return auth.isConnected(); }

    private void refresh(boolean force) {
        if (!running) return;
        main.removeCallbacks(tick);
        long revision = auth.revision();
        if (revision != authRevision) {
            authRevision = revision; categories.clear(); updated.clear(); attempted.clear(); errors.clear();
            nowPlaying = NowPlaying.EMPTY; currentAttempted = 0; currentError = "";
            // Ignore in-flight responses from the previous sign-in.
            if (pending != null) pending.cancel(true);
            pending = null; api.cancel();
            if (worker != null) worker.shutdownNow();
            worker = null; ++generation; catalogLoading = false;
        }
        if (!auth.isConnected()) { publishChanged(); schedule(); return; }
        long now = System.currentTimeMillis();
        if (now < api.retryAt() || pending != null) { publishChanged(); schedule(); return; }
        final boolean loadCatalog = force || now - attempted.getOrDefault(category, 0L) >= CATALOG_TTL_MS;
        final long currentInterval = currentError.isEmpty() ? CURRENT_TTL_MS : 30_000L;
        final boolean loadCurrent = force || now - currentAttempted >= currentInterval;
        if (!loadCatalog && !loadCurrent) { publishChanged(); schedule(); return; }
        final String requestedCategory = category;
        final long requestedRevision = authRevision;
        final int requestGeneration = generation;
        catalogLoading = loadCatalog;
        publishChanged();
        if (worker == null) worker = Executors.newSingleThreadExecutor();
        api.begin();
        pending = worker.submit(() -> {
            List<Item> loaded = null;
            NowPlaying current = null;
            String catalogError = "", playbackError = "";
            if (loadCurrent) {
                try { current = parseCurrent(api.get("/me/player?additional_types=track,episode")); }
                catch (Exception failure) { playbackError = message(failure); }
            }
            if (loadCatalog && !Thread.currentThread().isInterrupted()) {
                try { loaded = fetchCategory(requestedCategory); }
                catch (Exception failure) { catalogError = message(failure); }
            }
            final List<Item> received = loaded;
            final NowPlaying playback = current;
            final String receivedError = catalogError, receivedPlaybackError = playbackError;
            main.post(() -> {
                if (!running || requestGeneration != generation || requestedRevision != auth.revision()) return;
                pending = null; catalogLoading = false;
                long receivedAt = System.currentTimeMillis();
                if (loadCatalog) {
                    attempted.put(requestedCategory, receivedAt);
                    errors.put(requestedCategory, receivedError);
                    if (received != null) { categories.put(requestedCategory, received); updated.put(requestedCategory, receivedAt); }
                }
                if (loadCurrent) {
                    currentAttempted = receivedAt;
                    currentError = receivedPlaybackError;
                    if (playback != null) nowPlaying = playback;
                }
                publishChanged(); schedule();
                // A category selected while another request was running should load without a five-second wait.
                if (!category.equals(requestedCategory) && !updated.containsKey(category)) main.post(tick);
            });
        });
        schedule();
    }

    private void schedule() {
        main.removeCallbacks(tick);
        if (running) main.postDelayed(tick, Math.max(CURRENT_TTL_MS, api.retryAt() - System.currentTimeMillis()));
    }

    private void rebuildState() {
        List<Item> items = categories.getOrDefault(category, Collections.emptyList());
        String error = errors.getOrDefault(category, "");
        if (error.isEmpty() && items.isEmpty()) error = currentError;
        boolean connected = auth.isConnected(), loading = catalogLoading && !updated.containsKey(category);
        long lastUpdated = updated.getOrDefault(category, 0L);
        if (state != null && state.sameSource(category, items, connected, loading, error, lastUpdated, nowPlaying)) return;
        state = new State(category, items, connected, loading, error, lastUpdated, nowPlaying);
    }
    private void publish() { rebuildState(); if (running && listener != null) listener.accept(state); }
    private void publishChanged() {
        State before = state;
        rebuildState();
        if (state != before && running && listener != null) listener.accept(state);
    }

    void setConnect(SpotifyConnect connect) { this.connect = connect; }

    /** Only a current catalog selection may reach the explicitly chosen Connect destination. */
    boolean play(String id) {
        if (!running || !auth.isConnected() || auth.revision() != authRevision || connect == null
                || !SpotifyConnect.playableUri(id)) return false;
        for (Item item : state.items) if (item.id.equals(id)) {
            long resumeAt = "resume".equals(category) && "episode".equals(item.type) ? item.progressMillis : 0;
            return connect.play(id, resumeAt);
        }
        return false;
    }
    static boolean validUri(String value) { return SpotifyApiPolicy.validItemUri(value); }

    private List<Item> fetchCategory(String requested) throws Exception {
        if ("recent".equals(requested)) return readRecent(api::get);
        if ("playlists".equals(requested)) return readPlaylists(api::get);
        String next = "https://api.spotify.com/v1/me/episodes?limit=50";
        LinkedHashMap<String, Item> result = new LinkedHashMap<>();
        int pages = 0;
        while (next != null && !next.isEmpty() && pages++ < MAX_EPISODES / 50) {
            JSONObject response = api.get(next);
            JSONArray rows = response.optJSONArray("items");
            if (rows != null) for (int i = 0; i < rows.length() && result.size() < MAX_EPISODES; i++) {
                JSONObject row = rows.optJSONObject(i);
                JSONObject content = row == null ? null : row.optJSONObject("episode");
                Item item = parseItem(content, true);
                if (item != null) result.putIfAbsent(item.id, item);
            }
            next = response.isNull("next") ? "" : response.optString("next", "");
            if (!next.isEmpty() && !SpotifyApiPolicy.allowedApiUrl(next)) throw new IllegalArgumentException("Unsupported Spotify page");
        }
        return immutable(result);
    }

    interface PageReader { JSONObject get(String url) throws Exception; }

    /** One page of the last 48 play events. Repeats are removed without searching older history. */
    static List<Item> readRecent(PageReader source) throws Exception {
        return firstCatalogItems(source.get(RECENT_ENDPOINT), true, MAX_RECENT);
    }

    /** One page only; the panel never requests more playlists through the supplied next URL. */
    static List<Item> readPlaylists(PageReader source) throws Exception {
        return firstCatalogItems(source.get(PLAYLIST_ENDPOINT), false, MAX_PLAYLISTS);
    }

    private static List<Item> firstCatalogItems(JSONObject response, boolean recent, int maximum) {
        LinkedHashMap<String, Item> result = new LinkedHashMap<>();
        JSONArray rows = response.optJSONArray("items");
        if (rows != null) for (int i = 0; i < rows.length() && i < maximum; i++) {
            JSONObject row = rows.optJSONObject(i);
            Item item = parseItem(recent && row != null ? row.optJSONObject("track") : row, false);
            if (item != null) result.putIfAbsent(item.id, item);
        }
        return immutable(result);
    }

    private static List<Item> immutable(LinkedHashMap<String, Item> values) {
        return Collections.unmodifiableList(new ArrayList<>(values.values()));
    }

    static Item parseItem(JSONObject source, boolean resumeOnly) {
        if (source == null) return null;
        String uri = text(source, "uri", ""), title = bounded(text(source, "name", ""));
        if (!SpotifyApiPolicy.validItemUri(uri) || title.trim().isEmpty() || source.optBoolean("is_local", false)
                || source.has("is_playable") && !source.optBoolean("is_playable", true)) return null;
        String type = text(source, "type", uri.split(":")[1]);
        long progress = 0, duration = Math.max(0, source.optLong("duration_ms", 0));
        JSONObject resume = source.optJSONObject("resume_point");
        if (resume != null) progress = Math.max(0, resume.optLong("resume_position_ms", 0));
        if (resumeOnly && (!"episode".equals(type) || resume == null || resume.optBoolean("fully_played", false)
                || progress <= 0 || duration > 0 && progress >= duration)) return null;
        String subtitle = "";
        if ("track".equals(type)) subtitle = artists(source.optJSONArray("artists"));
        else if ("playlist".equals(type)) {
            JSONObject owner = source.optJSONObject("owner");
            subtitle = owner == null ? "Playlist" : bounded(text(owner, "display_name", "Playlist"));
        } else {
            JSONObject show = source.optJSONObject("show");
            subtitle = show == null ? bounded(text(source, "publisher", "Podcast")) : bounded(text(show, "name", "Podcast"));
        }
        JSONArray images = source.optJSONArray("images");
        if (images == null) { JSONObject album = source.optJSONObject("album"); if (album != null) images = album.optJSONArray("images"); }
        return new Item(uri, title, subtitle, image(images), type, progress, duration);
    }

    static NowPlaying parseCurrent(JSONObject source) {
        JSONObject content = source.optJSONObject("item");
        Item item = parseItem(content, false);
        if (item == null) return NowPlaying.EMPTY;
        JSONObject device = source.optJSONObject("device");
        String deviceName = device == null ? "" : bounded(text(device, "name", ""));
        String deviceId = device == null ? "" : bounded(text(device, "id", ""));
        String type = device == null ? "" : text(device, "type", "");
        boolean tv = "TV".equalsIgnoreCase(type) || "Chromecast".equalsIgnoreCase(type)
                || "CastVideo".equalsIgnoreCase(type) || "STB".equalsIgnoreCase(type);
        return new NowPlaying(item.id, item.title, item.subtitle, item.imageUrl, deviceName, deviceId,
                source.optBoolean("is_playing", false), tv, Math.max(0, source.optLong("progress_ms", 0)),
                item.durationMillis, System.currentTimeMillis());
    }

    private static String image(JSONArray images) {
        String best = "";
        int bestWidth = Integer.MAX_VALUE;
        if (images != null) for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) continue;
            String url = text(image, "url", "");
            if (!SpotifyApiPolicy.allowedImageUrl(url)) continue;
            int width = image.optInt("width", 0);
            if (best.isEmpty() || width >= 128 && width < bestWidth) { best = url; bestWidth = width >= 128 ? width : Integer.MAX_VALUE; }
        }
        return best;
    }
    private static String artists(JSONArray artists) {
        StringBuilder result = new StringBuilder();
        if (artists != null) for (int i = 0; i < artists.length() && i < 8; i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            String name = bounded(text(artist, "name", ""));
            if (name.isEmpty()) continue;
            if (result.length() > 0) result.append(", ");
            result.append(name);
        }
        return bounded(result.toString());
    }
    private static String text(JSONObject object, String key, String fallback) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : fallback;
    }
    private static String bounded(String value) { return value.length() > 512 ? value.substring(0, 512) : value; }
    private static boolean validCategory(String value) { return "playlists".equals(value) || "recent".equals(value) || "resume".equals(value); }
    private static String message(Exception failure) {
        return failure instanceof SpotifyApi.ApiException ? failure.getMessage() : "Spotify is unavailable. The panel will retry.";
    }

    private void connectNative() {
        disconnectNative();
        final int attempt = ++nativeGeneration;
        ComponentName service = findService();
        if (service == null) return;
        connecting = true;
        MediaBrowser.ConnectionCallback callback = new MediaBrowser.ConnectionCallback() {
            private boolean active() { return running && attempt == nativeGeneration && browser != null; }
            @Override public void onConnected() {
                if (!active()) return;
                cancelBrowserTimeout(); connecting = false;
                try { if (tokenListener != null) tokenListener.accept(browser.getSessionToken()); }
                catch (RuntimeException ignored) { disconnectNative(); }
            }
            @Override public void onConnectionSuspended() {
                if (!active()) return;
                cancelBrowserTimeout(); connecting = false;
                if (tokenListener != null) tokenListener.accept(null);
            }
            @Override public void onConnectionFailed() { if (active()) disconnectNative(); }
        };
        browser = new MediaBrowser(context, service, callback, null);
        browserTimeout = callback::onConnectionFailed;
        main.postDelayed(browserTimeout, BROWSER_TIMEOUT_MS);
        try { browser.connect(); } catch (RuntimeException ignored) { disconnectNative(); }
    }
    private ComponentName findService() {
        try {
            Intent query = new Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(SpotifyPlayback.PACKAGE);
            for (ResolveInfo match : context.getPackageManager().queryIntentServices(query, 0)) {
                ServiceInfo service = match.serviceInfo;
                if (service == null || !service.exported || !service.enabled || !SpotifyPlayback.PACKAGE.equals(service.packageName)
                        || service.applicationInfo == null || !service.applicationInfo.enabled) continue;
                if (service.permission != null && !service.permission.isEmpty()
                        && context.checkSelfPermission(service.permission) != PackageManager.PERMISSION_GRANTED) continue;
                return new ComponentName(service.packageName, service.name);
            }
        } catch (RuntimeException ignored) { }
        return null;
    }
    private void cancelBrowserTimeout() { if (browserTimeout != null) main.removeCallbacks(browserTimeout); browserTimeout = null; }
    private void disconnectNative() {
        ++nativeGeneration;
        cancelBrowserTimeout();
        MediaBrowser previous = browser; browser = null; connecting = false;
        if (previous != null) try { previous.disconnect(); } catch (RuntimeException ignored) { }
        if (tokenListener != null) tokenListener.accept(null);
    }
}
