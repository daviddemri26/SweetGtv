package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VolumeDrawerKeysTest {
    private static final int DOWN = 0, UP = 1, LEFT = 21, RIGHT = 22, BACK = 4;
    private static final int DPAD_UP = 19, DPAD_DOWN = 20, CENTER = 23, MENU = 82;

    @Test public void onlyFreshLeftAtAnEligibleEdgeOpens() {
        VolumeDrawerKeys keys = new VolumeDrawerKeys();
        assertEquals(VolumeDrawerKeys.PASS, keys.handle(DOWN, LEFT, 0, 0, false));
        assertEquals(VolumeDrawerKeys.PASS, keys.handle(DOWN, LEFT, 1, 50, true));
        assertFalse(keys.isOpen());
        assertEquals(VolumeDrawerKeys.OPEN, keys.handle(DOWN, LEFT, 0, 100, true));
        assertTrue(keys.isOpen());
    }

    @Test public void heldOpeningLeftDoesNotCloseUntilReleasedAndPressedAgain() {
        VolumeDrawerKeys keys = opened();
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(DOWN, LEFT, 1, 100, true));
        assertTrue(keys.isOpen());
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(UP, LEFT, 0, 120, true));
        assertEquals(VolumeDrawerKeys.CLOSE, keys.handle(DOWN, LEFT, 0, 200, true));
        assertFalse(keys.isOpen());
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(DOWN, LEFT, 1, 300, true));
        assertFalse(keys.isOpen());
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(UP, LEFT, 0, 320, true));
        assertEquals(VolumeDrawerKeys.OPEN, keys.handle(DOWN, LEFT, 0, 400, true));
    }

    @Test public void backReleaseCannotCloseTheUnderlyingLibrary() {
        VolumeDrawerKeys keys = opened();
        assertEquals(VolumeDrawerKeys.CLOSE, keys.handle(DOWN, BACK, 0, 100, true));
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(DOWN, BACK, 1, 150, true));
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(UP, BACK, 0, 200, true));
        assertEquals(VolumeDrawerKeys.PASS, keys.handle(DOWN, BACK, 0, 250, true));
    }

    @Test public void volumeRepeatsAreBoundedButFreshTapsAreImmediate() {
        VolumeDrawerKeys keys = opened();
        assertEquals(VolumeDrawerKeys.RAISE, keys.handle(DOWN, DPAD_UP, 0, 100, false));
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(DOWN, DPAD_UP, 1, 150, false));
        assertEquals(VolumeDrawerKeys.RAISE, keys.handle(DOWN, DPAD_UP, 2, 220, false));
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(UP, DPAD_UP, 0, 230, false));
        assertEquals(VolumeDrawerKeys.RAISE, keys.handle(DOWN, DPAD_UP, 0, 240, false));
        assertEquals(VolumeDrawerKeys.LOWER, keys.handle(DOWN, DPAD_DOWN, 0, 250, false));
    }

    @Test public void underlyingActionsAreConsumedButPhysicalVolumeAndHomePassThrough() {
        VolumeDrawerKeys keys = opened();
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(DOWN, CENTER, 0, 100, false));
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(UP, CENTER, 0, 110, false));
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(DOWN, MENU, 0, 120, false));
        assertEquals(VolumeDrawerKeys.PASS, keys.handle(DOWN, 24, 0, 130, false));
        assertEquals(VolumeDrawerKeys.PASS, keys.handle(DOWN, 3, 0, 140, false));
    }

    @Test public void rightClosesWithoutMovingToAnotherTileOnRelease() {
        VolumeDrawerKeys keys = opened();
        assertEquals(VolumeDrawerKeys.CLOSE, keys.handle(DOWN, RIGHT, 0, 100, false));
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(UP, RIGHT, 0, 110, false));
        assertEquals(VolumeDrawerKeys.PASS, keys.handle(DOWN, RIGHT, 0, 200, false));
    }

    @Test public void pauseResetsHeldKeysForTheNextVisit() {
        VolumeDrawerKeys keys = opened();
        keys.reset();
        assertFalse(keys.isOpen());
        assertEquals(VolumeDrawerKeys.OPEN, keys.handle(DOWN, LEFT, 0, 200, true));
    }

    @Test public void pointerOpenWorksAndPreservesAClosingKeysRelease() {
        VolumeDrawerKeys keys = new VolumeDrawerKeys();
        keys.open();
        assertTrue(keys.isOpen());
        assertEquals(VolumeDrawerKeys.RAISE, keys.handle(DOWN, DPAD_UP, 0, 100, false));
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(UP, DPAD_UP, 0, 110, false));
        assertEquals(VolumeDrawerKeys.CLOSE, keys.handle(DOWN, BACK, 0, 200, false));
        keys.open();
        assertEquals(VolumeDrawerKeys.CONSUME, keys.handle(UP, BACK, 0, 210, false));
        assertTrue(keys.isOpen());
        keys.reset();
        assertFalse(keys.isOpen());
        assertEquals(VolumeDrawerKeys.PASS, keys.handle(UP, BACK, 0, 220, false));
    }

    private static VolumeDrawerKeys opened() {
        VolumeDrawerKeys keys = new VolumeDrawerKeys();
        assertEquals(VolumeDrawerKeys.OPEN, keys.handle(DOWN, LEFT, 0, 0, true));
        return keys;
    }
}
