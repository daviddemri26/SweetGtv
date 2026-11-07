package com.daviddemri.tv.spotify;

import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Small, bounded foreground-only Spotify covers; no auth headers or arbitrary image origins. */
final class SpotifyArtwork {
    private static final int MAX_BYTES = 2 * 1024 * 1024, TARGET = 160, FADE_MS = 160;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<>(32);
    private final LruCache<String, Long> failures = new LruCache<>(32);
    // Main-thread ownership keeps one bounded download per URL, even for a header and several cards.
    private final Map<String, Flight> flights = new HashMap<>();
    private ThreadPoolExecutor worker;
    private boolean motionEnabled = true;
    private volatile boolean active = true;
    private volatile int generation;

    private static final class Request {
        final WeakReference<ImageView> view;
        final String url;
        final int generation;
        Flight flight;
        Request(ImageView view, String url, int generation) {
            this.view = new WeakReference<>(view); this.url = url; this.generation = generation;
        }
    }

    private static final class Flight {
        final String url;
        final int generation;
        final List<Request> requests = new ArrayList<>();
        volatile boolean canceled;
        volatile HttpURLConnection connection;
        Future<?> future;
        Flight(String url, int generation) { this.url = url; this.generation = generation; }
    }

    void setMotionEnabled(boolean enabled) { motionEnabled = enabled; }

    void resume() { active = true; }

    void load(ImageView view, String url) {
        if (!active || !allowed(url)) return;
        Long retry = failures.get(url);
        if (retry != null && retry > System.currentTimeMillis()) return;
        Object tag = view.getTag();
        if (tag instanceof Request) {
            Request old = (Request) tag;
            if (old.generation == generation && old.url.equals(url)) return;
        }
        cancel(view);
        Request request = new Request(view, url, generation);
        view.setTag(request);
        Bitmap ready = cache.get(url);
        if (ready != null && !ready.isRecycled()) { apply(view, ready, false); return; }
        Flight existing = flights.get(url);
        if (existing != null) {
            request.flight = existing; existing.requests.add(request);
            return;
        }
        if (worker == null) worker = new ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
        Flight flight = new Flight(url, generation);
        request.flight = flight; flight.requests.add(request); flights.put(url, flight);
        try {
            flight.future = worker.submit(() -> {
                Bitmap bitmap = download(flight);
                if (current(flight)) main.post(() -> complete(flight, bitmap));
            });
        } catch (RuntimeException fullOrPaused) {
            flights.remove(url); request.flight = null;
            view.setTag(null); // A later visible-row pass can retry a bounded queue overflow.
        }
    }

    private void complete(Flight flight, Bitmap bitmap) {
        if (!current(flight) || flights.get(flight.url) != flight) return;
        flights.remove(flight.url);
        if (bitmap == null) failures.put(flight.url, System.currentTimeMillis() + 30_000);
        else { failures.remove(flight.url); cache.put(flight.url, bitmap); }
        for (Request request : flight.requests) {
            request.flight = null;
            ImageView target = request.view.get();
            if (target == null || target.getTag() != request) continue;
            if (bitmap == null) target.setTag(null);
            else if (target.isAttachedToWindow()) apply(target, bitmap, true);
        }
        flight.requests.clear();
    }

    void cancel(ImageView view) {
        Object tag = view.getTag();
        view.setTag(null);
        if (!(tag instanceof Request)) return;
        Request request = (Request) tag;
        Flight flight = request.flight;
        request.flight = null;
        if (flight == null) return;
        flight.requests.remove(request);
        if (flight.requests.isEmpty()) {
            if (flights.get(flight.url) == flight) flights.remove(flight.url);
            cancel(flight);
        }
    }

    private void cancel(Flight flight) {
        flight.canceled = true;
        if (flight.future != null) {
            flight.future.cancel(true);
            if (worker != null && flight.future instanceof Runnable) worker.remove((Runnable) flight.future);
        }
        HttpURLConnection connection = flight.connection;
        if (connection != null) connection.disconnect();
    }

    private boolean current(Flight flight) {
        return active && flight.generation == generation && !flight.canceled && !Thread.currentThread().isInterrupted();
    }

