package com.daviddemri.tv.spotify;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** A personal Spotify panel over Home; network data access stays in this companion. */
public final class SpotifyWidgetActivity extends Activity {
    private FrameLayout root;
    private SpotifyDrawer drawer;
    private boolean opened, closing, backArmed;
    private final Runnable finishClose = this::finish;
    private final Runnable openPanel = () -> {
        if (isFinishing() || opened || closing) return;
        opened = true;
        drawer.open(null);
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClipChildren(false);
        setContentView(root);
        drawer = new SpotifyDrawer(root, true, false);
        drawer.setConnectAction(() -> new AlertDialog.Builder(this)
                .setTitle(R.string.spotify_connect)
                .setMessage(R.string.spotify_finish_sign_in)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> { })
                .show());
    }

    @Override protected void onResume() {
        super.onResume();
        drawer.resume();
        root.post(openPanel);
    }

    @Override protected void onPause() {
        root.removeCallbacks(openPanel);
        opened = false;
        backArmed = false;
        drawer.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        root.removeCallbacks(openPanel);
        root.removeCallbacks(finishClose);
        drawer.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                backArmed = !event.isCanceled();
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (backArmed && !event.isCanceled()) closePanel();
                backArmed = false;
            }
            return true;
        }
        if (closing && (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_CENTER
                || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER
                || key == KeyEvent.KEYCODE_MENU)) return true;
        if (!closing && drawer.handleKeyEvent(event, false, getCurrentFocus())) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() { closePanel(); }

    private void closePanel() {
        if (closing) return;
        closing = true;
        drawer.dismiss(true, false);
        root.postDelayed(finishClose, 170L);
    }
}
