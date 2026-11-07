package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.function.IntConsumer;

/** A two-control volume rail inside Home; it never appears above another application. */
final class VolumeDrawer {
    private static final long IDLE_TIMEOUT_MS = 7_000L;
    private final FrameLayout root, panel;
    private final ImageView shortcut;
    private final boolean animations, reducedMotion;
    private final IntConsumer adjust;
    private final VolumeDrawerKeys keys = new VolumeDrawerKeys();
    private final Button up, down;
    private final float density;
    private WeakReference<View> returnFocus = new WeakReference<>(null);
    private Runnable restoreFallback, shortcutOpener;
    private int transition;
    private boolean destroyed;
    private final Runnable idleDismiss = () -> {
        if (!destroyed && keys.isOpen()) dismiss(true, true);
    };

    VolumeDrawer(FrameLayout root, boolean animations, boolean reducedMotion, IntConsumer adjustDirection) {
        this.root = root;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        adjust = adjustDirection;
        density = root.getResources().getDisplayMetrics().density;
        shortcut = new ImageView(root.getContext());
        shortcut.setImageResource(R.drawable.ic_volume_hint);
        shortcut.setPadding(dp(5), dp(5), dp(5), dp(5));
        shortcut.setContentDescription("Volume controls. Press Left at a row edge to open.");
        shortcut.setFocusable(false);
        shortcut.setClickable(true);
        GradientDrawable hintBackground = new GradientDrawable();
        hintBackground.setShape(GradientDrawable.OVAL);
        hintBackground.setColor(0xDD1B1D22);
        shortcut.setBackground(hintBackground);
        FrameLayout.LayoutParams hintPosition = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        hintPosition.leftMargin = dp(10);
        root.addView(shortcut, hintPosition);
        shortcut.setOnClickListener(v -> {
            if (!destroyed && shortcutOpener != null) shortcutOpener.run();
        });
        panel = new FrameLayout(root.getContext());
        panel.setFocusable(true);
        panel.setContentDescription("Volume. Up increases, Down decreases. Left, Right or Back closes.");
        panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setVisibility(View.GONE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF51B1D22);
        background.setCornerRadius(dp(28));
        background.setStroke(dp(1), 0x40FFFFFF);
        panel.setBackground(background);
        panel.setElevation(dp(6));
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(dp(56), dp(120), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        position.leftMargin = dp(10);
        root.addView(panel, position);
        up = new Button(R.drawable.ic_volume_drawer_up, "Volume up", 12, 1);
        down = new Button(R.drawable.ic_volume_drawer_down, "Volume down", 64, -1);
    }

    void setRestoreFallback(Runnable fallback) { restoreFallback = fallback; }
    void setShortcutOpener(Runnable opener) { shortcutOpener = opener; }

    void open(View origin) {
        if (destroyed) return;
        keys.open();
        show(origin);
        restartIdleTimeout();
    }

    boolean isOpen() { return keys.isOpen(); }

    boolean handleKeyEvent(KeyEvent event, boolean canOpen, View origin) {
        if (destroyed) return false;
        int action = keys.handle(event.getAction(), event.getKeyCode(), event.getRepeatCount(), event.getEventTime(), canOpen);
        if (keys.isOpen()) restartIdleTimeout();
        switch (action) {
            case VolumeDrawerKeys.PASS: return false;
            case VolumeDrawerKeys.OPEN: show(origin); break;
            case VolumeDrawerKeys.CLOSE: dismiss(true, true); break;
            case VolumeDrawerKeys.RAISE: change(1); break;
            case VolumeDrawerKeys.LOWER: change(-1); break;
            default: break;
        }
        return true;
    }

    private void show(View origin) {
        returnFocus = new WeakReference<>(origin);
        boolean wasVisible = panel.getVisibility() == View.VISIBLE;
        ++transition;
        panel.animate().cancel();
        panel.animate().setListener(null).withEndAction(null);
        if (!wasVisible) {
            panel.setAlpha(animations ? 0f : 1f);
            panel.setTranslationX(animations && !reducedMotion ? -dp(76) : 0);
        }
        shortcut.setVisibility(View.INVISIBLE);
        panel.setVisibility(View.VISIBLE);
        panel.requestFocus();
        if (!animations) {
            panel.setAlpha(1f);
            panel.setTranslationX(0);
        } else {
            panel.animate().alpha(1f).translationX(0)
                    .setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        }
    }

    void dismiss(boolean animate, boolean restoreFocus) {
        root.removeCallbacks(idleDismiss);
        if (restoreFocus) keys.close();
        else keys.reset();
        final int generation = ++transition;
        panel.animate().cancel();
        panel.animate().setListener(null).withEndAction(null);
        up.stop();
        down.stop();
        if (panel.getVisibility() != View.VISIBLE) return;
        if (restoreFocus) restoreOrigin();
        else returnFocus.clear();
        if (!animate || !animations) {
            hide();
            return;
        }
        panel.animate().alpha(0f).translationX(reducedMotion ? 0 : -dp(76))
                .setDuration(reducedMotion ? Motion.REDUCED_MS : 160)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .withEndAction(() -> { if (transition == generation && !keys.isOpen()) hide(); }).start();
    }

    private void hide() {
        panel.setVisibility(View.GONE);
        panel.setAlpha(1f);
        panel.setTranslationX(0);
        shortcut.setVisibility(View.VISIBLE);
    }

    private void restoreOrigin() {
        View origin = returnFocus.get();
        returnFocus.clear();
        if (origin != null && origin.isAttachedToWindow() && origin.isShown() && origin.requestFocus()) return;
        if (restoreFallback != null) restoreFallback.run();
    }

    private void change(int direction) {
        if (!keys.isOpen() || destroyed) return;
        restartIdleTimeout();
        adjust.accept(direction);
        (direction > 0 ? up : down).pulse();
    }

    /** One timer per open drawer; key repeats, releases and direct icon taps all count as use. */
    private void restartIdleTimeout() {
        root.removeCallbacks(idleDismiss);
        root.postDelayed(idleDismiss, IDLE_TIMEOUT_MS);
    }

    void destroy() {
        if (destroyed) return;
        dismiss(false, false);
        destroyed = true;
        restoreFallback = null;
        shortcutOpener = null;
        root.removeView(panel);
        root.removeView(shortcut);
    }

    private int dp(float value) { return Math.round(value * density); }

    private final class Button {
        final ImageView view;
        final GradientDrawable background = new GradientDrawable();
        ValueAnimator pulse;
        final Runnable reset = this::resetAppearance;

        Button(int icon, String description, int top, int direction) {
            view = new ImageView(root.getContext());
            view.setImageResource(icon);
            view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            view.setPadding(dp(9), dp(9), dp(9), dp(9));
            view.setContentDescription(description);
            view.setFocusable(false);
            view.setClickable(true);
            background.setShape(GradientDrawable.OVAL);
            background.setColor(0x00FFFFFF);
            view.setBackground(background);
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            position.topMargin = dp(top);
            panel.addView(view, position);
            view.setOnClickListener(v -> change(direction));
        }

        void pulse() {
            stop();
            if (!animations) {
                background.setColor(0x55FFFFFF);
                view.postDelayed(reset, 140);
                return;
            }
            pulse = ValueAnimator.ofFloat(1f, 0f);
            pulse.setDuration(reducedMotion ? Motion.REDUCED_MS : 160);
            pulse.setInterpolator(new DecelerateInterpolator(1.5f));
            pulse.addUpdateListener(animation -> {
                float amount = (float) animation.getAnimatedValue();
                background.setColor((Math.round(85 * amount) << 24) | 0x00FFFFFF);
                float scale = reducedMotion ? 1f : 1f + .1f * amount;
                view.setScaleX(scale);
                view.setScaleY(scale);
            });
            pulse.start();
        }

        void stop() {
            view.removeCallbacks(reset);
            if (pulse != null) { pulse.cancel(); pulse = null; }
            resetAppearance();
        }

        void resetAppearance() {
            background.setColor(0x00FFFFFF);
            view.setScaleX(1f);
            view.setScaleY(1f);
        }
    }
}
