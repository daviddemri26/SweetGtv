package com.daviddemri.tv.spotify;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Explicit, device-targeted Spotify Connect commands and state-preserving session transfers. */
final class SpotifyConnect {
    private static final String WRITE_SCOPE = "user-modify-playback-state";
    private static final String CHOOSE_DEVICE = "Choose a playback device first.";

    static final class Device {
        final String id, name, type;
        final boolean active, restricted;
        final int volume;
        Device(String id, String name, String type, boolean active, boolean restricted, int volume) {
            this.id = id; this.name = name; this.type = type;
            this.active = active; this.restricted = restricted; this.volume = volume;
        }
    }

    static final class State {
        final List<Device> devices;
        final boolean loading, busy, authorized, confirmationPending, unconfirmedResult;
        final String selectedId, selectedName, error, notice;
        State(List<Device> devices, boolean loading, boolean busy, boolean authorized, boolean confirming, boolean unconfirmedResult,
              String selectedId, String selectedName, String error, String notice) {
            this.devices = devices; this.loading = loading; this.busy = busy;
            this.authorized = authorized; confirmationPending = confirming;
            this.unconfirmedResult = unconfirmedResult;
            this.selectedId = selectedId; this.selectedName = selectedName;
            this.error = error; this.notice = notice;
        }
    }

    /** Immutable command request; paths always contain a non-empty, encoded destination. */
    static final class Command {
        final String method, path, uri;
        final JSONObject body;
        final boolean playing;
        final long positionMillis;
        Command(String method, String operation, String deviceId, JSONObject body, String uri, boolean playing) throws Exception {
            this(method, operation, deviceId, body, uri, playing, -1);
        }
        Command(String method, String operation, String deviceId, JSONObject body, String uri, boolean playing,
                long positionMillis) throws Exception {
            if (!validDeviceId(deviceId)) throw new IllegalArgumentException(CHOOSE_DEVICE);
            this.method = method;
            path = "/me/player/" + operation + "?device_id="
                    + URLEncoder.encode(deviceId, StandardCharsets.UTF_8.name())
                    + ("seek".equals(operation) ? "&position_ms=" + positionMillis : "");
            this.body = body; this.uri = uri; this.playing = playing;
            this.positionMillis = positionMillis;
        }
    }

    static final class TransferSession {
        final String uri, deviceId, contextUri;
        final boolean playing;
        final long positionMillis;
        TransferSession(String uri, String deviceId, String contextUri, boolean playing, long positionMillis) {
            this.uri = uri; this.deviceId = deviceId; this.contextUri = contextUri;
            this.playing = playing; this.positionMillis = positionMillis;
        }
    }

    private enum Action { PLAY, TOGGLE, NEXT, PREVIOUS, SEEK }

    private final SpotifyAuthStore auth;
    private final SharedPreferences preferences;
    private final Consumer<State> listener;
    private final Runnable refreshPlayback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private Future<?> pending;
    private SpotifyApi api;
    private volatile boolean running;
    private volatile int generation;
    private volatile String selectedId = "";
    private long authRevision = -1;
    private List<Device> devices = Collections.emptyList();
    private boolean loading, busy, confirmationPending, unconfirmedResult;
    private String selectedName = "", error = "", notice = "";
    private State state;

    SpotifyConnect(Context context, Consumer<State> listener, Runnable refreshPlayback) {
        Context application = context.getApplicationContext();
        auth = new SpotifyAuthStore(application);
        preferences = application.getSharedPreferences("spotify_connect_destination", Context.MODE_PRIVATE);
        this.listener = listener; this.refreshPlayback = refreshPlayback;
        synchronizeAuthorization(); rebuildState();
    }

    State snapshot() { return state; }

    void resume() {
        if (!running) { running = true; ++generation; }
        synchronizeAuthorization();
        refreshDevices();
    }

    void pause() {
        running = false; ++generation;
        cancelRequest();
        loading = false; busy = false; confirmationPending = false; unconfirmedResult = false;
        rebuildState();
    }

