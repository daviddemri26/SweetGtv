package com.gathin.tvhome;

/** Circular movement among the currently visible, enabled header controls. */
final class TopBarNavigation {
    private TopBarNavigation() { }
    static int next(int current, int count, boolean left) {
        if (count <= 0) return -1;
        if (current < 0 || current >= count) return left ? count - 1 : 0;
        return (current + (left ? count - 1 : 1)) % count;
    }
}
