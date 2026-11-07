package com.daviddemri.tv.spotify;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Read-only title stream, bound only while the signed Home is visible. Never starts playback. */
public final class SpotifyHomeService extends Service {
    private static final String HOME = "com.gathin.tvhome";
    private static final int SUBSCRIBE = 1, UNSUBSCRIBE = 2, SNAPSHOT = 100;
    private static final long POLL_MS = 5_000, ERROR_POLL_MS = 30_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SpotifyHomeState state = new SpotifyHomeState();
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final Runnable poll = this::readCurrent;
    private final Runnable expire = this::publish;
    private IBinder.DeathRecipient clientDeath;
    private SpotifyAuthStore auth;
    private SpotifyApi api;
    private Messenger client;
    private ExecutorService worker;
    private Future<?> request;
    private boolean running;
    private int generation;
    private long authRevision = -1;
    private String sentTitle = "", sentArtist = "";
    private boolean sentPlaying, sent;

    @Override public void onCreate() {
        super.onCreate();
        auth = new SpotifyAuthStore(this);
        api = new SpotifyApi(auth);
    }

    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { stopObserving(); return false; }
    @Override public void onDestroy() { stopObserving(); super.onDestroy(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private boolean receive(Message message) {
        if (!trusted(message.sendingUid) || message.replyTo == null) return true;
        if (message.what == SUBSCRIBE) {
            stopObserving();
            client = message.replyTo;
            IBinder subscribed = client.getBinder();
            clientDeath = () -> main.post(() -> {
                if (client != null && subscribed.equals(client.getBinder())) stopObserving();
            });
            try { client.getBinder().linkToDeath(clientDeath, 0); }
            catch (RemoteException dead) { client = null; clientDeath = null; return true; }
            running = true; ++generation; sent = false;
            readCurrent();
        } else if (message.what == UNSUBSCRIBE && client != null
                && client.getBinder().equals(message.replyTo.getBinder())) stopObserving();
        return true;
    }

    private boolean trusted(int uid) {
        if (uid < 0 || getPackageManager().checkSignatures(uid, android.os.Process.myUid())
                != PackageManager.SIGNATURE_MATCH) return false;
        String[] packages = getPackageManager().getPackagesForUid(uid);
        return packages != null && Arrays.asList(packages).contains(HOME);
    }

    private void readCurrent() {
        if (!running) return;
        main.removeCallbacks(poll);
        long revision = auth.revision();
        if (revision != authRevision) { authRevision = revision; state.clear(); main.removeCallbacks(expire); }
        publish();
        if (!running) return;
        if (!auth.isConnected() || request != null || System.currentTimeMillis() < api.retryAt()) {
            main.postDelayed(poll, Math.max(POLL_MS, api.retryAt() - System.currentTimeMillis()));
            return;
        }
        int expected = generation;
        if (worker == null) worker = Executors.newSingleThreadExecutor();
        api.begin();
        request = worker.submit(() -> {
            SpotifyLibrary.NowPlaying result = null;
            try { result = SpotifyLibrary.parseCurrent(api.get("/me/player?additional_types=track,episode")); }
            catch (Exception unavailable) { /* No metadata, credentials, request URLs or errors enter logs. */ }
            SpotifyLibrary.NowPlaying received = result;
            main.post(() -> {
                if (!running || expected != generation) return;
                request = null;
                if (revision == auth.revision()) {
                    if (received != null) {
                        main.removeCallbacks(expire);
                        state.update(received.title, received.subtitle, received.playing, SystemClock.elapsedRealtime());
                        main.postDelayed(expire, SpotifyHomeState.REMOTE_MAX_AGE_MS);
                    }
                } else state.clear();
                // Refresh the Home cache's age even when this successful read kept the same labels.
                publish(received != null);
                main.removeCallbacks(poll);
                if (running) main.postDelayed(poll, received == null ? ERROR_POLL_MS : POLL_MS);
            });
        });
    }

    private void publish() { publish(false); }

    private void publish(boolean freshRead) {
        if (!running || client == null) return;
        // A newly bound reader has no observation yet. Do not turn that into a false pause flash.
        if (!state.known() && auth.isConnected()) return;
        SpotifyHomeState.Snapshot current = state.snapshot(SystemClock.elapsedRealtime());
        if (!freshRead && sent && sentPlaying == current.playing && sentTitle.equals(current.title) && sentArtist.equals(current.artist)) return;
        Bundle data = new Bundle();
        data.putString("title", current.title);
        data.putString("artist", current.artist);
        data.putBoolean("playing", current.playing);
        data.putLong("updated_at", SystemClock.elapsedRealtime());
        Message message = Message.obtain(null, SNAPSHOT);
        message.setData(data);
        try {
            client.send(message);
            sent = true; sentTitle = current.title; sentArtist = current.artist; sentPlaying = current.playing;
        } catch (RemoteException dead) { stopObserving(); }
    }

    private void stopObserving() {
        running = false; ++generation;
        main.removeCallbacks(poll);
        main.removeCallbacks(expire);
        if (client != null && clientDeath != null) {
            try { client.getBinder().unlinkToDeath(clientDeath, 0); } catch (java.util.NoSuchElementException ignored) { }
        }
        client = null; clientDeath = null;
        if (api != null) api.cancel();
        if (request != null) request.cancel(true);
        request = null;
        if (worker != null) worker.shutdownNow();
        worker = null;
        state.clear();
        sent = false;
    }
}
