package com.gathin.tvhome;

/** In-memory exception for one intentional native-Home visit; never persists across a service restart. */
final class StockHomeVisit {
    static final long ENTRY_WINDOW_MS = 10_000L;
    private String stockPackage, originPackage;
    private long requestedAt;
    private boolean arrived;
    private boolean waitingForWake;

    void begin(String stockPackage, String originPackage, long now) {
        this.stockPackage = stockPackage;
        this.originPackage = originPackage;
        requestedAt = now;
        arrived = false;
        waitingForWake = false;
    }

    boolean isActive() { return stockPackage != null; }

    boolean isWaitingForWake() { return waitingForWake; }

    /** No redirect or launch while asleep; retain only a one-shot wake recovery. */
    void waitForWake() {
        clear();
        waitingForWake = true;
    }

    /** True only for the selected stock HOME window, not unrelated activities with the same class name. */
    boolean observeWindow(String pkg, boolean activity, boolean homeWindow, long now) {
        if (!isActive()) return false;
        if (!arrived && (now < requestedAt || now - requestedAt >= ENTRY_WINDOW_MS)) {
            clear();
            return false;
        }
        if (stockPackage.equals(pkg)) {
            if (homeWindow) arrived = true;
            return homeWindow;
        }
        // System overlays, toasts and keyboards are not navigation out of the native launcher.
        if (!activity || "com.android.systemui".equals(pkg)) return false;
        // A queued event from the launching activity can precede the first native Home window.
        if (!arrived && originPackage.equals(pkg)) return false;
        clear();
        return false;
    }

    void clear() {
        stockPackage = null;
        originPackage = null;
        arrived = false;
        requestedAt = 0;
        waitingForWake = false;
    }
}
