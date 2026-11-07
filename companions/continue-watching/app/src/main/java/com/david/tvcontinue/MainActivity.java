package com.david.tvcontinue;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground-only, read-only browser for the series exposed by the platform. */
public final class MainActivity extends Activity {
    private static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final int REQUEST = 71;
    private static final int TEXT = Color.rgb(241, 244, 248);
    private static final int MUTED = Color.rgb(174, 187, 204);
    private static final int SURFACE = Color.rgb(28, 33, 40);
    private static final int FOCUS = Color.rgb(222, 235, 252);
    private static final int INK = Color.rgb(18, 26, 37);
    private static final String[] SOURCES = {"com.stremio.one", "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus",
            "com.peacocktv.peacockandroid", "com.apple.atve.androidtv.appletv",
            "com.google.android.youtube.tv", "com.spotify.tv.android"};
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout rows;
    private TextView status;
    private Button refresh;
    private int generation;
    private boolean resumed;
    private CancellationSignal activeLoad;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(30), dp(48), dp(28));
        root.setBackgroundColor(Color.rgb(11, 13, 16));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.heading), 30, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
        refresh = button(getString(R.string.refresh), this::load);
        header.addView(refresh);
        Button back = button(getString(R.string.back), this::finish);
        header.addView(back);
        root.addView(header);
        status = text("", 15, MUTED);
        status.setPadding(0, dp(4), 0, dp(16));
        root.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(2), dp(2), dp(2), dp(12));
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        load();
    }

    @Override protected void onPause() {
        resumed = false;
        generation++;
        cancelLoad();
        super.onPause();
    }

    @Override protected void onDestroy() {
        generation++;
        cancelLoad();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == REQUEST) load();
    }

    private void load() {
        if (!resumed || isFinishing()) return;
        int current = ++generation;
        cancelLoad();
        if (checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            permissionState();
            return;
        }
        refresh.setEnabled(false);
        status.setText(R.string.loading);
        CancellationSignal cancellation = new CancellationSignal();
        activeLoad = cancellation;
        worker.execute(() -> {
            ProgramReader.Result result;
            try {
                result = ProgramReader.read(getApplicationContext(), cancellation);
                cancellation.throwIfCanceled();
            } catch (OperationCanceledException canceled) {
                return;
            }
            runOnUiThread(() -> {
                if (cancellation.isCanceled() || current != generation || !resumed
                        || isFinishing() || isDestroyed()) return;
                if (activeLoad == cancellation) activeLoad = null;
                refresh.setEnabled(true);
                render(result);
            });
        });
    }

    private void cancelLoad() {
        if (activeLoad == null) return;
        activeLoad.cancel();
        activeLoad = null;
    }

    private void permissionState() {
        refresh.setEnabled(true);
        rows.removeAllViews();
        status.setText(R.string.permission_title);
        TextView explanation = text(getString(R.string.permission_body), 20, TEXT);
        explanation.setPadding(0, dp(16), dp(120), dp(24));
        rows.addView(explanation);
        Button allow = button(getString(R.string.permission_button),
                () -> requestPermissions(new String[]{PERMISSION}, REQUEST));
        rows.addView(allow, new LinearLayout.LayoutParams(dp(240), dp(56)));
        addApps();
        allow.requestFocus();
    }

    private void render(ProgramReader.Result result) {
        boolean refreshHadFocus = refresh.hasFocus();
        rows.removeAllViews();
        if (result.entries.isEmpty()) {
            status.setText(result.failed ? R.string.provider_error : R.string.limited);
            TextView heading = text(getString(R.string.empty_title), 24, TEXT);
            heading.setPadding(0, dp(12), 0, dp(16));
            rows.addView(heading);
            TextView description = text(getString(R.string.empty_body), 19, MUTED);
            description.setMaxWidth(dp(730));
            description.setPadding(0, 0, dp(32), dp(24));
            rows.addView(description);
        } else {
            status.setText(getString(R.string.list_status,
                    getResources().getQuantityString(R.plurals.available_count, result.entries.size(), result.entries.size()),
                    getString(result.failed ? R.string.provider_error : R.string.limited)));
            for (ProgramReader.Entry e : result.entries) addEntry(e);
        }
        addApps();
        View first = firstFocusable(rows);
        if (first != null) {
            refresh.setNextFocusDownId(first.getId());
            if (!refreshHadFocus) first.requestFocus();
        }
    }

    private void addEntry(ProgramReader.Entry e) {
        LinearLayout row = new LinearLayout(this);
        row.setId(View.generateViewId());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(18), dp(13), dp(20), dp(13));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(shape(SURFACE));
        ImageView icon = new ImageView(this);
        try { icon.setImageDrawable(getPackageManager().getApplicationIcon(e.owner)); }
        catch (PackageManager.NameNotFoundException ignored) { icon.setImageResource(R.drawable.icon); }
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(22), 0, dp(20), 0);
        TextView title = text(e.title, 21, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        String detail = appName(e.owner);
        if (!e.season.isEmpty() && !e.number.isEmpty()) detail += " · " + getString(R.string.season_episode, e.season, e.number);
        else if (!e.number.isEmpty()) detail += " · " + getString(R.string.episode_only, e.number);
        if (!e.episode.isEmpty() && !e.episode.equals(e.title)) detail += " · " + e.episode;
        TextView subtitle = text(detail, 15, MUTED);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(subtitle);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout tail = new LinearLayout(this);
        tail.setOrientation(LinearLayout.VERTICAL);
        TextView progress = text(progressLabel(e), 14, MUTED);
        progress.setGravity(Gravity.END);
        tail.addView(progress);
        if (e.duration > 0 && e.position > 0 && e.kind == 0) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress((int) Math.min(1000, e.position * 1000.0 / e.duration));
            bar.setProgressTintList(ColorStateList.valueOf(Color.rgb(121, 164, 214)));
            tail.addView(bar, new LinearLayout.LayoutParams(-1, dp(7)));
        }
        row.addView(tail, new LinearLayout.LayoutParams(dp(156), -2));
        row.setContentDescription(e.title + ". " + detail + ". " + progressLabel(e));
        row.setOnFocusChangeListener((v, focused) -> {
            row.setBackground(shape(focused ? FOCUS : SURFACE));
            title.setTextColor(focused ? INK : TEXT);
            subtitle.setTextColor(focused ? INK : MUTED);
            progress.setTextColor(focused ? INK : MUTED);
        });
        row.setOnClickListener(v -> openEntry(e));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(90));
        lp.bottomMargin = dp(10);
        rows.addView(row, lp);
    }

    private void addApps() {
        TextView heading = text(getString(R.string.inside_apps), 20, TEXT);
        heading.setPadding(0, dp(20), 0, dp(12));
        rows.addView(heading);
        LinearLayout group = null;
        int count = 0;
        for (String owner : SOURCES) {
            if (getPackageManager().getLeanbackLaunchIntentForPackage(owner) == null) continue;
            if (count++ % 4 == 0) {
                group = new LinearLayout(this);
                rows.addView(group);
            }
            Button b = button(appName(owner), () -> openApp(owner, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(186), dp(54));
            lp.setMarginEnd(dp(10));
            lp.bottomMargin = dp(10);
            group.addView(b, lp);
        }
    }

    private void openEntry(ProgramReader.Entry entry) {
        EntryLauncher.openEntry(this, entry);
    }

    private void openApp(String owner, boolean fallback) {
        EntryLauncher.openApp(this, owner, fallback);
    }

    private String progressLabel(ProgramReader.Entry e) {
        if (e.kind == 1) return getString(R.string.next_episode);
        if (e.kind == 2) return getString(R.string.new_episode);
        if (e.duration > 0 && e.position > 0) return getString(R.string.progress,
                Math.max(1, e.position / 60000), Math.max(1, e.duration / 60000));
        return getString(R.string.resume);
    }

    private String appName(String owner) {
        try {
            ApplicationInfo app = getPackageManager().getApplicationInfo(owner, 0);
            return String.valueOf(getPackageManager().getApplicationLabel(app));
        } catch (PackageManager.NameNotFoundException e) { return owner; }
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setId(View.generateViewId());
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setBackground(shape(SURFACE));
        b.setFocusable(true);
        b.setOnFocusChangeListener((v, focused) -> {
            b.setBackground(shape(focused ? FOCUS : SURFACE));
            b.setTextColor(focused ? INK : TEXT);
        });
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(128), dp(48));
        lp.setMarginStart(dp(10));
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(12));
        return d;
    }

    private View firstFocusable(LinearLayout container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child.isFocusable()) return child;
            if (child instanceof LinearLayout) {
                View nested = firstFocusable((LinearLayout) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
