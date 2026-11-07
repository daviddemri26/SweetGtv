package com.daviddemri.tv.spotify;

import android.content.res.ColorStateList;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Compact Spotify panel. Explicit playback actions always target the selected Connect device. */
final class SpotifyDrawer {
    private static final int GREEN = 0xFF1ED760, COLUMNS = 4, HEADER_ART_SIZE = 116, SEEK_MAX = 1000;
    private static final String[] CATEGORIES = {"recent", "playlists", "resume"};
    private final FrameLayout root;
    private final LinearLayout panel, categories;
    private final TextView title, subtitle, status, duration, deviceButton, libraryMessage;
    private final SpotifySeekBar progress;
    private final TextView[] tabs = new TextView[3];
    private final ImageView artwork, previous, play, next, openApp, libraryAction;
    private final SpotifyDrawerKeys keys = new SpotifyDrawerKeys();
    private final SpotifyCategoryPanel categoryPanel = new SpotifyCategoryPanel();
    private final SpotifySeekDraft seekDraft = new SpotifySeekDraft();
    private final SpotifySeekDraft seekPreview = new SpotifySeekDraft();
    private final SpotifyArtwork covers = new SpotifyArtwork();
    private final SpotifyPlayback playback;
    private final SpotifyLibrary library;
    private final SpotifyConnect connect;
    private final SpotifyDevicePicker devicePicker;
    private final GridLayout libraryGrid;
    private final ScrollView libraryScroll;
    private final SpotifyFade metadataFade, libraryFade, messageFade;
    private final List<Cell> cells = new ArrayList<>();
    private final boolean animations, reducedMotion;
    private final float density;
    private final int columnWidth;
    private WeakReference<View> activationTarget = new WeakReference<>(null);
    private SpotifyPlayback.Snapshot nativeState;
    private SpotifyLibrary.State libraryState;
    private SpotifyConnect.State connectState;
    private List<SpotifyLibrary.Item> renderedItems = Collections.emptyList();
    private String renderedCategory = "", headerArt = "", headerIdentity = "", lastError = "";
    private String lastLibraryError = "", lastUnconfirmedNotice = "";
    private Runnable connectAction;
    private boolean destroyed, initialSelection;
    private boolean librarySwapScheduled, libraryClosing;
    private boolean seekPreviewPlaying;
    private long seekPreviewAt;
    private int lastTransport = 1, lastCategoryFocus, transition, heldSeekKey;
    private String observedUri = "", observedDevice = "";
    private String requestedTitle = "", requestedArtist = "", requestedMessage = "";
    private int playResource, libraryResource;
    private long observedAt, observedPosition;
    private final Runnable progressTick = this::tickProgress;

