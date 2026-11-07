package com.daviddemri.tv.spotify;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Network and playback identifiers accepted by the Spotify account integration. */
final class SpotifyApiPolicy {
    private static final Pattern ITEM = Pattern.compile("spotify:(playlist|track|album|episode|show):[A-Za-z0-9]{22}");
    private static final long DEFAULT_RETRY_MS = 30_000L;

    private SpotifyApiPolicy() { }

    static boolean validItemUri(String value) {
        return value != null && ITEM.matcher(value).matches();
    }

    static boolean allowedApiUrl(String value) {
        URI uri = https(value, 4096);
        if (uri == null || !"api.spotify.com".equalsIgnoreCase(uri.getHost())) return false;
        String path = uri.getPath();
        if (path == null || !path.startsWith("/v1/") || path.length() <= 4 || path.indexOf('\\') >= 0) return false;
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) return false;
        }
        String rawPath = uri.getRawPath().toLowerCase(Locale.ROOT);
        if (rawPath.contains("%2f") || rawPath.contains("%5c")) return false;
        String query = uri.getRawQuery();
        if (query != null) {
            try {
                for (String parameter : query.split("&", -1)) {
                    String key = parameter.split("=", 2)[0];
                    key = URLDecoder.decode(key, StandardCharsets.UTF_8.name()).toLowerCase(Locale.ROOT);
                    if ("access_token".equals(key) || "refresh_token".equals(key)
                            || "client_secret".equals(key) || "authorization".equals(key)
                            || "password".equals(key)) return false;
                }
            } catch (Exception malformedQuery) {
                return false;
            }
        }
        return true;
    }

    static boolean allowedImageUrl(String value) {
        URI uri = https(value, 8192);
        if (uri == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("scdn.co") || host.endsWith(".scdn.co")
                || host.equals("spotifycdn.com") || host.endsWith(".spotifycdn.com");
    }

    /** Player writes require one explicit destination and a known method/operation pair. */
    static boolean allowedPlayerCommand(String method, String value) {
        if (!allowedApiUrl(value)) return false;
        try {
            URI uri = new URI(value);
            String path = uri.getRawPath();
            boolean seek = "PUT".equals(method) && "/v1/me/player/seek".equals(path);
            boolean known = "PUT".equals(method) && ("/v1/me/player/play".equals(path) || "/v1/me/player/pause".equals(path))
                    || "POST".equals(method) && ("/v1/me/player/next".equals(path) || "/v1/me/player/previous".equals(path)) || seek;
            if (!known) return false;
            String query = uri.getRawQuery();
            if (query == null) return false;
            String[] fields = query.split("&", -1);
            if (fields.length != (seek ? 2 : 1)) return false;
            String id = null, position = null;
            for (String field : fields) {
                String[] parameter = field.split("=", 2);
                if (parameter.length != 2) return false;
                if ("device_id".equals(parameter[0]) && id == null)
                    id = URLDecoder.decode(parameter[1], StandardCharsets.UTF_8.name());
                else if (seek && "position_ms".equals(parameter[0]) && position == null)
                    position = URLDecoder.decode(parameter[1], StandardCharsets.UTF_8.name());
                else return false;
            }
            if (!validDeviceId(id)) return false;
            if (seek && (position == null || !position.matches("[0-9]{1,10}") || Long.parseLong(position) > Integer.MAX_VALUE)) return false;
            return true;
        } catch (Exception malformed) { return false; }
    }

    static boolean validDeviceId(String id) {
        if (id == null || id.isEmpty() || id.length() > 512 || !id.equals(id.trim())) return false;
        for (int index = 0; index < id.length(); index++) if (Character.isISOControl(id.charAt(index))) return false;
        return true;
    }

    static long retryDelayMillis(String value) {
        if (value == null) return DEFAULT_RETRY_MS;
        try {
            String text = value.trim();
            if (text.isEmpty() || !text.matches("[0-9]+")) return DEFAULT_RETRY_MS;
            long seconds = Long.parseLong(text);
            return Math.max(1L, Math.min(900L, seconds)) * 1000L;
        } catch (NumberFormatException malformed) {
            return DEFAULT_RETRY_MS;
        }
    }

    private static URI https(String value, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum) return null;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque() || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() != -1 && uri.getPort() != 443) return null;
            String path = uri.getPath();
            if (path != null) {
                for (int i = 0; i < path.length(); i++) if (Character.isISOControl(path.charAt(i))) return null;
            }
            return uri;
        } catch (URISyntaxException malformed) {
            return null;
        }
    }
}
