package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StockHomeVisitTest {
    private static final String STOCK = "com.example.nativehome", HOME = "com.gathin.tvhome";
    private static final long NOW = 50_000L;
    private final StockHomeVisit visit = new StockHomeVisit();

    private void begin() { visit.begin(STOCK, HOME, NOW); }

    @Test public void ordinaryHomeIsNeverExempt() {
        assertFalse(visit.observeWindow(STOCK, true, true, NOW));
        assertFalse(visit.isActive());
    }

    @Test public void deliberateVisitSurvivesNativeTabsAndRepeatedHomeEvents() {
        begin();
        assertTrue(visit.observeWindow(STOCK, true, true, NOW + 1));
        assertFalse(visit.observeWindow(STOCK, true, false, NOW + 2));
        assertTrue(visit.observeWindow(STOCK, true, true, NOW + 60_000));
        assertTrue(visit.isActive());
    }

    @Test public void queuedOriginEventBeforeArrivalDoesNotBounceTheLaunch() {
        begin();
        assertFalse(visit.observeWindow(HOME, true, false, NOW + 1));
        assertTrue(visit.isActive());
        assertTrue(visit.observeWindow(STOCK, true, true, NOW + 2));
    }

    @Test public void anUnconfirmedLaunchExpiresBeforeItCanBypassHomeLater() {
        begin();
        assertFalse(visit.observeWindow(STOCK, true, true, NOW + StockHomeVisit.ENTRY_WINDOW_MS));
        assertFalse(visit.isActive());
    }

    @Test public void externalAppEndsVisitSoItsNextHomeIsRedirected() {
        begin();
        assertTrue(visit.observeWindow(STOCK, true, true, NOW + 1));
        assertFalse(visit.observeWindow("com.example.player", true, false, NOW + 2));
        assertFalse(visit.isActive());
        assertFalse(visit.observeWindow(STOCK, true, true, NOW + 3));
    }

    @Test public void returningToCleanYourTvEndsTheArrivedVisit() {
        begin();
        assertTrue(visit.observeWindow(STOCK, true, true, NOW + 1));
        assertFalse(visit.observeWindow(HOME, true, false, NOW + 2));
        assertFalse(visit.isActive());
    }

    @Test public void overlaysAndUnknownWindowsDoNotEndTheVisit() {
        begin();
        assertTrue(visit.observeWindow(STOCK, true, true, NOW + 1));
        assertFalse(visit.observeWindow("com.android.systemui", true, false, NOW + 2));
        assertFalse(visit.observeWindow("com.example.keyboard", false, false, NOW + 3));
        assertFalse(visit.observeWindow(null, false, false, NOW + 4));
        assertTrue(visit.observeWindow(STOCK, true, true, NOW + 5));
    }

    @Test public void explicitHomeFailureOrDestroyClearTheException() {
        begin();
        assertTrue(visit.observeWindow(STOCK, true, true, NOW + 1));
        visit.clear();
        assertFalse(visit.isActive());
        assertFalse(visit.observeWindow(STOCK, true, true, NOW + 2));
    }

    @Test public void sameClassFromAnotherPackageDoesNotReceiveAnException() {
        begin();
        assertFalse(visit.observeWindow("com.example.other", true, true, NOW + 1));
        assertFalse(visit.isActive());
    }

    @Test public void anotherRealAppBeforeArrivalCancelsPendingVisit() {
        begin();
        assertFalse(visit.observeWindow("com.example.player", true, false, NOW + 1));
        assertFalse(visit.observeWindow(STOCK, true, true, NOW + 2));
    }

    @Test public void screenOffEndsTheExceptionButKeepsOneShotWakeRecovery() {
        begin();
        assertTrue(visit.observeWindow(STOCK, true, true, NOW + 1));
        visit.waitForWake();
        assertFalse(visit.isActive());
        assertTrue(visit.isWaitingForWake());
        assertFalse(visit.observeWindow(STOCK, true, true, NOW + 2));
        visit.clear();
        assertFalse(visit.isWaitingForWake());
    }

    @Test public void newVisitOrTeardownCancelsOldWakeRecovery() {
        begin();
        visit.waitForWake();
        begin();
        assertFalse(visit.isWaitingForWake());
        assertTrue(visit.isActive());
        visit.waitForWake();
        visit.clear();
        assertFalse(visit.isActive());
        assertFalse(visit.isWaitingForWake());
    }
}
