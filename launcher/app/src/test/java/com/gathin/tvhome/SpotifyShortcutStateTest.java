package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SpotifyShortcutStateTest {
    @Test public void initialUnknownStateHasOnlyTheMusicIcon() {
        SpotifyShortcutState state = new SpotifyShortcutState();
        state.expire(10);
        assertEquals("", state.title());
        assertEquals("", state.artist());
        assertEquals(0, state.remaining(10));
    }

    @Test public void reconnectRetainsConfirmedPairOnlyWithinItsFreshnessWindow() {
        SpotifyShortcutState state = new SpotifyShortcutState();
        assertTrue(state.accept("Song", "Artist", true, 1000, 1000));
        state.expire(45_999);
        assertEquals("Song", state.title());
        assertEquals("Artist", state.artist());
        state.expire(46_000);
        assertEquals("", state.title());
        assertEquals("", state.artist());
    }

    @Test public void identicalHeartbeatExtendsFreshnessWithoutChangingThePair() {
        SpotifyShortcutState state = new SpotifyShortcutState();
        assertTrue(state.accept("Song", "Artist", true, 1000, 1000));
        assertTrue(state.accept("Song", "Artist", true, 6000, 6000));
        state.expire(46_000);
        assertEquals("Song", state.title());
        assertEquals("Artist", state.artist());
        assertEquals(5000, state.remaining(46_000));
    }

    @Test public void confirmedPauseClearsBothLabelsImmediately() {
        SpotifyShortcutState state = new SpotifyShortcutState();
        state.accept("Song", "Artist", true, 1000, 1000);
        state.accept("Song", "Artist", false, 2000, 2000);
        assertEquals("", state.title());
        assertEquals("", state.artist());
        state.expire(3000);
        assertEquals("", state.title());
    }

    @Test public void anOldPacketCannotResurrectAPausedSong() {
        SpotifyShortcutState state = new SpotifyShortcutState();
        state.accept("Song", "Artist", false, 6000, 6000);
        assertFalse(state.accept("Song", "Artist", true, 5000, 7000));
        assertEquals("", state.title());
    }

    @Test public void staleFutureOrNegativeTimestampsLeaveTheConfirmedPairUntouched() {
        SpotifyShortcutState state = new SpotifyShortcutState();
        state.accept("Song", "Artist", true, 10_000, 10_000);
        assertFalse(state.accept("Wrong", "Other", true, 10_000, 55_001));
        assertFalse(state.accept("Wrong", "Other", true, 11_001, 10_000));
        assertFalse(state.accept("Wrong", "Other", true, -1, 0));
        assertEquals("Song", state.title());
        assertEquals("Artist", state.artist());
    }

    @Test public void missingSongNeverLeavesAnArtistLabelOnItsOwn() {
        SpotifyShortcutState state = new SpotifyShortcutState();
        state.accept("", "Artist", true, 1000, 1000);
        assertEquals("", state.title());
        assertEquals("", state.artist());
    }

    @Test public void controlCharactersAreRemovedAndUnicodeIsBoundedWithoutSplittingPairs() {
        SpotifyShortcutState state = new SpotifyShortcutState();
        state.accept("  Song\n\u202E next ", " Artist\t Name ", true, 1000, 1000);
        assertEquals("Song next", state.title());
        assertEquals("Artist Name", state.artist());
        state.accept("🎵".repeat(300), "🎹".repeat(300), true, 2000, 2000);
        assertEquals(256, state.title().codePointCount(0, state.title().length()));
        assertEquals(256, state.artist().codePointCount(0, state.artist().length()));
        assertTrue(state.title().endsWith("🎵"));
        assertTrue(state.artist().endsWith("🎹"));
    }
}
