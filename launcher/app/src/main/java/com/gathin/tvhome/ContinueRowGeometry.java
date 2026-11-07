package com.gathin.tvhome;

/** Pixel geometry for four complete landscape posters and a fifth-poster continuation cue. */
final class ContinueRowGeometry {
    private ContinueRowGeometry() { }

    static int posterWidth(int viewport, int leading, int inset, int gap) {
        // Fifth poster starts at leading + 4 * (poster + 2 * inset + gap) + inset.
        return Math.max(1, (int) Math.floor((viewport - leading - 9d * inset - 4d * gap) / 4.2d));
    }

    static int posterHeight(int width) { return Math.max(1, Math.round(width * 9f / 16f)); }

    static int scrollFor(int index, int current, int step, int maximum) {
        if (step <= 0) return 0;
        int first = Math.max(0, Math.round((float) current / step));
        int target = current;
        if (index < first) target = index * step;
        else if (index > first + 3) target = (index - 3) * step;
        return Math.max(0, Math.min(target, Math.max(0, maximum)));
    }
}