    private Bitmap download(Flight flight) {
        String address = flight.url;
        for (int redirects = 0; redirects <= 3; redirects++) {
            if (!current(flight) || !allowed(address)) return null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(address).openConnection();
                flight.connection = connection;
                if (!current(flight)) return null;
                connection.setConnectTimeout(5_000);
                connection.setReadTimeout(5_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "image/*");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String next = connection.getHeaderField("Location");
                    if (next == null) return null;
                    address = new URI(address).resolve(next).toString();
                    continue;
                }
                if (status != 200 || connection.getContentLengthLong() > MAX_BYTES) return null;
                byte[] bytes;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8_192]; int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (!current(flight)
                                || output.size() + count > MAX_BYTES) return null;
                        output.write(buffer, 0, count);
                    }
                    bytes = output.toByteArray();
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 4096 || options.outHeight > 4096) return null;
                options.inJustDecodeBounds = false;
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > TARGET || options.outHeight / options.inSampleSize > TARGET) options.inSampleSize *= 2;
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            } catch (Exception unavailable) {
                return null;
            } finally {
                flight.connection = null;
                if (connection != null) connection.disconnect();
            }
        }
        return null;
    }

    static boolean allowed(String value) {
        return SpotifyApiPolicy.allowedImageUrl(value);
    }

    /** Native metadata and downloaded covers use the same retained-image transition. */
    void show(ImageView view, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        cancel(view);
        apply(view, bitmap, true);
    }

    private void apply(ImageView view, Bitmap bitmap, boolean downloaded) {
        Drawable previous = view.getDrawable();
        // Flatten an interrupted transition instead of nesting drawable/bitmap chains.
        if (previous instanceof TransitionDrawable) previous = ((TransitionDrawable) previous).getDrawable(1);
        Bitmap old = previous instanceof BitmapDrawable ? ((BitmapDrawable) previous).getBitmap() : null;
        if (old == bitmap) return; // Polls, cache hits and rebinds never restart an existing fade.
        view.setPadding(0, 0, 0, 0);
        view.setImageTintList((ColorStateList) null);
        BitmapDrawable next = new CoverDrawable(view, bitmap);
        if (!motionEnabled || !ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow() || old == null && !downloaded) {
            view.setImageDrawable(next);
            return;
        }
        Drawable before = old == null ? new ColorDrawable(Color.TRANSPARENT) : previous;
        TransitionDrawable fade = new TransitionDrawable(new Drawable[]{before, next});
        fade.setCrossFadeEnabled(true);
        view.setImageDrawable(fade);
        fade.startTransition(FADE_MS);
        WeakReference<ImageView> target = new WeakReference<>(view);
        main.postDelayed(() -> {
            ImageView current = target.get();
            if (current != null && current.getDrawable() == fade) current.setImageDrawable(next);
        }, FADE_MS);
    }

    /** Each layer keeps its own crop, so changing aspect ratios cannot stretch during a crossfade. */
    private static final class CoverDrawable extends BitmapDrawable {
        private final Rect source = new Rect();
        CoverDrawable(ImageView view, Bitmap bitmap) {
            super(view.getResources(), bitmap);
            setFilterBitmap(true);
        }
        @Override public int getIntrinsicWidth() { return -1; }
        @Override public int getIntrinsicHeight() { return -1; }
        @Override protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            int width = getBitmap().getWidth(), height = getBitmap().getHeight();
            if (bounds.width() <= 0 || bounds.height() <= 0) { source.set(0, 0, width, height); return; }
            double scale = Math.min((double) width / bounds.width(), (double) height / bounds.height());
            int cropWidth = Math.max(1, Math.min(width, (int) Math.round(bounds.width() * scale)));
            int cropHeight = Math.max(1, Math.min(height, (int) Math.round(bounds.height() * scale)));
            int left = (width - cropWidth) / 2, top = (height - cropHeight) / 2;
            source.set(left, top, left + cropWidth, top + cropHeight);
        }
        @Override public void draw(Canvas canvas) { canvas.drawBitmap(getBitmap(), source, getBounds(), getPaint()); }
    }

    void pause() {
        active = false; ++generation;
        for (Flight flight : flights.values()) cancel(flight);
        flights.clear();
        if (worker != null) worker.shutdownNow();
        worker = null;
    }

    void destroy() { pause(); main.removeCallbacksAndMessages(null); cache.evictAll(); failures.evictAll(); }
}
