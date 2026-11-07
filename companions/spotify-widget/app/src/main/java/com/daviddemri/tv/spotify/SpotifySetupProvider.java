package com.daviddemri.tv.spotify;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Local provisioning only: the manifest requires WRITE_SECURE_SETTINGS for every external call. */
public final class SpotifySetupProvider extends ContentProvider {
    private static final String AUTHORITY = "com.daviddemri.tv.spotify.setup";
    private static final int MAX_BYTES = 16384;
    private volatile String provisionId = "", error = "";
    private SpotifyAuthStore auth;

    @Override public boolean onCreate() {
        auth = new SpotifyAuthStore(getContext());
        return true;
    }

    private String path(Uri uri) {
        if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null || uri.getPathSegments().size() != 1) {
            throw new IllegalArgumentException("Unsupported setup URI");
        }
        return uri.getLastPathSegment();
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!"status".equals(path(uri)) || projection != null || selection != null || args != null || sort != null) {
            throw new IllegalArgumentException("Unsupported status request");
        }
        JSONObject status = auth.status();
        try { status.put("provision_id", provisionId).put("provisioning_error", error); }
        catch (Exception ignored) { }
        MatrixCursor cursor = new MatrixCursor(new String[]{"status"}, 1);
        cursor.addRow(new Object[]{status.toString()});
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"auth".equals(path(uri)) || !("w".equals(mode) || "wt".equals(mode))) {
            throw new FileNotFoundException("Unsupported provisioning request");
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread receiver = new Thread(() -> receive(pipe[0]), "Spotify authorization setup");
            receiver.setDaemon(true);
            receiver.start();
            return pipe[1];
        } catch (Exception unavailable) {
            throw new FileNotFoundException("Could not open setup channel");
        }
    }

    private synchronized void receive(ParcelFileDescriptor descriptor) {
        String requestId = "";
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new IllegalArgumentException("Setup payload too large");
                output.write(buffer, 0, count);
            }
            JSONObject payload = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            requestId = payload.optString("provision_id", "");
            if (!requestId.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid provisioning identifier");
            auth.saveOAuth(payload);
            error = "";
        } catch (Exception invalid) {
            // Do not expose tokens, response bodies, or input data in errors or logs.
            error = "Spotify authorization could not be saved. Please reconnect.";
        } finally {
            provisionId = requestId;
            getContext().getContentResolver().notifyChange(Uri.parse("content://" + AUTHORITY + "/status"), null);
        }
    }

    @Override public String getType(Uri uri) { return "status".equals(path(uri)) ? "vnd.android.cursor.item/vnd.spotify.setup" : null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Use the protected setup pipe"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read-only status"); }
    @Override public int delete(Uri uri, String selection, String[] args) {
        if (!"auth".equals(path(uri)) || selection != null || args != null) throw new IllegalArgumentException("Unsupported disconnect request");
        auth.clear();
        provisionId = "";
        error = "";
        getContext().getContentResolver().notifyChange(Uri.parse("content://" + AUTHORITY + "/status"), null);
        return 1;
    }
}
