package com.daviddemri.tv.spotify;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Inset focus border matching the card's rounded TL/TR/BR corners and square BL corner. */
final class SpotifyFocusFrame extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF insetBounds = new RectF();
    private final float[] corners = new float[8];
    private final float radius, stroke;
    private boolean focused;

    /** Dimensions are pixels; callers convert their existing dp radius and stroke once. */
    SpotifyFocusFrame(float radiusPx, float strokePx, int color) {
        radius = Math.max(0, radiusPx);
        stroke = Math.max(0, strokePx);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeJoin(Paint.Join.MITER);
        paint.setStrokeMiter(4f);
    }

    void setFocused(boolean focused) {
        if (this.focused == focused) return;
        this.focused = focused;
        invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        path.reset();
        if (stroke <= 0 || bounds.width() <= stroke || bounds.height() <= stroke) return;
        float halfStroke = stroke / 2f;
        insetBounds.set(bounds);
        insetBounds.inset(halfStroke, halfStroke);
        float curve = Math.min(Math.max(0, radius - halfStroke),
                Math.min(insetBounds.width(), insetBounds.height()) / 2f);
        for (int index = 0; index < 6; index++) corners[index] = curve;
        corners[6] = corners[7] = 0;
        path.addRoundRect(insetBounds, corners, Path.Direction.CW);
    }

    @Override public void draw(Canvas canvas) {
        if (focused && !path.isEmpty()) canvas.drawPath(path, paint);
    }

    @Override public void setAlpha(int alpha) {
        paint.setAlpha(Math.max(0, Math.min(255, alpha)));
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
