package com.david.tvcontinue;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.CancellationSignal;
import android.os.SystemClock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class HomeEntries {
    static final class Item {
        final ProgramReader.Entry entry;
        final String key, version, subtitle, shelf;
        Item(Context context, ProgramReader.Entry entry) {
            this(context, entry, HomeContract.SHELF_CONTINUE);
        }
        Item(Context context, ProgramReader.Entry entry, String shelf) {
            this.entry = entry;
            this.shelf = shelf;
            key = HomeContract.keyFor(shelf, entry.owner, entry.intent);
            version = HomeContract.hash(ArtworkGeometry.FORMAT + '\n' + entry.owner + '\n' + entry.poster);
            String label;
            try { label = String.valueOf(context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(entry.owner, 0))); }
            catch (PackageManager.NameNotFoundException ignored) { label = "App"; }
            if (!entry.season.isEmpty() && !entry.number.isEmpty())
                label += " · " + context.getString(R.string.season_episode, entry.season, entry.number);
            else if (!entry.number.isEmpty()) label += " · " + context.getString(R.string.episode_only, entry.number);
            subtitle = HomeContract.bounded(label);
        }
        String artUri() { return "content://" + HomeContract.AUTHORITY
                + (HomeContract.SHELF_YOUTUBE.equals(shelf) ? "/youtube_art/" : "/art/") + key + "/" + version; }
    }
    private static volatile Snapshot snapshot = new Snapshot(Collections.emptyMap(), 0);
    private static volatile Snapshot youtubeSnapshot = new Snapshot(Collections.emptyMap(), 0);
    private static final class Snapshot {
        final Map<String, Item> items;
        final long time;
        Snapshot(Map<String, Item> items, long time) { this.items = items; this.time = time; }
    }
    static Map<String, Item> refresh(Context context, CancellationSignal signal) {
        return refresh(context, HomeContract.SHELF_CONTINUE, signal);
    }
    static Map<String, Item> refresh(Context context, String shelf, CancellationSignal signal) {
        if (!HomeContract.validShelf(shelf)) throw new IllegalArgumentException("Unsupported shelf");
        // Every home query rereads progress. Only key resolution can reuse a short-lived snapshot.
        ProgramReader.Result result = HomeContract.SHELF_YOUTUBE.equals(shelf)
                ? ProgramReader.readYouTube(context, signal) : ProgramReader.read(context, signal);
        if (result.failed && result.entries.isEmpty())
            throw new IllegalStateException("TV listings temporarily unavailable");
        Map<String, Item> items = new LinkedHashMap<>();
        for (ProgramReader.Entry entry : result.entries) {
            signal.throwIfCanceled();
            if (items.size() == 60) break;
            Item item = new Item(context, entry, shelf);
            items.put(item.key, item);
        }
        signal.throwIfCanceled();
        Map<String, Item> frozen = Collections.unmodifiableMap(items);
        Snapshot current = new Snapshot(frozen, SystemClock.elapsedRealtime());
        if (HomeContract.SHELF_YOUTUBE.equals(shelf)) youtubeSnapshot = current;
        else snapshot = current;
        return frozen;
    }
    static Item resolve(Context context, String key, CancellationSignal signal) {
        return resolve(context, HomeContract.SHELF_CONTINUE, key, signal);
    }
    static Item resolve(Context context, String shelf, String key, CancellationSignal signal) {
        signal.throwIfCanceled();
        if (!HomeContract.validShelf(shelf) || !HomeContract.validKey(key)) return null;
        Snapshot current = HomeContract.SHELF_YOUTUBE.equals(shelf) ? youtubeSnapshot : snapshot;
        if (SystemClock.elapsedRealtime() - current.time <= 60000 && current.items.containsKey(key))
            return current.items.get(key);
        return refresh(context, shelf, signal).get(key);
    }
}
