package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class RetainedArtworkTest {
    @Test public void unchangedResumeDoesNotReloadOrClearThePoster() {
        RetainedArtwork<Object> art = new RetainedArtwork<>();
        Object poster = new Object();
        art.source("v1");
        assertTrue(art.needsLoad());
        art.accept("v1", poster);
        art.source("v1");
        assertFalse(art.needsLoad());
        assertSame(poster, art.value());
    }

    @Test public void timedRefreshKeepsThePosterUntilSuccessfulReplacement() {
        RetainedArtwork<Object> art = new RetainedArtwork<>();
        Object before = new Object(), after = new Object();
        art.source("v1"); art.accept("v1", before);
        art.invalidate();
        assertTrue(art.needsLoad());
        assertSame(before, art.value());
        art.accept("v1", null);
        assertSame(before, art.value());
        assertTrue(art.needsLoad());
        art.accept("v1", after);
        assertSame(after, art.value());
        assertFalse(art.needsLoad());
    }

    @Test public void changedPosterRejectsAnOldRequestWithoutFlashingBlank() {
        RetainedArtwork<Object> art = new RetainedArtwork<>();
        Object before = new Object(), after = new Object();
        art.source("v1"); art.accept("v1", before);
        art.source("v2");
        assertSame(before, art.value());
        assertTrue(art.needsLoad());
        art.accept("v1", new Object());
        assertSame(before, art.value());
        assertTrue(art.needsLoad());
        art.accept("v2", after);
        assertSame(after, art.value());
        assertFalse(art.needsLoad());
    }

    @Test public void offscreenReleaseStillAllowsReloadWhenReturning() {
        RetainedArtwork<Object> art = new RetainedArtwork<>();
        art.source("v1"); art.accept("v1", new Object());
        art.release();
        assertNull(art.value());
        assertTrue(art.needsLoad());
    }

    @Test public void ExplicitlyRemovedArtworkIsNotKeptOrResurrected() {
        RetainedArtwork<Object> art = new RetainedArtwork<>();
        art.source("v1"); art.accept("v1", new Object());
        art.source(null);
        art.accept("v1", new Object());
        assertNull(art.value());
        assertFalse(art.needsLoad());
    }
}