    /** Refreshing the picker is read-only; the account's current player is never selected implicitly. */
    void refreshDevices() {
        if (!running) return;
        synchronizeAuthorization();
        if (busy || pending != null) return;
        if (!auth.isConnected()) { error = "Connect Spotify to choose a playback device."; publish(); return; }
        final int requestGeneration = generation;
        final long revision = auth.revision();
        final SpotifyApi requestApi = startRequest();
        loading = true; error = ""; publish();
        pending = worker.submit(() -> {
            List<Device> received = null;
            String failure = "";
            try { received = parseDevices(requestApi.get("/me/player/devices")); }
            catch (Exception unavailable) { failure = message(unavailable); }
            final List<Device> result = received;
            final String resultError = failure;
            main.post(() -> {
                if (!validRequest(requestGeneration, revision)) { recoverChangedAuthorization(requestGeneration); return; }
                pending = null; loading = false;
                if (result != null) updateDevices(result);
                error = resultError;
                publish();
            });
        });
    }

    /** Moves the current session while preserving play/pause; without a readable session, records a destination. */
    boolean selectDevice(String id) {
        if (!running) return false;
        synchronizeAuthorization();
        if (busy) return false;
        unconfirmedResult = false;
        if (!authorized()) { error = "Authorize Spotify playback control to switch devices."; publish(); return false; }
        if (pending != null) { error = "Refreshing devices. Try again in a moment."; publish(); return false; }
        Device device = findDevice(devices, id);
        if (device == null || device.restricted) {
            error = device == null ? "This device is no longer available. Refresh the device list."
                    : "Spotify does not allow remote control of this device.";
            publish(); return false;
        }
        final int requestGeneration = generation;
        final long revision = auth.revision();
        final String previousDestination = selectedId;
        final SpotifyApi requestApi = startRequest();
        busy = true; confirmationPending = false; error = ""; notice = "Checking " + device.name + "…"; publish();
        pending = worker.submit(() -> performSelection(requestApi, requestGeneration, revision,
                previousDestination, device.id, device.name));
        return true;
    }

    private void performSelection(SpotifyApi requestApi, int requestGeneration, long revision,
                                  String previousDestination, String destination, String requestedName) {
        List<Device> received = null;
        boolean accepted = false, chosen = false;
        String failure = "", resultNotice = "", targetName = requestedName;
        try {
            received = parseDevices(requestApi.get("/me/player/devices"));
            targetName = requireAvailableDevice(received, destination).name;
            JSONObject before = requestApi.get("/me/player?additional_types=track,episode");
            TransferSession source = transferSession(before);
            long observedAt = System.nanoTime();
            if (!validRequest(requestGeneration, revision) || !previousDestination.equals(selectedId)) {
                main.post(() -> recoverChangedAuthorization(requestGeneration)); return;
            }
            if (source == null) {
                chosen = true;
                resultNotice = "Selected " + targetName + " for the next playback. No current session was available to transfer.";
            } else if (destination.equals(source.deviceId)) {
                chosen = true;
                resultNotice = "Selected " + targetName + ". The current session is already on this device.";
            } else {
                // Some receivers keep their local paused state with play:false; explicitly resume a playing source.
                requestApi.transfer(destination, source.playing, revision);
                accepted = true;
                final String sentTo = targetName;
                main.post(() -> {
                    if (!validRequest(requestGeneration, revision)) { recoverChangedAuthorization(requestGeneration); return; }
                    confirmationPending = true; notice = "Moving the current session to " + sentTo + "…";
                    publish();
                    if (refreshPlayback != null) refreshPlayback.run();
                });
                for (int attempt = 0; attempt < 5; attempt++) {
                    if (attempt > 0) Thread.sleep(attempt == 1 ? 600 : attempt == 2 ? 1400 : 2000);
                    if (!validRequest(requestGeneration, revision) || !previousDestination.equals(selectedId)) {
                        main.post(() -> recoverChangedAuthorization(requestGeneration)); return;
                    }
                    JSONObject current = requestApi.get("/me/player?additional_types=track,episode");
                    long elapsed = Math.max(0, (System.nanoTime() - observedAt) / 1_000_000L);
                    if (confirmsTransfer(source, destination, current, elapsed)) { chosen = true; break; }
                }
                resultNotice = chosen ? "Current session moved to " + targetName + (source.playing ? "." : " and remains paused.")
                        : "Transfer requested to " + targetName + ", but Spotify has not confirmed the same session. The previous destination is still selected.";
                if (!chosen) failure = "The transfer could not be confirmed. The previous playback destination is still selected.";
            }
        } catch (Exception unavailable) {
            if (!accepted) failure = message(unavailable);
            else {
                failure = "The transfer could not be confirmed. The previous playback destination is still selected.";
                resultNotice = "Transfer requested to " + targetName + ", but its result could not be confirmed. The previous destination is still selected.";
            }
        }
        final List<Device> result = received;
        final String resultError = failure, finalNotice = resultNotice, finalName = targetName;
        final boolean confirmedChoice = chosen, wasAccepted = accepted;
        main.post(() -> {
            if (!validRequest(requestGeneration, revision)) { recoverChangedAuthorization(requestGeneration); return; }
            pending = null; busy = false; confirmationPending = false;
            unconfirmedResult = wasAccepted && !confirmedChoice;
            // An uncertain transfer must not silently replace the destination used by transport buttons.
            if (result != null) devices = result;
            if (confirmedChoice) saveDestination(destination, finalName, revision);
            error = resultError; notice = finalNotice;
            publish();
            if ((wasAccepted || confirmedChoice) && refreshPlayback != null) refreshPlayback.run();
        });
    }

