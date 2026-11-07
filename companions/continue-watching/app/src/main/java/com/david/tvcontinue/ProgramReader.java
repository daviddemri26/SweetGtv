package com.david.tvcontinue;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads only the platform's searchable rows. It never updates playback history. */
final class ProgramReader {
    private static final int MAX_ROWS = 5000;
    private static final String[] YOUTUBE_COLUMNS = {"_id", "channel_id", "package_name", "title", "intent_uri", "poster_art_uri"};
    private static final String[] YOUTUBE_METADATA_COLUMNS = append(YOUTUBE_COLUMNS, "author", "release_date",
            "duration_millis", "interaction_type", "interaction_count", "live", "short_description");
    private static final String[] PROGRAM_COLUMNS = {"_id", "package_name", "title", "episode_title",
            "season_display_number", "episode_display_number", "intent_uri",
            "last_playback_position_millis", "duration_millis", "poster_art_uri"};

    static final class Entry {
        final String owner, title, episode, season, number, intent, poster;
        final long position, duration, last;
        final int kind;
        final YouTubeMetadata youtubeMetadata;
        Entry(Cursor c, int kind, long last) {
            youtubeMetadata = YouTubeMetadata.EMPTY;
            owner = value(c, "package_name");
            title = value(c, "title");
            episode = value(c, "episode_title");
            season = value(c, "season_display_number");
            number = value(c, "episode_display_number");
            intent = value(c, "intent_uri");
            poster = value(c, "poster_art_uri");
            position = number(c, "last_playback_position_millis");
            duration = number(c, "duration_millis");
            this.last = last;
            this.kind = kind;
        }
        Entry(YouTubePreviewPolicy.Row row, String poster) {
            youtubeMetadata = row.metadata;
            owner = row.owner;
            title = YouTubePreviewPolicy.displayTitle(row.title);
            episode = season = number = "";
            intent = row.intent;
            this.poster = poster;
            position = -1;
            duration = last = 0;
            kind = 0;
        }
    }

    static final class Result {
        final List<Entry> entries = new ArrayList<>();
        boolean failed;
        int watchRows, previewRows, resumeChannels;
    }

    static Result read(Context context, CancellationSignal cancellation) {
        cancellation.throwIfCanceled();
        ContentResolver resolver = context.getContentResolver();
        Result result = new Result();
        Map<String, Entry> entries = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        String[] watchColumns = append(PROGRAM_COLUMNS, "watch_next_type", "last_engagement_time_utc_millis");
        // No SQL selection: the TV provider rejects selection for non-privileged readers.
        try (Cursor c = resolver.query(Uri.parse("content://android.media.tv/watch_next_program"),
                watchColumns, null, null, null, cancellation)) {
            if (c != null) while (result.watchRows < MAX_ROWS) {
                cancellation.throwIfCanceled();
                if (!c.moveToNext()) break;
                cancellation.throwIfCanceled();
                result.watchRows++;
                Entry e = new Entry(c, (int) number(c, "watch_next_type"), number(c, "last_engagement_time_utc_millis"));
                add(entries, e, now);
            }
        } catch (OperationCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException e) {
            cancellation.throwIfCanceled();
            result.failed = true;
            Log.w("ContinueWatching", "Watch Next unavailable: " + e.getClass().getSimpleName());
        }
        cancellation.throwIfCanceled();
        Set<Long> channels = new HashSet<>();
        try (Cursor c = resolver.query(Uri.parse("content://android.media.tv/channel"),
                new String[]{"_id", "package_name", "display_name"}, null, null, null, cancellation)) {
            int count = 0;
            if (c != null) while (count < MAX_ROWS) {
                cancellation.throwIfCanceled();
                if (!c.moveToNext()) break;
                cancellation.throwIfCanceled();
                count++;
                if (EntryPolicy.isResumeChannel(value(c, "package_name"), value(c, "display_name"))) {
                    channels.add(number(c, "_id"));
                }
            }
        } catch (OperationCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException e) {
            cancellation.throwIfCanceled();
            result.failed = true;
            Log.w("ContinueWatching", "Channels unavailable: " + e.getClass().getSimpleName());
        }
        cancellation.throwIfCanceled();
        result.resumeChannels = channels.size();
        if (!channels.isEmpty()) {
            try (Cursor c = resolver.query(Uri.parse("content://android.media.tv/preview_program"),
                    append(PROGRAM_COLUMNS, "channel_id"), null, null, null, cancellation)) {
                if (c != null) while (result.previewRows < MAX_ROWS) {
                    cancellation.throwIfCanceled();
                    if (!c.moveToNext()) break;
                    cancellation.throwIfCanceled();
                    result.previewRows++;
                    if (!channels.contains(number(c, "channel_id"))) continue;
                    Entry e = new Entry(c, 0, 0);
                    // No recommendations: only Stremio's exact resume channel, with actual playback progress.
                    if (EntryPolicy.STREMIO.equals(e.owner) && e.position > 0) add(entries, e, now);
                }
            } catch (OperationCanceledException canceled) {
                throw canceled;
            } catch (RuntimeException e) {
                cancellation.throwIfCanceled();
                result.failed = true;
                Log.w("ContinueWatching", "Stremio preview unavailable: " + e.getClass().getSimpleName());
            }
        }
        cancellation.throwIfCanceled();
        result.entries.addAll(entries.values());
        result.entries.sort(Comparator.comparingLong((Entry e) -> e.last).reversed()
                .thenComparing(e -> e.title, String.CASE_INSENSITIVE_ORDER));
        if (result.entries.size() > 60) result.entries.subList(60, result.entries.size()).clear();
        cancellation.throwIfCanceled();
        // Counts only. No titles, identifiers, provider rows or intent URLs in logs or files.
        Log.i("ContinueWatching", "watchRows=" + result.watchRows + " stremioChannels="
                + result.resumeChannels + " previewRows=" + result.previewRows
                + " visibleEntries=" + result.entries.size() + " readFailure=" + result.failed);
        return result;
    }

