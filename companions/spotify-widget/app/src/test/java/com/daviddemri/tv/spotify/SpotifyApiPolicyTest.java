package com.daviddemri.tv.spotify;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpotifyApiPolicyTest {
    private static final String ID = "0123456789AbCdEfGhIjKl";

    @Test public void acceptsCanonicalCatalogUrisForSupportedItemTypes() {
        for (String type : new String[]{"playlist", "track", "album", "episode", "show"}) {
            assertTrue(type, SpotifyApiPolicy.validItemUri("spotify:" + type + ":" + ID));
        }
    }

    @Test public void rejectsNonCanonicalOrUnsupportedPlaybackIdentifiers() {
        for (String value : new String[]{"", "spotify:track:" + ID + "x", "spotify:track:" + ID.substring(1),
                "spotify:track:" + ID + "?si=secret", "spotify:track:" + ID + "#fragment",
                "spotify:TRACK:" + ID, "spotify:artist:" + ID, "Spotify:track:" + ID,
                "spotify:track:0123456789AbCdEfGhIjK_", "spotify:nl:opaque", " https://open.spotify.com/track/" + ID,
                "https://open.spotify.com/track/" + ID, "spotify:track:" + ID + "\n"}) {
            assertFalse(value, SpotifyApiPolicy.validItemUri(value));
        }
        assertFalse(SpotifyApiPolicy.validItemUri(null));
    }

    @Test public void acceptsOfficialApiEndpointsAndPagination() {
        assertTrue(SpotifyApiPolicy.allowedApiUrl("https://api.spotify.com/v1/me/playlists?limit=50&offset=50"));
        assertTrue(SpotifyApiPolicy.allowedApiUrl("https://api.spotify.com:443/v1/me/player?additional_types=track%2Cepisode"));
        assertTrue(SpotifyApiPolicy.allowedApiUrl("HTTPS://API.SPOTIFY.COM/v1/me/episodes?limit=50"));
    }

    @Test public void rejectsApiHostSchemePortAndPathConfusion() {
        for (String value : new String[]{"http://api.spotify.com/v1/me", "https://api.spotify.com.evil.test/v1/me",
                "https://evil.test/api.spotify.com/v1/me", "https://api.spotify.com@evil.test/v1/me",
                "https://evil.test@api.spotify.com/v1/me", "https://api.spotify.com%40evil.test/v1/me",
                "https://api.spotify.com:444/v1/me", "https://api.spotify.com/v1/me#fragment",
                "//api.spotify.com/v1/me", "https:api.spotify.com/v1/me", "https://api.spotify.com/v10/me",
                "https://api.spotify.com/api/token", "https://api.spotify.com/v1/../api/token",
                "https://api.spotify.com/v1/%2e%2e/api/token", "https://api.spotify.com/v1/me%2f..%2ftoken",
                "https://api.spotify.com/v1/me%5ctoken", "https://api.spotify.com/v1/me%00",
                "https://api.spotify.com/v1/", "https://api.spotify.com./v1/me", "not a URI"}) {
            assertFalse(value, SpotifyApiPolicy.allowedApiUrl(value));
        }
        assertFalse(SpotifyApiPolicy.allowedApiUrl(null));
    }

    @Test public void credentialsNeverTravelInApiQueryParameters() {
        for (String key : new String[]{"access_token", "refresh_token", "client_secret", "authorization", "password",
                "ACCESS_TOKEN", "access%5ftoken"}) {
            assertFalse(key, SpotifyApiPolicy.allowedApiUrl("https://api.spotify.com/v1/me?limit=50&" + key + "=value"));
        }
    }

    @Test public void permitsOnlySpotifyArtworkHostsOverHttps() {
        for (String value : new String[]{"https://i.scdn.co/image/cover", "https://mosaic.scdn.co/300/cover",
                "https://image-cdn-ak.spotifycdn.com/image/cover", "https://partner-ai-cdn.spotifycdn.com/images/cover",
                "https://I.SCDN.CO:443/image/cover?size=64"}) {
            assertTrue(value, SpotifyApiPolicy.allowedImageUrl(value));
        }
        for (String value : new String[]{"http://i.scdn.co/image/cover", "https://i.scdn.co.evil.test/image/cover",
                "https://evilscdn.co/image/cover", "https://scdn.co.evil.test/cover", "https://127.0.0.1/cover",
                "https://user@i.scdn.co/image/cover", "https://i.scdn.co:8443/image/cover",
                "https://i.scdn.co/image/cover#fragment", "content://i.scdn.co/image/cover", "file:///cover"}) {
            assertFalse(value, SpotifyApiPolicy.allowedImageUrl(value));
        }
        assertFalse(SpotifyApiPolicy.allowedImageUrl(null));
    }

    @Test public void boundsServerRetrySecondsWithoutOverflow() {
        assertEquals(1000L, SpotifyApiPolicy.retryDelayMillis("0"));
        assertEquals(1000L, SpotifyApiPolicy.retryDelayMillis("1"));
        assertEquals(15000L, SpotifyApiPolicy.retryDelayMillis(" 15 "));
        assertEquals(900000L, SpotifyApiPolicy.retryDelayMillis("900"));
        assertEquals(900000L, SpotifyApiPolicy.retryDelayMillis("9223372036854775807"));
    }

    @Test public void permitsOnlyExplicitDevicePlayerCommands() {
        for (String operation : new String[]{"play", "pause"})
            assertTrue(SpotifyApiPolicy.allowedPlayerCommand("PUT", "https://api.spotify.com/v1/me/player/" + operation + "?device_id=abc%2Fxyz"));
        for (String operation : new String[]{"next", "previous"})
            assertTrue(SpotifyApiPolicy.allowedPlayerCommand("POST", "https://api.spotify.com/v1/me/player/" + operation + "?device_id=abc"));
        for (String value : new String[]{"https://api.spotify.com/v1/me/player/play", "https://api.spotify.com/v1/me/player/play?device_id=",
                "https://api.spotify.com/v1/me/player/play?device_id=%20", "https://api.spotify.com/v1/me/player/play?device_id=abc%0A",
                "https://api.spotify.com/v1/me/player/play?device_id=abc&device_id=def", "https://api.spotify.com/v1/me/player/play?device_id=abc&other=x",
                "https://api.spotify.com/v1/me/player/play?device_id=abc&", "https://api.spotify.com/v1/me/player/play?other=abc",
                "https://api.spotify.com/v1/me/player/volume?device_id=abc", "https://api.spotify.com/v1/me/player/%70lay?device_id=abc"})
            assertFalse(value, SpotifyApiPolicy.allowedPlayerCommand("PUT", value));
        assertFalse(SpotifyApiPolicy.allowedPlayerCommand("POST", "https://api.spotify.com/v1/me/player/play?device_id=abc"));
        assertFalse(SpotifyApiPolicy.allowedPlayerCommand("PUT", "https://api.spotify.com/v1/me/player/next?device_id=abc"));
        assertFalse(SpotifyApiPolicy.allowedPlayerCommand("DELETE", "https://api.spotify.com/v1/me/player/play?device_id=abc"));
    }

    @Test public void seekRequiresAnExplicitDeviceAndBoundedPositionOnly() {
        String url = "https://api.spotify.com/v1/me/player/seek";
        assertTrue(SpotifyApiPolicy.allowedPlayerCommand("PUT", url + "?device_id=abc&position_ms=0"));
        assertTrue(SpotifyApiPolicy.allowedPlayerCommand("PUT", url + "?position_ms=2147483647&device_id=abc"));
        for (String query : new String[]{"device_id=abc", "position_ms=10", "device_id=&position_ms=10",
                "device_id=abc&position_ms=", "device_id=abc&position_ms=-1", "device_id=abc&position_ms=1.5",
                "device_id=abc&position_ms=2147483648", "device_id=abc&position_ms=99999999999",
                "device_id=abc&position_ms=0&position_ms=10", "device_id=abc&device_id=def",
                "device_id=abc&other=0", "device_id=abc&position_ms=0&"})
            assertFalse(query, SpotifyApiPolicy.allowedPlayerCommand("PUT", url + "?" + query));
        assertFalse(SpotifyApiPolicy.allowedPlayerCommand("POST", url + "?device_id=abc&position_ms=0"));
        assertFalse(SpotifyApiPolicy.allowedPlayerCommand("PUT", "https://api.spotify.com/v1/me/player/play?device_id=abc&position_ms=0"));
    }

    @Test public void malformedRetryHeadersUseBoundedDefault() {
        for (String value : new String[]{"", "-1", "+1", "1.5", "soon", "9999999999999999999999999999999",
                "Wed, 21 Oct 2015 07:28:00 GMT"}) {
            assertEquals(value, 30000L, SpotifyApiPolicy.retryDelayMillis(value));
        }
        assertEquals(30000L, SpotifyApiPolicy.retryDelayMillis(null));
    }
}
