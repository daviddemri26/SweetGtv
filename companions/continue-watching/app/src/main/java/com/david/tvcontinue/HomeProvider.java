package com.david.tvcontinue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.List;

/** Signature-checked local interface; every data path is read-only. */
public final class HomeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        PackageManager pm = getContext().getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages != null && Arrays.asList(packages).contains(HomeContract.HOME)
                && pm.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH) return;
        throw new SecurityException("Caller is not the signed home application");
    }
    private List<String> path(Uri uri) {
        if (!"content".equals(uri.getScheme()) || !HomeContract.AUTHORITY.equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Unsupported URI");
        return uri.getPathSegments();
    }
    private String entriesShelf(Uri uri) {
        List<String> segments = path(uri);
        if (segments.size() == 1) {
            if ("entries".equals(segments.get(0))) return HomeContract.SHELF_CONTINUE;
            if ("youtube_entries".equals(segments.get(0))) return HomeContract.SHELF_YOUTUBE;
        }
        throw new IllegalArgumentException("Unsupported path");
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                   String[] args, String sort) {
        return query(uri, projection, selection, args, sort, null);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                   String[] args, String sort, CancellationSignal cancellation) {
        enforceCaller();
        String shelf = entriesShelf(uri);
        if (selection != null || args != null || sort != null)
            throw new IllegalArgumentException("Filtering is not supported");
        String[] columns = projection == null ? HomeContract.COLUMNS.clone() : projection.clone();
        String[] supported = HomeContract.columnsForShelf(shelf);
        if (columns.length > supported.length) throw new IllegalArgumentException("Too many columns");
        for (String column : columns) if (!Arrays.asList(supported).contains(column))
            throw new IllegalArgumentException("Unsupported column");
        CancellationSignal signal = cancellation == null ? new CancellationSignal() : cancellation;
        long token = Binder.clearCallingIdentity();
        try {
            MatrixCursor cursor = new MatrixCursor(columns, 60);
            for (HomeEntries.Item item : HomeEntries.refresh(getContext(), shelf, signal).values()) {
                signal.throwIfCanceled();
                ProgramReader.Entry e = item.entry;
                MatrixCursor.RowBuilder row = cursor.newRow();
                for (String column : columns) {
                    switch (column) {
                        case "_id": row.add(item.key); break;
                        case "title": row.add(HomeContract.bounded(e.title)); break;
                        case "subtitle": row.add(item.subtitle); break;
                        case "owner": row.add(e.owner); break;
                        case "progress": row.add(HomeContract.progress(shelf, e.position, e.duration, e.kind)); break;
                        case "art_uri": row.add(item.artUri()); break;
                        case "youtube_channel": row.add(e.youtubeMetadata.channel); break;
                        case "youtube_published_date": row.add(e.youtubeMetadata.releaseDate); break;
                        case "youtube_published_label": row.add(e.youtubeMetadata.publishedLabel); break;
                        case "youtube_duration_millis": row.add(e.youtubeMetadata.durationMillis); break;
                        case "youtube_view_count": row.add(e.youtubeMetadata.viewCount); break;
                        case "youtube_live": row.add(e.youtubeMetadata.live); break;
                        default: throw new IllegalArgumentException("Unsupported column");
                    }
                }
            }
            signal.throwIfCanceled();
            return cursor;
        } finally { Binder.restoreCallingIdentity(token); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openFile(uri, mode, null);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal cancellation)
            throws FileNotFoundException {
        enforceCaller();
        List<String> segments = path(uri);
        if (!"r".equals(mode) || segments.size() != 3
                || !("art".equals(segments.get(0)) || "youtube_art".equals(segments.get(0)))
                || !HomeContract.validKey(segments.get(1)) || !HomeContract.validKey(segments.get(2)))
            throw new FileNotFoundException("Unsupported artwork request");
        String shelf = "youtube_art".equals(segments.get(0)) ? HomeContract.SHELF_YOUTUBE : HomeContract.SHELF_CONTINUE;
        CancellationSignal signal = cancellation == null ? new CancellationSignal() : cancellation;
        long token = Binder.clearCallingIdentity();
        try {
            HomeEntries.Item item = HomeEntries.resolve(getContext(), shelf, segments.get(1), signal);
            if (item == null || !item.version.equals(segments.get(2)))
                throw new FileNotFoundException("Entry no longer available");
            return PosterCache.open(getContext(), item, signal);
        } finally { Binder.restoreCallingIdentity(token); }
    }
    @Override public String getType(Uri uri) {
        enforceCaller();
        List<String> segments = path(uri);
        if (segments.size() == 1 && ("entries".equals(segments.get(0)) || "youtube_entries".equals(segments.get(0))))
            return "vnd.android.cursor.dir/vnd.com.david.tvcontinue.entry";
        if (segments.size() == 3 && ("art".equals(segments.get(0)) || "youtube_art".equals(segments.get(0)))
                && HomeContract.validKey(segments.get(1)) && HomeContract.validKey(segments.get(2))) return "image/png";
        throw new IllegalArgumentException("Unsupported path");
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
