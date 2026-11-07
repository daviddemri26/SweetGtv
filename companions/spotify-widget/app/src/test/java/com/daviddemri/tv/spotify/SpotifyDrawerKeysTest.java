package com.daviddemri.tv.spotify;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpotifyDrawerKeysTest {
    private static final int DOWN = 0, UP = 1, MULTIPLE = 2;
    private static final int DPAD_UP = 19, DPAD_DOWN = 20, LEFT = 21, RIGHT = 22;
    private static final int BACK = 4, ESCAPE = 111, CENTER = 23, ENTER = 66, NUMPAD_ENTER = 160, MENU = 82;

    @Test public void onlyFreshUpFromHeaderOpens() {
        SpotifyDrawerKeys keys = new SpotifyDrawerKeys();
        assertEquals(SpotifyDrawerKeys.PASS, keys.handle(DOWN, DPAD_UP, 0, false));
        assertEquals(SpotifyDrawerKeys.PASS, keys.handle(DOWN, DPAD_UP, 1, true));
        assertFalse(keys.isOpen());
        assertEquals(SpotifyDrawerKeys.PASS, keys.handle(DOWN, DPAD_UP, 0, true, true));
        assertEquals(SpotifyDrawerKeys.PASS, keys.handle(DOWN, LEFT, 0, true));
        assertEquals(SpotifyDrawerKeys.OPEN, keys.handle(DOWN, DPAD_UP, 0, true));
        assertTrue(keys.isOpen());
    }

    @Test public void openingUpIsConsumedUntilReleaseBeforeVerticalNavigationStarts() {
        SpotifyDrawerKeys keys = opened();
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(DOWN, DPAD_UP, 1, true));
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, DPAD_UP, 0, true));
        assertEquals(SpotifyDrawerKeys.UP_FOCUS, keys.handle(DOWN, DPAD_UP, 0, false));
        assertEquals(SpotifyDrawerKeys.UP_FOCUS, keys.handle(DOWN, DPAD_UP, 1, false));
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, DPAD_UP, 0, false));
        assertTrue(keys.isOpen());
    }

    @Test public void onlyBackAndEscapeCloseAndOwnRepeatsAndRelease() {
        for (int key : new int[]{BACK, ESCAPE}) {
            SpotifyDrawerKeys keys = opened();
            assertEquals(SpotifyDrawerKeys.CLOSE, keys.handle(DOWN, key, 0, false));
            assertFalse(keys.isOpen());
            assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(DOWN, key, 1, false));
            assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, key, 0, false));
            assertEquals(SpotifyDrawerKeys.PASS, keys.handle(DOWN, key, 0, false));
        }
    }

    @Test public void inheritedClosingKeyRepeatDoesNotDismiss() {
        SpotifyDrawerKeys keys = opened();
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(DOWN, BACK, 1, false));
        assertTrue(keys.isOpen());
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, BACK, 0, false));
        assertEquals(SpotifyDrawerKeys.CLOSE, keys.handle(DOWN, BACK, 0, false));
    }

    @Test public void arrowsMoveFocusAndRepeatWithoutLeakingReleases() {
        SpotifyDrawerKeys keys = opened();
        assertEquals(SpotifyDrawerKeys.PREVIOUS_FOCUS, keys.handle(DOWN, LEFT, 0, false));
        assertEquals(SpotifyDrawerKeys.PREVIOUS_FOCUS, keys.handle(DOWN, LEFT, 1, false));
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, LEFT, 0, false));
        assertEquals(SpotifyDrawerKeys.NEXT_FOCUS, keys.handle(DOWN, RIGHT, 0, false));
        assertEquals(SpotifyDrawerKeys.NEXT_FOCUS, keys.handle(DOWN, RIGHT, 1, false));
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, RIGHT, 0, false));
    }

    @Test public void downAndUpNavigateRepeatedlyWithoutClosing() {
        SpotifyDrawerKeys keys = opened();
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, DPAD_UP, 0, false));
        assertEquals(SpotifyDrawerKeys.DOWN_FOCUS, keys.handle(DOWN, DPAD_DOWN, 0, false));
        assertEquals(SpotifyDrawerKeys.DOWN_FOCUS, keys.handle(DOWN, DPAD_DOWN, 1, false));
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, DPAD_DOWN, 0, false));
        assertEquals(SpotifyDrawerKeys.UP_FOCUS, keys.handle(DOWN, DPAD_UP, 0, false));
        assertEquals(SpotifyDrawerKeys.UP_FOCUS, keys.handle(DOWN, DPAD_UP, 1, false));
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, DPAD_UP, 0, false));
        assertTrue(keys.isOpen());
    }

    @Test public void eachOkVariantActivatesExactlyOnceOnRelease() {
        for (int key : new int[]{CENTER, ENTER, NUMPAD_ENTER}) {
            SpotifyDrawerKeys keys = opened();
            assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(DOWN, key, 0, false));
            assertEquals(SpotifyDrawerKeys.ACTIVATE, keys.handle(UP, key, 0, false));
            assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, key, 0, false));
        }
    }

    @Test public void heldOrCanceledOkDoesNotActivate() {
        for (int key : new int[]{CENTER, ENTER, NUMPAD_ENTER}) {
            SpotifyDrawerKeys keys = opened();
            keys.handle(DOWN, key, 0, false);
            assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(DOWN, key, 1, false));
            assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, key, 0, false));
            keys.handle(DOWN, key, 0, false);
            assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, key, 0, false, true));
            assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, key, 0, false));
        }
    }

    @Test public void unmatchedOrInheritedOkReleaseDoesNotActivate() {
        SpotifyDrawerKeys keys = opened();
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, CENTER, 0, false));
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(DOWN, CENTER, 1, false));
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, CENTER, 0, false));
    }

    @Test public void programmaticDismissRetainsReleaseAndCannotActivate() {
        SpotifyDrawerKeys keys = opened();
        keys.handle(DOWN, CENTER, 0, false);
        keys.close();
        assertFalse(keys.isOpen());
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(DOWN, CENTER, 1, false));
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, CENTER, 0, false));
        assertEquals(SpotifyDrawerKeys.PASS, keys.handle(DOWN, CENTER, 0, false));
    }

    @Test public void pointerOpenUsesTheSameRemoteNavigation() {
        SpotifyDrawerKeys keys = new SpotifyDrawerKeys();
        keys.open();
        assertTrue(keys.isOpen());
        assertEquals(SpotifyDrawerKeys.NEXT_FOCUS, keys.handle(DOWN, RIGHT, 0, false));
        assertEquals(SpotifyDrawerKeys.DOWN_FOCUS, keys.handle(DOWN, DPAD_DOWN, 0, false));
        assertTrue(keys.isOpen());
        assertEquals(SpotifyDrawerKeys.CLOSE, keys.handle(DOWN, BACK, 0, false));
    }

    @Test public void pauseResetsHeldKeysAndPendingActivation() {
        SpotifyDrawerKeys keys = opened();
        keys.handle(DOWN, CENTER, 0, false);
        keys.reset();
        assertFalse(keys.isOpen());
        assertEquals(SpotifyDrawerKeys.PASS, keys.handle(UP, CENTER, 0, false));
        assertEquals(SpotifyDrawerKeys.OPEN, keys.handle(DOWN, DPAD_UP, 0, true));
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, CENTER, 0, false));
    }

    @Test public void menuIsContainedAndSystemKeysPassThrough() {
        SpotifyDrawerKeys keys = opened();
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(DOWN, MENU, 0, false));
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(UP, MENU, 0, false));
        assertEquals(SpotifyDrawerKeys.CONSUME, keys.handle(MULTIPLE, CENTER, 0, false));
        for (int key : new int[]{3, 24, 25, 164}) {
            assertEquals(SpotifyDrawerKeys.PASS, keys.handle(DOWN, key, 0, false));
            assertEquals(SpotifyDrawerKeys.PASS, keys.handle(UP, key, 0, false));
        }
    }

    private static SpotifyDrawerKeys opened() {
        SpotifyDrawerKeys keys = new SpotifyDrawerKeys();
        assertEquals(SpotifyDrawerKeys.OPEN, keys.handle(DOWN, DPAD_UP, 0, true));
        return keys;
    }
}
