package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/** Local, opt-in media shelves. The companion owns all TV-provider and network access. */
final class ContinueRow {
    private static final String AUTHORITY = "com.david.tvcontinue.home";
    private static final String[] COLUMNS = {"_id", "title", "subtitle", "owner", "progress", "art_uri"};
    private static final String[] YOUTUBE_COLUMNS = {"_id", "title", "subtitle", "owner", "progress", "art_uri",
            "youtube_channel", "youtube_published_date", "youtube_published_label", "youtube_duration_millis", "youtube_view_count", "youtube_live"};
    private static final int MAX_ENTRIES = 60;
    private static final int MAX_ART_BYTES = 768 * 1024;
    private static final int MAX_BITMAP_SIDE = 512;

    private final Activity activity;
    private final boolean youtube;
    private final Uri entriesUri;
    private final Runnable focusUp, focusDown;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinearLayout root, cards;
    private final FrameLayout body;
    private final HorizontalScrollView scroll;
    private final TextView status;
    private final GlassView glass;
    private FrameLayout glassHost;
    private final Rect glassRect = new Rect();
    private final SpringAnimation springX, springY;
    private final boolean animations, reducedMotion, advancedEffects, focusOvershoot;
    private final boolean ambientGlow;
    private final float density;
    private int posterWidth, posterHeight;
    private int scrollTargetX;
    private ValueAnimator scrollAnimator;
    private Runnable focusChanged;
    private IntConsumer ambientColorChanged;
    private Card ambientCard;
    private int lastAmbientColor;
    private boolean glassShown;
    private float glassScrollShiftX, glassScrollShiftY;
    private final List<Card> shown = new ArrayList<>();
    private final Set<String> failedArt = new HashSet<>();
    private final LruCache<String, Art> artwork = new LruCache<String, Art>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Art value) { return value.bitmap.getAllocationByteCount(); }
    };
    // Query or one visible poster at a time; no unbounded executor queue or background polling.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
        Thread thread = new Thread(runnable, "continue-row");
        thread.setDaemon(true);
        return thread;
    });
    private final Runnable refresh = this::query;
    private final ContentObserver observer = new ContentObserver(ui) {
        @Override public void onChange(boolean selfChange) {
            if (!active) return;
            ui.removeCallbacks(refresh);
            ui.postDelayed(refresh, 300);
        }
    };
    private boolean active, destroyed, observing;
    private int generation;
    private Future<?> work;
    private CancellationSignal cancellation;
    private String lastId;
    private long lastArtworkInvalidation;
    private boolean resumePresentationPending;
    private int metadataLines;

    ContinueRow(Activity activity, Config config, Runnable focusUp, Runnable focusDown) {
        this(activity, config, focusUp, focusDown, false);
    }

    static ContinueRow youtube(Activity activity, Config config, Runnable focusUp, Runnable focusDown) {
        return new ContinueRow(activity, config, focusUp, focusDown, true);
    }

    private ContinueRow(Activity activity, Config config, Runnable focusUp, Runnable focusDown, boolean youtube) {
        this.activity = activity;
        this.youtube = youtube;
        entriesUri = Uri.parse("content://" + AUTHORITY + (youtube ? "/youtube_entries" : "/entries"));
        this.focusUp = focusUp;
        this.focusDown = focusDown;
        animations = config.animations;
        reducedMotion = config.reducedMotion;
        advancedEffects = animations && config.advancedEffects;
        focusOvershoot = config.focusOvershoot;
        ambientGlow = config.ambientGlow;
        density = activity.getResources().getDisplayMetrics().density;
        posterWidth = dp(180);
        posterHeight = ContinueRowGeometry.posterHeight(posterWidth);
        worker.allowCoreThreadTimeOut(true);
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusable(false);
        root.setClipChildren(false);
        if (youtube) {
            TextView heading = text("YouTube", 20, Color.WHITE);
            heading.setSingleLine(true);
            LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, dp(28));
            headingParams.leftMargin = headingParams.rightMargin = dp(16);
            root.addView(heading, headingParams);
        }
        body = new FrameLayout(activity);
        root.addView(body, new LinearLayout.LayoutParams(-1, posterHeight + dp(bodyExtraHeight())));
        scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFocusable(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setSmoothScrollingEnabled(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        cards = new LinearLayout(activity);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        cards.setFocusable(false);
        cards.setPadding(dp(12), 0, dp(12), 0);
        scroll.addView(cards, new HorizontalScrollView.LayoutParams(-2, -1));
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        status = text(youtube ? "Loading YouTube videos…" : "Loading your shows…", 14, 0xFFBFCBCB);
        status.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(status, new FrameLayout.LayoutParams(-1, -1));
        glass = new GlassView(activity, null);
        glass.setStyle(config.glassStyle);
        glass.setFocusable(false);
        glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        glass.setAlpha(0f);
        glassHost = body;
        body.addView(glass, new FrameLayout.LayoutParams(1, 1));
        springX = new SpringAnimation(glass, new FloatPropertyCompat<View>("continueGlassContentX") {
            @Override public float getValue(View view) { return view.getTranslationX() - glassScrollShiftX; }
            @Override public void setValue(View view, float value) { view.setTranslationX(value + glassScrollShiftX); }
        });
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("continueGlassContentY") {
            @Override public float getValue(View view) { return view.getTranslationY() - glassScrollShiftY; }
            @Override public void setValue(View view, float value) { view.setTranslationY(value + glassScrollShiftY); }
        });
        for (SpringAnimation spring : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) Motion.reduce(spring);
            else if (!focusOvershoot) spring.setSpring(new SpringForce()
                    .setStiffness(advancedEffects ? 520f : 440f).setDampingRatio(1f));
            else spring.setSpring(new SpringForce().setStiffness(advancedEffects ? 320f : 260f)
                    .setDampingRatio(advancedEffects ? .76f : .58f));
        }
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (glassShown) {
                int dx = x - oldX;
                glassScrollShiftX -= dx;
                glass.setTranslationX(glass.getTranslationX() - dx);
            }
            updateArtwork();
        });
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            resize(r - l);
            updateArtwork();
        });
        if (youtube) {
            status.setFocusable(true);
            status.setRevealOnFocusHint(false);
            status.setPadding(dp(16), 0, dp(16), 0);
            status.setOnFocusChangeListener((view, focused) -> {
                status.setTextColor(focused ? Color.WHITE : 0xFFBFCBCB);
                if (focused) {
                    hideGlass(true);
                    if (focusChanged != null) focusChanged.run();
                }
            });
            status.setOnKeyListener((view, code, event) -> {
                if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN
                        && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (code == KeyEvent.KEYCODE_DPAD_UP) focusUp.run();
                    else if (code == KeyEvent.KEYCODE_DPAD_DOWN) focusDown.run();
                }
                return true;
            });
        }
    }

    View view() { return root; }

    /** Share the Home overlay so the moving highlight is not clipped by this row's poster viewport. */
    void attachGlass(FrameLayout overlay) {
        if (destroyed || glassHost == overlay) return;
        hideGlass(true);
        glassHost.removeView(glass);
        glassHost = overlay;
        glassScrollShiftX = glassScrollShiftY = 0f;
        glassHost.addView(glass, new FrameLayout.LayoutParams(1, 1));
        for (Card card : shown) if (card.view.hasFocus()) {
            showGlass(card, false);
            break;
        }
    }

    /** Vertical scrolling stays outside the spring coordinates, just like the horizontal carousel. */
    void onViewportScroll(int dy) {
        if (glassHost == body) return;
        // Keep following the poster throughout its fade-out when focus moves to the app rows.
        glassScrollShiftY -= dy;
        glass.setTranslationY(glass.getTranslationY() - dy);
    }

    /** Lets the containing home scroll reveal this shelf without owning its horizontal position. */
    void setOnFocusChanged(Runnable listener) { focusChanged = listener; }

    /** Artwork is sampled off-thread; only the currently focused poster can tint the shared backdrop. */
    void setOnAmbientColorChanged(IntConsumer listener) { ambientColorChanged = listener; }

    /** Re-publish cached artwork after the Activity regains window focus, without moving selection. */
    void refreshAmbient() {
        ambientCard = null;
        for (Card card : shown) if (card.view.hasFocus()) {
            notifyAmbient(card);
            break;
        }
    }

    private void notifyAmbient(Card card) {
        if (ambientColorChanged == null || !active || destroyed || !card.view.isShown()
                || !card.view.hasFocus() || !activity.hasWindowFocus() || !shown.contains(card)) return;
        int color = card.art == null ? 0xFF444444 : card.art.color;
        if (ambientCard == card && lastAmbientColor == color) return;
        ambientCard = card;
        lastAmbientColor = color;
        ambientColorChanged.accept(color);
    }

    void resume() {
        if (destroyed || active) return;
        active = true;
        long now = SystemClock.elapsedRealtime();
        if (now - lastArtworkInvalidation >= 60000) {
            artwork.evictAll();
            lastArtworkInvalidation = now;
            for (Card card : shown) card.retained.invalidate();
        }
        try {
            activity.getContentResolver().registerContentObserver(entriesUri, true, observer);
            observing = true;
        } catch (RuntimeException ignored) {
            // Query below supplies the same concise unavailable state if the companion is missing.
        }
        query();
    }

    void pause() {
        active = false;
        ambientCard = null;
        resumePresentationPending = true;
        generation++;
        ui.removeCallbacks(refresh);
        cancelWork();
        moveScroll(scrollTargetX, false);
        hideGlass(true);
        for (Card card : shown) card.poster.animate().cancel();
        if (observing) {
            try { activity.getContentResolver().unregisterContentObserver(observer); }
            catch (RuntimeException ignored) { /* The companion may have been replaced while paused. */ }
            observing = false;
        }
    }

    void destroy() {
        if (destroyed) return;
        pause();
        destroyed = true;
        worker.shutdownNow();
        for (Card card : shown) card.image.setImageDrawable(null);
        artwork.evictAll();
        shown.clear();
        cards.removeAllViews();
        if (glassHost != body) glassHost.removeView(glass);
    }

    boolean hasEntries() { return !shown.isEmpty(); }

    /** YouTube remains reachable while its local catalogue is loading or empty. */
    boolean focusEntry() {
        if (!youtube || !shown.isEmpty()) return focusLast();
        return !destroyed && root.isShown() && status.getVisibility() == View.VISIBLE && status.requestFocus();
    }

    boolean focusFirst() {
        return !destroyed && !shown.isEmpty() && root.isShown() && shown.get(0).view.requestFocus();
    }

    /** The actual first programme, not the left edge of a scrolled viewport. */
    boolean isFirstItemFocused() { return !shown.isEmpty() && shown.get(0).view.hasFocus(); }

    /** Return to the last focused programme, not the last item in the horizontal list. */
    boolean focusLast() {
        if (destroyed || shown.isEmpty() || !root.isShown()) return false;
        for (Card card : shown) if (card.entry.id.equals(lastId)) return card.view.requestFocus();
        return shown.get(0).view.requestFocus();
    }

    private void cancelWork() {
        if (cancellation != null) cancellation.cancel();
        if (work != null) work.cancel(true);
        cancellation = null;
        work = null;
        worker.purge();
    }

    private void query() {
        if (!active || destroyed) return;
        final int token = ++generation;
        cancelWork();
        final CancellationSignal signal = new CancellationSignal();
        cancellation = signal;
        work = worker.submit(() -> {
            List<Entry> entries = new ArrayList<>();
            boolean success = false;
            try (Cursor cursor = queryEntries(signal)) {
                if (cursor == null) throw new IOException("No local catalogue");
                int id = cursor.getColumnIndexOrThrow("_id"), title = cursor.getColumnIndexOrThrow("title");
                int subtitle = cursor.getColumnIndexOrThrow("subtitle");
                int progress = cursor.getColumnIndexOrThrow("progress"), art = cursor.getColumnIndexOrThrow("art_uri");
                Set<String> seen = new HashSet<>();
                while (entries.size() < MAX_ENTRIES && cursor.moveToNext()) {
                    signal.throwIfCanceled();
                    String key = cursor.getString(id);
                    if (key == null || !key.matches("[a-f0-9]{64}") || !seen.add(key)) continue;
                    entries.add(new Entry(key, clean(cursor.getString(title), youtube ? "YouTube video" : "Continue watching", 160),
                            clean(cursor.getString(subtitle), "", 120),
                            youtube || cursor.isNull(progress) ? -1 : Math.max(-1, Math.min(1000, cursor.getInt(progress))),
                            localArt(cursor.getString(art), key), youtube ? readVideoMetadata(cursor) : YouTubeCardMetadata.EMPTY));
                }
                success = true;
            } catch (IOException | RuntimeException ignored) {
                // Provider/permission failures are handled on the row, never as technical UI details.
            }
            final boolean loaded = success;
            ui.post(() -> {
                if (!active || destroyed || token != generation) return;
                work = null;
                cancellation = null;
                if (!loaded) entries.clear();
                render(entries, loaded);
            });
        });
    }

    private Cursor queryEntries(CancellationSignal signal) {
        if (youtube) {
            try {
                return activity.getContentResolver().query(entriesUri, YOUTUBE_COLUMNS, null, null, null, signal);
            } catch (IllegalArgumentException olderCompanion) {
                // A rollback or a companion updated after Home must still show the existing video shelf.
                signal.throwIfCanceled();
            }
        }
        return activity.getContentResolver().query(entriesUri, COLUMNS, null, null, null, signal);
    }

    private YouTubeCardMetadata readVideoMetadata(Cursor cursor) {
        int channel = cursor.getColumnIndex("youtube_channel"), date = cursor.getColumnIndex("youtube_published_date");
        int published = cursor.getColumnIndex("youtube_published_label");
        return new YouTubeCardMetadata(channel < 0 ? "" : cursor.getString(channel), date < 0 ? "" : cursor.getString(date),
                published < 0 ? "" : cursor.getString(published),
                optionalNumber(cursor, "youtube_duration_millis"), optionalNumber(cursor, "youtube_view_count"),
                optionalNumber(cursor, "youtube_live") == 1);
    }

    private static long optionalNumber(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? -1 : cursor.getLong(index);
    }

    private int bodyExtraHeight() { return youtube ? 60 + metadataLines * 12 : 74; }

    private void render(List<Entry> entries, boolean loaded) {
        // Only restore focus if this row owned it at refresh time. New data must not pull it from apps.
        boolean ownedFocus = root.hasFocus() && activity.hasWindowFocus();
        int oldScroll = scrollTargetX;
        if (!loaded && !shown.isEmpty()) {
            updateArtwork();
            return; // Keep usable cards during a transient read failure.
        }
        // Reconcile by programme identity. Progress, title and artwork updates must not recreate
        // the whole row or detach unchanged cards (which also loses their focus and decoded images).
        boolean sameOrder = entries.size() == shown.size();
        Map<String, Card> previous = new HashMap<>();
        for (Card card : shown) previous.put(card.entry.id, card);
        List<Card> next = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (sameOrder && !shown.get(i).entry.id.equals(entry.id)) sameOrder = false;
            Card card = previous.remove(entry.id);
            if (card == null) card = new Card(entry);
            else card.bind(entry);
            next.add(card);
        }
        if (youtube) {
            int lines = 0;
            for (Entry entry : entries) lines = Math.max(lines, entry.metadata.lines(Locale.getDefault()).length);
            if (metadataLines != lines) {
                metadataLines = lines;
                ViewGroup.LayoutParams bodyParams = body.getLayoutParams();
                bodyParams.height = posterHeight + dp(bodyExtraHeight());
                body.setLayoutParams(bodyParams);
            }
        }
        if (!sameOrder) {
            cancelScroll();
            for (Card card : shown) card.poster.animate().cancel();
            cards.removeAllViews();
            shown.clear();
            shown.addAll(next);
            for (Card card : shown) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(posterWidth + 2 * dp(4), -1);
                params.setMarginEnd(dp(12));
                cards.addView(card.view, params);
            }
            for (Card removed : previous.values()) {
                removed.retained.release();
                removed.art = null;
                removed.image.setImageDrawable(null);
            }
        }
        failedArt.clear();
        for (Card card : shown) {
            if (resumePresentationPending) {
                float scale = card.view.hasFocus() ? focusScale() : 1f;
                card.poster.setScaleX(scale);
                card.poster.setScaleY(scale);
                card.poster.setTranslationY(card.view.hasFocus() && advancedEffects ? -4f * density : 0f);
            }
            if (card.view.hasFocus()) {
                if (!glassShown) showGlass(card, false);
                notifyAmbient(card);
            }
        }
        resumePresentationPending = false;
        if (sameOrder && !shown.isEmpty()) {
            updateArtwork();
            return;
        }
        status.setText(youtube
                ? loaded ? "YouTube videos will appear here." : "YouTube videos are unavailable right now."
                : loaded ? "Your shows will appear here." : "Continue watching is unavailable right now.");
        status.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        scroll.setVisibility(shown.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        if (ownedFocus) {
            if (!focusEntry()) focusDown.run();
        }
        final int renderedGeneration = generation;
        scroll.post(() -> {
            if (!active || destroyed || renderedGeneration != generation) return;
            if (!root.hasFocus()) moveScroll(oldScroll, false);
            else for (Card card : shown) if (card.view.hasFocus()) {
                reveal(card.view, false);
                showGlass(card, false);
            }
            updateArtwork();
        });
    }

    /** Drop offscreen view references; request only the viewport plus one neighbouring card. */
    private void updateArtwork() {
        if (!active || destroyed || scroll.getWidth() <= 0) return;
        int buffer = posterWidth + 2 * dp(4) + dp(12), left = scroll.getScrollX() - buffer;
        int right = scroll.getScrollX() + scroll.getWidth() + buffer;
        String next = null;
        for (Card card : shown) {
            boolean nearby = card.view.getRight() > left && card.view.getLeft() < right;
            String uri = card.entry.art;
            if (!nearby) card.retained.release();
            else if (card.retained.needsLoad()) card.retained.accept(uri, artwork.get(uri));
            // Even after invalidation or a changed URI, keep the last good visible bitmap until
            // the replacement arrives. Eviction only drops cards outside the buffered viewport.
            Art art = card.retained.value();
            if (card.art != art) {
                card.art = art;
                card.image.setImageBitmap(art == null ? null : art.bitmap);
                card.placeholder.setVisibility(art == null ? View.VISIBLE : View.GONE);
                notifyAmbient(card);
            }
            if (nearby && card.retained.needsLoad() && !failedArt.contains(uri) && next == null) next = uri;
        }
        if (next == null || work != null) return;
        final String uri = next;
        final int token = generation;
        final CancellationSignal signal = new CancellationSignal();
        cancellation = signal;
        work = worker.submit(() -> {
            Bitmap bitmap = readArtwork(Uri.parse(uri), signal);
            Art art = bitmap == null ? null : new Art(bitmap, ambientGlow
                    ? AppAdapter.artworkColor(new BitmapDrawable(activity.getResources(), bitmap), false)
                    : 0xFF444444);
            ui.post(() -> {
                if (!active || destroyed || token != generation) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                work = null;
                cancellation = null;
                if (bitmap == null) failedArt.add(uri);
                else {
                    artwork.put(uri, art);
                    // A bitmap larger than the LRU budget can be evicted immediately; publish it
                    // directly to its current card too, avoiding an endless decode loop.
                    for (Card card : shown) card.retained.accept(uri, art);
                }
                updateArtwork();
            });
        });
    }

    private Bitmap readArtwork(Uri uri, CancellationSignal signal) {
        try (AssetFileDescriptor file = activity.getContentResolver().openAssetFileDescriptor(uri, "r", signal)) {
            if (file == null || file.getLength() > MAX_ART_BYTES) return null;
            byte[] data;
            try (InputStream input = file.createInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192];
                int length;
                while ((length = input.read(block)) >= 0) {
                    signal.throwIfCanceled();
                    if (output.size() + length > MAX_ART_BYTES) return null;
                    output.write(block, 0, length);
                }
                data = output.toByteArray();
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 4096 || options.outHeight > 4096) return null;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > MAX_BITMAP_SIDE)
                options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            signal.throwIfCanceled();
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private final class Card {
        Entry entry;
        final LinearLayout view;
        final FrameLayout poster;
        final ImageView image;
        final TextView placeholder, title, subtitle, videoMetadata, videoDetails, videoBadge;
        final ProgressBar progress;
        final RetainedArtwork<Art> retained = new RetainedArtwork<>();
        Art art;

        Card(Entry initial) {
            entry = initial;
            view = new LinearLayout(activity);
            view.setId(View.generateViewId());
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(dp(4), dp(16), dp(4), 0);
            view.setFocusable(true);
            view.setClickable(true);
            // Our four-card reveal policy owns scrolling, including deferred focus during layout.
            view.setRevealOnFocusHint(false);
            view.setClipChildren(false);
            // Padding reserves room for zoom and glow; it must not cut the scaled artwork into a square.
            view.setClipToPadding(false);
            view.setContentDescription(entry.title + (entry.subtitle.isEmpty() ? "" : ", " + entry.subtitle));
            poster = new FrameLayout(activity);
            poster.setBackground(surface(0xFF171E22, 0));
            poster.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            AppAdapter.cornerRadius(view.getWidth(), density));
                }
            });
            poster.setClipToOutline(true);
            view.addView(poster, new LinearLayout.LayoutParams(posterWidth, posterHeight));
            image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            poster.addView(image, new FrameLayout.LayoutParams(-1, -1));
            String appLabel = entry.subtitle.split("·", 2)[0].trim();
            placeholder = text(appLabel.isEmpty() ? (youtube ? "YouTube" : "Continue watching") : appLabel, 12, 0xFFBFCBCB);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setMaxLines(3);
            placeholder.setPadding(dp(8), dp(8), dp(8), dp(8));
            poster.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            if (youtube) {
                videoBadge = text("", 10, Color.WHITE);
                videoBadge.setSingleLine(true);
                videoBadge.setGravity(Gravity.CENTER);
                videoBadge.setPadding(dp(4), dp(2), dp(4), dp(2));
                videoBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                GradientDrawable badgeBackground = new GradientDrawable();
                badgeBackground.setCornerRadius(dp(3));
                videoBadge.setBackground(badgeBackground);
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
                badgeParams.rightMargin = badgeParams.bottomMargin = dp(7);
                poster.addView(videoBadge, badgeParams);
            } else videoBadge = null;
            if (!youtube) {
                progress = new PlaybackProgress(activity, density);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM);
                params.leftMargin = params.rightMargin = dp(12);
                params.bottomMargin = dp(5);
                poster.addView(progress, params);
            } else progress = null;
            title = text(entry.title, 14, Color.WHITE);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, dp(34));
            titleParams.topMargin = dp(5);
            view.addView(title, titleParams);
            if (!youtube) {
                subtitle = text(entry.subtitle, 11, 0xFFBFCBCB);
                subtitle.setSingleLine(true);
                subtitle.setEllipsize(TextUtils.TruncateAt.END);
                view.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(16)));
                videoMetadata = videoDetails = null;
            } else {
                subtitle = null;
                videoMetadata = metadataText();
                view.addView(videoMetadata, new LinearLayout.LayoutParams(-1, -2));
                videoDetails = metadataText();
                view.addView(videoDetails, new LinearLayout.LayoutParams(-1, -2));
            }
            bind(entry);
            view.setOnFocusChangeListener((v, focused) -> {
                animatePoster(poster, focused);
                if (focused) {
                    lastId = entry.id;
                    reveal(view);
                    showGlass(this, true);
                    updateArtwork();
                    if (focusChanged != null) focusChanged.run();
                    notifyAmbient(this);
                } else {
                    if (ambientCard == this) ambientCard = null;
                    ui.post(() -> { if (!root.hasFocus()) hideGlass(false); });
                }
            });
            view.setOnKeyListener((v, code, event) -> {
                if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN
                        && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (code == KeyEvent.KEYCODE_DPAD_UP) focusUp.run();
                    else if (code == KeyEvent.KEYCODE_DPAD_DOWN) focusDown.run();
                    else {
                        int index = shown.indexOf(this) + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                        if (index >= 0 && index < shown.size()) shown.get(index).view.requestFocus();
                    }
                }
                return true; // At either horizontal edge, focus remains in this row.
            });
            view.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent().setComponent(new ComponentName("com.david.tvcontinue",
                            "com.david.tvcontinue.OpenEntryActivity")).putExtra("entry_key", entry.id);
                    if (youtube) intent.putExtra("shelf", "youtube");
                    activity.startActivity(intent);
                } catch (RuntimeException ignored) {
                    Toast.makeText(activity, youtube ? "This video is unavailable right now."
                            : "This show is unavailable right now.", Toast.LENGTH_SHORT).show();
                }
            });
        }

        void bind(Entry next) {
            entry = next;
            retained.source(next.art);
            if (!TextUtils.equals(title.getText(), next.title)) title.setText(next.title);
            if (subtitle != null && !TextUtils.equals(subtitle.getText(), next.subtitle)) subtitle.setText(next.subtitle);
            if (videoMetadata != null) {
                String[] lines = next.metadata.lines(Locale.getDefault());
                String first = lines.length == 0 ? "" : lines[0], second = lines.length < 2 ? "" : lines[1];
                if (!TextUtils.equals(videoMetadata.getText(), first)) videoMetadata.setText(first);
                videoMetadata.setVisibility(first.isEmpty() ? View.GONE : View.VISIBLE);
                if (!TextUtils.equals(videoDetails.getText(), second)) videoDetails.setText(second);
                videoDetails.setVisibility(second.isEmpty() ? View.GONE : View.VISIBLE);
                String badge = next.metadata.badge();
                if (!TextUtils.equals(videoBadge.getText(), badge)) videoBadge.setText(badge);
                videoBadge.setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);
                ((GradientDrawable) videoBadge.getBackground()).setColor(next.metadata.live ? 0xFFE32636 : 0xE6000000);
            }
            String label = next.subtitle.split("·", 2)[0].trim();
            String fallback = label.isEmpty() ? (youtube ? "YouTube" : "Continue watching") : label;
            if (!TextUtils.equals(placeholder.getText(), fallback)) placeholder.setText(fallback);
            String details = youtube ? next.metadata.accessibility(Locale.getDefault()) : next.subtitle;
            view.setContentDescription(next.title + (details.isEmpty() ? "" : ", " + details));
            if (progress != null) {
                progress.setVisibility(next.progress < 0 ? View.GONE : View.VISIBLE);
                progress.setProgress(Math.max(0, next.progress));
            }
        }
    }

    /** The dark edge keeps the light segment distinct even over a white poster. */
    private static final class PlaybackProgress extends ProgressBar {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float edge, minimumFill;

        PlaybackProgress(Context context, float density) {
            super(context, null, android.R.attr.progressBarStyleHorizontal);
            setMax(1000);
            edge = .75f * density;
            minimumFill = 5f * density;
        }

        @Override protected synchronized void onDraw(Canvas canvas) {
            float width = getWidth(), height = getHeight();
            float radius = height / 2f;
            paint.setColor(0xFF26282D);
            canvas.drawRoundRect(0, 0, width, height, radius, radius, paint);

            int value = getProgress();
            float available = width - 2 * edge;
            if (value <= 0 || available <= 0 || height <= 2 * edge) return;
            // Give tiny positive values a visible pill without changing the stored progress.
            float fill = Math.min(available, Math.max(minimumFill, available * value / getMax()));
            float left = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? width - edge - fill : edge;
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(left, edge, left + fill, height - edge, radius - edge, radius - edge, paint);
        }
    }

    private static final class Art {
        final Bitmap bitmap;
        final int color;
        Art(Bitmap bitmap, int color) { this.bitmap = bitmap; this.color = color; }
    }

    private TextView metadataText() {
        TextView text = text("", 10, 0xFFBFCBCB);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setIncludeFontPadding(false);
        return text;
    }

    private static final class Entry {
        final String id, title, subtitle, art;
        final int progress;
        final YouTubeCardMetadata metadata;
        Entry(String id, String title, String subtitle, int progress, String art, YouTubeCardMetadata metadata) {
            this.id = id; this.title = title; this.subtitle = subtitle;
            this.progress = progress; this.art = art;
            this.metadata = metadata;
        }
    }

    private void reveal(View view) { reveal(view, true); }

    private void reveal(View view, boolean animate) {
        int index = cards.indexOfChild(view);
        int maximum = Math.max(0, cards.getWidth() - scroll.getWidth());
        // Base a repeat press on the intended window, not an intermediate animation frame.
        int target = ContinueRowGeometry.scrollFor(index, scrollTargetX, posterWidth + 2 * dp(4) + dp(12), maximum);
        moveScroll(target, animate);
    }

    private void cancelScroll() {
        if (scrollAnimator == null) return;
        scrollAnimator.removeAllUpdateListeners();
        scrollAnimator.cancel();
        scrollAnimator = null;
    }

    private void moveScroll(int target, boolean animate) {
        int maximum = Math.max(0, cards.getWidth() - scroll.getWidth());
        target = Math.max(0, Math.min(target, maximum));
        if (animate && target == scrollTargetX && scrollAnimator != null) return;
        cancelScroll();
        scrollTargetX = target;
        if (!animate || !animations || reducedMotion || scroll.getScrollX() == target) {
            scroll.scrollTo(target, 0);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(scroll.getScrollX(), target);
        scrollAnimator = animator;
        animator.setDuration(240);
        animator.setInterpolator(new DecelerateInterpolator(2f));
        animator.addUpdateListener(value -> scroll.scrollTo((int) value.getAnimatedValue(), 0));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (scrollAnimator == animation) scrollAnimator = null;
            }
        });
        animator.start();
    }

    private void resize(int viewport) {
        if (viewport <= 0) return;
        int width = ContinueRowGeometry.posterWidth(viewport, dp(12), dp(4), dp(12));
        if (width == posterWidth) return;
        posterWidth = width;
        posterHeight = ContinueRowGeometry.posterHeight(width);
        cancelScroll();
        ViewGroup.LayoutParams bodyParams = body.getLayoutParams();
        bodyParams.height = posterHeight + dp(bodyExtraHeight());
        body.setLayoutParams(bodyParams);
        for (Card card : shown) {
            ViewGroup.LayoutParams params = card.view.getLayoutParams();
            params.width = posterWidth + 2 * dp(4);
            card.view.setLayoutParams(params);
            ViewGroup.LayoutParams posterParams = card.poster.getLayoutParams();
            posterParams.width = posterWidth;
            posterParams.height = posterHeight;
            card.poster.setLayoutParams(posterParams);
            card.poster.setBackground(surface(0xFF171E22, 0));
            card.poster.invalidateOutline();
        }
        scroll.post(() -> {
            if (!active || destroyed) return;
            boolean focused = false;
            for (Card card : shown) if (card.view.hasFocus()) {
                focused = true;
                reveal(card.view, false);
                showGlass(card, false);
            }
            if (!focused) moveScroll(scrollTargetX, false);
            updateArtwork();
        });
    }

    private float focusScale() { return !animations ? 1f : advancedEffects ? 1.12f : 1.10f; }

    /** Same scale, lift, easing and reduced-motion rules as the application's AppAdapter tiles. */
    private void animatePoster(View poster, boolean focused) {
        poster.animate().cancel();
        float scale = focused ? focusScale() : 1f;
        float lift = focused && advancedEffects ? -4f * density : 0f;
        if (!animations) {
            poster.setScaleX(1f); poster.setScaleY(1f); poster.setTranslationY(0f);
        } else if (reducedMotion) {
            poster.animate().scaleX(scale).scaleY(scale).translationY(lift)
                    .setDuration(focused ? Motion.REDUCED_MS : 60)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            poster.animate().scaleX(scale).scaleY(scale).translationY(lift)
                    .setDuration(advancedEffects ? (focused ? 220 : 170) : (focused ? 260 : 180))
                    .setInterpolator(focused && !focusOvershoot ? new DecelerateInterpolator(1.5f)
                            : new OvershootInterpolator(focused ? (advancedEffects ? .7f : 1.6f) : 0f))
                    .withLayer().start();
        }
    }

    /** One shared highlight moves between posters; scroll offsets stay outside the spring's coordinates. */
    private void showGlass(Card card, boolean animate) {
        if (card.poster.getWidth() <= 0 || card.poster.getHeight() <= 0) return;
        glass.bringToFront();
        float scale = focusScale(), pad = 6f * density;
        float width = card.poster.getWidth(), height = card.poster.getHeight();
        glassRect.set(0, 0, card.poster.getWidth(), card.poster.getHeight());
        glassHost.offsetDescendantRectToMyCoords(card.poster, glassRect);
        float x = glassRect.left - (width * scale - width) / 2f - pad;
        float y = glassRect.top - (height * scale - height) / 2f - pad - (advancedEffects ? 4f * density : 0f);
        ViewGroup.LayoutParams params = glass.getLayoutParams();
        int desiredWidth = Math.round(width * scale + 2 * pad), desiredHeight = Math.round(height * scale + 2 * pad);
        if (params.width != desiredWidth || params.height != desiredHeight) {
            params.width = desiredWidth;
            params.height = desiredHeight;
            glass.setLayoutParams(params);
        }
        glass.setRadius(AppAdapter.cornerRadius(card.poster.getWidth(), density) * scale);
        glass.animate().cancel();
        if (!glassShown || !animations || !animate) {
            springX.cancel(); springY.cancel();
            glass.setTranslationX(x); glass.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x - glassScrollShiftX);
            springY.animateToFinalPosition(y - glassScrollShiftY);
        }
        if (!glassShown && animations && !reducedMotion) glass.animate().alpha(1f).setDuration(180).start();
        else glass.setAlpha(1f);
        glassShown = true;
    }

    private void hideGlass(boolean immediate) {
        if (!glassShown && glass.getAlpha() == 0f) return;
        glassShown = false;
        springX.cancel(); springY.cancel();
        glass.animate().cancel();
        if (!immediate && animations && !reducedMotion) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    private String localArt(String value, String entryKey) {
        if (value == null || value.length() > 256) return null;
        Uri uri = Uri.parse(value);
        List<String> path = uri.getPathSegments();
        return "content".equals(uri.getScheme()) && AUTHORITY.equals(uri.getAuthority())
                && uri.getQuery() == null && uri.getFragment() == null && path.size() == 3
                && (youtube ? "youtube_art" : "art").equals(path.get(0)) && entryKey.equals(path.get(1))
                && path.get(2).matches("[a-f0-9]{64}") ? value : null;
    }

    private static String clean(String value, String fallback, int limit) {
        if (value == null) return fallback;
        String result = value.replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", " ").trim();
        if (result.isEmpty()) return fallback;
        return result.length() <= limit ? result : result.substring(0, limit);
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        text.setFocusable(false);
        return text;
    }

    private GradientDrawable surface(int color, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(AppAdapter.cornerRadius(posterWidth, density));
        if (stroke != 0) drawable.setStroke(dp(2), stroke);
        return drawable;
    }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
