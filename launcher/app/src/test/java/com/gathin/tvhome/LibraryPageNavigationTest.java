package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LibraryPageNavigationTest {
    @Test public void sidewaysNavigationDoesNotWrapRows() {
        assertEquals(3, LibraryPageNavigation.next(3, 24, 4, 22));
        assertEquals(4, LibraryPageNavigation.next(4, 24, 4, 21));
        assertEquals(5, LibraryPageNavigation.next(4, 24, 4, 22));
    }
    @Test public void incompleteLastRowKeepsNearestAvailableColumn() {
        assertEquals(9, LibraryPageNavigation.next(7, 10, 4, 20));
        assertEquals(9, LibraryPageNavigation.next(9, 10, 4, 20));
        assertEquals(5, LibraryPageNavigation.next(9, 10, 4, 19));
    }
    @Test public void topAndEmptyCataloguesStayBounded() {
        assertEquals(2, LibraryPageNavigation.next(2, 24, 4, 19));
        assertEquals(-1, LibraryPageNavigation.next(0, 0, 4, 22));
        assertEquals(0, LibraryPageNavigation.next(0, 1, 4, 20));
    }
}