    private void saveDestination(String id, String name, long revision) {
        selectedId = id; selectedName = name;
        preferences.edit().putString("device_id", id).putString("device_name", name)
                .putLong("auth_revision", revision).apply();
    }

    boolean play(String uri) { return play(uri, 0); }
    boolean play(String uri, long positionMillis) {
        if (!playableUri(uri)) { unconfirmedResult = false; error = "This Spotify item cannot be played from the panel."; publish(); return false; }
        return submit(Action.PLAY, uri, Math.max(0, positionMillis));
    }

    /** The live state is checked again before toggling, so a stale header cannot pause another device. */
    boolean playPause(SpotifyLibrary.NowPlaying ignoredCurrent) { return submit(Action.TOGGLE, "", 0); }
    boolean previous() { return submit(Action.PREVIOUS, "", 0); }
    boolean next() { return submit(Action.NEXT, "", 0); }

    /** Moves within the current item; never starts or transfers playback to a different device. */
    boolean seek(long positionMillis) { return submit(Action.SEEK, "", positionMillis); }

    /** Binds a progress-bar edit to the item displayed when the user made that edit. */
    boolean seek(long positionMillis, String displayedUri) {
        if (!seekableUri(displayedUri)) {
            unconfirmedResult = false;
            error = "Refresh the current item before moving its progress bar."; publish(); return false;
        }
        return submit(Action.SEEK, displayedUri, positionMillis);
    }

    private boolean submit(Action action, String uri, long positionMillis) {
        if (!running) return false;
        synchronizeAuthorization();
        if (busy) return false;
        unconfirmedResult = false;
        if (!authorized()) { error = "Authorize Spotify playback control to use these buttons."; publish(); return false; }
        if (selectedId.isEmpty()) { error = CHOOSE_DEVICE; publish(); return false; }
        if (pending != null) { error = "Refreshing devices. Try again in a moment."; publish(); return false; }
        final int requestGeneration = generation;
        final long revision = auth.revision();
        final String destination = selectedId;
        final SpotifyApi requestApi = startRequest();
        busy = true; confirmationPending = false; error = ""; notice = "Checking " + selectedName + "…"; publish();
        pending = worker.submit(() -> perform(requestApi, requestGeneration, revision, destination, action, uri, positionMillis));
        return true;
    }

