package com.daviddemri.tv.spotify;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifyHomeStateTest {
    @Test public void noCurrentSessionDoesNotClaimPlayback() {
        SpotifyHomeState state = new SpotifyHomeState();
        assertFalse(state.snapshot(0).playing);
        assertEquals("", state.snapshot(0).title);
        assertEquals("", state.snapshot(0).artist);
        assertFalse(state.known());
    }

    @Test public void aConfirmedEmptyResponseIsDifferentFromAnUnfinishedRead() {
        SpotifyHomeState state = new SpotifyHomeState();
        assertFalse(state.known());
        state.update("", "", false, 1000);
        assertTrue(state.known());
        assertFalse(state.snapshot(1200).playing);
        state.clear();
        assertFalse(state.known());
    }

    @Test public void repeatedSuccessfulReadsKeepAnUnchangedSongFresh() {
        SpotifyHomeState state = new SpotifyHomeState();
        state.update("Same track", "Same artist", true, 1000);
        state.update("Same track", "Same artist", true, 40_000);
        assertTrue(state.snapshot(50_000).playing);
        assertEquals("Same track", state.snapshot(50_000).title);
        assertFalse(state.snapshot(85_000).playing);
    }

    @Test public void playingSessionShowsItsSongAndArtist() {
        SpotifyHomeState state = new SpotifyHomeState();
        state.update("Current track", "Current artist", true, 1000);
        SpotifyHomeState.Snapshot snapshot = state.snapshot(1200);
        assertTrue(snapshot.playing);
        assertEquals("Current track", snapshot.title);
        assertEquals("Current artist", snapshot.artist);
    }

    @Test public void pausingHidesBothLabels() {
        SpotifyHomeState state = new SpotifyHomeState();
        state.update("Track", "Artist", true, 1000);
        state.update("Track", "Artist", false, 2000);
        assertFalse(state.snapshot(2200).playing);
        assertEquals("", state.snapshot(2200).title);
        assertEquals("", state.snapshot(2200).artist);
    }

    @Test public void changingSongCannotRetainThePreviousArtist() {
        SpotifyHomeState state = new SpotifyHomeState();
        state.update("Previous track", "Previous artist", true, 1000);
        state.update("New track", "", true, 2000);
        assertEquals("New track", state.snapshot(2200).title);
        assertEquals("", state.snapshot(2200).artist);
    }

    @Test public void snapshotExpiresAtTheDeadlineAndRejectsClockRegression() {
        SpotifyHomeState state = new SpotifyHomeState();
        state.update("Remote track", "Artist", true, 1000);
        assertTrue(state.snapshot(1000 + SpotifyHomeState.REMOTE_MAX_AGE_MS - 1).playing);
        assertFalse(state.snapshot(1000 + SpotifyHomeState.REMOTE_MAX_AGE_MS).playing);
        assertFalse(state.snapshot(999).playing);
    }

    @Test public void freshReadCanReplaceAnExpiredSession() {
        SpotifyHomeState state = new SpotifyHomeState();
        state.update("Expired track", "Old artist", true, 1000);
        assertFalse(state.snapshot(100_000).playing);
        state.update("New device track", "New artist", true, 100_000);
        assertEquals("New device track", state.snapshot(100_001).title);
        assertEquals("New artist", state.snapshot(100_001).artist);
    }

    @Test public void authorizationReplacementClearsAllLabels() {
        SpotifyHomeState state = new SpotifyHomeState();
        state.update("Remote track", "Artist", true, 1000);
        state.clear();
        assertFalse(state.snapshot(2000).playing);
        assertEquals("", state.snapshot(2000).title);
        assertEquals("", state.snapshot(2000).artist);
    }

    @Test public void missingSongTitleNeverShowsAnArtistAloneAsOngoingPlayback() {
        SpotifyHomeState state = new SpotifyHomeState();
        state.update(" \n ", "Artist", true, 1000);
        assertFalse(state.snapshot(2000).playing);
        assertEquals("", state.snapshot(2000).artist);
    }

    @Test public void controlsAndBidiOverridesAreRemovedFromBothLabels() {
        SpotifyHomeState state = new SpotifyHomeState();
        state.update("  One\n\t song\u202E  ", " Artist\u2066 \r name ", true, 1000);
        assertEquals("One song", state.snapshot(1200).title);
        assertEquals("Artist name", state.snapshot(1200).artist);
    }

    @Test public void labelBoundsPreserveSurrogatePairs() {
        String face = new String(Character.toChars(0x1F3B5));
        String longTitle = new String(new char[300]).replace("\0", face);
        String bounded = SpotifyHomeState.cleanTitle(longTitle);
        assertEquals(256, bounded.codePointCount(0, bounded.length()));
        assertEquals(512, bounded.length());
        assertTrue(Character.isLowSurrogate(bounded.charAt(bounded.length() - 1)));
        assertEquals("", SpotifyHomeState.cleanTitle(null));
    }
}
