package com.gathin.tvhome;

/** D-pad grid movement: never wrap sideways, and keep the nearest column on an incomplete last row. */
final class LibraryPageNavigation {
    private LibraryPageNavigation() { }

    static int next(int current, int count, int columns, int key) {
        if (count <= 0 || columns <= 0) return -1;
        current = Math.max(0, Math.min(current, count - 1));
        if (key == 19) return current >= columns ? current - columns : current; // DPAD_UP
        if (key == 20) return (current / columns + 1) * columns < count
                ? Math.min(current + columns, count - 1) : current; // DPAD_DOWN
        if (key == 21) return current % columns > 0 ? current - 1 : current; // DPAD_LEFT
        if (key == 22) return current % columns < columns - 1 && current + 1 < count ? current + 1 : current;
        return current;
    }
}
