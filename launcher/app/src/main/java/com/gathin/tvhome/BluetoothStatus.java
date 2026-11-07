package com.gathin.tvhome;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2;
import android.media.RouteDiscoveryPreference;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Read-only Bluetooth power and media-output status, observed only while Home is visible. */
final class BluetoothStatus {
    interface Listener {
        void onStatus(State state);
    }

    static final class State {
        final boolean enabledKnown;
        final boolean enabled;
        final String label;
        final boolean bluetoothAudioSelected;

        State(boolean enabledKnown, boolean enabled, String label, boolean bluetoothAudioSelected) {
            this.enabledKnown = enabledKnown;
            this.enabled = enabled;
            this.label = label;
            this.bluetoothAudioSelected = bluetoothAudioSelected;
        }

        boolean sameAs(State other) {
            return other != null && enabledKnown == other.enabledKnown
                    && enabled == other.enabled && label.equals(other.label)
                    && bluetoothAudioSelected == other.bluetoothAudioSelected;
        }
    }

    private final Context context;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::readStatus;
    private Listener listener;
    private State last;
    private boolean active, destroyed, audioRegistered, receiverRegistered;
    private Api30RouteWatcher routeWatcher;

    private final AudioDeviceCallback audioCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { scheduleRefresh(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { scheduleRefresh(); }
    };

    private final BroadcastReceiver adapterReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) scheduleRefresh();
        }
    };

    BluetoothStatus(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        audio = this.context.getSystemService(AudioManager.class);
    }

    void resume() {
        if (destroyed || active) return;
        active = true;
        if (audio != null) {
            try {
                audio.registerAudioDeviceCallback(audioCallback, handler);
                audioRegistered = true;
            } catch (RuntimeException ignored) {
                // A read at resume still works on firmware without callback support.
            }
        }
        try {
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                // Bluetooth broadcasts can originate in the privileged Bluetooth app, not system UID.
                // This action is system-protected; third-party apps cannot send it.
                context.registerReceiver(adapterReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(adapterReceiver, filter);
            }
            receiverRegistered = true;
        } catch (RuntimeException ignored) {
            // Never request a permission or change the adapter to obtain status.
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                routeWatcher = new Api30RouteWatcher(context, handler, this::scheduleRefresh);
                routeWatcher.start();
            } catch (RuntimeException ignored) {
                routeWatcher = null;
            }
        }
        readStatus();
    }

    void pause() {
        active = false;
        handler.removeCallbacks(refresh);
        if (audioRegistered) {
            try { audio.unregisterAudioDeviceCallback(audioCallback); }
            catch (RuntimeException ignored) { }
            audioRegistered = false;
        }
        if (receiverRegistered) {
            try { context.unregisterReceiver(adapterReceiver); }
            catch (RuntimeException ignored) { }
            receiverRegistered = false;
        }
        if (routeWatcher != null) {
            routeWatcher.stop();
            routeWatcher = null;
        }
    }

    void destroy() {
        pause();
        destroyed = true;
        listener = null;
        last = null;
    }

    private void scheduleRefresh() {
        if (!active || destroyed) return;
        handler.removeCallbacks(refresh);
        // Coalesce adapter, route and device events while Android settles the media output.
        handler.postDelayed(refresh, 750);
    }

    private void readStatus() {
        if (!active || destroyed) return;
        int state = BluetoothAdapter.ERROR;
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter != null) state = adapter.getState();
        } catch (RuntimeException ignored) {
            // Includes SecurityException on firmware that restricts otherwise read-only status.
        }
        boolean known = state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_OFF
                || state == BluetoothAdapter.STATE_TURNING_ON || state == BluetoothAdapter.STATE_TURNING_OFF;
        boolean enabled = state == BluetoothAdapter.STATE_ON;
        State next;
        if (state == BluetoothAdapter.STATE_OFF) next = new State(known, enabled, "Off", false);
        else if (state == BluetoothAdapter.STATE_TURNING_ON) next = new State(known, enabled, "Turning on…", false);
        else if (state == BluetoothAdapter.STATE_TURNING_OFF) next = new State(known, enabled, "Turning off…", false);
        else if (Build.VERSION.SDK_INT >= 33) next = readMediaStatus(known, enabled);
        else next = new State(known, enabled, enabled ? "On" : "Status unavailable", false);
        if (!next.sameAs(last)) {
            last = next;
            if (listener != null) listener.onStatus(next);
        }
    }

    @TargetApi(33)
    private State readMediaStatus(boolean known, boolean enabled) {
        if (audio == null) return new State(known, enabled, "Status unavailable", false);
        try {
            AudioAttributes media = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).build();
            List<AudioDeviceInfo> outputs = audio.getAudioDevicesForAttributes(media);
            Set<String> names = new LinkedHashSet<>();
            for (AudioDeviceInfo output : outputs) {
                int type = output.getType();
                if (type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        && type != AudioDeviceInfo.TYPE_BLE_HEADSET
                        && type != AudioDeviceInfo.TYPE_BLE_SPEAKER
                        && type != AudioDeviceInfo.TYPE_HEARING_AID) continue;
                CharSequence product = output.getProductName();
                String name = product == null ? "" : product.toString().trim();
                names.add(name.isEmpty() ? "Headphones" : name);
            }
            if (!names.isEmpty()) return new State(known, enabled, String.join(" + ", names), true);
            // A connected/paired accessory inventory would not prove the selected media output.
            return new State(known, enabled, enabled && !outputs.isEmpty() ? "" : "Status unavailable", false);
        } catch (RuntimeException ignored) {
            return new State(known, enabled, "Status unavailable", false);
        }
    }

    /** Controller updates also catch switching between outputs that were already connected. */
    @TargetApi(30)
    private static final class Api30RouteWatcher {
        private final MediaRouter2 router;
        private final Handler handler;
        private final MediaRouter2.ControllerCallback callback;
        private final MediaRouter2.RouteCallback routeCallback;

        Api30RouteWatcher(Context context, Handler handler, Runnable changed) {
            router = MediaRouter2.getInstance(context);
            this.handler = handler;
            callback = new MediaRouter2.ControllerCallback() {
                @Override public void onControllerUpdated(MediaRouter2.RoutingController controller) {
                    changed.run();
                }
            };
            routeCallback = new MediaRouter2.RouteCallback() {
                @Override public void onRoutesUpdated(List<MediaRoute2Info> routes) { changed.run(); }
                @Override public void onRoutesAdded(List<MediaRoute2Info> routes) { changed.run(); }
                @Override public void onRoutesRemoved(List<MediaRoute2Info> routes) { changed.run(); }
                @Override public void onRoutesChanged(List<MediaRoute2Info> routes) { changed.run(); }
            };
        }

        void start() {
            router.registerControllerCallback(handler::post, callback);
            try {
                // A route callback establishes the system subscription. Local outputs only,
                // with active discovery explicitly disabled; never scan or transfer a route.
                router.registerRouteCallback(handler::post, routeCallback,
                        new RouteDiscoveryPreference.Builder(
                                // AOSP's local-route feature is a string contract; its Java constant is @hide.
                                Collections.singletonList("android.media.route.feature.LOCAL_PLAYBACK"), false).build());
            } catch (RuntimeException error) {
                stop();
                throw error;
            }
        }

        void stop() {
            try { router.unregisterRouteCallback(routeCallback); }
            catch (RuntimeException ignored) { }
            try { router.unregisterControllerCallback(callback); }
            catch (RuntimeException ignored) { }
        }
    }
}
