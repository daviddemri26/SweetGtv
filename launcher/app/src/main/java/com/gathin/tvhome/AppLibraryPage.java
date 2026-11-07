package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/** A real content page below the shared header. Only app actions use a small anchored menu. */
final class AppLibraryPage implements AppAdapter.TileListener {
    private static final int COLUMNS = 4;
    private final Activity activity;
    private final Config config;
    private final Predicate<String> onHome;
    private final Runnable changed, focusHeader;
    private final float density;
    private final boolean animations, reducedMotion, advancedEffects;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout root;
    private final FrameLayout viewport;
    private final TextView count, status;
    private final RecyclerView grid;
    private final GridLayoutManager layout;
    private final Catalog adapter = new Catalog();
    private final GlassView glass;
    private FrameLayout glassHost;
    private final SpringAnimation springX, springY;
    private final ThreadPoolExecutor loader = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> {
        Thread thread = new Thread(task, "app-library-page");
        thread.setDaemon(true);
        return thread;
    });
    private final LruCache<String, Art> artwork = new LruCache<String, Art>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Art value) { return value.bitmap.getAllocationByteCount(); }
    };
    private final Set<String> loadingArtwork = new HashSet<>();
    private List<Entry> apps = new ArrayList<>();
    private Future<?> catalogueWork;
    private final Runnable refreshCatalogue = this::refresh;
    private boolean catalogueLoaded;
    private int generation, selectedPosition, pendingPosition = -1;
    private String selectedPackage;
    private boolean active, destroyed, focusPending, restoreOnResume, glassShown;
    private float scrollShift;
    private float pageAlpha = 1f;
    private int tileWidth, tileHeight;
    private PopupWindow actionPopup;
    private IntConsumer ambientColorChanged;

    AppLibraryPage(Activity activity, Config config, Predicate<String> onHome,
                   Runnable changed, Runnable focusHeader) {
        this.activity = activity;
        this.config = config;
        this.onHome = onHome;
        this.changed = changed;
        this.focusHeader = focusHeader;
        density = activity.getResources().getDisplayMetrics().density;
        animations = config.animations;
        reducedMotion = config.reducedMotion;
        advancedEffects = animations && config.advancedEffects;
        tileWidth = dp(180);
        tileHeight = AppAdapter.tileHeightPx(tileWidth);
        loader.allowCoreThreadTimeOut(true);
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusable(false);
        root.setClipChildren(false);
        // Reserve the header caption's space even while the grid scrolls underneath it.
        root.setPadding(0, dp(16), 0, 0);
        // The count lives in the shared header, leaving the same content origin as Home.
        count = text("", 13, 0xFFBFCBCB);
        count.setIncludeFontPadding(false);
        count.setFocusable(false);
        viewport = new FrameLayout(activity);
        viewport.setClipChildren(true);
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        grid = new RecyclerView(activity);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        // Net 8dp lower than Home posters: the enlarged focus frame stays near their top edge.
        grid.setPadding(dp(10), dp(12), dp(10), dp(24));
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setItemAnimator(null);
        grid.setHasFixedSize(true);
        grid.setFocusable(false);
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        layout = new GridLayoutManager(activity, COLUMNS) {
            @Override public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent,
                    @NonNull View child, @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
                // The page reveals the zoomed tile and its label together with one smooth movement.
                return false;
            }
        };
        grid.setLayoutManager(layout);
        grid.setAdapter(adapter);
        viewport.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        status = text("Loading apps…", 16, 0xFFBFCBCB);
        status.setGravity(Gravity.CENTER);
        status.setFocusable(true);
        status.setOnKeyListener((v, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_UP) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                focusPending = false;
                focusHeader.run();
            }
            return true;
        });
        viewport.addView(status, new FrameLayout.LayoutParams(-1, -1));
        glass = new GlassView(activity, null);
        glass.setStyle(config.glassStyle);
        glass.setAlpha(0f);
        glass.setFocusable(false);
        glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        glassHost = viewport;
        viewport.addView(glass, new FrameLayout.LayoutParams(1, 1));
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("libraryPageContentY") {
            @Override public float getValue(View view) { return view.getTranslationY() - scrollShift; }
            @Override public void setValue(View view, float value) { view.setTranslationY(value + scrollShift); }
        });
        for (SpringAnimation spring : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) Motion.reduce(spring);
            else spring.setSpring(new SpringForce()
                    .setStiffness(config.focusOvershoot ? (advancedEffects ? 320f : 260f) : 440f)
                    .setDampingRatio(config.focusOvershoot ? (advancedEffects ? .76f : .58f) : 1f));
        }
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                scrollShift -= dy;
                if (glassShown) glass.setTranslationY(glass.getTranslationY() - dy);
                if (pendingPosition >= 0) tryPendingFocus();
            }
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) tryPendingFocus();
            }
        });
        grid.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = AppAdapter.tileWidthPx(right - left - grid.getPaddingLeft() - grid.getPaddingRight(), COLUMNS, density);
            if (width != tileWidth) {
                tileWidth = width;
                tileHeight = AppAdapter.tileHeightPx(width);
                grid.post(() -> { if (!destroyed) adapter.notifyDataSetChanged(); });
            }
            if (focusPending || pendingPosition >= 0) grid.post(this::tryPendingFocus);
        });
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (active && !root.hasFocus() && actionPopup == null) {
                focusPending = false;
                pendingPosition = -1;
                hideGlass();
            }
        });
    }

    View view() { return root; }

    /** Use the shared content gutter for spring overshoot while the grid keeps its own viewport. */
    void attachGlass(FrameLayout overlay) {
        if (destroyed || glassHost == overlay) return;
        hideGlass();
        glassHost.removeView(glass);
        glassHost = overlay;
        scrollShift = 0f;
        glassHost.addView(glass, new FrameLayout.LayoutParams(1, 1));
        if (active && root.hasFocus()) {
            View focus = activity.getCurrentFocus();
            RecyclerView.ViewHolder holder = focus == null ? null : grid.findContainingViewHolder(focus);
            if (holder instanceof AppAdapter.Holder) {
                onTileFocused(holder.getBindingAdapterPosition(), ((AppAdapter.Holder) holder).card);
            }
        }
    }

    TextView countView() { return count; }

    void setPageAlpha(float alpha) {
        pageAlpha = alpha;
        glass.setAlpha(glassShown ? pageAlpha : 0f);
    }

    void setOnAmbientColorChanged(IntConsumer listener) { ambientColorChanged = listener; }

    /** Window focus can return after the focused tile and its artwork were already restored. */
    void refreshAmbient() {
        View focus = activity.getCurrentFocus();
        RecyclerView.ViewHolder holder = focus == null ? null : grid.findContainingViewHolder(focus);
        if (!(holder instanceof AppAdapter.Holder)) return;
        int position = holder.getBindingAdapterPosition();
        if (position < 0 || position >= apps.size()) return;
        Entry entry = apps.get(position);
        publishAmbient(((AppAdapter.Holder) holder).card, entry, artwork.get(entry.key));
    }

    boolean isFirstColumnFocused() {
        if (actionPopup != null || !grid.hasFocus()) return false;
        View focus = activity.getCurrentFocus();
        RecyclerView.ViewHolder holder = focus == null ? null : grid.findContainingViewHolder(focus);
        if (holder == null) return false;
        int position = holder.getBindingAdapterPosition();
        return position >= 0 && position < apps.size() && position % COLUMNS == 0;
    }

    void resume() {
        if (destroyed || active) return;
        active = true;
        focusPending = restoreOnResume;
        restoreOnResume = false;
        // Reuse attached tiles during the page fade. Check for installed-app changes afterward.
        if (catalogueLoaded) {
            bindMissingArtwork();
            if (focusPending) grid.post(this::tryPendingFocus);
            main.postDelayed(refreshCatalogue, animations && !reducedMotion ? 220 : 0);
        } else refresh();
    }

    void pause() {
        restoreOnResume = grid.hasFocus() || focusPending;
        active = false;
        main.removeCallbacks(refreshCatalogue);
        generation++;
        if (catalogueWork != null) catalogueWork.cancel(true);
        catalogueWork = null;
        loader.getQueue().clear();
        loadingArtwork.clear();
        dismissActions();
        grid.stopScroll();
        pendingPosition = -1;
        focusPending = false;
        hideGlass();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View card = grid.getChildAt(i).findViewById(R.id.card);
            if (card != null) {
                card.animate().cancel();
                float scale = card.hasFocus() && animations ? advancedEffects ? 1.12f : 1.1f : 1f;
                card.setScaleX(scale);
                card.setScaleY(scale);
                card.setTranslationY(card.hasFocus() && advancedEffects ? -dp(4) : 0);
            }
        }
    }

    void destroy() {
        if (destroyed) return;
        pause();
        destroyed = true;
        loader.shutdownNow();
        grid.setAdapter(null);
        grid.getRecycledViewPool().clear();
        artwork.evictAll();
        apps.clear();
        if (glassHost != viewport) glassHost.removeView(glass);
    }

    /** Called explicitly on entry or from the header, never by a background refresh. */
    boolean focusLast() {
        if (!active || destroyed) return false;
        focusPending = true;
        if (apps.isEmpty()) {
            status.requestFocus();
            return true; // The explicit request is kept until the asynchronous catalogue arrives.
        }
        requestPosition(Math.min(selectedPosition, apps.size() - 1));
        return true;
    }

    void refresh() {
        if (!active || destroyed) return;
        main.removeCallbacks(refreshCatalogue);
        final int token = ++generation;
        if (catalogueWork != null) catalogueWork.cancel(true);
        loader.getQueue().clear();
        loadingArtwork.clear();
        final PackageManager pm = activity.getPackageManager();
        catalogueWork = loader.submit(() -> {
            List<Entry> result = new ArrayList<>();
            boolean success = false;
            try {
                Map<String, Entry> unique = new LinkedHashMap<>();
                for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                    for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                        if (Thread.currentThread().isInterrupted()) return;
                        if (info.activityInfo == null || !info.activityInfo.exported) continue;
                        String pkg = info.activityInfo.packageName;
                        if (pkg.equals(activity.getPackageName()) || unique.containsKey(pkg)) continue;
                        String label = config.appLabels.get(pkg);
                        if (label == null) label = String.valueOf(info.loadLabel(pm));
                        long updated = 0;
                        try { updated = pm.getPackageInfo(pkg, 0).lastUpdateTime; }
                        catch (PackageManager.NameNotFoundException ignored) { }
                        unique.put(pkg, new Entry(info, Cards.cleanLabel(label, pkg),
                                Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category), updated));
                    }
                }
                result.addAll(unique.values());
                Collator collator = Collator.getInstance();
                result.sort((a, b) -> {
                    int labels = collator.compare(a.label, b.label);
                    return labels != 0 ? labels : a.pkg.compareTo(b.pkg);
                });
                success = true;
            } catch (RuntimeException unavailable) { /* The existing catalogue remains usable. */ }
            final boolean loaded = success;
            main.post(() -> {
                if (!active || destroyed || token != generation) return;
                catalogueWork = null;
                if (!loaded) {
                    if (apps.isEmpty()) status.setText("Apps are unavailable right now.");
                    return;
                }
                if (catalogueLoaded && sameCatalogue(result)) {
                    bindMissingArtwork();
                    return; // No layout, focus or artwork reset when nothing changed.
                }
                catalogueLoaded = true;
                boolean restoreFocus = focusPending || grid.hasFocus();
                Parcelable state = layout.onSaveInstanceState();
                apps = result;
                selectedPosition = Math.min(selectedPosition, Math.max(0, apps.size() - 1));
                for (int i = 0; i < apps.size(); i++) if (apps.get(i).pkg.equals(selectedPackage)) selectedPosition = i;
                adapter.notifyDataSetChanged();
                layout.onRestoreInstanceState(state);
                count.setText(activity.getResources().getQuantityString(R.plurals.library_app_count, apps.size(), apps.size()));
                status.setText(activity.getString(R.string.library_empty));
                status.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
                if (restoreFocus) {
                    focusPending = true;
                    grid.post(() -> { if (active && !destroyed) focusLast(); });
                }
            });
        });
    }

    private boolean sameCatalogue(List<Entry> result) {
        if (apps.size() != result.size()) return false;
        for (int i = 0; i < apps.size(); i++) {
            Entry before = apps.get(i), after = result.get(i);
            if (!before.key.equals(after.key) || !before.label.equals(after.label)
                    || before.tv != after.tv
                    || !before.info.activityInfo.name.equals(after.info.activityInfo.name)) return false;
        }
        return true;
    }

    /** Pause cancels outstanding artwork jobs; a retained tile must be able to retry on entry. */
    private void bindMissingArtwork() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            RecyclerView.ViewHolder found = grid.getChildViewHolder(grid.getChildAt(i));
            int position = found.getBindingAdapterPosition();
            if (!(found instanceof AppAdapter.Holder) || position < 0 || position >= apps.size()) continue;
            Entry entry = apps.get(position);
            if (artwork.get(entry.key) == null) bindArtwork((AppAdapter.Holder) found, entry);
        }
    }

    private void requestPosition(int position) {
        if (!active || destroyed || apps.isEmpty()) return;
        pendingPosition = Math.max(0, Math.min(position, apps.size() - 1));
        focusPending = true;
        if (tryPendingFocus()) return;
        if (!animations || reducedMotion) {
            layout.scrollToPosition(pendingPosition);
            grid.post(this::tryPendingFocus);
        } else {
            LinearSmoothScroller scroller = new LinearSmoothScroller(activity) {
                @Override protected float calculateSpeedPerPixel(DisplayMetrics metrics) { return 36f / metrics.densityDpi; }
                @Override protected int calculateTimeForDeceleration(int distance) { return Math.min(260, super.calculateTimeForDeceleration(distance)); }
            };
            scroller.setTargetPosition(pendingPosition);
            layout.startSmoothScroll(scroller);
        }
    }

    private boolean tryPendingFocus() {
        if (!active || destroyed || !root.isShown() || actionPopup != null) return false;
        int target = pendingPosition >= 0 ? pendingPosition : focusPending ? selectedPosition : -1;
        if (target < 0 || target >= apps.size()) return false;
        RecyclerView.ViewHolder found = grid.findViewHolderForAdapterPosition(target);
        if (!(found instanceof AppAdapter.Holder)) return false;
        AppAdapter.Holder holder = (AppAdapter.Holder) found;
        pendingPosition = -1;
        focusPending = false;
        if (holder.card.hasFocus()) onTileFocused(target, holder.card);
        else holder.card.requestFocus();
        return true;
    }

    @Override public boolean onTileVerticalKey(int position, int key) {
        int current = pendingPosition >= 0 ? pendingPosition : position;
        if (key == KeyEvent.KEYCODE_DPAD_UP && current < COLUMNS) {
            pendingPosition = -1;
            focusPending = false;
            grid.stopScroll();
            hideGlass();
            focusHeader.run();
            return true;
        }
        int target = LibraryPageNavigation.next(current, apps.size(), COLUMNS, key);
        if (target != current) requestPosition(target);
        return true;
    }

    @Override public void onTileFocused(int position, View card) {
        if (!active || destroyed || position < 0 || position >= apps.size()) return;
        selectedPosition = position;
        selectedPackage = apps.get(position).pkg;
        Entry entry = apps.get(position);
        publishAmbient(card, entry, artwork.get(entry.key));
        if (card.getWidth() == 0) { card.post(() -> { if (active && card.hasFocus()) onTileFocused(position, card); }); return; }
        Rect rect = new Rect(0, 0, card.getWidth(), card.getHeight());
        viewport.offsetDescendantRectToMyCoords(card, rect);
        float scale = animations ? advancedEffects ? 1.12f : 1.1f : 1f;
        float pad = dp(6), lift = advancedEffects ? dp(4) : 0;
        // Reveal decisions stay in viewport coordinates; the highlight may use a wider sibling host.
        float viewportTop = rect.top - card.getHeight() * (scale - 1) / 2f - pad - lift;
        if (glassHost != viewport) {
            rect.set(0, 0, card.getWidth(), card.getHeight());
            glassHost.offsetDescendantRectToMyCoords(card, rect);
        }
        float x = rect.left - card.getWidth() * (scale - 1) / 2f - pad;
        float y = rect.top - card.getHeight() * (scale - 1) / 2f - pad - lift;
        glass.bringToFront();
        ViewGroup.LayoutParams params = glass.getLayoutParams();
        int width = Math.round(card.getWidth() * scale + 2 * pad), height = Math.round(card.getHeight() * scale + 2 * pad);
        if (params.width != width || params.height != height) {
            params.width = width; params.height = height; glass.setLayoutParams(params);
        }
        glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        if (!glassShown || !animations) {
            springX.cancel(); springY.cancel();
            glass.setTranslationX(x); glass.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y - scrollShift);
        }
        glass.setAlpha(pageAlpha);
        glassShown = true;
        int top = Math.round(viewportTop), bottom = Math.round(viewportTop + height + dp(26));
        int delta = top < 0 ? top : bottom > viewport.getHeight() ? bottom - viewport.getHeight() : 0;
        if (delta != 0) {
            if (animations && !reducedMotion) grid.smoothScrollBy(0, delta, new DecelerateInterpolator(1.6f), 240);
            else grid.scrollBy(0, delta);
        }
    }

    private void hideGlass() {
        springX.cancel(); springY.cancel();
        glassShown = false;
        glass.setAlpha(0f);
    }

    @Override public void onTileLongPress(int position, View card) {
        if (position >= 0 && position < apps.size()) showActions(apps.get(position), card);
    }
    @Override public void onLibraryRequested() { }
    @Override public void onLibraryFocused() { }
    @Override public void onLibraryUp() { focusHeader.run(); }

    /** Gives the Activity's Back handler a chance to close actions before returning Home. */
    boolean dismissActions() {
        PopupWindow popup = actionPopup;
        actionPopup = null;
        if (popup != null) popup.dismiss();
        return popup != null;
    }

    private boolean isOnHome(String pkg) {
        if (LibraryApps.loadRemoved(activity).contains(pkg)) return false;
        return LibraryApps.load(activity).contains(pkg) || onHome.test(pkg);
    }

    private void open(Entry entry) {
        dismissActions();
        Intent fallback = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(entry.pkg, entry.info.activityInfo.name));
        if (entry.tv) fallback.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent intent = LaunchActions.select(entry.pkg, config.launchActions, fallback, (pkg, action) -> {
            Intent custom = new Intent(action).setPackage(pkg);
            return activity.getPackageManager().resolveActivity(custom, 0) != null ? custom : null;
        });
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, entry.label), Toast.LENGTH_SHORT).show();
        }
    }

    private void showActions(Entry entry, View anchor) {
        dismissActions();
        View panel = LayoutInflater.from(activity).inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow menu = new PopupWindow(panel, dp(230), -2, true);
        actionPopup = menu;
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setAnimationStyle(0);
        menu.setOnDismissListener(() -> {
            if (actionPopup == menu) actionPopup = null;
            if (active && !destroyed && root.isShown()) grid.post(this::focusLast);
        });
        addAction(rows, R.string.library_open, () -> open(entry));
        boolean home = isOnHome(entry.pkg);
        addAction(rows, home ? R.string.library_remove_shortcut : R.string.library_add_home, () -> {
            if (!LibraryApps.save(activity, entry.pkg, !home)) {
                Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
                return;
            }
            dismissActions();
            changed.run();
        });
        Intent info = SystemSettings.appInfo(activity, entry.pkg);
        if (info != null) addAction(rows, R.string.library_app_info, () -> {
            dismissActions();
            try { activity.startActivity(info); }
            catch (ActivityNotFoundException | SecurityException unavailable) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, entry.label), Toast.LENGTH_SHORT).show();
            }
        });
        panel.measure(View.MeasureSpec.makeMeasureSpec(dp(230), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] location = new int[2], origin = new int[2];
        anchor.getLocationOnScreen(location);
        root.getLocationOnScreen(origin);
        int x = Math.max(origin[0] + dp(8), Math.min(location[0], origin[0] + root.getWidth() - dp(238)));
        int y = Math.max(origin[1] + dp(8), Math.min(location[1] + dp(8), origin[1] + root.getHeight() - panel.getMeasuredHeight() - dp(8)));
        menu.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.LEFT, x, y);
        if (animations) {
            panel.setAlpha(0f);
            panel.setTranslationY(reducedMotion ? 0 : -dp(6));
            panel.animate().alpha(1f).translationY(0).setDuration(reducedMotion ? Motion.REDUCED_MS : 160)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
        rows.getChildAt(0).requestFocus();
    }

    private void addAction(LinearLayout rows, int label, Runnable action) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_input, rows, false);
        ((TextView) row.findViewById(R.id.title)).setText(label);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> action.run());
        row.setOnKeyListener((v, key, event) -> {
            int index = rows.indexOfChild(v);
            return event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP && index == 0
                    || key == KeyEvent.KEYCODE_DPAD_DOWN && index == rows.getChildCount() - 1);
        });
        rows.addView(row);
    }

    private final class Catalog extends RecyclerView.Adapter<AppAdapter.Holder> {
        @NonNull @Override public AppAdapter.Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View item = LayoutInflater.from(activity).inflate(R.layout.item_app, parent, false);
            return new AppAdapter.Holder(item, AppLibraryPage.this, animations, advancedEffects, reducedMotion, 1f, config.focusOvershoot);
        }
        @Override public void onBindViewHolder(@NonNull AppAdapter.Holder holder, int position) {
            Entry entry = apps.get(position);
            ViewGroup.LayoutParams cardParams = holder.card.getLayoutParams();
            cardParams.width = tileWidth; cardParams.height = tileHeight; holder.card.setLayoutParams(cardParams);
            ViewGroup.MarginLayoutParams nameParams = (ViewGroup.MarginLayoutParams) holder.name.getLayoutParams();
            nameParams.width = tileWidth; nameParams.height = dp(22); nameParams.topMargin = tileHeight + dp(12);
            holder.name.setLayoutParams(nameParams);
            holder.itemView.getLayoutParams().height = tileHeight + dp(54);
            holder.itemView.setPadding(0, dp(12), 0, dp(8));
            holder.card.setRevealOnFocusHint(false);
            holder.card.setContentDescription(entry.label);
            holder.card.setBackground(holder.cardBackground);
            holder.name.setText(entry.label);
            holder.name.setAlpha(1f);
            holder.name.setVisibility(View.VISIBLE);
            holder.name.setTranslationY(holder.card.hasFocus() ? dp(4) : 0);
            holder.label.setVisibility(View.GONE);
            holder.card.setTag(entry.key);
            float scale = holder.card.hasFocus() && animations ? advancedEffects ? 1.12f : 1.1f : 1f;
            holder.card.setScaleX(scale); holder.card.setScaleY(scale);
            holder.card.setTranslationY(holder.card.hasFocus() && advancedEffects ? -dp(4) : 0);
            holder.card.setOnClickListener(v -> open(entry));
            holder.card.setOnLongClickListener(v -> { showActions(entry, holder.card); return true; });
            bindArtwork(holder, entry);
        }
        @Override public int getItemCount() { return apps.size(); }
        @Override public void onViewRecycled(@NonNull AppAdapter.Holder holder) {
            holder.card.animate().cancel(); holder.banner.animate().cancel(); holder.name.animate().cancel();
            holder.card.setTag(null);
            holder.banner.setImageDrawable(null); holder.icon.setImageDrawable(null);
        }
    }

    private void bindArtwork(AppAdapter.Holder holder, Entry entry) {
        Art cached = artwork.get(entry.key);
        showArtwork(holder, entry, cached);
        if (cached != null || !active || !loadingArtwork.add(entry.key)) return;
        final int token = generation;
        try {
            loader.execute(() -> {
                Art loaded = loadArtwork(entry);
                main.post(() -> {
                    if (!active || destroyed || token != generation) return;
                    loadingArtwork.remove(entry.key);
                    if (loaded != null) artwork.put(entry.key, loaded);
                    if (entry.key.equals(holder.card.getTag())) showArtwork(holder, entry, loaded);
                });
            });
        } catch (RejectedExecutionException full) { loadingArtwork.remove(entry.key); }
    }

    private void showArtwork(AppAdapter.Holder holder, Entry entry, Art art) {
        holder.banner.setImageDrawable(art != null && art.banner ? new BitmapDrawable(activity.getResources(), art.bitmap) : null);
        holder.banner.setVisibility(art != null && art.banner ? View.VISIBLE : View.GONE);
        holder.icon.setImageDrawable(art == null ? activity.getDrawable(R.drawable.ic_apps)
                : !art.banner ? new BitmapDrawable(activity.getResources(), art.bitmap) : null);
        holder.icon.setVisibility(art == null || !art.banner ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams iconParams = (FrameLayout.LayoutParams) holder.icon.getLayoutParams();
        iconParams.width = iconParams.height = Math.round(tileHeight * .55f);
        iconParams.bottomMargin = 0;
        holder.icon.setLayoutParams(iconParams);
        publishAmbient(holder.card, entry, art);
    }

    /** Late artwork can update the backdrop only while this exact tile still owns focus. */
    private void publishAmbient(View card, Entry entry, Art art) {
        if (ambientColorChanged == null || !active || destroyed || !root.isShown()
                || !activity.hasWindowFocus() || !card.hasFocus() || !entry.key.equals(card.getTag())) return;
        RecyclerView.ViewHolder holder = grid.findContainingViewHolder(card);
        int position = holder == null ? -1 : holder.getBindingAdapterPosition();
        if (position < 0 || position >= apps.size() || !entry.key.equals(apps.get(position).key)) return;
        ambientColorChanged.accept(art == null ? 0xFF444444 : art.color);
    }

    private Art loadArtwork(Entry entry) {
        try {
            PackageManager pm = activity.getPackageManager();
            Drawable drawable = null;
            String override = config.appBanners.get(entry.pkg);
            if (override != null && override.startsWith("banner_")) {
                int id = activity.getResources().getIdentifier(override, "drawable", activity.getPackageName());
                if (id != 0) drawable = activity.getDrawable(id);
            }
            if (drawable == null) {
                int id = entry.info.activityInfo.banner;
                if (id == 0) id = entry.info.activityInfo.applicationInfo.banner;
                if (id != 0) {
                    Resources resources = pm.getResourcesForApplication(entry.info.activityInfo.applicationInfo);
                    drawable = resources.getDrawableForDensity(id, Math.max(DisplayMetrics.DENSITY_XHIGH,
                            activity.getResources().getDisplayMetrics().densityDpi), null);
                }
                if (drawable == null) drawable = entry.info.activityInfo.loadBanner(pm);
            }
            boolean banner = drawable != null && AppAdapter.isBannerShaped(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            if (!banner) drawable = entry.info.loadIcon(pm);
            if (drawable == null || Thread.currentThread().isInterrupted()) return null;
            // Sample the same source as Home, once on the existing artwork worker.
            int color = config.ambientGlow ? AppAdapter.artworkColor(drawable, !banner && config.uniformTiles) : 0xFF444444;
            int width = banner ? 480 : 128, height = banner ? 270 : 128;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            int sourceW = drawable.getIntrinsicWidth(), sourceH = drawable.getIntrinsicHeight();
            float scale = sourceW > 0 && sourceH > 0
                    ? (banner ? Math.max((float) width / sourceW, (float) height / sourceH)
                    : Math.min((float) width / sourceW, (float) height / sourceH)) : 1f;
            int w = sourceW > 0 ? Math.round(sourceW * scale) : width;
            int h = sourceH > 0 ? Math.round(sourceH * scale) : height;
            drawable.setBounds((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2);
            drawable.draw(canvas);
            return new Art(bitmap, banner, color);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { return null; }
    }

    private static final class Art {
        final Bitmap bitmap;
        final boolean banner;
        final int color;
        Art(Bitmap bitmap, boolean banner, int color) {
            this.bitmap = bitmap; this.banner = banner; this.color = color;
        }
    }

    private static final class Entry {
        final ResolveInfo info;
        final String pkg, label, key;
        final boolean tv;
        Entry(ResolveInfo info, String label, boolean tv, long updated) {
            this.info = info; this.pkg = info.activityInfo.packageName; this.label = label;
            this.tv = tv; this.key = pkg + ":" + updated;
        }
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(android.graphics.Typeface.create(activity.getString(R.string.font_medium), android.graphics.Typeface.NORMAL));
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        return text;
    }

    private int dp(float value) { return Math.round(value * density); }
}
