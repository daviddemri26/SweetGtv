package com.daviddemri.tv.spotify;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SpotifyFocusNavigationTest {
    @Test public void horizontalEdgesStayInTheirRow() {
        assertEquals(-1, SpotifyFocusNavigation.target(0, 5, 3, 17));
        assertEquals(-1, SpotifyFocusNavigation.target(2, 5, 3, 66));
        assertEquals(-1, SpotifyFocusNavigation.target(3, 5, 3, 17));
        assertEquals(-1, SpotifyFocusNavigation.target(4, 5, 3, 66));
        assertEquals(1, SpotifyFocusNavigation.target(0, 5, 3, 66));
        assertEquals(3, SpotifyFocusNavigation.target(4, 5, 3, 17));
    }
    @Test public void downIntoPartialRowUsesItsLastAvailableCell() {
        assertEquals(3, SpotifyFocusNavigation.target(0, 5, 3, 130));
        assertEquals(4, SpotifyFocusNavigation.target(1, 5, 3, 130));
        assertEquals(4, SpotifyFocusNavigation.target(2, 5, 3, 130));
    }
    @Test public void downFromLastRowDoesNotEscape() {
        assertEquals(-1, SpotifyFocusNavigation.target(3, 5, 3, 130));
        assertEquals(-1, SpotifyFocusNavigation.target(4, 5, 3, 130));
    }
    @Test public void upFromFirstRowReturnsToHeader() {
        for (int i = 0; i < 3; i++) assertEquals(-2, SpotifyFocusNavigation.target(i, 5, 3, 33));
        assertEquals(1, SpotifyFocusNavigation.target(4, 5, 3, 33));
    }
    @Test public void invalidOrEmptyGridStays() {
        assertEquals(-1, SpotifyFocusNavigation.target(0, 0, 3, 130));
        assertEquals(-1, SpotifyFocusNavigation.target(-1, 5, 3, 33));
        assertEquals(-1, SpotifyFocusNavigation.target(5, 5, 3, 33));
        assertEquals(-1, SpotifyFocusNavigation.target(0, 5, 0, 66));
    }
    @Test public void compactFourColumnRowsKeepHorizontalEdges() {
        assertEquals(-1, SpotifyFocusNavigation.target(3, 10, 4, 66));
        assertEquals(-1, SpotifyFocusNavigation.target(4, 10, 4, 17));
        assertEquals(-1, SpotifyFocusNavigation.target(9, 10, 4, 66));
        assertEquals(6, SpotifyFocusNavigation.target(5, 10, 4, 66));
    }
    @Test public void compactFourColumnPartialRowIsReachable() {
        assertEquals(9, SpotifyFocusNavigation.target(7, 10, 4, 130));
        assertEquals(5, SpotifyFocusNavigation.target(9, 10, 4, 33));
        assertEquals(-1, SpotifyFocusNavigation.target(9, 10, 4, 130));
        assertEquals(-2, SpotifyFocusNavigation.target(3, 10, 4, 33));
    }
}