    private void perform(SpotifyApi requestApi, int requestGeneration, long revision, String destination,
                         Action action, String uri, long positionMillis) {
        List<Device> received = null;
        boolean accepted = false, confirmed = false;
        String failure = "", destinationName = selectedName;
        try {
            received = parseDevices(requestApi.get("/me/player/devices"));
            Device device = requireAvailableDevice(received, destination);
            destinationName = device.name;
            JSONObject before = action == Action.PLAY ? new JSONObject()
                    : requestApi.get("/me/player?additional_types=track,episode");
            Command command = action == Action.PLAY ? playCommand(destination, uri, positionMillis)
                    : action == Action.SEEK ? seekCommand(destination, positionMillis, uri, before)
                    : transportCommand(action.name(), destination, before);
            if (action == Action.SEEK) {
                // A track transition or external pause during the first read must not redirect a seek.
                JSONObject latest = requestApi.get("/me/player?additional_types=track,episode");
                revalidateSeek(command, destination, latest);
                before = latest;
            }
            // Pause/sign-out/account changes invalidate the request before any write is dispatched.
            if (!validRequest(requestGeneration, revision) || !destination.equals(selectedId)) {
                main.post(() -> recoverChangedAuthorization(requestGeneration)); return;
            }
            requestApi.command(command.method, command.path, command.body, revision);
            accepted = true;
            final String sentTo = destinationName;
            main.post(() -> {
                if (!validRequest(requestGeneration, revision)) { recoverChangedAuthorization(requestGeneration); return; }
                confirmationPending = true; notice = "Request sent to " + sentTo + ". Checking playback…";
                publish();
                if (refreshPlayback != null) refreshPlayback.run();
            });
            // A 204 acknowledges the request; only a later player state can confirm the result.
            for (int attempt = 0; attempt < 3; attempt++) {
                if (attempt > 0) Thread.sleep(attempt == 1 ? 600 : 1400);
                if (!validRequest(requestGeneration, revision) || !destination.equals(selectedId)) {
                    main.post(() -> recoverChangedAuthorization(requestGeneration)); return;
                }
                JSONObject current = requestApi.get("/me/player?additional_types=track,episode");
                if (confirms(command, destination, before, current)) { confirmed = true; break; }
            }
        } catch (Exception unavailable) {
            if (!accepted) failure = message(unavailable);
            // A failed status refresh must not turn an accepted command into a false playback failure.
        }
        final List<Device> result = received;
        final String resultError = failure, targetName = destinationName;
        final boolean wasAccepted = accepted, wasConfirmed = confirmed;
        main.post(() -> {
            if (!validRequest(requestGeneration, revision)) { recoverChangedAuthorization(requestGeneration); return; }
            pending = null; busy = false; confirmationPending = false;
            unconfirmedResult = wasAccepted && !wasConfirmed;
            if (result != null) updateDevices(result);
            error = resultError;
            notice = wasConfirmed ? "Playback updated on " + targetName + "."
                    : wasAccepted ? "Request sent to " + targetName + ". Spotify has not confirmed the result yet." : "";
            publish();
            if (wasAccepted && refreshPlayback != null) refreshPlayback.run();
        });
    }

    private SpotifyApi startRequest() {
        if (worker == null) worker = Executors.newSingleThreadExecutor();
        // Each request owns its cancellation flag; a resumed panel cannot revive an older request.
        api = new SpotifyApi(auth); api.begin(); return api;
    }

    private void cancelRequest() {
        if (api != null) api.cancel();
        if (pending != null) pending.cancel(true);
        pending = null;
        if (worker != null) worker.shutdownNow();
        worker = null; api = null;
    }

    private boolean validRequest(int requestGeneration, long revision) {
        return running && generation == requestGeneration && auth.revision() == revision
                && !Thread.currentThread().isInterrupted();
    }

    /** Provisioning may replace OAuth while the overlay stays open, without an Activity pause. */
    private void recoverChangedAuthorization(int requestGeneration) {
        if (running && generation == requestGeneration && auth.revision() != authRevision) {
            synchronizeAuthorization(); publish();
        }
    }

    private void synchronizeAuthorization() {
        long revision = auth.revision();
        if (revision == authRevision) return;
        boolean first = authRevision == -1;
        ++generation; cancelRequest();
        devices = Collections.emptyList(); loading = false; busy = false; confirmationPending = false; unconfirmedResult = false;
        error = ""; notice = ""; selectedId = ""; selectedName = ""; authRevision = revision;
        if (first && preferences.getLong("auth_revision", -1) == revision) {
            String saved = preferences.getString("device_id", "");
            if (validDeviceId(saved)) {
                selectedId = saved; selectedName = preferences.getString("device_name", "Spotify device");
            }
        } else preferences.edit().clear().apply();
    }

    private boolean authorized() {
        SpotifyAuthStore.Credentials credentials = auth.credentials();
        if (credentials == null) return false;
        for (String scope : credentials.scope.trim().split("\\s+")) if (WRITE_SCOPE.equals(scope)) return true;
        return false;
    }

    private void updateDevices(List<Device> received) {
        devices = received;
        Device selected = findDevice(received, selectedId);
        if (selected != null && !selected.restricted) selectedName = selected.name;
        else if (!selectedId.isEmpty()) {
            selectedId = ""; selectedName = "";
            preferences.edit().clear().apply();
            notice = "The selected device is unavailable. Choose a playback device.";
        }
    }

    private void rebuildState() {
        state = new State(devices, loading, busy, authorized(), confirmationPending, unconfirmedResult,
                selectedId, selectedName, error, notice);
    }
    private void publish() { rebuildState(); if (running && listener != null) listener.accept(state); }

