package com.daviddemri.tv.spotify;

import java.util.HashSet;
import java.util.Set;

/** Owns a complete remote press, including releases after the Spotify panel closes. */
final class SpotifyDrawerKeys {
    static final int PASS = 0, CONSUME = 1, OPEN = 2, CLOSE = 3;
    static final int PREVIOUS_FOCUS = 4, NEXT_FOCUS = 5, ACTIVATE = 6;
    static final int UP_FOCUS = 7, DOWN_FOCUS = 8;
    private static final int DOWN = 0, UP = 1;
    // Primitive Android key codes keep navigation rules independently testable on the JVM.
    private static final int DPAD_UP = 19, DPAD_DOWN = 20, LEFT = 21, RIGHT = 22;
    private static final int BACK = 4, ESCAPE = 111, CENTER = 23, ENTER = 66, NUMPAD_ENTER = 160, MENU = 82;

    private final Set<Integer> captured = new HashSet<>();
    private final Set<Integer> activationArmed = new HashSet<>();
    private boolean open, openingUpHeld;

    boolean isOpen() { return open; }

    int handle(int action, int key, int repeats, boolean canOpen) {
        return handle(action, key, repeats, canOpen, false);
    }

    int handle(int action, int key, int repeats, boolean canOpen, boolean canceled) {
        if (action == UP) {
            boolean ownedPress = captured.remove(key);
            boolean activate = activationArmed.remove(key);
            if (key == DPAD_UP) openingUpHeld = false;
            if (ownedPress) return open && activate && !canceled && repeats == 0 ? ACTIVATE : CONSUME;
            return open && owned(key) ? CONSUME : PASS;
        }
        if (action != DOWN) return open && owned(key) ? CONSUME : PASS;
        if (captured.contains(key)) {
            // Held OK must never produce multiple transport requests, or activate on its later release.
            activationArmed.remove(key);
            if (open && !canceled && key == LEFT) return PREVIOUS_FOCUS;
            if (open && !canceled && key == RIGHT) return NEXT_FOCUS;
            if (open && !canceled && key == DPAD_UP && !openingUpHeld) return UP_FOCUS;
            if (open && !canceled && key == DPAD_DOWN) return DOWN_FOCUS;
            return CONSUME;
        }
        if (!open) {
            // Holding Up while arriving at the header must not unexpectedly open the panel.
            if (canOpen && key == DPAD_UP && repeats == 0 && !canceled) {
                captured.add(key);
                open = true;
                openingUpHeld = true;
                return OPEN;
            }
            return PASS;
        }
        if (!owned(key)) return PASS;
        captured.add(key);
        if (canceled) return CONSUME;
        if (key == BACK || key == ESCAPE) {
            if (repeats != 0) return CONSUME;
            close();
            return CLOSE;
        }
        if (key == LEFT) return PREVIOUS_FOCUS;
        if (key == RIGHT) return NEXT_FOCUS;
        if (key == DPAD_UP) return UP_FOCUS;
        if (key == DPAD_DOWN) return DOWN_FOCUS;
        if (activation(key) && repeats == 0) activationArmed.add(key);
        return CONSUME;
    }

    /** Pointer activation opens the same panel without inventing a remote key press. */
    void open() {
        activationArmed.clear();
        open = true;
    }

    /** Focus returns within Home, so swallow any releases belonging to the dismissed panel. */
    void close() {
        open = false;
        activationArmed.clear();
    }

    /** Leaving Home drops held-key state before the next visit. */
    void reset() {
        close();
        captured.clear();
        openingUpHeld = false;
    }

    private static boolean activation(int key) {
        return key == CENTER || key == ENTER || key == NUMPAD_ENTER;
    }

    private static boolean owned(int key) {
        return key == DPAD_UP || key == DPAD_DOWN || key == LEFT || key == RIGHT
                || key == BACK || key == ESCAPE || key == MENU || activation(key);
    }
}
