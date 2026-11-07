package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/** A small Home affordance; the personal music companion owns the complete Spotify panel. */
final class SpotifyShortcut {
    private static final String COMPANION = "com.daviddemri.tv.spotify";
    private static final int SUBSCRIBE = 1, UNSUBSCRIBE = 2, SNAPSHOT = 100;
    private final FrameLayout root;
    private final LinearLayout hint;
    private final TextView artist, title;
    private final Runnable requestOpen;
    private final Handler messages = new Handler(Looper.getMainLooper(), this::receive);
    private final Messenger receiver = new Messenger(messages);
    private final SpotifyShortcutState playback = new SpotifyShortcutState();
    private final Runnable expireTitle = this::renderTitle;
    private final boolean animateTitles;
    private final float density;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!active || !bound || !COMPANION.equals(name.getPackageName())) return;
            service = new Messenger(binder);
            send(SUBSCRIBE);
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; renderTitle(); }
        @Override public void onNullBinding(ComponentName name) { releaseBinding(); }
        @Override public void onBindingDied(ComponentName name) {
            releaseBinding();
            if (active) root.post(SpotifyShortcut.this::bind);
        }
    };
    private WeakReference<View> returnFocus = new WeakReference<>(null);
    private Messenger service;
    private int serviceUid = -1;
    private int titleTransition;
    private String shownTitle = "", shownArtist = "";
    private boolean capturedUp, returning, active, bound;

    SpotifyShortcut(FrameLayout root, Runnable requestOpen, boolean animations, boolean reducedMotion) {
        this.root = root;
        this.requestOpen = requestOpen;
        animateTitles = animations && !reducedMotion;
        density = root.getResources().getDisplayMetrics().density;
        hint = new LinearLayout(root.getContext());
        hint.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        artist = label(Gravity.END);
        LinearLayout.LayoutParams artistPosition = new LinearLayout.LayoutParams(-2, -1);
        artistPosition.rightMargin = dp(6);
        hint.addView(artist, artistPosition);
        ImageView icon = new ImageView(root.getContext());
        icon.setImageResource(R.drawable.ic_music_note);
        icon.setPadding(dp(5), dp(5), dp(5), dp(5));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hint.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        title = label(Gravity.START);
        LinearLayout.LayoutParams titlePosition = new LinearLayout.LayoutParams(-2, -1);
        titlePosition.leftMargin = dp(6);
        hint.addView(title, titlePosition);
        artist.setVisibility(View.GONE); title.setVisibility(View.GONE);
        int available = Math.min(dp(356), Math.max(dp(120), root.getResources().getDisplayMetrics().widthPixels - dp(260)));
        artist.setMaxWidth(Math.round((available - dp(56)) * .4f));
        title.setMaxWidth(Math.round((available - dp(56)) * .6f));
        artist.setTextColor(0xFFC3C8CC);
        hint.setContentDescription(root.getContext().getString(R.string.spotify_shortcut));
        hint.setFocusable(false);
        hint.setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(14));
        background.setColor(0xDD1B1D22);
        hint.setBackground(background);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-2, dp(28), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        position.topMargin = dp(6);
        root.addView(hint, position);
        hint.setOnClickListener(v -> requestOpen.run());
    }

    private TextView label(int horizontalGravity) {
        TextView text = new TextView(root.getContext());
        text.setTextSize(12);
        text.setTextColor(0xFFFFFFFF);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setIncludeFontPadding(false);
        text.setGravity(horizontalGravity | Gravity.CENTER_VERTICAL);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return text;
    }

    void resume() { if (!active) { active = true; renderTitle(); bind(); } }

    private void bind() {
        if (!active || bound) return;
        try {
            PackageManager packages = root.getContext().getPackageManager();
            serviceUid = packages.getApplicationInfo(COMPANION, 0).uid;
            if (packages.checkSignatures(serviceUid, android.os.Process.myUid()) != PackageManager.SIGNATURE_MATCH) return;
            bound = root.getContext().bindService(new Intent().setClassName(COMPANION, COMPANION + ".SpotifyHomeService"),
                    connection, Context.BIND_AUTO_CREATE);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            bound = false;
        }
    }

    private boolean receive(Message message) {
        if (!active || !bound || message.what != SNAPSHOT || message.sendingUid != serviceUid) return true;
        Bundle data = message.getData();
        Object rawTitle = data.get("title"), rawArtist = data.get("artist"), rawPlaying = data.get("playing"), rawTime = data.get("updated_at");
        if (!(rawTitle instanceof String) || !(rawArtist instanceof String)
                || !(rawPlaying instanceof Boolean) || !(rawTime instanceof Long)) return true;
        if (playback.accept((String) rawTitle, (String) rawArtist, (Boolean) rawPlaying,
                (Long) rawTime, SystemClock.elapsedRealtime())) renderTitle();
        return true;
    }

    private void send(int what) {
        if (service == null) return;
        Message message = Message.obtain(null, what);
        message.replyTo = receiver;
        try { service.send(message); }
        catch (RemoteException unavailable) { service = null; renderTitle(); }
    }

    private void releaseBinding() {
        send(UNSUBSCRIBE);
        service = null;
        if (bound) {
            bound = false;
            try { root.getContext().unbindService(connection); } catch (RuntimeException ignored) { }
        }
    }

    private void renderTitle() {
        long now = SystemClock.elapsedRealtime();
        playback.expire(now);
        messages.removeCallbacks(expireTitle);
        if (active && playback.remaining(now) > 0) messages.postDelayed(expireTitle, playback.remaining(now));
        String song = playback.title(), musician = playback.artist();
        if (song.equals(shownTitle) && musician.equals(shownArtist)) return;
        shownTitle = song; shownArtist = musician;
        int transition = ++titleTransition;
        cancelTitleAnimation();
        if (!animateTitles || !active || !hint.isShown() || hint.getWindowToken() == null) {
            applyTitle(); hint.setAlpha(1f); return;
        }
        hint.animate().alpha(0f).setDuration(70).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (transition != titleTransition) return;
                    applyTitle();
                    hint.animate().alpha(1f).setDuration(130).setInterpolator(new DecelerateInterpolator())
                            .withEndAction(null).start();
                }).start();
    }

    private void applyTitle() {
        String song = shownTitle, musician = shownArtist;
        boolean visible = !song.isEmpty();
        artist.setText(visible ? musician : "");
        title.setText(visible ? song : "");
        artist.setVisibility(visible && !musician.isEmpty() ? View.VISIBLE : View.GONE);
        title.setVisibility(visible ? View.VISIBLE : View.GONE);
        hint.setPadding(visible && !musician.isEmpty() ? dp(8) : 0, 0, visible ? dp(8) : 0, 0);
        String description = root.getContext().getString(R.string.spotify_shortcut);
        hint.setContentDescription(visible ? description + ", " + (musician.isEmpty() ? "" : musician + ", ") + song : description);
    }

    private void cancelTitleAnimation() {
        hint.animate().cancel();
        hint.animate().setListener(null).withEndAction(null);
    }

    private void settleTitle() {
        ++titleTransition;
        cancelTitleAnimation();
        applyTitle(); hint.setAlpha(1f);
    }

    private int dp(float value) { return Math.round(value * density); }

    boolean handleKeyEvent(KeyEvent event, boolean eligible) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_DPAD_UP) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && capturedUp) {
            capturedUp = false;
            if (eligible && !event.isCanceled()) requestOpen.run();
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (capturedUp) return true;
        if (!eligible || event.getRepeatCount() != 0 || event.isCanceled()) return false;
        capturedUp = true;
        return true;
    }

    void open() {
        returnFocus = new WeakReference<>(root.findFocus());
        returning = true;
        try {
            root.getContext().startActivity(new Intent("com.daviddemri.tv.spotify.OPEN")
                    .setClassName("com.daviddemri.tv.spotify", "com.daviddemri.tv.spotify.SpotifyWidgetActivity"));
            hint.setVisibility(View.INVISIBLE);
        } catch (RuntimeException unavailable) {
            returning = false;
            returnFocus.clear();
            Toast.makeText(root.getContext(), R.string.spotify_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    void restoreFocus() {
        if (!returning) return;
        returning = false;
        hint.setVisibility(View.VISIBLE);
        View origin = returnFocus.get();
        returnFocus.clear();
        if (origin != null && origin.isAttachedToWindow() && origin.isShown()) origin.requestFocus();
    }

    void pause() {
        capturedUp = false; active = false; messages.removeCallbacks(expireTitle);
        settleTitle(); releaseBinding();
    }
    void clear() { capturedUp = false; returning = false; returnFocus.clear(); settleTitle(); hint.setVisibility(View.VISIBLE); }
    void destroy() { pause(); clear(); root.removeView(hint); }
}
