package com.daviddemri.tv.spotify;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Bounded Spotify reads and explicit-device player commands. Authorization never enters URLs or logs. */
final class SpotifyApi {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final SpotifyAuthStore auth;
    private volatile HttpURLConnection active;
    private volatile boolean canceled;
    // The quota belongs to the application, including requests made by another foreground controller.
    private static volatile long notBefore;

    static final class ApiException extends IOException {
        final int status;
        final long retryAt;
        ApiException(int status, String message, long retryAt) { super(message); this.status = status; this.retryAt = retryAt; }
    }

    SpotifyApi(SpotifyAuthStore auth) { this.auth = auth; }
    void begin() { canceled = false; }
    void cancel() { canceled = true; HttpURLConnection request = active; if (request != null) request.disconnect(); }
    long retryAt() { return notBefore; }

    JSONObject get(String pathOrUrl) throws Exception {
        String url = pathOrUrl.startsWith("/") ? "https://api.spotify.com/v1" + pathOrUrl : pathOrUrl;
        if (!SpotifyApiPolicy.allowedApiUrl(url)) throw new IllegalArgumentException("Unsupported Spotify endpoint");
        checkReady();
        SpotifyAuthStore.Credentials credentials = auth.credentials();
        if (credentials == null) throw new ApiException(401, "Connect Spotify to load your library.", 0);
        if (credentials.accessToken.isEmpty() || credentials.expiresAt < System.currentTimeMillis() + 30000) credentials = refresh(credentials);
        try {
            return request(url, credentials.accessToken, "GET", null, null, -1);
        } catch (ApiException failure) {
            if (failure.status != 401) throw failure;
            credentials = refresh(credentials);
            return request(url, credentials.accessToken, "GET", null, null, -1);
        }
    }

    JSONObject command(String method, String path, JSONObject body) throws Exception {
        return command(method, path, body, auth.revision());
    }

    JSONObject command(String method, String path, JSONObject body, long expectedRevision) throws Exception {
        String url = path.startsWith("/") ? "https://api.spotify.com/v1" + path : path;
        if (!SpotifyApiPolicy.allowedPlayerCommand(method, url)) throw new IllegalArgumentException("Unsupported Spotify player command");
        return executeCommand(method, url, body, expectedRevision);
    }

    /** Transfer has one explicit target and the observed source playing state. */
    JSONObject transfer(String deviceId, boolean playing, long expectedRevision) throws Exception {
        if (!SpotifyApiPolicy.validDeviceId(deviceId)) throw new IllegalArgumentException("Choose a playback device first.");
        // TV receivers may retain their own paused state with play:false even when the source plays.
        JSONObject body = new JSONObject().put("device_ids", new JSONArray().put(deviceId)).put("play", playing);
        return executeCommand("PUT", "https://api.spotify.com/v1/me/player", body, expectedRevision);
    }

    private JSONObject executeCommand(String method, String url, JSONObject body, long expectedRevision) throws Exception {
        checkReady();
        SpotifyAuthStore.Credentials credentials = commandCredentials(expectedRevision);
        if (credentials.accessToken.isEmpty() || credentials.expiresAt < System.currentTimeMillis() + 30000) credentials = refresh(credentials);
        commandCredentials(expectedRevision);
        String json = body == null ? null : body.toString();
        if (json != null && json.length() > 16384) throw new IllegalArgumentException("Spotify command is too large");
        try {
            return request(url, credentials.accessToken, method, json, "application/json", expectedRevision);
        } catch (ApiException failure) {
            if (failure.status != 401) throw failure;
            commandCredentials(expectedRevision);
            credentials = refresh(credentials);
            commandCredentials(expectedRevision);
            return request(url, credentials.accessToken, method, json, "application/json", expectedRevision);
        }
    }

    private SpotifyAuthStore.Credentials commandCredentials(long expectedRevision) throws ApiException {
        SpotifyAuthStore.Credentials current = auth.credentials();
        if (current == null || current.revision != expectedRevision)
            throw new ApiException(401, "Spotify authorization changed. Reopen the panel and try again.", 0);
        boolean granted = false;
        for (String scope : current.scope.trim().split("\\s+")) if ("user-modify-playback-state".equals(scope)) granted = true;
        if (!granted) throw new ApiException(403, "Reconnect Spotify and authorize playback control to use these buttons.", 0);
        return current;
    }

