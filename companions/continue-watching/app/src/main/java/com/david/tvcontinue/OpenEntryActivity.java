package com.david.tvcontinue;

import android.app.Activity;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Accepts an opaque key and an exact shelf name. External intent URLs are ignored. */
public final class OpenEntryActivity extends Activity {
    private final CancellationSignal cancellation = new CancellationSignal();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String key, requestedShelf;
        try {
            key = getIntent().getStringExtra("entry_key");
            requestedShelf = getIntent().getStringExtra("shelf");
        }
        catch (RuntimeException malformed) { finish(); return; }
        String shelf = requestedShelf == null ? HomeContract.SHELF_CONTINUE : requestedShelf;
        if (!HomeContract.validShelf(shelf) || !HomeContract.validKey(key)) { finish(); return; }
        TextView loading = new TextView(this);
        loading.setText(R.string.opening);
        loading.setGravity(android.view.Gravity.CENTER);
        loading.setTextSize(22);
        setContentView(loading);
        worker.execute(() -> {
            HomeEntries.Item item;
            try { item = HomeEntries.resolve(getApplicationContext(), shelf, key, cancellation); }
            catch (OperationCanceledException stopped) { return; }
            catch (RuntimeException unavailable) { item = null; }
            HomeEntries.Item resolved = item;
            runOnUiThread(() -> {
                if (cancellation.isCanceled() || isFinishing() || isDestroyed()) return;
                if (resolved != null) EntryLauncher.openEntry(this, resolved.entry);
                else Toast.makeText(this, R.string.entry_unavailable, Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }
    @Override protected void onPause() { cancellation.cancel(); finish(); super.onPause(); }
    @Override protected void onDestroy() { cancellation.cancel(); worker.shutdownNow(); super.onDestroy(); }
}
