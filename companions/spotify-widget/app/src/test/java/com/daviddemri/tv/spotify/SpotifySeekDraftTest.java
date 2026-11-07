package com.daviddemri.tv.spotify;

import org.junit.Test;

import java.math.BigInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpotifySeekDraftTest {
    private static final String URI = "spotify:track:1234567890123456789012", DEVICE = "living-room-tv";

    @Test public void inactiveDraftHasNoIdentityOrPendingChange() {
        SpotifySeekDraft draft = new SpotifySeekDraft();
        assertFalse(draft.active());
        assertFalse(draft.dirty());
        assertEquals("", draft.uri());
        assertEquals("", draft.deviceId());
        assertEquals(0, draft.position());
        assertEquals(0, draft.duration());
        assertEquals(0, draft.step(Long.MAX_VALUE));
        assertEquals(0, draft.setFraction(1, 1));
        assertFalse(draft.matches("", "", 0));
    }

    @Test public void beginCapturesMediaIdentityAndInitialPosition() {
        SpotifySeekDraft draft = begun(30_000, 180_000);
        assertTrue(draft.active());
        assertEquals(URI, draft.uri());
        assertEquals(DEVICE, draft.deviceId());
        assertEquals(30_000, draft.position());
        assertEquals(180_000, draft.duration());
        assertTrue(draft.matches(URI, DEVICE, 180_000));
        assertFalse(draft.dirty());
    }

    @Test public void initialPositionClampsWithoutBecomingDirty() {
        SpotifySeekDraft draft = begun(Long.MIN_VALUE, 180_000);
        assertEquals(0, draft.position());
        assertFalse(draft.dirty());
        assertTrue(draft.begin(URI, DEVICE, Long.MAX_VALUE, 180_000));
        assertEquals(180_000, draft.position());
        assertFalse(draft.dirty());
    }

    @Test public void repeatedStepsKeepOneDraftAndReturningToInitialClearsDirty() {
        SpotifySeekDraft draft = begun(30_000, 180_000);
        assertEquals(40_000, draft.step(10_000));
        assertEquals(50_000, draft.step(10_000));
        assertEquals(60_000, draft.step(10_000));
        assertTrue(draft.dirty());
        assertEquals(30_000, draft.step(-30_000));
        assertFalse(draft.dirty());
        assertEquals(30_000, draft.step(0));
        assertTrue(draft.matches(URI, DEVICE, 180_000));
    }

    @Test public void stepsClampAtBothEndsWithoutLongOverflow() {
        SpotifySeekDraft draft = begun(Long.MAX_VALUE - 10, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, draft.step(20));
        assertEquals(Long.MAX_VALUE, draft.step(Long.MAX_VALUE));
        assertEquals(0, draft.step(Long.MIN_VALUE));
        assertEquals(0, draft.step(-1));
        assertEquals(Long.MAX_VALUE, draft.step(Long.MAX_VALUE));
        assertEquals(0, draft.step(-Long.MAX_VALUE));
    }

    @Test public void fractionClampsOvershootAndNegativeValues() {
        SpotifySeekDraft draft = begun(50, 100);
        assertEquals(0, draft.setFraction(Integer.MIN_VALUE, 100));
        assertTrue(draft.dirty());
        assertEquals(100, draft.setFraction(Integer.MAX_VALUE, 100));
        assertEquals(50, draft.setFraction(50, 100));
        assertFalse(draft.dirty());
    }

    @Test public void invalidFractionScaleLeavesCurrentDraftUntouched() {
        SpotifySeekDraft draft = begun(50, 100);
        draft.step(10);
        assertEquals(60, draft.setFraction(99, 0));
        assertEquals(60, draft.setFraction(99, -5));
        assertTrue(draft.dirty());
    }

    @Test public void fractionsRemainExactWithLongMaximumDuration() {
        SpotifySeekDraft draft = begun(0, Long.MAX_VALUE);
        for (int scale : new int[]{1, 1000, Integer.MAX_VALUE}) {
            for (int value : new int[]{0, 1, scale / 2, scale - 1, scale}) {
                long expected = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(value))
                        .divide(BigInteger.valueOf(scale)).longValueExact();
                assertEquals("fraction " + value + "/" + scale, expected, draft.setFraction(value, scale));
            }
        }
    }

    @Test public void fractionsUseMillisecondsWithoutRoundingPastTheTarget() {
        SpotifySeekDraft draft = begun(0, 1001);
        assertEquals(333, draft.setFraction(1, 3));
        assertEquals(667, draft.setFraction(2, 3));
        assertEquals(1001, draft.setFraction(3, 3));
    }

    @Test public void mediaDeviceAndDurationChangesInvalidateIdentity() {
        SpotifySeekDraft draft = begun(10, 100);
        assertFalse(draft.matches("spotify:track:9876543210987654321098", DEVICE, 100));
        assertFalse(draft.matches(URI, "iPad", 100));
        assertFalse(draft.matches(URI, DEVICE, 101));
        assertFalse(draft.matches(null, DEVICE, 100));
        assertFalse(draft.matches(URI, null, 100));
    }

    @Test public void cancelDiscardsIdentityAndUncommittedPosition() {
        SpotifySeekDraft draft = begun(50, 100);
        draft.step(20);
        draft.cancel();
        assertFalse(draft.active());
        assertFalse(draft.dirty());
        assertFalse(draft.matches(URI, DEVICE, 100));
        assertEquals("", draft.uri());
        assertEquals("", draft.deviceId());
        assertEquals(0, draft.position());
        assertEquals(0, draft.duration());
    }

    @Test public void invalidBeginClearsAnyPreviousDraft() {
        SpotifySeekDraft draft = new SpotifySeekDraft();
        for (String[] identity : new String[][]{{null, DEVICE}, {"", DEVICE}, {"  ", DEVICE},
                {URI, null}, {URI, ""}, {URI, " \t"}}) {
            assertTrue(draft.begin(URI, DEVICE, 50, 100));
            draft.step(10);
            assertFalse(draft.begin(identity[0], identity[1], 50, 100));
            assertFalse(draft.active());
            assertFalse(draft.dirty());
            assertEquals(0, draft.position());
        }
        for (long duration : new long[]{0, -1, Long.MIN_VALUE}) {
            assertTrue(draft.begin(URI, DEVICE, 50, 100));
            assertFalse(draft.begin(URI, DEVICE, 50, duration));
            assertFalse(draft.active());
        }
    }

    @Test public void newBeginResetsTheDirtyBaseline() {
        SpotifySeekDraft draft = begun(50, 100);
        draft.step(10);
        assertTrue(draft.begin(URI, DEVICE, 70, 100));
        assertFalse(draft.dirty());
        draft.step(-10);
        assertTrue(draft.dirty());
        draft.step(10);
        assertFalse(draft.dirty());
    }

    @Test public void rapidDirectionChangesStayInOnePreviewAndCancellationDiscardsQueuedAdjustments() {
        SpotifySeekDraft draft = begun(120_000, 180_000);
        for (int press = 0; press < 8; press++) draft.step(10_000);
        assertEquals(180_000, draft.position());
        for (int press = 0; press < 3; press++) draft.step(-10_000);
        for (int press = 0; press < 2; press++) draft.step(10_000);
        assertEquals(170_000, draft.position());
        assertTrue(draft.dirty());
        assertTrue(draft.matches(URI, DEVICE, 180_000));

        // Blur, a cancelled gesture or a track transition discards the whole unconfirmed edit.
        draft.cancel();
        assertEquals(0, draft.step(10_000));
        assertEquals(0, draft.step(-10_000));
        assertEquals(0, draft.setFraction(500, 1000));
        assertFalse(draft.active());
        assertFalse(draft.dirty());
        assertEquals("", draft.uri());
    }

    @Test public void aNewTrackRequiresAnExplicitBeginAndCannotInheritCancelledPreviewPosition() {
        SpotifySeekDraft draft = begun(90_000, 180_000);
        draft.step(70_000);
        String next = "spotify:track:9876543210987654321098";
        assertFalse(draft.matches(next, DEVICE, 60_000));
        draft.cancel();
        // Late input from the previous media cannot reopen the cancelled draft.
        assertEquals(0, draft.step(10_000));
        assertFalse(draft.active());

        assertTrue(draft.begin(next, DEVICE, 5_000, 60_000));
        assertEquals(5_000, draft.position());
        assertFalse(draft.dirty());
        assertTrue(draft.matches(next, DEVICE, 60_000));
        assertEquals(15_000, draft.step(10_000));
        assertEquals(5_000, draft.step(-10_000));
        assertFalse(draft.dirty());
        assertFalse(draft.matches(URI, DEVICE, 180_000));
    }

    private static SpotifySeekDraft begun(long progress, long duration) {
        SpotifySeekDraft draft = new SpotifySeekDraft();
        assertTrue(draft.begin(URI, DEVICE, progress, duration));
        return draft;
    }
}
