package com.daviddemri.tv.spotify;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** OAuth credentials stay in private storage encrypted by a non-exportable Android Keystore key. */
final class SpotifyAuthStore {
    private static final String ALIAS = "com.daviddemri.tv.spotify.oauth.v1";
    private static final Object LOCK = new Object();
    private static String cachedSealed;
    private static Credentials cachedCredentials;
    private final SharedPreferences preferences;

    static final class Credentials {
        final String clientId, accessToken, refreshToken, scope;
        final long expiresAt, revision;
        Credentials(JSONObject source, long revision) {
            clientId = text(source, "client_id", "");
            accessToken = text(source, "access_token", "");
            refreshToken = text(source, "refresh_token", "");
            scope = text(source, "scope", "");
            expiresAt = source.optLong("expires_at", 0);
            this.revision = revision;
        }
    }

    SpotifyAuthStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("spotify_oauth", Context.MODE_PRIVATE);
    }

    /** Called only by the app's protected provisioning provider. Never returns a token. */
    void saveOAuth(JSONObject source) throws Exception {
        String client = bounded(text(source, "client_id", ""), 32);
        String refresh = bounded(text(source, "refresh_token", ""), 4096);
        String access = bounded(text(source, "access_token", ""), 4096);
        String scope = bounded(text(source, "scope", ""), 2048);
        if (!client.matches("[A-Fa-f0-9]{32}") || refresh.isEmpty()) throw new IllegalArgumentException("Invalid OAuth credentials");
        Set<String> granted = new HashSet<>(Arrays.asList(scope.trim().split("\\s+")));
        if (!granted.contains("playlist-read-private") || !granted.contains("user-read-recently-played")
                || !granted.contains("user-read-playback-state")) throw new IllegalArgumentException("Required Spotify read scopes are missing");
        long now = System.currentTimeMillis();
        long expiry = source.optLong("expires_at", 0);
        if (expiry <= 0) expiry = now + Math.max(0, Math.min(86400, source.optLong("expires_in", 0))) * 1000;
        JSONObject clean = new JSONObject().put("client_id", client).put("refresh_token", refresh)
                .put("access_token", access).put("expires_at", expiry).put("scope", scope);
        synchronized (LOCK) { write(clean, Math.max(now, preferences.getLong("revision", 0) + 1)); }
    }

    boolean isConnected() { return credentials() != null; }
    long revision() { synchronized (LOCK) { return preferences.getLong("revision", 0); } }

    JSONObject status() {
        JSONObject status = new JSONObject();
        Credentials credentials = credentials();
        try {
            status.put("connected", credentials != null);
            status.put("revision", revision());
            if (credentials != null) {
                status.put("client_id", credentials.clientId);
                status.put("scope", credentials.scope);
                status.put("expires_at", credentials.expiresAt);
            }
        } catch (Exception ignored) { }
        return status;
    }

    void clear() {
        synchronized (LOCK) {
            if (!preferences.edit().remove("sealed").putLong("revision", Math.max(System.currentTimeMillis(), revision() + 1)).commit()) {
                throw new IllegalStateException("Could not remove Spotify authorization");
            }
            cachedSealed = null;
            cachedCredentials = null;
        }
    }

    Credentials credentials() {
        synchronized (LOCK) {
            String sealed = preferences.getString("sealed", "");
            if (sealed.isEmpty()) return null;
            long revision = preferences.getLong("revision", 0);
            if (sealed.equals(cachedSealed) && cachedCredentials != null && cachedCredentials.revision == revision) return cachedCredentials;
            try {
                byte[] payload = Base64.decode(sealed, Base64.NO_WRAP);
                if (payload.length < 29 || payload.length > 16384) return null;
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(payload, 0, 12)));
                JSONObject value = new JSONObject(new String(cipher.doFinal(Arrays.copyOfRange(payload, 12, payload.length)), StandardCharsets.UTF_8));
                Credentials result = new Credentials(value, revision);
                if (!result.clientId.matches("[A-Fa-f0-9]{32}") || result.refreshToken.isEmpty()) return null;
                cachedSealed = sealed;
                cachedCredentials = result;
                return result;
            } catch (Exception unavailable) {
                // Reconnection, rather than plaintext fallback, recovers from a lost/invalidated key.
                return null;
            }
        }
    }

    /** A response for an older sign-in must never overwrite a newly provisioned account. */
    boolean updateTokens(Credentials expected, JSONObject response) throws Exception {
        synchronized (LOCK) {
            Credentials current = credentials();
            if (current == null || current.revision != expected.revision || !current.refreshToken.equals(expected.refreshToken)) return false;
            String access = bounded(text(response, "access_token", ""), 4096);
            String refresh = bounded(text(response, "refresh_token", current.refreshToken), 4096);
            if (access.isEmpty() || refresh.isEmpty()) throw new IllegalArgumentException("Invalid token response");
            long lifetime = Math.max(1, Math.min(86400, response.optLong("expires_in", 3600)));
            JSONObject next = new JSONObject().put("client_id", current.clientId).put("access_token", access)
                    .put("refresh_token", refresh).put("scope", text(response, "scope", current.scope))
                    .put("expires_at", System.currentTimeMillis() + lifetime * 1000);
            write(next, current.revision);
            return true;
        }
    }

    void invalidate(Credentials expected) {
        synchronized (LOCK) {
            Credentials current = credentials();
            if (current != null && current.revision == expected.revision && current.refreshToken.equals(expected.refreshToken)) clear();
        }
    }

    private void write(JSONObject value, long revision) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new IllegalStateException("Unexpected IV");
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        byte[] payload = Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
        String sealed = Base64.encodeToString(payload, Base64.NO_WRAP);
        if (!preferences.edit().putString("sealed", sealed).putLong("revision", revision).commit()) {
            throw new IllegalStateException("Could not save Spotify authorization");
        }
        cachedSealed = sealed;
        cachedCredentials = new Credentials(value, revision);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private static String text(JSONObject object, String key, String fallback) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : fallback;
    }
    private static String bounded(String value, int length) {
        if (value.length() > length || value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid OAuth field");
        }
        return value;
    }
}
