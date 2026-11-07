package com.gathin.tvhome;

import java.util.HashSet;
import java.util.Set;

/** Key ownership for the local volume drawer; never leaks a closing key-up to the page below. */
final class VolumeDrawerKeys {
    static final int PASS = 0, CONSUME = 1, OPEN = 2, CLOSE = 3, RAISE = 4, LOWER = 5;
    private static final int DOWN = 0, UP = 1;
    // Android key codes, kept as primitives so the press-sequence rules run in JVM tests.
    private static final int LEFT = 21, RIGHT = 22, DPAD_UP = 19, DPAD_DOWN = 20;
    private static final int BACK = 4, ESCAPE = 111, CENTER = 23, ENTER = 66, NUMPAD_ENTER = 160, MENU = 82;
    private static final long REPEAT_MS = 120;

    private final Set<Integer> captured = new HashSet<>();
    private boolean open;
    private long lastStepTime = Long.MIN_VALUE;

    boolean isOpen() { return open; }

    /** Pointer opening preserves releases still owned by the previous drawer visit. */
    void open() {
        open = true;
        lastStepTime = Long.MIN_VALUE;
    }

    int handle(int action, int key, int repeats, long time, boolean canOpen) {
        if (action == UP) {
            if (captured.remove(key)) return CONSUME;
            return open && owned(key) ? CONSUME : PASS;
        }
        if (action != DOWN) return open && owned(key) ? CONSUME : PASS;
        if (captured.contains(key)) {
            if (open && (key == DPAD_UP || key == DPAD_DOWN)) return step(key, repeats, time);
            return CONSUME;
        }
        if (!open) {
            // Holding Left while crossing a row must not open the drawer upon reaching its edge.
            if (canOpen && key == LEFT && repeats == 0) {
                captured.add(key);
                open = true;
                lastStepTime = Long.MIN_VALUE;
                return OPEN;
            }
            return PASS;
        }
        if (!owned(key)) return PASS;
        captured.add(key);
        if (key == LEFT || key == RIGHT || key == BACK || key == ESCAPE) {
            if (repeats != 0) return CONSUME;
            open = false;
            return CLOSE;
        }
        if (key == DPAD_UP || key == DPAD_DOWN) return step(key, repeats, time);
        return CONSUME;
    }

    private int step(int key, int repeats, long time) {
        if (repeats != 0 && lastStepTime != Long.MIN_VALUE && time - lastStepTime < REPEAT_MS) return CONSUME;
        lastStepTime = time;
        return key == DPAD_UP ? RAISE : LOWER;
    }

    private static boolean owned(int key) {
        return key == LEFT || key == RIGHT || key == DPAD_UP || key == DPAD_DOWN
                || key == BACK || key == ESCAPE || key == CENTER || key == ENTER
                || key == NUMPAD_ENTER || key == MENU;
    }

    /** Keep captured releases until they arrive when dismissing into the same activity. */
    void close() { open = false; }

    /** Leaving the activity ends all press ownership; no stale held key survives the next resume. */
    void reset() {
        open = false;
        captured.clear();
        lastStepTime = Long.MIN_VALUE;
    }
}
