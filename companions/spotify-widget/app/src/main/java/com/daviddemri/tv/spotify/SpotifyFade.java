package com.daviddemri.tv.spotify;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Small interruptible opacity transitions; updates coalesce while the outgoing view fades. */
final class SpotifyFade {
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.8f);
    private final View[] views;
    private final boolean animations, reducedMotion;
    private int generation;
    private Runnable pending;
    private boolean leaving;

    SpotifyFade(boolean animations, boolean reducedMotion, View... views) {
        this.animations = animations; this.reducedMotion = reducedMotion; this.views = views;
    }

    static boolean enabled(boolean animations) { return animations && ValueAnimator.areAnimatorsEnabled(); }

    void change(Runnable update, boolean animate) {
        if (!animate || !enabled(animations) || !views[0].isShown()) {
            cancel(); update.run(); return;
        }
        pending = update;
        if (leaving) return;
        leaving = true;
        int expected = ++generation;
        stopAnimations();
        animate(0, reducedMotion ? 40 : 70, () -> {
            if (expected != generation) return;
            Runnable latest = pending; pending = null; leaving = false;
            if (latest != null) latest.run();
            if (expected == generation) animate(1, reducedMotion ? 60 : 130, null);
        });
    }

    void enter() {
        cancel();
        if (!enabled(animations)) return;
        for (View view : views) view.setAlpha(0f);
        animate(1, reducedMotion ? 60 : 140, null);
    }

    void exit(Runnable hidden, boolean animate) {
        cancel();
        if (!animate || !enabled(animations) || !views[0].isShown()) { hidden.run(); return; }
        int expected = ++generation;
        animate(0, reducedMotion ? 40 : 90, () -> {
            if (expected != generation) return;
            hidden.run();
            for (View view : views) view.setAlpha(1f);
        });
    }

    void cancel() {
        ++generation; pending = null; leaving = false; stopAnimations();
        for (View view : views) view.setAlpha(1f);
    }

    private void stopAnimations() {
        for (View view : views) {
            view.animate().cancel(); view.animate().setListener(null).withEndAction(null);
        }
    }

    private void animate(float alpha, long duration, Runnable end) {
        for (int i = 0; i < views.length; i++)
            views[i].animate().alpha(alpha).setDuration(duration).setInterpolator(EASE)
                    .withEndAction(i == views.length - 1 ? end : null).start();
    }
}
