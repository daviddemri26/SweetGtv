package com.daviddemri.tv.spotify;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;

/** Native playback thumb plus a separate, local seek preview. No playback commands live here. */
final class SpotifySeekBar extends SeekBar {
    private final Paint pendingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float pendingRadius;
    private int pendingProgress = -1;
    private boolean interactionBlocked;
    private Runnable touchCanceled;

    SpotifySeekBar(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        float stroke = 1.5f * density;
        pendingPaint.setColor(0xFF1ED760);
        pendingPaint.setStyle(Paint.Style.STROKE);
        pendingPaint.setStrokeWidth(stroke);
        pendingRadius = 6f * density - stroke / 2f;
    }

    void setPendingProgress(int progress) {
        int next = progress < 0 ? -1 : Math.min(progress, getMax());
        if (pendingProgress == next) return;
        pendingProgress = next;
        invalidate();
    }

    void setInteractionBlocked(boolean blocked) { interactionBlocked = blocked; }

    void setOnTouchCanceled(Runnable callback) { touchCanceled = callback; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pendingProgress < 0 || getMax() <= 0) return;
        float fraction = Math.max(0f, Math.min(1f, (float) pendingProgress / getMax()));
        if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) fraction = 1f - fraction;
        Drawable thumb = getThumb();
        int thumbWidth = thumb == null ? 0 : Math.max(0, thumb.getIntrinsicWidth());
        float available = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight()
                - thumbWidth + getThumbOffset() * 2);
        float x = getPaddingLeft() - getThumbOffset() + thumbWidth / 2f + available * fraction;
        float y = thumb != null && thumb.getBounds().height() > 0
                ? getPaddingTop() + thumb.getBounds().exactCenterY()
                : getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        canvas.drawCircle(x, y, pendingRadius, pendingPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (touchCanceled != null) touchCanceled.run();
            return super.onTouchEvent(event);
        }
        if (interactionBlocked) return true;
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick() { return super.performClick(); }
}
