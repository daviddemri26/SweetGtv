package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TopBarNavigationTest {
    @Test public void wrapsBetweenSettingsAndNativeHome() {
        assertEquals(5, TopBarNavigation.next(0, 6, true));
        assertEquals(0, TopBarNavigation.next(5, 6, false));
    }
    @Test public void walksBothDirectionsAndHandlesRemovedControls() {
        assertEquals(2, TopBarNavigation.next(3, 6, true));
        assertEquals(4, TopBarNavigation.next(3, 6, false));
        assertEquals(3, TopBarNavigation.next(-1, 4, true));
        assertEquals(0, TopBarNavigation.next(5, 4, false));
    }
    @Test public void emptyOrSingleControlNeverEscapesTheBar() {
        assertEquals(-1, TopBarNavigation.next(0, 0, true));
        assertEquals(0, TopBarNavigation.next(0, 1, true));
        assertEquals(0, TopBarNavigation.next(0, 1, false));
    }
}
