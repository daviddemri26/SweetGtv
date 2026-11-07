package com.david.tvcontinue;

import org.junit.Test;
import static org.junit.Assert.*;

public class EntryPolicyTest {
    private static final String APP = EntryPolicy.STREMIO;
    private static final long NOW = 2_000_000_000_000L;

    @Test public void onlyStremiosResumeChannelIsSelected() {
        assertTrue(EntryPolicy.isResumeChannel(APP, "Continue Watching"));
        assertTrue(EntryPolicy.isResumeChannel(APP, "Continuer à regarder"));
        assertFalse(EntryPolicy.isResumeChannel(APP, "Recommended for you"));
        assertFalse(EntryPolicy.isResumeChannel("com.other.app", "Continue Watching"));
    }

    @Test public void realInProgressRowIsAccepted() {
        assertTrue(EntryPolicy.include(APP, "Episode", "stremio://detail/test", 0,
                NOW - 60_000, 12_000, 60_000, NOW));
    }

    @Test public void watchedRowsAndWatchlistsAreNotResumeRows() {
        assertFalse(EntryPolicy.include(APP, "Episode", "stremio://detail/test", 0,
                NOW, 59_900, 60_000, NOW));
        assertFalse(EntryPolicy.include(APP, "Episode", "stremio://detail/test", 3,
                NOW, 0, 0, NOW));
        assertTrue(EntryPolicy.include(APP, "Episode", "stremio://detail/test", 1,
                NOW, 60_000, 60_000, NOW));
    }

    @Test public void staleRowsAreExcludedButUndatedPreviewRowsRemain() {
        assertFalse(EntryPolicy.include(APP, "Episode", "stremio://detail/test", 0,
                NOW - EntryPolicy.MAX_AGE_MS - 1, 0, 60_000, NOW));
        assertTrue(EntryPolicy.include(APP, "Episode", "stremio://detail/test", 0,
                0, 20_000, 60_000, NOW));
    }

    @Test public void incompleteOrUnknownSourcesAreExcluded() {
        assertFalse(EntryPolicy.include(APP, " ", "stremio://detail/test", 0, NOW, 0, 0, NOW));
        assertFalse(EntryPolicy.include(APP, "Episode", "", 0, NOW, 0, 0, NOW));
        assertFalse(EntryPolicy.include("com.factory.app", "Episode", "app://test", 0, NOW, 0, 0, NOW));
    }

    @Test public void linksMustStayWithinTheirPublishingApp() {
        assertTrue(EntryPolicy.safeLink(APP, APP, APP, "android.intent.action.VIEW", "stremio", true));
        assertFalse(EntryPolicy.safeLink(APP, "com.android.settings", APP, "android.intent.action.VIEW", "stremio", true));
        assertFalse(EntryPolicy.safeLink(APP, APP, "com.android.settings", "android.intent.action.VIEW", "stremio", true));
    }

    @Test public void dangerousUrisAndActionsAreNotLaunched() {
        for (String scheme : new String[]{"file", "content", "data", "javascript"}) {
            assertFalse(EntryPolicy.safeLink(APP, null, APP, "android.intent.action.VIEW", scheme, true));
        }
        assertFalse(EntryPolicy.safeLink(APP, null, APP, "android.intent.action.DELETE", "stremio", true));
        assertFalse(EntryPolicy.safeLink(APP, null, APP, "android.intent.action.VIEW", null, false));
    }
}
