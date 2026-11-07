package com.daviddemri.tv.spotify;

/** Bounded grid movement: arrows never jump diagonally into another control row. */
final class SpotifyFocusNavigation {
    static final int STAY = -1, HEADER = -2;
    static final int LEFT = 17, UP = 33, RIGHT = 66, DOWN = 130;
    private SpotifyFocusNavigation() { }

    static int target(int current, int count, int columns, int direction) {
        if (current < 0 || current >= count || columns <= 0) return STAY;
        if (direction == LEFT) return current % columns > 0 ? current - 1 : STAY;
        if (direction == RIGHT) return current % columns < columns - 1 && current + 1 < count ? current + 1 : STAY;
        if (direction == UP) return current < columns ? HEADER : current - columns;
        if (direction == DOWN && current / columns < (count - 1) / columns) return Math.min(current + columns, count - 1);
        return STAY;
    }
}
