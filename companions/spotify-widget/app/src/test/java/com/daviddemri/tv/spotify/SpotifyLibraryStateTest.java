package com.daviddemri.tv.spotify;

import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifyLibraryStateTest {
    @Test public void unchangedSourceDoesNotRequireAnotherRender() {
        List<SpotifyLibrary.Item> items = Collections.emptyList();
        SpotifyLibrary.NowPlaying current = current(10, 1000);
        SpotifyLibrary.State state = new SpotifyLibrary.State("recent", items, true, false, "", 10, current);
        assertTrue(state.sameSource("recent", items, true, false, "", 10, current));
    }

    @Test public void freshPlayerObservationsStillReachTheSeekBarEvenWhenLabelsMatch() {
        List<SpotifyLibrary.Item> items = Collections.emptyList();
        SpotifyLibrary.State state = new SpotifyLibrary.State("recent", items, true, false, "", 10, current(10, 1000));
        assertFalse(state.sameSource("recent", items, true, false, "", 10, current(10, 1005)));
        assertFalse(state.sameSource("recent", items, true, false, "", 10, current(15, 1005)));
    }

    @Test public void catalogueAndConnectionFeedbackCannotBeSuppressed() {
        List<SpotifyLibrary.Item> items = Collections.emptyList();
        SpotifyLibrary.NowPlaying current = current(10, 1000);
        SpotifyLibrary.State state = new SpotifyLibrary.State("recent", items, true, false, "", 10, current);
        assertFalse(state.sameSource("playlists", items, true, false, "", 10, current));
        assertFalse(state.sameSource("recent", items, false, false, "", 10, current));
        assertFalse(state.sameSource("recent", items, true, true, "", 10, current));
        assertFalse(state.sameSource("recent", items, true, false, "Unavailable", 10, current));
        assertFalse(state.sameSource("recent", items, true, false, "", 15, current));
    }

    @Test public void aReplacementCatalogueStillReconcilesItsCards() {
        List<SpotifyLibrary.Item> items = Collections.emptyList();
        SpotifyLibrary.NowPlaying current = current(10, 1000);
        SpotifyLibrary.State state = new SpotifyLibrary.State("recent", items, true, false, "", 10, current);
        List<SpotifyLibrary.Item> changed = Collections.singletonList(new SpotifyLibrary.Item(
                "spotify:track:0123456789012345678901", "Track", "Artist", "", "track", 0, 100));
        assertFalse(state.sameSource("recent", changed, true, false, "", 10, current));
    }

    private static SpotifyLibrary.NowPlaying current(long progress, long updatedAt) {
        return new SpotifyLibrary.NowPlaying("spotify:track:0123456789012345678901", "Track", "Artist",
                "", "TV", "device", true, true, progress, 100, updatedAt);
    }
}
