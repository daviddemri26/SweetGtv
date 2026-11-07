package com.david.tvcontinue;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ArtworkGeometryTest {
    @Test public void matchingLandscapeFitsWithoutCropping() {
        assertArrayEquals(new float[]{0, 0, 480, 270}, ArtworkGeometry.centerCrop(1920, 1080), .001f);
    }

    @Test public void portraitAndWideImagesFillFrameWithoutStretching() {
        for (int[] source : new int[][]{{1080, 1920}, {2000, 1000}, {1000, 1000}, {1, 500}}) {
            float[] bounds = ArtworkGeometry.centerCrop(source[0], source[1]);
            float width = bounds[2] - bounds[0], height = bounds[3] - bounds[1];
            assertTrue(bounds[0] <= .001f && bounds[1] <= .001f);
            assertTrue(bounds[2] >= 479.999f && bounds[3] >= 269.999f);
            assertEquals(240f, (bounds[0] + bounds[2]) / 2, .001f);
            assertEquals(135f, (bounds[1] + bounds[3]) / 2, .001f);
            assertEquals((float) source[0] / source[1], width / height, .001f);
        }
    }

    @Test public void fallbackIconsRemainCenteredAndKeepTheirAspectRatio() {
        assertArrayEquals(new float[]{150, 45, 330, 225}, ArtworkGeometry.centeredIcon(96, 96), .001f);
        assertArrayEquals(new float[]{150, 90, 330, 180}, ArtworkGeometry.centeredIcon(200, 100), .001f);
        assertArrayEquals(new float[]{195, 45, 285, 225}, ArtworkGeometry.centeredIcon(100, 200), .001f);
        assertArrayEquals(new float[]{150, 45, 330, 225}, ArtworkGeometry.centeredIcon(-1, -1), .001f);
    }

    @Test public void landscapeFormatInvalidatesSquareArtworkKeys() {
        String previous = HomeContract.hash("owner\nhttps://cdn.example/poster.jpg");
        String current = HomeContract.hash(ArtworkGeometry.FORMAT + "\nowner\nhttps://cdn.example/poster.jpg");
        assertNotEquals(previous, current);
        assertTrue(HomeContract.validKey(current));
    }
}
