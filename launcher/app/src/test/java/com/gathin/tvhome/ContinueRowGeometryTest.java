package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContinueRowGeometryTest {
    @Test public void fifthPosterPeekIsTwentyPercentAcrossTvViewportSizes() {
        for (int viewport : new int[]{640, 848, 960, 1280, 1696, 1920}) {
            int leading = 12, inset = 4, gap = 12;
            int width = ContinueRowGeometry.posterWidth(viewport, leading, inset, gap);
            int fifthStart = leading + 4 * (width + 2 * inset + gap) + inset;
            assertTrue("Four complete posters must fit", fifthStart - gap - 2 * inset <= viewport);
            assertEquals(width * .2, viewport - fifthStart, 4.21);
            assertEquals(width * 9d / 16d, ContinueRowGeometry.posterHeight(width), .51);
        }
    }

    @Test public void enteringTheFifthCardMovesOneSlotAndKeepsTheNextCue() {
        assertEquals(0, ContinueRowGeometry.scrollFor(0, 0, 200, 5000));
        assertEquals(0, ContinueRowGeometry.scrollFor(3, 0, 200, 5000));
        assertEquals(200, ContinueRowGeometry.scrollFor(4, 0, 200, 5000));
        assertEquals(400, ContinueRowGeometry.scrollFor(5, 200, 200, 5000));
        assertEquals(200, ContinueRowGeometry.scrollFor(1, 400, 200, 5000));
    }

    @Test public void lastCardAndShortListsStopAtTheRealContentEdge() {
        assertEquals(0, ContinueRowGeometry.scrollFor(3, 0, 200, 0));
        assertEquals(11152, ContinueRowGeometry.scrollFor(59, 11000, 200, 11152));
        assertEquals(0, ContinueRowGeometry.scrollFor(0, 500, 200, 500));
    }
}
