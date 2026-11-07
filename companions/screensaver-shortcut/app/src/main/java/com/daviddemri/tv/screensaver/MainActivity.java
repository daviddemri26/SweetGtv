package com.daviddemri.tv.screensaver;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** A launcher tile that asks Android to start its already configured screensaver. */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            // Android TV's exported settings trampoline calls DreamBackend.startDreaming()
            // for this exact action. Despite the historical name "SLEEP", it starts the
            // selected DreamService; it does not call PowerManager.goToSleep().
            Intent tvScreensaver = new Intent("com.google.android.pano.action.SLEEP")
                    .setClassName("com.android.tv.settings",
                            "com.android.tv.settings.device.display.daydream.DaydreamVoiceAction");
            if (open(tvScreensaver)) return;

            // Older devices expose the equivalent SystemUI shortcut instead. The Google
            // TV Android 14 TvSystemUI installed on this Chromecast does not include it.
            Intent screensaver = new Intent(Intent.ACTION_MAIN).setClassName(
                    "com.android.systemui", "com.android.systemui.Somnambulator");
            if (open(screensaver)) return;

            Intent ambientSettings = new Intent("com.google.android.tv.settings.ambient")
                    .setClassName("com.android.tv.settings",
                            "com.google.android.tv.settings.AmbientActivity");
            if (open(ambientSettings) || open(new Intent(Settings.ACTION_DREAM_SETTINGS))) {
                Toast.makeText(this, R.string.choose_start_now, Toast.LENGTH_LONG).show();
            } else if (open(new Intent(Settings.ACTION_SETTINGS))) {
                Toast.makeText(this, R.string.open_ambient_settings, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.unavailable, Toast.LENGTH_LONG).show();
            }
        } finally {
            // Theme.NoDisplay activities must finish before onResume. No background
            // service, timer, receiver or wake lock survives this launcher action.
            finish();
        }
    }

    private boolean open(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }
}
