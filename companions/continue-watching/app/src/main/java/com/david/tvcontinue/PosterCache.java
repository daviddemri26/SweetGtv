package com.david.tvcontinue;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** On-demand landscape thumbnails. Disk bytes, decoded pixels and concurrent requests are bounded. */
final class PosterCache {
    private static final int MAX_PNG = 512 * 1024;
    private static final long MAX_DISK = 8L * 1024 * 1024;
    private static final Object DISK = new Object();
    private static final Semaphore FETCHES = new Semaphore(2);
    private static final ScheduledThreadPoolExecutor TIMEOUTS = new ScheduledThreadPoolExecutor(1);
    static {
        TIMEOUTS.setRemoveOnCancelPolicy(true);
        TIMEOUTS.setKeepAliveTime(5, TimeUnit.SECONDS);
        TIMEOUTS.allowCoreThreadTimeOut(true);
    }

    static ParcelFileDescriptor open(Context context, HomeEntries.Item item, CancellationSignal signal)
            throws FileNotFoundException {
        signal.throwIfCanceled();
        File directory = new File(context.getCacheDir(), "home-art");
        String name = item.key + "-" + item.version;
        synchronized (DISK) {
            ParcelFileDescriptor hit = cached(directory, name + ".png", 24L * 60 * 60 * 1000);
            if (hit == null) hit = cached(directory, name + "-fallback.png", 60000);
            if (hit != null) return hit;
        }
        // Saturated requests fail quickly; callers can retain their local placeholder and retry later.
        if (!FETCHES.tryAcquire()) throw new FileNotFoundException("Artwork workers busy");
        try {
            Bitmap image = null;
            boolean fallback = false;
            try { image = thumbnail(source(context, item, signal), signal); }
            catch (OperationCanceledException canceled) { throw canceled; }
            catch (IOException | RuntimeException unavailable) { signal.throwIfCanceled(); }
            if (image == null) { fallback = true; image = icon(context, item.entry.owner); }
            byte[] bytes;
            try {
                signal.throwIfCanceled();
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, encoded)) throw new IOException("Artwork unavailable");
                bytes = encoded.toByteArray();
                if (bytes.length > MAX_PNG) throw new IOException("Thumbnail too large");
            } finally { image.recycle(); }
            signal.throwIfCanceled();
            synchronized (DISK) {
                signal.throwIfCanceled();
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cache unavailable");
                File target = new File(directory, name + (fallback ? "-fallback.png" : ".png"));
                File temporary = new File(directory, "pending.tmp");
                if (temporary.exists() && !temporary.delete()) throw new IOException("Cache unavailable");
                if (target.exists() && !target.delete()) throw new IOException("Cache unavailable");
                makeRoom(directory, bytes.length);
                try {
                    try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); }
                    signal.throwIfCanceled();
                    if (!temporary.renameTo(target)) throw new IOException("Cache unavailable");
                } finally { if (temporary.exists()) temporary.delete(); }
                return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
            }
        } catch (IOException unavailable) {
            throw new FileNotFoundException("Artwork unavailable");
        } finally { FETCHES.release(); }
    }

    private static ParcelFileDescriptor cached(File directory, String name, long maxAge)
            throws FileNotFoundException {
        File file = new File(directory, name);
        long age = System.currentTimeMillis() - file.lastModified();
        if (file.isFile() && file.length() > 0 && file.length() <= MAX_PNG && age >= 0 && age < maxAge)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        return null;
    }
    private static void makeRoom(File directory, long required) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cache unavailable");
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = 0;
        for (File file : files) total += file.length();
        for (File file : files) {
            if (total + required <= MAX_DISK) break;
            long length = file.length();
            if (!file.delete()) throw new IOException("Cache full");
            total -= length;
        }
        if (total + required > MAX_DISK) throw new IOException("Cache full");
    }

    private static byte[] source(Context context, HomeEntries.Item item, CancellationSignal signal)
            throws IOException {
        String poster = item.entry.poster;
        if (poster.isEmpty() || poster.length() > 8192) throw new IOException("No artwork");
        Uri uri = Uri.parse(poster);
        if ("https".equalsIgnoreCase(uri.getScheme())) return PosterNetwork.fetch(poster, signal);
        if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null
                || uri.getUserInfo() != null || HomeContract.AUTHORITY.equals(uri.getAuthority()))
            throw new IOException("Unsupported artwork");
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
        // Only the source app's own provider can supply a local poster. Never read arbitrary local files.
        if (provider == null || !item.entry.owner.equals(provider.packageName)) throw new IOException("Unsupported artwork owner");
        try (AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r", signal)) {
            if (descriptor == null || descriptor.getLength() > PosterNetwork.MAX_BYTES) throw new IOException("Artwork unavailable");
            ScheduledFuture<?> timeout = TIMEOUTS.schedule(() -> close(descriptor), 5, TimeUnit.SECONDS);
            signal.setOnCancelListener(() -> close(descriptor));
            try (InputStream stream = descriptor.createInputStream()) {
                return PosterNetwork.read(stream, -1, signal, SystemClock.elapsedRealtime() + 5000);
            } finally { timeout.cancel(false); signal.setOnCancelListener(null); }
        }
    }

    private static Bitmap thumbnail(byte[] bytes, CancellationSignal signal) throws IOException {
        signal.throwIfCanceled();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        int width = options.outWidth, height = options.outHeight;
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384
                || (long) width * height > 16000000) throw new IOException("Unsupported image dimensions");
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (width / options.inSampleSize > 640 || height / options.inSampleSize > 640)
            options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) throw new IOException("Invalid image");
        try {
            signal.throwIfCanceled();
            Bitmap result = Bitmap.createBitmap(ArtworkGeometry.WIDTH, ArtworkGeometry.HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawColor(Color.rgb(27, 34, 44));
            float[] bounds = ArtworkGeometry.centerCrop(decoded.getWidth(), decoded.getHeight());
            canvas.drawBitmap(decoded, null, new RectF(bounds[0], bounds[1], bounds[2], bounds[3]),
                    new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            return result;
        } finally { decoded.recycle(); }
    }
    private static Bitmap icon(Context context, String owner) {
        Bitmap bitmap = Bitmap.createBitmap(ArtworkGeometry.WIDTH, ArtworkGeometry.HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(27, 34, 44));
        Drawable icon;
        try { icon = context.getPackageManager().getApplicationIcon(owner); }
        catch (Exception missing) { icon = context.getDrawable(R.drawable.icon); }
        if (icon != null) {
            float[] bounds = ArtworkGeometry.centeredIcon(icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
            icon.setBounds(Math.round(bounds[0]), Math.round(bounds[1]), Math.round(bounds[2]), Math.round(bounds[3]));
            icon.draw(canvas);
        }
        return bitmap;
    }
    private static void close(AssetFileDescriptor descriptor) {
        try { descriptor.close(); } catch (IOException ignored) { }
    }
}
