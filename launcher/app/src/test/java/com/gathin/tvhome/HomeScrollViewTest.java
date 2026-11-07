package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class HomeScrollViewTest {
    @Test public void visibleRowsDoNotMoveTheWholeHome() {
        assertEquals(0, HomeScrollView.revealTarget(0, 24, 360, 760, 24, 400));
        assertEquals(0, HomeScrollView.revealTarget(0, 400, 720, 760, 24, 400));
    }
    @Test public void lowerRowAndReturnToReprisesMoveTheSameViewport() {
        assertEquals(284, HomeScrollView.revealTarget(0, 700, 1020, 760, 24, 400));
        assertEquals(0, HomeScrollView.revealTarget(284, 24, 360, 760, 24, 400));
    }
    @Test public void movementStopsAtContentEdges() {
        assertEquals(200, HomeScrollView.revealTarget(0, 600, 1020, 760, 24, 200));
        assertEquals(0, HomeScrollView.revealTarget(0, 12, 200, 760, 24, 0));
    }
}