    private SpotifyAuthStore.Credentials refresh(SpotifyAuthStore.Credentials previous) throws Exception {
        checkReady();
        String body = "grant_type=refresh_token&client_id=" + encode(previous.clientId) + "&refresh_token=" + encode(previous.refreshToken);
        try {
            JSONObject response = request("https://accounts.spotify.com/api/token", null, "POST", body, "application/x-www-form-urlencoded", -1);
            if (!auth.updateTokens(previous, response)) throw new ApiException(401, "Spotify authorization changed. Reopen the panel and try again.", 0);
            SpotifyAuthStore.Credentials current = auth.credentials();
            if (current == null) throw new ApiException(401, "Reconnect Spotify to continue.", 0);
            return current;
        } catch (ApiException failure) {
            if (failure.status == 400 || failure.status == 401) auth.invalidate(previous);
            throw failure;
        }
    }

    private JSONObject request(String url, String accessToken, String method, String body, String contentType, long expectedRevision) throws Exception {
        checkReady();
        boolean playerCommand = expectedRevision >= 0;
        if (body != null && !playerCommand && !"https://accounts.spotify.com/api/token".equals(url)) throw new IllegalArgumentException("Unsupported token endpoint");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        active = connection;
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "PersonalSpotifyPanel/2");
        connection.setRequestMethod(method);
        if (accessToken != null) connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        try {
            checkCanceled();
            if (body != null) {
                connection.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", contentType);
                if (playerCommand) commandCredentials(expectedRevision);
                checkCanceled();
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            checkCanceled();
            if (playerCommand) commandCredentials(expectedRevision);
            int status = connection.getResponseCode();
            if (status == 429) {
                String retry = connection.getHeaderField("Retry-After");
                long delay = SpotifyApiPolicy.retryDelayMillis(retry);
                // The UI's normal backoff is bounded; an explicit longer server quota block is not.
                try {
                    long seconds = Long.parseLong(retry.trim());
                    if (seconds > 900) delay = Math.multiplyExact(seconds, 1000L);
                } catch (ArithmeticException overflow) { delay = Long.MAX_VALUE; }
                catch (RuntimeException malformed) { }
                long now = System.currentTimeMillis();
                synchronized (SpotifyApi.class) {
                    notBefore = Math.max(notBefore, delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay);
                }
                throw new ApiException(status, "Spotify is busy. The panel will retry shortly.", notBefore);
            }
            if (status == 204) return new JSONObject();
            if (status != 200) {
                String message = playerCommand && status == 404 ? "This device is unavailable. Open Spotify on it, then refresh the device list."
                        : playerCommand && status >= 500 ? "Spotify could not complete this request. Open Spotify on the selected device, then try again."
                        : playerCommand && status == 403 ? "Spotify denied playback control. Check the account's Premium access and playback permission."
                        : playerCommand && status == 400 ? "Spotify could not play this selection on that device. Try another selection."
                        : status == 401 || status == 400 ? "Reconnect Spotify to continue."
                        : status == 403 ? "Spotify denied this API access. Check the application's account access and read permissions."
                        : "Spotify is temporarily unavailable.";
                throw new ApiException(status, message, 0);
            }
            // Some Spotify receivers acknowledge successful player writes with a 200 string body.
            // This is only an acknowledgement; Connect verifies the later player state separately.
            if (playerCommand) return new JSONObject();
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCanceled();
                    if (output.size() + count > MAX_BYTES) throw new IOException("Spotify response is too large");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
            if (active == connection) active = null;
        }
    }

    private void checkReady() throws IOException {
        checkCanceled();
        if (System.currentTimeMillis() < notBefore) throw new ApiException(429, "Spotify is busy. The panel will retry shortly.", notBefore);
    }
    private void checkCanceled() throws IOException { if (canceled || Thread.currentThread().isInterrupted()) throw new IOException("Canceled"); }
    private static String encode(String value) throws Exception { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
}