    /** Independent YouTube preview shelf. No WatchNext, progress, history or recommendations inference. */
    static Result readYouTube(Context context, CancellationSignal cancellation) {
        Result result = new Result();
        List<YouTubePreviewPolicy.Row> rows = new ArrayList<>();
        cancellation.throwIfCanceled();
        try (Cursor cursor = queryYouTube(context.getContentResolver(), cancellation)) {
            if (cursor == null) throw new IllegalStateException("TV listings unavailable");
            while (result.previewRows < MAX_ROWS && cursor.moveToNext()) {
                cancellation.throwIfCanceled();
                result.previewRows++;
                String owner = value(cursor, "package_name");
                if (!YouTubePreviewPolicy.OWNER.equals(owner)) continue;
                rows.add(new YouTubePreviewPolicy.Row(owner, value(cursor, "title"), value(cursor, "intent_uri"),
                        value(cursor, "poster_art_uri"), number(cursor, "channel_id"), number(cursor, "_id"),
                        YouTubeMetadata.from(optionalValue(cursor, "author"), optionalValue(cursor, "release_date"),
                                optionalValue(cursor, "duration_millis"), optionalValue(cursor, "interaction_type"),
                                optionalValue(cursor, "interaction_count"), optionalValue(cursor, "live"),
                                optionalValue(cursor, "short_description"))));
            }
            for (YouTubePreviewPolicy.Row row : YouTubePreviewPolicy.select(rows, MAX_ROWS)) {
                cancellation.throwIfCanceled();
                Entry entry = new Entry(row, allowedYouTubePoster(context, row.poster));
                if (entry.title.isEmpty() || !EntryLauncher.canOpenEntry(context, entry)) continue;
                result.entries.add(entry);
                if (result.entries.size() == 60) break;
            }
        } catch (OperationCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException unavailable) {
            cancellation.throwIfCanceled();
            result.failed = true;
            Log.w("YouTubeShelf", "Preview unavailable: " + unavailable.getClass().getSimpleName());
        }
        cancellation.throwIfCanceled();
        Log.i("YouTubeShelf", "previewRows=" + result.previewRows + " visibleEntries="
                + result.entries.size() + " readFailure=" + result.failed);
        return result;
    }

    private static Cursor queryYouTube(ContentResolver resolver, CancellationSignal cancellation) {
        Uri uri = Uri.parse("content://android.media.tv/preview_program");
        try {
            Cursor cursor = resolver.query(uri, YOUTUBE_METADATA_COLUMNS, null, null, null, cancellation);
            if (cursor != null) return cursor;
        } catch (OperationCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException optionalColumnsUnavailable) {
            cancellation.throwIfCanceled();
        }
        // Older providers can reject the whole projection for an unknown optional column.
        cancellation.throwIfCanceled();
        return resolver.query(uri, YOUTUBE_COLUMNS, null, null, null, cancellation);
    }

    private static String optionalValue(Cursor cursor, String column) {
        try { return value(cursor, column); }
        catch (RuntimeException malformedOptionalValue) { return ""; }
    }

    /** Unsupported artwork falls back to the app icon; the normal cache checks every actual read again. */
    private static String allowedYouTubePoster(Context context, String value) {
        if (value.isEmpty() || value.length() > 8192) return "";
        try {
            Uri uri = Uri.parse(value);
            if ("https".equalsIgnoreCase(uri.getScheme()))
                return PosterPolicy.publicHttps(new java.net.URI(value)) ? value : "";
            if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null || uri.getUserInfo() != null
                    || HomeContract.AUTHORITY.equals(uri.getAuthority())) return "";
            android.content.pm.ProviderInfo provider = context.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
            return provider != null && YouTubePreviewPolicy.OWNER.equals(provider.packageName) ? value : "";
        } catch (java.net.URISyntaxException | RuntimeException invalid) {
            return "";
        }
    }

    private static void add(Map<String, Entry> entries, Entry e, long now) {
        if (EntryPolicy.include(e.owner, e.title, e.intent, e.kind, e.last, e.position, e.duration, now)) {
            entries.putIfAbsent(e.owner + '\n' + e.intent, e);
        }
    }

    private static String[] append(String[] values, String... extra) {
        String[] out = java.util.Arrays.copyOf(values, values.length + extra.length);
        System.arraycopy(extra, 0, out, values.length, extra.length);
        return out;
    }

    private static String value(Cursor c, String key) {
        int i = c.getColumnIndex(key);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private static long number(Cursor c, String key) {
        int i = c.getColumnIndex(key);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }
}