    static List<Device> parseDevices(JSONObject response) {
        JSONArray rows = response.optJSONArray("devices");
        List<Device> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (rows != null) for (int index = 0; index < rows.length() && result.size() < 100; index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row == null) continue;
            Object rawId = row.opt("id");
            String id = rawId instanceof String ? (String) rawId : "";
            if (!validDeviceId(id) || !seen.add(id)) continue;
            String name = row.optString("name", "Spotify device");
            if (name.trim().isEmpty()) name = "Spotify device";
            if (name.length() > 120) name = name.substring(0, 120);
            String type = row.optString("type", "");
            if (type.length() > 40) type = type.substring(0, 40);
            int volume = row.isNull("volume_percent") ? -1 : row.optInt("volume_percent", -1);
            if (volume < 0 || volume > 100) volume = -1;
            result.add(new Device(id, name, type, row.optBoolean("is_active", false),
                    row.optBoolean("is_restricted", false), volume));
        }
        return Collections.unmodifiableList(result);
    }

    static Device findDevice(List<Device> devices, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Device device : devices) if (device.id.equals(id)) return device;
        return null;
    }

    static Device requireAvailableDevice(List<Device> devices, String id) {
        Device device = findDevice(devices, id);
        if (device == null) throw new IllegalArgumentException("The selected device is unavailable. Choose a playback device.");
        if (device.restricted) throw new IllegalArgumentException("Spotify does not allow remote control of this device.");
        return device;
    }

    static TransferSession transferSession(JSONObject current) {
        String uri = itemUri(current), device = deviceId(current);
        Object progress = current.opt("progress_ms"), playing = current.opt("is_playing");
        JSONObject rawDevice = current.optJSONObject("device");
        if (!seekableUri(uri) || !validDeviceId(device) || !(progress instanceof Number)
                || ((Number) progress).longValue() < 0 || !(playing instanceof Boolean)
                || rawDevice == null || !(rawDevice.opt("id") instanceof String)) return null;
        JSONObject context = current.optJSONObject("context");
        String contextUri = context == null ? "" : context.optString("uri", "");
        return new TransferSession(uri, device, contextUri, (Boolean) playing, ((Number) progress).longValue());
    }

    static boolean confirmsTransfer(TransferSession source, String destination, JSONObject current, long elapsedMillis) {
        TransferSession received = transferSession(current);
        if (source == null || received == null || !destination.equals(received.deviceId)
                || !source.uri.equals(received.uri) || source.playing != received.playing
                || !source.contextUri.isEmpty() && !source.contextUri.equals(received.contextUri)) return false;
        long advance = source.playing ? Math.max(0, elapsedMillis) : 0;
        long expected = advance > Long.MAX_VALUE - source.positionMillis ? Long.MAX_VALUE : source.positionMillis + advance;
        long minimum = Math.max(0, expected - 5000);
        long maximum = expected > Long.MAX_VALUE - 5000 ? Long.MAX_VALUE : expected + 5000;
        return received.positionMillis >= minimum && received.positionMillis <= maximum;
    }

    static Command playCommand(String destination, String uri, long positionMillis) throws Exception {
        if (!playableUri(uri)) throw new IllegalArgumentException("This Spotify item cannot be played from the panel.");
        JSONObject body = new JSONObject();
        if (uri.startsWith("spotify:playlist:") || uri.startsWith("spotify:album:")) body.put("context_uri", uri);
        else body.put("uris", new JSONArray().put(uri));
        if (positionMillis > 0) body.put("position_ms", Math.min(Integer.MAX_VALUE, positionMillis));
        return new Command("PUT", "play", destination, body, uri, true);
    }

    static Command transportCommand(String action, String destination, JSONObject current) throws Exception {
        if ("TOGGLE".equals(action)) {
            boolean pause = destination.equals(deviceId(current)) && current.optBoolean("is_playing", false);
            return new Command("PUT", pause ? "pause" : "play", destination, null, "", !pause);
        }
        if ("NEXT".equals(action) || "PREVIOUS".equals(action))
            return new Command("POST", "NEXT".equals(action) ? "next" : "previous", destination, null, "", true);
        throw new IllegalArgumentException("Unsupported playback action");
    }

    static Command seekCommand(String destination, long positionMillis, String displayedUri, JSONObject current) throws Exception {
        String uri = requireSeekCurrent(destination, current);
        if (displayedUri != null && !displayedUri.isEmpty() && !uri.equals(displayedUri))
            throw new IllegalArgumentException("The current item changed. Move its progress bar again.");
        long duration = current.optJSONObject("item").optLong("duration_ms", 0);
        long target = Math.min(Integer.MAX_VALUE, Math.min(Math.max(0, positionMillis), duration - 1));
        return new Command("PUT", "seek", destination, null, uri, current.optBoolean("is_playing", false), target);
    }

    /** Spotify's seek endpoint has no conditional media ID, so recheck immediately before dispatch. */
    static void revalidateSeek(Command command, String destination, JSONObject current) {
        String uri = requireSeekCurrent(destination, current);
        long duration = current.optJSONObject("item").optLong("duration_ms", 0);
        if (!command.uri.equals(uri) || command.playing != current.optBoolean("is_playing", false)
                || command.positionMillis < 0 || command.positionMillis >= duration)
            throw new IllegalArgumentException("Playback changed. Move the progress bar again.");
    }

    private static String requireSeekCurrent(String destination, JSONObject current) {
        if (!destination.equals(deviceId(current)))
            throw new IllegalArgumentException("Start playback on the selected device before moving its progress bar.");
        JSONObject item = current.optJSONObject("item");
        if (item == null || !seekableUri(itemUri(current)) || item.optLong("duration_ms", 0) <= 0
                || !(current.opt("is_playing") instanceof Boolean))
            throw new IllegalArgumentException("This item does not have an available playback position.");
        JSONObject device = current.optJSONObject("device");
        if (device != null && device.optBoolean("is_restricted", false))
            throw new IllegalArgumentException("Spotify does not allow remote control of this device.");
        return itemUri(current);
    }

    static boolean confirms(Command command, String destination, JSONObject before, JSONObject current) {
        if (!destination.equals(deviceId(current))) return false;
        boolean playing = current.optBoolean("is_playing", false);
        if (command.path.startsWith("/me/player/seek?")) {
            Object rawPosition = current.opt("progress_ms");
            if (!(rawPosition instanceof Number) || !(current.opt("is_playing") instanceof Boolean)) return false;
            long position = ((Number) rawPosition).longValue();
            return command.uri.equals(itemUri(current)) && command.playing == playing && position >= 0
                    && position >= Math.max(0, command.positionMillis - 2500)
                    && position <= command.positionMillis + 2500;
        }
        if (command.path.startsWith("/me/player/pause?")) return !playing;
        if (command.path.startsWith("/me/player/next?") || command.path.startsWith("/me/player/previous?")) {
            String priorUri = itemUri(before), nextUri = itemUri(current);
            return !nextUri.isEmpty() && (!nextUri.equals(priorUri)
                    || command.path.startsWith("/me/player/previous?") && before.optLong("progress_ms", 0) > 5000
                    && current.optLong("progress_ms", Long.MAX_VALUE) < 3000);
        }
        if (!playing) return false;
        if (command.uri.isEmpty()) return true;
        if (command.uri.startsWith("spotify:playlist:") || command.uri.startsWith("spotify:album:")) {
            JSONObject context = current.optJSONObject("context");
            return context != null && command.uri.equals(context.optString("uri", ""));
        }
        return command.uri.equals(itemUri(current));
    }

    private static String deviceId(JSONObject current) {
        JSONObject device = current.optJSONObject("device");
        return device == null ? "" : device.optString("id", "");
    }
    private static String itemUri(JSONObject current) {
        JSONObject item = current.optJSONObject("item");
        return item == null ? "" : item.optString("uri", "");
    }
    static boolean playableUri(String uri) {
        return uri != null && uri.matches("spotify:(playlist|album|track|episode):[A-Za-z0-9]{22}");
    }
    private static boolean seekableUri(String uri) {
        return uri != null && uri.matches("spotify:(track|episode):[A-Za-z0-9]{22}");
    }
    static boolean validDeviceId(String id) {
        if (id == null || id.isEmpty() || id.length() > 512 || !id.equals(id.trim())) return false;
        for (int index = 0; index < id.length(); index++) if (Character.isISOControl(id.charAt(index))) return false;
        return true;
    }
    private static String message(Exception failure) {
        String detail = failure.getMessage();
        return detail == null || detail.isEmpty() ? "Spotify is temporarily unavailable. Try again." : detail;
    }
}
