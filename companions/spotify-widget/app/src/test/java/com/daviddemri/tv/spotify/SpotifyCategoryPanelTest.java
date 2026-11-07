package com.daviddemri.tv.spotify;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpotifyCategoryPanelTest {
    @Test public void startsAsACompactPlayerWithoutAnExpandedCategory() {
        SpotifyCategoryPanel panel = new SpotifyCategoryPanel();
        assertClosed(panel);
    }

    @Test public void explicitActivationOpensEachCategoryAndTheSecondActivationClosesIt() {
        for (int index = 0; index < 3; index++) {
            SpotifyCategoryPanel panel = new SpotifyCategoryPanel();
            assertTrue(panel.activate(index));
            assertOnlyActive(panel, index);
            assertTrue(panel.activate(index));
            assertClosed(panel);
        }
    }

    @Test public void activatingAnotherCategoryReplacesTheOpenContentInOneStep() {
        for (int previous = 0; previous < 3; previous++) {
            for (int next = 0; next < 3; next++) {
                if (previous == next) continue;
                SpotifyCategoryPanel panel = new SpotifyCategoryPanel();
                panel.activate(previous);
                assertTrue(panel.activate(next));
                assertOnlyActive(panel, next);
            }
        }
    }

    @Test public void invalidActivationCannotOpenAClosedPanelOrReplaceAnOpenCategory() {
        for (int invalid : new int[]{Integer.MIN_VALUE, -2, -1, 3, 4, Integer.MAX_VALUE}) {
            SpotifyCategoryPanel closed = new SpotifyCategoryPanel();
            assertFalse(closed.activate(invalid));
            assertClosed(closed);
            for (int active = 0; active < 3; active++) {
                SpotifyCategoryPanel open = new SpotifyCategoryPanel();
                open.activate(active);
                assertFalse(open.activate(invalid));
                assertOnlyActive(open, active);
            }
        }
    }

    @Test public void closingOnSignOutIsIdempotentAndRequiresAnotherExplicitActivation() {
        SpotifyCategoryPanel panel = new SpotifyCategoryPanel();
        panel.activate(2);
        panel.close();
        panel.close();
        assertClosed(panel);
        assertTrue(panel.activate(2));
        assertOnlyActive(panel, 2);
    }

    @Test public void switchingAndClosingDoesNotRestoreAnEarlierCategory() {
        SpotifyCategoryPanel panel = new SpotifyCategoryPanel();
        panel.activate(0);
        panel.activate(1);
        panel.activate(2);
        panel.activate(2);
        assertClosed(panel);
        panel.activate(1);
        assertOnlyActive(panel, 1);
    }

    @Test public void namedClicksDistinguishLoadTransitionsFromClosingWithoutLoading() {
        SpotifyCategoryPanel panel = new SpotifyCategoryPanel();
        assertFalse(panel.isExpanded());
        assertEquals("", panel.category());
        assertEquals(SpotifyCategoryPanel.OPEN, panel.click("recent"));
        assertTrue(panel.isExpanded());
        assertEquals("recent", panel.category());
        assertTrue(panel.shows("recent"));
        assertFalse(panel.shows("playlists"));
        assertEquals(SpotifyCategoryPanel.REPLACE, panel.click("playlists"));
        assertEquals("playlists", panel.category());
        assertFalse(panel.shows("recent"));
        assertTrue(panel.isActive("playlists"));
        assertEquals(SpotifyCategoryPanel.CLOSE, panel.click("playlists"));
        assertFalse(panel.isExpanded());
        assertFalse(panel.shows("playlists"));
        assertEquals("", panel.category());
        assertEquals(SpotifyCategoryPanel.OPEN, panel.click("resume"));
        assertTrue(panel.shows("resume"));
    }

    @Test public void invalidOrLateCategoryResultsCannotReplaceTheExplicitChoice() {
        SpotifyCategoryPanel panel = new SpotifyCategoryPanel();
        panel.click("resume");
        for (String invalid : new String[]{null, "", "RECENT", "recent ", "other"}) {
            assertEquals(SpotifyCategoryPanel.NONE, panel.click(invalid));
            assertFalse(panel.shows(invalid));
            assertTrue(panel.shows("resume"));
        }
        // A response for another category is readable only when it matches the explicit open choice.
        assertFalse(panel.shows("recent"));
        panel.close();
        assertFalse(panel.shows("resume"));
        assertFalse(panel.shows("recent"));
        assertClosed(panel);
    }

    private static void assertClosed(SpotifyCategoryPanel panel) {
        assertEquals(-1, panel.activeIndex());
        assertFalse(panel.isOpen());
        for (int index = -1; index <= 3; index++) assertFalse(panel.isActive(index));
    }

    private static void assertOnlyActive(SpotifyCategoryPanel panel, int active) {
        assertEquals(active, panel.activeIndex());
        assertTrue(panel.isOpen());
        for (int index = -1; index <= 3; index++) assertEquals(index == active, panel.isActive(index));
    }
}
