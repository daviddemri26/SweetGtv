package com.david.tvcontinue;

import org.junit.Test;

import static org.junit.Assert.*;

public final class YouTubeShelfContractTest {
    private static final String OWNER = "com.google.android.youtube.tv";
    private static final String LINK = "https://www.youtube.com/watch?v=sample";

    @Test public void optionalMetadataDoesNotChangeTheDefaultOrContinueContract() {
        String[] legacy = {"_id", "title", "subtitle", "owner", "progress", "art_uri"};
        assertArrayEquals(legacy, HomeContract.COLUMNS);
        assertArrayEquals(legacy, HomeContract.columnsForShelf(HomeContract.SHELF_CONTINUE));
        String[] youtube = HomeContract.columnsForShelf(HomeContract.SHELF_YOUTUBE);
        assertEquals(12, youtube.length);
        assertArrayEquals(legacy, java.util.Arrays.copyOf(youtube, 6));
        assertArrayEquals(new String[]{"youtube_channel", "youtube_published_date", "youtube_duration_millis",
                "youtube_view_count", "youtube_live", "youtube_published_label"}, java.util.Arrays.copyOfRange(youtube, 6, 12));
    }

    @Test public void onlyExplicitKnownShelvesAreValid() {
        assertEquals("youtube", HomeContract.SHELF_YOUTUBE);
        assertEquals("continue", HomeContract.SHELF_CONTINUE);
        assertTrue(HomeContract.validShelf(HomeContract.SHELF_YOUTUBE));
        assertTrue(HomeContract.validShelf(HomeContract.SHELF_CONTINUE));
        for (String invalid : new String[]{null, "", "YouTube", "youtube ", "../youtube", "history", "art"})
            assertFalse(String.valueOf(invalid), HomeContract.validShelf(invalid));
    }

    @Test public void sameVideoHasSeparateStableKeysForEachShelf() {
        String continuing = HomeContract.keyFor(HomeContract.SHELF_CONTINUE, OWNER, LINK);
        String youtube = HomeContract.keyFor(HomeContract.SHELF_YOUTUBE, OWNER, LINK);

        assertEquals(HomeContract.hash(OWNER + '\n' + LINK), continuing);
        assertEquals(HomeContract.hash("youtube\n" + OWNER + '\n' + LINK), youtube);
        assertNotEquals(continuing, youtube);
        assertTrue(HomeContract.validKey(continuing));
        assertTrue(HomeContract.validKey(youtube));
        assertEquals(youtube, HomeContract.keyFor(HomeContract.SHELF_YOUTUBE, OWNER, LINK));
        assertNotEquals(youtube, HomeContract.keyFor(HomeContract.SHELF_YOUTUBE, OWNER, LINK + "2"));
    }

    @Test public void youtubeNeverClaimsPlaybackProgress() {
        assertEquals(-1, HomeContract.progress(HomeContract.SHELF_YOUTUBE, 0, 60, 0));
        assertEquals(-1, HomeContract.progress(HomeContract.SHELF_YOUTUBE, 30, 60, 0));
        assertEquals(-1, HomeContract.progress(HomeContract.SHELF_YOUTUBE, 60, 60, 0));
        assertEquals(-1, HomeContract.progress(HomeContract.SHELF_YOUTUBE, -1, 0, 0));
        assertEquals(-1, HomeContract.progress(HomeContract.SHELF_YOUTUBE, 30, 60, 1));
        assertEquals(500, HomeContract.progress(HomeContract.SHELF_CONTINUE, 30, 60, 0));
        assertEquals(-1, HomeContract.progress(HomeContract.SHELF_CONTINUE, 30, 60, 1));
    }

    @Test public void youtubeLinksMustStayInTheirPublishingApplication() {
        assertTrue(EntryPolicy.safeLink(OWNER, null, null, "android.intent.action.VIEW", "https", true));
        assertTrue(EntryPolicy.safeLink(OWNER, OWNER, OWNER, "android.intent.action.VIEW", "https", true));
        assertTrue(EntryPolicy.safeLink(OWNER, OWNER, null, "android.intent.action.MAIN", null, false));
        assertFalse(EntryPolicy.safeLink(OWNER, "com.android.settings", OWNER,
                "android.intent.action.VIEW", "https", true));
        assertFalse(EntryPolicy.safeLink(OWNER, OWNER, "com.android.settings",
                "android.intent.action.VIEW", "https", true));
        assertFalse(EntryPolicy.safeLink(OWNER + ".fake", null, null,
                "android.intent.action.VIEW", "https", true));
    }

    @Test public void youtubeDoesNotBypassExistingLinkSafetyRules() {
        for (String scheme : new String[]{"javascript", "file", "content", "data", "FILE"})
            assertFalse(scheme, EntryPolicy.safeLink(OWNER, null, null,
                    "android.intent.action.VIEW", scheme, true));
        assertFalse(EntryPolicy.safeLink(OWNER, OWNER, OWNER, "android.intent.action.DELETE", "https", true));
        assertFalse(EntryPolicy.safeLink(OWNER, null, null, "android.intent.action.VIEW", null, true));
        assertFalse(EntryPolicy.safeLink(OWNER, null, null, "android.intent.action.VIEW", null, false));
    }
}
