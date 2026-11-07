package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ScrollView;

/** One focus-driven viewport for the continuation shelf and every Home app row. */
final class HomeScrollView extends ScrollView {
    private final boolean animations, reducedMotion;
    private ValueAnimator movement;
    private int destination;

    HomeScrollView(Context context, boolean animations, boolean reducedMotion) {
        super(context);
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFocusable(false);
        setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        setSmoothScrollingEnabled(false);
    }

    // Android's focus reveal otherwise moves first, before our focus listener can animate the move.
    @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }
    @Override public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        return false;
    }

    static int revealTarget(int current, int top, int bottom, int height, int margin, int maximum) {
        int target = current;
        if (top < current + margin) target = top - margin;
        else if (bottom > current + height - margin) target = bottom - height + margin;
        return Math.max(0, Math.min(target, Math.max(0, maximum)));
    }

    void reveal(View view) {
        reveal(view, movement != null && movement.isRunning() ? destination : getScrollY());
    }

    /** Return from a lower shelf without leaving that shelf peeking below the selected Home row. */
    void revealFromTop(View view) { reveal(view, 0); }

    private void reveal(View view, int current) {
        if (getChildCount() == 0 || view.getHeight() == 0 || getHeight() == 0) return;
        ViewGroup content = (ViewGroup) getChildAt(0);
        Rect rect = new Rect(0, 0, view.getWidth(), view.getHeight());
        content.offsetDescendantRectToMyCoords(view, rect);
        int margin = Math.round(12 * getResources().getDisplayMetrics().density);
        moveTo(revealTarget(current, rect.top, rect.bottom, getHeight(), margin,
                content.getHeight() - getHeight()));
    }

    void moveTo(int target) {
        if (movement != null && movement.isRunning() && destination == target) return;
        stop();
        destination = target;
        if (!animations || reducedMotion || target == getScrollY()) {
            scrollTo(0, target);
            return;
        }
        movement = ValueAnimator.ofInt(getScrollY(), target);
        movement.setDuration(260);
        movement.setInterpolator(new DecelerateInterpolator(1.8f));
        movement.addUpdateListener(value -> scrollTo(0, (int) value.getAnimatedValue()));
        movement.start();
    }

    void reset() { stop(); destination = 0; scrollTo(0, 0); }
    void stop() { if (movement != null) movement.cancel(); movement = null; }
}
