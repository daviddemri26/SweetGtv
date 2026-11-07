package com.david.tvcontinue;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HomeContractTest {
    @Test public void keysAreStableOpaqueAndScopedToOwner() {
        String key = HomeContract.hash("com.stremio.one\nstremio:///detail/one");
        assertTrue(HomeContract.validKey(key));
        assertEquals(key, HomeContract.hash("com.stremio.one\nstremio:///detail/one"));
        assertNotEquals(key, HomeContract.hash("com.netflix.ninja\nstremio:///detail/one"));
        assertNotEquals(key, HomeContract.hash("com.stremio.one\nstremio:///detail/two"));
        assertFalse(HomeContract.validKey("../" + key));
        assertFalse(HomeContract.validKey(key.toUpperCase()));
        assertFalse(HomeContract.validKey(null));
    }
    @Test public void artworkVersionsChangeWhenPublishedPosterChanges() {
        assertNotEquals(HomeContract.hash("owner\nhttps://cdn.example/a.jpg"),
                HomeContract.hash("owner\nhttps://cdn.example/b.jpg"));
    }
    @Test public void progressUsesThousandthsAndRejectsUnknownOrNextEpisode() {
        assertEquals(500, HomeContract.progress(30, 60, 0));
        assertEquals(0, HomeContract.progress(0, 60, 0));
        assertEquals(1000, HomeContract.progress(Long.MAX_VALUE, 60, 0));
        assertEquals(-1, HomeContract.progress(30, 0, 0));
        assertEquals(-1, HomeContract.progress(-1, 60, 0));
        assertEquals(-1, HomeContract.progress(30, 60, 1));
        assertEquals(-1, HomeContract.progress(30, 60, 2));
    }
    @Test public void displayStringsAreBounded() {
        assertEquals("hello", HomeContract.bounded("hello"));
        assertEquals(512, HomeContract.bounded(new String(new char[4096])).length());
    }
}
