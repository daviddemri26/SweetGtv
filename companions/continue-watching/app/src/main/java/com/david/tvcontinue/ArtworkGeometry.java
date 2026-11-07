package com.david.tvcontinue;

/** Landscape thumbnail geometry, shared with tests without Android graphics dependencies. */
final class ArtworkGeometry {
    static final int WIDTH = 480, HEIGHT = 270;
    static final String FORMAT = "format-v2";

    static float[] centerCrop(int width, int height) {
        float scale = Math.max((float) WIDTH / width, (float) HEIGHT / height);
        return centered(width * scale, height * scale);
    }

    static float[] centeredIcon(int width, int height) {
        if (width <= 0 || height <= 0) { width = 1; height = 1; }
        float scale = Math.min(180f / width, 180f / height);
        return centered(width * scale, height * scale);
    }

    private static float[] centered(float width, float height) {
        float left = (WIDTH - width) / 2, top = (HEIGHT - height) / 2;
        return new float[]{left, top, left + width, top + height};
    }
}