    SpotifyDrawer(FrameLayout root, boolean animations, boolean reducedMotion) {
        this.root = root;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        covers.setMotionEnabled(animations && !reducedMotion);
        density = root.getResources().getDisplayMetrics().density;
        panel = new LinearLayout(root.getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, 0, 0, dp(14));
        panel.setBackground(background(0xFC1B1D22, 16));
        panel.setClipToOutline(true);
        panel.setElevation(dp(8));
        panel.setVisibility(View.GONE);
        int width = Math.min(dp(680), root.getResources().getDisplayMetrics().widthPixels - dp(32));
        columnWidth = Math.max(1, (width - dp(14) * 2 - COLUMNS * dp(2) * 2) / COLUMNS);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(width, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        position.topMargin = dp(14);
        root.addView(panel, position);

        LinearLayout now = new LinearLayout(root.getContext());
        now.setGravity(Gravity.CENTER_VERTICAL);
        now.setMinimumHeight(dp(HEADER_ART_SIZE));
        panel.addView(now, new LinearLayout.LayoutParams(-1, -2));
        artwork = cover(new HeaderArtwork(root.getContext(), dp(16)), HEADER_ART_SIZE, 0);
        now.addView(artwork, new LinearLayout.LayoutParams(dp(HEADER_ART_SIZE), -1));
        LinearLayout content = new LinearLayout(root.getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(2), dp(14), 0);
        LinearLayout.LayoutParams contentPosition = new LinearLayout.LayoutParams(0, -2, 1);
        contentPosition.leftMargin = dp(12);
        now.addView(content, contentPosition);
        title = singleLine(19, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMarqueeRepeatLimit(-1);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleRow = new LinearLayout(root.getContext());
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        openApp = bareIcon(R.drawable.ic_music_spotify, R.string.spotify_open);
        LinearLayout.LayoutParams openPosition = new LinearLayout.LayoutParams(dp(32), dp(28));
        openPosition.leftMargin = dp(8);
        titleRow.addView(openApp, openPosition);
        content.addView(titleRow, new LinearLayout.LayoutParams(-1, dp(28)));
        subtitle = singleLine(12, 0xFFE0E3E5);
        LinearLayout.LayoutParams artistPosition = new LinearLayout.LayoutParams(-1, -2);
        artistPosition.topMargin = dp(2);
        content.addView(subtitle, artistPosition);
        status = singleLine(10, 0xFFC3C8CC);
        status.setGravity(Gravity.CENTER_VERTICAL);
        duration = singleLine(10, 0xFFC3C8CC);
        duration.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout timeline = new LinearLayout(root.getContext());
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        timeline.addView(status, new LinearLayout.LayoutParams(dp(38), -1));
        progress = new SpotifySeekBar(root.getContext());
        progress.setMax(SEEK_MAX);
        progress.setPadding(dp(7), 0, dp(7), 0);
        progress.setSplitTrack(false);
        progress.setProgressTintList(ColorStateList.valueOf(GREEN));
        progress.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF59635D));
        ClipDrawable played = new ClipDrawable(background(GREEN, 2), Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable track = new LayerDrawable(new Drawable[] {background(0xFF59635D, 2), played});
        track.setId(0, android.R.id.background); track.setId(1, android.R.id.progress);
        for (int layer = 0; layer < 2; layer++) {
            track.setLayerHeight(layer, dp(4));
            track.setLayerGravity(layer, Gravity.FILL_HORIZONTAL | Gravity.CENTER_VERTICAL);
        }
        progress.setProgressDrawable(track);
        GradientDrawable handle = new GradientDrawable();
        handle.setShape(GradientDrawable.OVAL); handle.setColor(Color.WHITE); handle.setSize(dp(10), dp(10));
        progress.setThumb(handle); progress.setThumbOffset(dp(5));
        configureControl(progress, 6);
        ((GradientDrawable) progress.getBackground()).setColor(Color.TRANSPARENT);
        progress.setOnFocusChangeListener((view, focused) -> {
            focusAppearance(view, focused);
            if (!focused) { cancelSeek(); renderProgress(displayedCurrent()); }
            marquee(title, focused);
        });
        progress.setContentDescription(string(R.string.spotify_seek_hint));
        timeline.addView(progress, new LinearLayout.LayoutParams(0, -1, 1));
        timeline.addView(duration, new LinearLayout.LayoutParams(dp(38), -1));
        LinearLayout.LayoutParams timelinePosition = new LinearLayout.LayoutParams(-1, dp(20));
        timelinePosition.topMargin = dp(2);
        content.addView(timeline, timelinePosition);
        LinearLayout transport = new LinearLayout(root.getContext());
        transport.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams transportPosition = new LinearLayout.LayoutParams(-1, dp(40));
        transportPosition.topMargin = dp(6);
        content.addView(transport, transportPosition);
        previous = icon(transport, R.drawable.ic_music_previous, R.string.spotify_previous);
        play = icon(transport, R.drawable.ic_music_play, R.string.spotify_play);
        next = icon(transport, R.drawable.ic_music_next, R.string.spotify_next);
        View transportSpace = new View(root.getContext());
        transport.setBaselineAligned(false);
        transport.addView(transportSpace, new LinearLayout.LayoutParams(0, 1, 1));
        deviceButton = singleLine(10, 0xFFD8DEDA);
        deviceButton.setText(R.string.spotify_choose_device);
        deviceButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        deviceButton.setMaxWidth(dp(140));
        deviceButton.setPadding(dp(4), 0, dp(7), 0);
        deviceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_music_devices, 0);
        deviceButton.setCompoundDrawablePadding(dp(6));
        deviceButton.setCompoundDrawableTintList(ColorStateList.valueOf(0xFFD8DEDA));
        deviceButton.setFocusable(true); deviceButton.setClickable(true); deviceButton.setBackground(null);
        deviceButton.setOnFocusChangeListener((view, focused) -> {
            int color = focused ? GREEN : 0xFFD8DEDA;
            deviceButton.setTextColor(color);
            deviceButton.setCompoundDrawableTintList(ColorStateList.valueOf(color));
            marquee(title, focused);
        });
        LinearLayout.LayoutParams devicePosition = new LinearLayout.LayoutParams(-2, dp(32));
        devicePosition.leftMargin = dp(8);
        transport.addView(deviceButton, devicePosition);

        categories = new LinearLayout(root.getContext());
        categories.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams categoryPosition = new LinearLayout.LayoutParams(-1, dp(32));
        categoryPosition.topMargin = dp(10);
        categoryPosition.bottomMargin = dp(6);
        categoryPosition.leftMargin = categoryPosition.rightMargin = dp(14);
        panel.addView(categories, categoryPosition);
        String[] labels = {"Recent", "Playlists", "Resume"};
        int[] categoryIcons = {R.drawable.ic_music_recent, R.drawable.ic_music_list, R.drawable.ic_music_resume};
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            TextView tab = singleLine(10, Color.WHITE);
            tab.setText(labels[i]); tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(9), 0, dp(9), 0);
            tab.setCompoundDrawablesRelativeWithIntrinsicBounds(categoryIcons[i], 0, 0, 0);
            tab.setCompoundDrawablePadding(dp(5));
            tab.setCompoundDrawableTintList(ColorStateList.valueOf(0xFFC3C8CC));
            tab.setContentDescription(labels[i]);
            LinearLayout.LayoutParams tabPosition = new LinearLayout.LayoutParams(-2, dp(28));
            tabPosition.rightMargin = dp(4);
            categories.addView(tab, tabPosition);
            configureControl(tab, 7);
            tabs[i] = tab;
            tab.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.Button");
                    info.setCheckable(true); info.setChecked(categoryPanel.isActive(CATEGORIES[index]));
                }
            });
            tab.setOnFocusChangeListener((view, focused) -> {
                if (focused) lastCategoryFocus = index;
                styleTab(index);
            });
            tab.setOnClickListener(v -> toggleCategory(index));
        }
        View spacer = new View(root.getContext());
        categories.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        libraryAction = bareIcon(R.drawable.ic_music_refresh, R.string.spotify_refresh);
        categories.addView(libraryAction, new LinearLayout.LayoutParams(dp(32), dp(32)));
        libraryAction.setOnClickListener(v -> onLibraryAction());
        libraryMessage = text(10, 0xFFC3C8CC);
        libraryMessage.setMaxLines(2);
        libraryMessage.setEllipsize(TextUtils.TruncateAt.END);
        libraryMessage.setPadding(dp(16), dp(6), dp(16), dp(7));
        libraryMessage.setVisibility(View.GONE);
        panel.addView(libraryMessage, new LinearLayout.LayoutParams(-1, -2));
        libraryScroll = new ScrollView(root.getContext());
        libraryScroll.setFocusable(false);
        libraryScroll.setClipToPadding(false);
        libraryScroll.setFillViewport(false);
        libraryScroll.setVerticalScrollBarEnabled(false);
        libraryScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams libraryPosition = new LinearLayout.LayoutParams(-1, dp(174));
        libraryPosition.leftMargin = libraryPosition.rightMargin = dp(14);
        panel.addView(libraryScroll, libraryPosition);
        libraryGrid = new GridLayout(root.getContext());
        libraryGrid.setColumnCount(COLUMNS);
        libraryGrid.setPadding(0, dp(2), 0, dp(2));
        libraryScroll.addView(libraryGrid, new ScrollView.LayoutParams(-1, -2));
        metadataFade = new SpotifyFade(animations, reducedMotion, title, subtitle);
        libraryFade = new SpotifyFade(animations, reducedMotion, libraryScroll);
        messageFade = new SpotifyFade(animations, reducedMotion, libraryMessage);
        libraryScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> loadVisibleCovers());
        libraryScroll.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> loadVisibleCovers());

        playback = new SpotifyPlayback(root.getContext(), this::renderNative);
        library = new SpotifyLibrary(root.getContext(), this::renderLibrary, playback::setBrowserSession);
        connect = new SpotifyConnect(root.getContext(), this::renderConnect, library::refresh);
        library.setConnect(connect);
        devicePicker = new SpotifyDevicePicker(root.getContext(), connect::selectDevice, connect::refreshDevices, animations, reducedMotion);
        previous.setOnClickListener(v -> runCommand(connect::previous));
        play.setOnClickListener(v -> runCommand(() -> connect.playPause(libraryState == null ? SpotifyLibrary.NowPlaying.EMPTY : libraryState.current)));
        next.setOnClickListener(v -> runCommand(connect::next));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { beginSeek(); }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && seekDraft.active()) { seekDraft.setFraction(value, SEEK_MAX); renderSeekDraft(); }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) { renderProgress(displayedCurrent()); }
        });
        progress.setOnTouchCanceled(() -> { cancelSeek(); renderProgress(displayedCurrent()); });
        progress.setOnClickListener(v -> commitSeek());
        deviceButton.setOnClickListener(v -> showDevices());
        openApp.setOnClickListener(v -> {
            if (!playback.openSpotify()) Toast.makeText(root.getContext(), R.string.spotify_unavailable, Toast.LENGTH_LONG).show();
        });
        renderNative(playback.snapshot());
        renderLibrary(library.snapshot());
        renderConnect(connect.snapshot());
    }

    void setConnectAction(Runnable action) { connectAction = action; }
    void open(View origin) { if (!destroyed) show(); }
    boolean isOpen() { return keys.isOpen(); }
    void resume() {
        if (destroyed) return;
        covers.resume();
        playback.start(); library.resume(); connect.resume();
        panel.post(this::loadVisibleCovers);
    }
    void pause() { devicePicker.dismiss(); dismiss(false, false); connect.pause(); library.pause(); playback.stop(); covers.pause(); }

    boolean handleKeyEvent(KeyEvent event, boolean canOpen, View origin) {
        if (destroyed) return false;
        if (keys.isOpen() && progress.hasFocus() && seekKey(event)) return true;
        boolean activation = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                || event.getKeyCode() == KeyEvent.KEYCODE_ENTER || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (activation && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && keys.isOpen()) {
            activationTarget = new WeakReference<>(panel.findFocus());
        }
        int action = keys.handle(event.getAction(), event.getKeyCode(), event.getRepeatCount(), canOpen, event.isCanceled());
        switch (action) {
            case SpotifyDrawerKeys.PASS: return false;
            case SpotifyDrawerKeys.OPEN: show(); break;
            case SpotifyDrawerKeys.CLOSE: dismiss(true, true); break;
            case SpotifyDrawerKeys.PREVIOUS_FOCUS: moveFocus(View.FOCUS_LEFT); break;
            case SpotifyDrawerKeys.NEXT_FOCUS: moveFocus(View.FOCUS_RIGHT); break;
            case SpotifyDrawerKeys.UP_FOCUS: moveFocus(View.FOCUS_UP); break;
            case SpotifyDrawerKeys.DOWN_FOCUS: moveFocus(View.FOCUS_DOWN); break;
            case SpotifyDrawerKeys.ACTIVATE:
                initialSelection = false;
                View focused = panel.findFocus();
                if (focused != null && focused == activationTarget.get() && focused.isEnabled()) focused.performClick();
                break;
            default: break;
        }
        if (activation && event.getAction() == KeyEvent.ACTION_UP) activationTarget.clear();
        return true;
    }

    private void show() {
        initialSelection = true; keys.open(); ++transition;
        panel.animate().cancel();
        panel.animate().setListener(null).withEndAction(null);
        if (panel.getVisibility() != View.VISIBLE) {
            panel.setAlpha(animations ? 0f : 1f);
            panel.setTranslationY(animations && !reducedMotion ? -dp(20) : 0);
        }
        panel.setVisibility(View.VISIBLE); panel.bringToFront();
        playback.refresh(); library.resume(); focusPrimary();
        panel.removeCallbacks(progressTick); panel.post(progressTick);
        if (animations) panel.animate().alpha(1f).translationY(0).setDuration(reducedMotion ? 100 : 200)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        else { panel.setAlpha(1f); panel.setTranslationY(0); }
        panel.post(this::loadVisibleCovers);
    }

    void dismiss(boolean animate, boolean restoreFocus) {
        panel.removeCallbacks(progressTick); cancelSeek(); seekPreview.cancel();
        metadataFade.cancel();
        title.setText(requestedTitle); subtitle.setText(requestedArtist);
        libraryFade.cancel(); messageFade.cancel(); librarySwapScheduled = libraryClosing = false;
        libraryMessage.setText(requestedMessage);
        libraryMessage.setVisibility(requestedMessage.isEmpty() ? View.GONE : View.VISIBLE);
        devicePicker.dismiss();
        activationTarget.clear();
        if (restoreFocus) keys.close(); else keys.reset();
        final int token = ++transition;
        panel.animate().cancel(); panel.animate().setListener(null).withEndAction(null);
        if (panel.getVisibility() != View.VISIBLE) return;
        if (!animate || !animations) { hide(); return; }
        panel.animate().alpha(0f).translationY(reducedMotion ? 0 : -dp(20)).setDuration(reducedMotion ? 100 : 160)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .withEndAction(() -> { if (transition == token && !keys.isOpen()) hide(); }).start();
    }

    private void hide() {
        panel.setVisibility(View.GONE); panel.setAlpha(1f); panel.setTranslationY(0);
        renderLibraryBody(false);
    }

    private void renderNative(SpotifyPlayback.Snapshot state) { nativeState = state; renderHeader(); }

    private SpotifyLibrary.NowPlaying displayedCurrent() {
        SpotifyLibrary.NowPlaying current = libraryState == null ? SpotifyLibrary.NowPlaying.EMPTY : libraryState.current;
        // Native media callbacks can lead the Web API after a track change. Do not show/seek the older URI.
        boolean nativeDifferent = nativeState != null && (nativeState.playing || nativeState.buffering)
                && !nativeState.uri.isEmpty() && !nativeState.uri.equals(current.uri);
        return nativeDifferent ? SpotifyLibrary.NowPlaying.EMPTY : current;
    }

    private void tickProgress() {
        if (destroyed || !keys.isOpen() || panel.getVisibility() != View.VISIBLE) return;
        renderProgress(displayedCurrent());
        panel.postDelayed(progressTick, 1000);
    }

    private long estimatedPosition(SpotifyLibrary.NowPlaying current) {
        long elapsed = current.playing ? Math.min(20_000, Math.max(0, System.currentTimeMillis() - current.updatedAt)) : 0;
        return Math.min(Math.max(0, current.durationMillis), Math.max(0, current.progressMillis) + elapsed);
    }

    private boolean canSeek(SpotifyLibrary.NowPlaying current) {
        return connectState != null && !current.uri.isEmpty() && current.durationMillis > 0
                && !connectState.selectedId.isEmpty() && connectState.selectedId.equals(current.deviceId);
    }

    private void renderProgress(SpotifyLibrary.NowPlaying current) {
        boolean enabled = canSeek(current);
        if (current.updatedAt > observedAt) {
            if (current.uri.equals(observedUri) && current.deviceId.equals(observedDevice)
                    && current.progressMillis + 2500 < observedPosition) cancelSeek();
            observedUri = current.uri; observedDevice = current.deviceId;
            observedAt = current.updatedAt; observedPosition = current.progressMillis;
        }
        boolean ended = current.playing && current.durationMillis > 0 && estimatedPosition(current) >= current.durationMillis;
        if (seekDraft.active() && (!enabled || ended || !seekDraft.matches(current.uri, current.deviceId, current.durationMillis))) cancelSeek();
        enable(progress, enabled);
        progress.setInteractionBlocked(commandsBusy());
        if (enabled && commandsBusy()) progress.setAlpha(.55f);
        if (seekPreview.active()) {
            long age = Math.max(0, System.currentTimeMillis() - seekPreviewAt);
            long previewPosition = Math.min(seekPreview.duration(), seekPreview.position() + (seekPreviewPlaying ? age : 0));
            boolean observed = !commandsBusy() && current.updatedAt >= seekPreviewAt
                    && Math.abs(estimatedPosition(current) - previewPosition) < 3500;
            if (!enabled || !seekPreview.matches(current.uri, current.deviceId, current.durationMillis)
                    || age > 5000 || observed || connectState != null && !connectState.error.isEmpty()) seekPreview.cancel();
        }
        if (seekDraft.active()) renderSeekDraft();
        else if (seekPreview.active()) {
            long age = seekPreviewPlaying ? Math.max(0, System.currentTimeMillis() - seekPreviewAt) : 0;
            updateTimeline(Math.min(seekPreview.duration(), seekPreview.position() + age), seekPreview.duration());
        }
        else if (current.uri.isEmpty() || current.durationMillis <= 0) {
            status.setText("–:––"); duration.setText("–:––"); progress.setProgress(0);
            status.setTextColor(0xFFC3C8CC); progress.setPendingProgress(-1);
            progress.setContentDescription(string(R.string.spotify_seek_hint));
        } else updateTimeline(estimatedPosition(current), current.durationMillis);
    }

    private void updateTimeline(long position, long total) {
        status.setTextColor(0xFFC3C8CC); progress.setPendingProgress(-1);
        status.setText(time(position)); duration.setText(time(total));
        int value = total <= 0 ? 0 : (int) Math.round(SEEK_MAX * Math.min(1.0, (double) position / total));
        progress.setProgress(value);
        progress.setContentDescription(root.getContext().getString(R.string.spotify_seek_position, time(position), time(total)));
    }

    private void renderSeekDraft() {
        SpotifyLibrary.NowPlaying current = displayedCurrent();
        long actual = estimatedPosition(current), total = seekDraft.duration();
        updateTimeline(actual, total);
        status.setText(time(seekDraft.position())); status.setTextColor(GREEN);
        int target = total <= 0 ? 0 : (int) Math.round(SEEK_MAX * Math.min(1.0, (double) seekDraft.position() / total));
        progress.setPendingProgress(target);
        progress.setContentDescription(root.getContext().getString(R.string.spotify_seek_pending,
                time(actual), time(seekDraft.position()), time(total)));
    }

    private boolean beginSeek() {
        if (commandsBusy()) return false;
        SpotifyLibrary.NowPlaying current = displayedCurrent();
        if (!canSeek(current)) return false;
        if (current.playing && estimatedPosition(current) >= current.durationMillis) return false;
        seekPreview.cancel();
        initialSelection = false;
        return seekDraft.begin(current.uri, current.deviceId, estimatedPosition(current), current.durationMillis);
    }

    private void cancelSeek() { seekDraft.cancel(); heldSeekKey = 0; }

    private void commitSeek() {
        if (!seekDraft.active()) { renderProgress(displayedCurrent()); return; }
        long target = seekDraft.position();
        String uri = seekDraft.uri();
        String device = seekDraft.deviceId();
        long total = seekDraft.duration();
        cancelSeek();
        SpotifyLibrary.NowPlaying current = displayedCurrent();
        if (canSeek(current) && uri.equals(current.uri) && device.equals(current.deviceId)
                && total == current.durationMillis
                && !(current.playing && estimatedPosition(current) >= current.durationMillis)) {
            runCommand(() -> connect.seek(target, uri));
            if (commandsBusy()) {
                seekPreview.begin(uri, device, target, total);
                seekPreviewAt = System.currentTimeMillis();
                seekPreviewPlaying = displayedCurrent().playing;
            }
        }
        renderProgress(displayedCurrent());
    }

    private boolean seekKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (event.isCanceled()) { cancelSeek(); renderProgress(displayedCurrent()); return true; }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (commandsBusy()) return true;
            // A held key cannot start a second draft after a track/device change cancelled the first.
            if (!seekDraft.active() && (event.getRepeatCount() != 0 || !beginSeek())) return true;
            heldSeekKey = code;
            seekDraft.step(code == KeyEvent.KEYCODE_DPAD_LEFT ? -10_000 : 10_000);
            renderSeekDraft();
        } else if (event.getAction() == KeyEvent.ACTION_UP && heldSeekKey == code) heldSeekKey = 0;
        return true;
    }

    private void renderHeader() {
        if (destroyed || nativeState == null) return;
        SpotifyLibrary.NowPlaying current = displayedCurrent();
        boolean web = !current.uri.isEmpty();
        String heading = web ? current.title : nativeState.title;
        String label = heading.isEmpty() ? string(R.string.spotify_name) : heading;
        String artist = web ? current.subtitle : !nativeState.installed ? string(R.string.spotify_missing)
                : !nativeState.hasSession ? string(R.string.spotify_no_session) : nativeState.subtitle;
        String artistDescription = artist;
        if (web) {
            String device = current.deviceName.isEmpty() ? "Spotify" : current.deviceName;
            String state = current.playing ? "Playing on " : "Paused on ";
            if (System.currentTimeMillis() - current.updatedAt > 20_000) state = "Last seen on ";
            artistDescription = state + device + (artist.isEmpty() ? "" : " · " + artist);
        }
        if (!requestedTitle.equals(label) || !requestedArtist.equals(artist)) {
            boolean changing = !requestedTitle.isEmpty();
            requestedTitle = label; requestedArtist = artist;
            metadataFade.change(() -> { title.setText(requestedTitle); subtitle.setText(requestedArtist); }, changing && keys.isOpen());
        }
        subtitle.setContentDescription(artistDescription);
        renderProgress(current);
        String identity = web ? current.uri : !nativeState.uri.isEmpty() ? nativeState.uri
                : "native:" + nativeState.title + ':' + nativeState.subtitle;
        if (!headerIdentity.equals(identity)) {
            headerIdentity = identity; headerArt = "";
            covers.cancel(artwork);
        }
        String imageUrl = web ? current.imageUrl : nativeState.imageUrl;
        if (!imageUrl.isEmpty()) headerArt = imageUrl;
        if (headerArt.startsWith("https://")) {
            // load() deduplicates active/cached requests and retries a failed request on later updates.
            covers.load(artwork, headerArt);
        } else if (!web && nativeState.artwork != null && !nativeState.artwork.isRecycled()) {
            String key = "bitmap:" + System.identityHashCode(nativeState.artwork);
            if (!headerArt.equals(key)) {
                headerArt = key; covers.cancel(artwork);
                covers.show(artwork, nativeState.artwork);
            }
        }
        boolean busy = connectState != null && (connectState.busy || connectState.confirmationPending);
        boolean connected = libraryState != null && libraryState.connected;
        boolean pause = connectState != null && !connectState.selectedId.isEmpty()
                && connectState.selectedId.equals(current.deviceId) && current.playing;
        enable(previous, connected);
        enable(play, connected);
        enable(next, connected); enable(openApp, nativeState.installed);
        enable(deviceButton, connected);
        if (connected && busy) {
            previous.setAlpha(.55f); play.setAlpha(.55f); next.setAlpha(.55f); deviceButton.setAlpha(.55f);
        }
        String destination = connectState == null || connectState.selectedId.isEmpty() ? string(R.string.spotify_choose_device) : connectState.selectedName;
        if (!TextUtils.equals(deviceButton.getText(), destination)) deviceButton.setText(destination);
        deviceButton.setContentDescription(string(R.string.spotify_devices_title) + ": " + destination);
        int resource = pause ? R.drawable.ic_music_pause : R.drawable.ic_music_play;
        if (playResource != resource) { playResource = resource; play.setImageResource(resource); }
        play.setContentDescription((pause ? "Pause on " : "Play on ") + destination);
        if (initialSelection && keys.isOpen() && play.isEnabled()) { play.requestFocus(); initialSelection = false; }
        if (keys.isOpen() && (panel.findFocus() == null || !panel.findFocus().isEnabled())) focusPrimary();
    }

    private void renderLibrary(SpotifyLibrary.State state) {
        if (destroyed) return;
        libraryState = state;
        if (!state.connected) categoryPanel.close();
        for (int i = 0; i < tabs.length; i++) { enable(tabs[i], state.connected); styleTab(i); }
        int resource = state.connected ? R.drawable.ic_music_refresh : R.drawable.ic_music_connect;
        if (libraryResource != resource) { libraryResource = resource; libraryAction.setImageResource(resource); }
        libraryAction.setContentDescription(string(state.connected ? R.string.spotify_refresh : R.string.spotify_connect));
        libraryAction.setAlpha(state.loading ? .55f : 1f);
        if (state.failed && !state.items.isEmpty() && !state.error.isEmpty()
                && !state.error.equals(lastLibraryError) && keys.isOpen()) {
            Toast.makeText(root.getContext(), state.error, Toast.LENGTH_LONG).show();
        }
        lastLibraryError = state.failed ? state.error : "";
        renderLibraryBody(keys.isOpen());
        enableCells();
        renderHeader();
        panel.post(this::loadVisibleCovers);
    }

    private void renderLibraryBody(boolean animate) {
        SpotifyLibrary.State state = libraryState;
        boolean open = state.connected && categoryPanel.shows(state.parentId);
        if (!open || state.items.isEmpty() && !state.loading) {
            if (librarySwapScheduled) { libraryFade.cancel(); librarySwapScheduled = false; }
            if (libraryScroll.getVisibility() == View.VISIBLE && animate && !libraryClosing) {
                libraryClosing = true;
                libraryFade.exit(() -> {
                    libraryClosing = false; libraryScroll.setVisibility(View.GONE);
                    updateLibrarySpacing(); renderLibraryMessage();
                }, true);
            } else if (!libraryClosing || !animate) {
                libraryFade.cancel(); libraryClosing = false; libraryScroll.setVisibility(View.GONE);
                updateLibrarySpacing(); renderLibraryMessage();
            }
            return;
        }
        if (libraryClosing) { libraryFade.cancel(); libraryClosing = false; }
        if (state.items.isEmpty()) {
            // Keep the outgoing category visible during a slow request; it is no longer interactive.
            updateLibrarySpacing(); renderLibraryMessage(); return;
        }
        if (librarySwapScheduled) return;
        boolean visible = libraryScroll.getVisibility() == View.VISIBLE;
        if (visible && !renderedCategory.equals(state.parentId) && animate) {
            librarySwapScheduled = true;
            libraryFade.change(() -> {
                librarySwapScheduled = false;
                // The latest category wins even when another request completed during the fade.
                renderLibraryBody(false);
            }, true);
        } else {
            if (!sameItems(state)) rebuildCells(state);
            int rows = Math.min(3, (state.items.size() + COLUMNS - 1) / COLUMNS);
            int height = Math.min(dp(174), rows * (dp(54) + dp(2) * 2) + libraryGrid.getPaddingTop() + libraryGrid.getPaddingBottom());
            LinearLayout.LayoutParams viewport = (LinearLayout.LayoutParams) libraryScroll.getLayoutParams();
            if (viewport.height != height) { viewport.height = height; libraryScroll.setLayoutParams(viewport); }
            libraryScroll.setVisibility(View.VISIBLE);
            enableCells();
            updateLibrarySpacing(); renderLibraryMessage();
            if (!visible && animate) libraryFade.enter();
            panel.post(this::loadVisibleCovers);
        }
    }

    private void updateLibrarySpacing() {
        boolean compact = libraryState.connected && !categoryPanel.isExpanded();
        panel.setPadding(0, 0, 0, dp(compact ? 6 : 14));
        LinearLayout.LayoutParams position = (LinearLayout.LayoutParams) categories.getLayoutParams();
        int margin = compact ? 0 : dp(6);
        if (position.bottomMargin != margin) { position.bottomMargin = margin; categories.setLayoutParams(position); }
    }

    private void renderLibraryMessage() {
        SpotifyLibrary.State state = libraryState;
        if (state == null) return;
        String message = "";
        if (!state.connected) message = string(R.string.spotify_connect_hint);
        else if (!categoryPanel.shows(state.parentId)) message = "";
        else if (state.loading && state.items.isEmpty()) message = libraryScroll.getVisibility() == View.VISIBLE
                ? "" : string(R.string.spotify_loading_library);
        else if (state.failed && state.items.isEmpty()) message = state.error;
        else if (state.items.isEmpty()) message = string("playlists".equals(state.parentId) ? R.string.spotify_empty_playlists
                : "resume".equals(state.parentId) ? R.string.spotify_empty_resume : R.string.spotify_empty_recent);
        if (requestedMessage.equals(message)) return;
        requestedMessage = message;
        final String text = message;
        if (TextUtils.equals(libraryMessage.getText(), message)) {
            messageFade.cancel();
            libraryMessage.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        } else if (message.isEmpty()) {
            messageFade.cancel(); libraryMessage.setText(""); libraryMessage.setVisibility(View.GONE);
        } else if (libraryMessage.getVisibility() == View.VISIBLE) {
            messageFade.change(() -> libraryMessage.setText(text), keys.isOpen());
        } else {
            libraryMessage.setText(text); libraryMessage.setVisibility(View.VISIBLE);
            if (keys.isOpen()) messageFade.enter();
        }
    }

    private void renderConnect(SpotifyConnect.State state) {
        if (destroyed) return;
        if (state.busy) cancelSeek();
        connectState = state;
        if (devicePicker != null) devicePicker.render(state);
        renderLibraryMessage(); enableCells(); renderHeader();
        if (!state.error.isEmpty() && !state.error.equals(lastError) && keys.isOpen())
            Toast.makeText(root.getContext(), state.error, Toast.LENGTH_LONG).show();
        if (state.unconfirmedResult && state.error.isEmpty() && !state.notice.isEmpty()
                && !state.notice.equals(lastUnconfirmedNotice) && keys.isOpen()) {
            Toast.makeText(root.getContext(), state.notice, Toast.LENGTH_LONG).show();
        }
        lastUnconfirmedNotice = state.unconfirmedResult ? state.notice : "";
        lastError = state.error;
    }

    private void enableCells() {
        boolean busy = commandsBusy();
        for (Cell cell : cells) cell.view.setAlpha(busy ? .55f : 1f);
    }

    private boolean sameItems(SpotifyLibrary.State state) {
        if (!renderedCategory.equals(state.parentId) || renderedItems.size() != state.items.size()) return false;
        if (renderedItems == state.items) return true;
        for (int i = 0; i < renderedItems.size(); i++) {
            SpotifyLibrary.Item a = renderedItems.get(i), b = state.items.get(i);
            if (!a.id.equals(b.id) || !a.title.equals(b.title) || !a.subtitle.equals(b.subtitle)
                    || !a.imageUrl.equals(b.imageUrl) || a.progressMillis != b.progressMillis || a.durationMillis != b.durationMillis) return false;
        }
        renderedItems = state.items;
        return true;
    }

    private void rebuildCells(SpotifyLibrary.State state) {
        boolean hadFocus = libraryGrid.hasFocus();
        View focused = panel.findFocus();
        String focusedId = "";
        Map<String, Cell> reusable = new HashMap<>();
        for (Cell cell : cells) {
            reusable.put(cell.item.id, cell);
            if (cell.view == focused) focusedId = cell.item.id;
        }
        boolean sameOrder = cells.size() == state.items.size();
        List<Cell> nextCells = new ArrayList<>(state.items.size());
        for (int i = 0; i < state.items.size(); i++) {
            SpotifyLibrary.Item item = state.items.get(i);
            Cell cell = reusable.remove(item.id);
            if (cell == null) cell = new Cell(item); else cell.bind(item);
            if (sameOrder && cells.get(i) != cell) sameOrder = false;
            nextCells.add(cell);
        }
        for (Cell removed : reusable.values()) covers.cancel(removed.image);
        boolean changedCategory = !renderedCategory.equals(state.parentId);
        renderedCategory = state.parentId; renderedItems = state.items;
        if (!sameOrder) {
            libraryGrid.removeAllViews();
            for (int i = 0; i < nextCells.size(); i++) {
                GridLayout.LayoutParams position = new GridLayout.LayoutParams(
                        GridLayout.spec(i / COLUMNS, GridLayout.TOP), GridLayout.spec(i % COLUMNS, GridLayout.FILL));
                position.width = columnWidth; position.height = dp(54);
                position.setMargins(dp(2), dp(2), dp(2), dp(2));
                libraryGrid.addView(nextCells.get(i).view, position);
            }
        }
        cells.clear(); cells.addAll(nextCells);
        if (changedCategory) libraryScroll.scrollTo(0, 0);
        if (!sameOrder && keys.isOpen() && hadFocus) {
            Cell target = null;
            for (Cell cell : cells) if (cell.item.id.equals(focusedId)) { target = cell; break; }
            if (target != null) target.view.requestFocus();
            else if (!cells.isEmpty() && categoryPanel.shows(renderedCategory)) cells.get(0).view.requestFocus();
            else selectedCategoryControl().requestFocus();
        }
    }

    private void loadVisibleCovers() {
        if (destroyed || panel.getVisibility() != View.VISIBLE || !libraryContentVisible()) return;
        int top = libraryScroll.getScrollY() - dp(58), bottom = libraryScroll.getScrollY() + libraryScroll.getHeight() + dp(58);
        for (Cell cell : cells) if (cell.view.getBottom() >= top && cell.view.getTop() <= bottom) covers.load(cell.image, cell.item.imageUrl);
    }

    private final class Cell {
        SpotifyLibrary.Item item;
        final LinearLayout view, copy;
        final ImageView image;
        final TextView name, detail;
        private View resumeProgress;
        private ClipDrawable resumeFill;

        Cell(SpotifyLibrary.Item item) {
            view = new LinearLayout(root.getContext());
            view.setGravity(Gravity.CENTER_VERTICAL);
            image = cover(new HeaderArtwork(root.getContext(), dp(8), dp(8)), 54, 0);
            view.addView(image, new LinearLayout.LayoutParams(dp(54), -1));
            copy = new LinearLayout(root.getContext()); copy.setOrientation(LinearLayout.VERTICAL);
            copy.setPadding(0, dp(4), dp(6), dp(4));
            LinearLayout.LayoutParams copyPosition = new LinearLayout.LayoutParams(0, -2, 1); copyPosition.leftMargin = dp(7);
            view.addView(copy, copyPosition);
            name = singleLine(12, Color.WHITE); name.setMarqueeRepeatLimit(-1);
            copy.addView(name, new LinearLayout.LayoutParams(-1, -2));
            detail = singleLine(10, 0xFFC3C8CC);
            LinearLayout.LayoutParams detailPosition = new LinearLayout.LayoutParams(-1, -2); detailPosition.topMargin = dp(3);
            copy.addView(detail, detailPosition);
            configureControl(view, 8);
            view.setClipToOutline(false);
            float radius = dp(8);
            ((GradientDrawable) view.getBackground()).setCornerRadii(new float[] {radius, radius, radius, radius, radius, radius, 0, 0});
            view.setForeground(new SpotifyFocusFrame(dp(8), dp(3), GREEN));
            view.setOnFocusChangeListener((target, focused) -> {
                focusAppearance(target, focused); marquee(name, focused);
                if (focused) loadVisibleCovers();
            });
            view.setOnClickListener(v -> {
                if (!libraryContentVisible()) return;
                initialSelection = false; runCommand(() -> library.play(this.item.id));
            });
            bind(item);
        }

        void bind(SpotifyLibrary.Item next) {
            if (item != null && !item.imageUrl.equals(next.imageUrl)) covers.cancel(image);
            item = next;
            if (!TextUtils.equals(name.getText(), item.title)) name.setText(item.title);
            boolean resume = item.progressMillis > 0 && item.durationMillis > item.progressMillis;
            String line = resume ? time(item.durationMillis - item.progressMillis) + " left · " + item.subtitle : item.subtitle;
            if (!TextUtils.equals(detail.getText(), line)) detail.setText(line);
            if (resume && resumeProgress == null) {
                resumeProgress = new View(root.getContext());
                resumeFill = new ClipDrawable(background(GREEN, 1), Gravity.LEFT, ClipDrawable.HORIZONTAL);
                resumeProgress.setBackground(resumeFill);
                LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, dp(2)); position.topMargin = dp(4);
                copy.addView(resumeProgress, position);
            }
            if (resumeProgress != null) {
                resumeProgress.setVisibility(resume ? View.VISIBLE : View.GONE);
                if (resume) resumeFill.setLevel((int) Math.min(10_000, 10_000.0 * item.progressMillis / item.durationMillis));
            }
            view.setContentDescription(item.title + (item.subtitle.isEmpty() ? "" : ", " + item.subtitle));
        }
    }

    private void toggleCategory(int index) {
        initialSelection = false;
        if (libraryState == null || !libraryState.connected) return;
        lastCategoryFocus = index;
        int action = categoryPanel.click(CATEGORIES[index]);
        if (action == SpotifyCategoryPanel.CLOSE) {
            if (libraryGrid.hasFocus()) tabs[index].requestFocus();
            renderLibrary(libraryState);
        } else if (action == SpotifyCategoryPanel.OPEN || action == SpotifyCategoryPanel.REPLACE) {
            library.browse(CATEGORIES[index]);
        }
    }

    private boolean libraryContentVisible() {
        return libraryState != null && libraryState.connected && !libraryClosing && !librarySwapScheduled
                && libraryScroll.getVisibility() == View.VISIBLE && renderedCategory.equals(libraryState.parentId)
                && categoryPanel.shows(renderedCategory) && !cells.isEmpty();
    }

    private void onLibraryAction() {
        if (libraryState != null && libraryState.connected) library.refresh();
        else if (connectAction != null) connectAction.run();
        else Toast.makeText(root.getContext(), "Spotify connection setup is not available yet.", Toast.LENGTH_LONG).show();
    }

    private View selectedCategoryControl() {
        if (libraryState != null && libraryState.connected) {
            if (categoryPanel.isExpanded())
                for (int i = 0; i < tabs.length; i++) if (categoryPanel.isActive(CATEGORIES[i])) return tabs[i];
            return tabs[lastCategoryFocus];
        }
        return libraryAction;
    }

    private void styleTab(int index) {
        TextView tab = tabs[index]; if (tab == null) return;
        boolean selected = categoryPanel.isActive(CATEGORIES[index]);
        GradientDrawable background = (GradientDrawable) tab.getBackground();
        boolean active = selected && libraryState.connected;
        background.setColor(tab.isFocused() ? active ? 0xFF304B39 : 0xFF242A27 : active ? 0xFF293B2F : Color.TRANSPARENT);
        background.setStroke(tab.isFocused() ? dp(1) : 0, GREEN);
        tab.setTextColor(active || tab.isFocused() ? Color.WHITE : 0xFFC3C8CC);
        tab.setCompoundDrawableTintList(ColorStateList.valueOf(active ? GREEN : tab.isFocused() ? Color.WHITE : 0xFFC3C8CC));
        tab.setSelected(active);
        if (android.os.Build.VERSION.SDK_INT >= 30) tab.setStateDescription(active ? "Expanded" : "Collapsed");
    }

    private void moveFocus(int direction) {
        initialSelection = false;
        View current = panel.findFocus();
        View[] transport = {previous, play, next, deviceButton};
        if (current == openApp) {
            if (direction == View.FOCUS_DOWN) {
                if (progress.isEnabled()) progress.requestFocus();
                else if (deviceButton.isEnabled()) deviceButton.requestFocus();
                else libraryAction.requestFocus();
            }
            return;
        }
        if (current == progress) {
            if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) {
                cancelSeek();
                if (direction == View.FOCUS_DOWN) {
                    if (transport[lastTransport].isEnabled()) transport[lastTransport].requestFocus();
                    else focusPrimary();
                } else if (openApp.isEnabled()) openApp.requestFocus();
            }
            return;
        }
        for (int i = 0; i < transport.length; i++) if (transport[i] == current) {
            lastTransport = i;
            if (direction == View.FOCUS_DOWN) {
                if (current == deviceButton) libraryAction.requestFocus();
                else selectedCategoryControl().requestFocus();
            }
            else if (direction == View.FOCUS_UP) {
                if (progress.isEnabled()) progress.requestFocus();
                else if (openApp.isEnabled()) openApp.requestFocus();
            }
            else if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
                int step = direction == View.FOCUS_LEFT ? -1 : 1;
                for (int n = i + step; n >= 0 && n < transport.length; n += step) if (transport[n].isEnabled()) {
                    transport[n].requestFocus(); lastTransport = n; break;
                }
            }
            return;
        }
        View[] categories = {tabs[0], tabs[1], tabs[2], libraryAction};
        for (int i = 0; i < categories.length; i++) if (current == categories[i]) {
            if (direction == View.FOCUS_UP) {
                if (current == libraryAction && deviceButton.isEnabled()) { deviceButton.requestFocus(); lastTransport = 3; }
                else if (current == libraryAction && openApp.isEnabled()) openApp.requestFocus();
                else if (transport[lastTransport].isEnabled()) transport[lastTransport].requestFocus(); else focusPrimary();
            } else if (direction == View.FOCUS_DOWN && libraryContentVisible())
                cells.get(current == libraryAction ? Math.min(COLUMNS - 1, cells.size() - 1) : 0).view.requestFocus();
            else if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
                int step = direction == View.FOCUS_LEFT ? -1 : 1;
                for (int n = i + step; n >= 0 && n < categories.length; n += step) if (categories[n].isEnabled()) {
                    categories[n].requestFocus(); break;
                }
            }
            return;
        }
        int target = SpotifyFocusNavigation.target(libraryGrid.indexOfChild(current), cells.size(), COLUMNS, direction);
        if (target == SpotifyFocusNavigation.HEADER) selectedCategoryControl().requestFocus();
        else if (target >= 0) cells.get(target).view.requestFocus();
        else if (current == null || current == panel) focusPrimary();
    }

    private void runCommand(BooleanSupplier action) {
        if (commandsBusy()) return;
        seekPreview.cancel();
        boolean sent = action.getAsBoolean();
        if (sent) return;
        SpotifyConnect.State state = connect.snapshot();
        renderConnect(state);
        if (state.selectedId.isEmpty()) showDevices();
        else if (!state.authorized && connectAction != null) connectAction.run();
        else if (state.error.isEmpty()) Toast.makeText(root.getContext(), R.string.spotify_unavailable, Toast.LENGTH_LONG).show();
    }

    private void showDevices() {
        if (commandsBusy()) return;
        cancelSeek(); seekPreview.cancel();
        activationTarget.clear();
        devicePicker.show(connect.snapshot());
        connect.refreshDevices();
    }

    private boolean commandsBusy() { return connectState != null && (connectState.busy || connectState.confirmationPending); }

    private void focusPrimary() {
        if (play.isEnabled()) play.requestFocus();
        else if (libraryState != null && !libraryState.connected) libraryAction.requestFocus();
        else if (openApp.isEnabled()) openApp.requestFocus();
        else { panel.setFocusable(true); panel.requestFocus(); }
    }

    private ImageView icon(LinearLayout parent, int resource, int description) {
        ImageView button = new ImageView(root.getContext()); button.setImageResource(resource);
        button.setImageTintList(ColorStateList.valueOf(Color.WHITE)); button.setPadding(dp(9), dp(9), dp(9), dp(9));
        button.setContentDescription(string(description));
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(dp(40), dp(40)); position.rightMargin = dp(4);
        parent.addView(button, position); configureControl(button, 20); return button;
    }

    /** Small, frameless secondary action; the green glyph alone marks remote focus. */
    private ImageView bareIcon(int resource, int description) {
        ImageView button = new ImageView(root.getContext());
        button.setImageResource(resource); button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageTintList(ColorStateList.valueOf(0xFFD8DEDA));
        button.setContentDescription(string(description));
        button.setBackground(null); button.setFocusable(true); button.setClickable(true);
        button.setOnFocusChangeListener((view, focused) -> {
            ((ImageView) view).setImageTintList(ColorStateList.valueOf(focused ? GREEN : 0xFFD8DEDA));
            view.setScaleX(focused ? 1.15f : 1f); view.setScaleY(focused ? 1.15f : 1f);
            if (view == openApp && title != null) marquee(title, focused);
        });
        return button;
    }

    private ImageView cover(int size, int radius) {
        return cover(new ImageView(root.getContext()), size, radius);
    }

    private ImageView cover(ImageView image, int size, int radius) {
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(background(0xFF30343A, radius)); image.setClipToOutline(true);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); placeholder(image, size); return image;
    }

    /** Clip the whole image view, including its fallback background, on Android 26 and later. */
    private static final class HeaderArtwork extends ImageView {
        private final Path clip = new Path();
        private final RectF bounds = new RectF();
        private final float topLeftRadius, bottomRightRadius;
        HeaderArtwork(Context context, float radius) { this(context, 0, radius); }
        HeaderArtwork(Context context, float topLeftRadius, float bottomRightRadius) {
            super(context); this.topLeftRadius = topLeftRadius; this.bottomRightRadius = bottomRightRadius;
        }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            bounds.set(0, 0, width, height); clip.reset();
            clip.addRoundRect(bounds, new float[] {topLeftRadius, topLeftRadius, 0, 0, bottomRightRadius, bottomRightRadius, 0, 0}, Path.Direction.CW);
        }
        @Override public void draw(Canvas canvas) {
            int saved = canvas.save(); canvas.clipPath(clip); super.draw(canvas); canvas.restoreToCount(saved);
        }
    }

    private void placeholder(ImageView image, int size) {
        image.setPadding(dp(size / 4f), dp(size / 4f), dp(size / 4f), dp(size / 4f));
        image.setImageResource(R.drawable.ic_music_note); image.setImageTintList(ColorStateList.valueOf(0xFFB7C5BB));
    }

    private void configureControl(View button, int radius) {
        button.setFocusable(true); button.setClickable(true); button.setBackground(background(0xFF33363C, radius));
        button.setOnFocusChangeListener((view, focused) -> {
            focusAppearance(view, focused);
            boolean transportFocused = previous != null && previous.hasFocus() || play != null && play.hasFocus()
                    || next != null && next.hasFocus() || deviceButton != null && deviceButton.hasFocus() || openApp != null && openApp.hasFocus()
                    || progress != null && progress.hasFocus();
            if (title != null) marquee(title, transportFocused);
        });
    }

    private void focusAppearance(View view, boolean focused) {
        GradientDrawable background = (GradientDrawable) view.getBackground();
        if (view == progress) {
            background.setColor(Color.TRANSPARENT);
            background.setStroke(focused ? dp(1) : 0, GREEN);
            return;
        }
        background.setColor(focused ? 0xFF43664E : 0xFF33363C);
        if (view.getForeground() instanceof SpotifyFocusFrame) {
            background.setStroke(0, GREEN);
            ((SpotifyFocusFrame) view.getForeground()).setFocused(focused);
        } else background.setStroke(focused ? dp(2) : 0, GREEN);
    }

    private void marquee(TextView text, boolean focused) {
        boolean scroll = focused && !reducedMotion;
        text.setEllipsize(scroll ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END); text.setSelected(scroll);
    }

    private void enable(View control, boolean enabled) { control.setEnabled(enabled); control.setFocusable(enabled); control.setAlpha(enabled ? 1f : .35f); }
    private TextView singleLine(int size, int color) { TextView text = text(size, color); text.setSingleLine(true); text.setEllipsize(TextUtils.TruncateAt.END); return text; }
    private TextView text(int size, int color) { TextView text = new TextView(root.getContext()); text.setTextSize(size); text.setTextColor(color); text.setIncludeFontPadding(false); return text; }
    private GradientDrawable background(int color, int radius) { GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius)); return result; }
    private String time(long millis) { long seconds = Math.max(0, millis / 1000); return seconds >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    void destroy() { if (destroyed) return; pause(); destroyed = true; connectAction = null; covers.destroy(); root.removeView(panel); }
    private String string(int resource) { return root.getContext().getString(resource); }
    private int dp(float value) { return Math.round(value * density); }
}
