package com.daviddemri.tv.spotify;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** A modal destination selector that waits for confirmed playback transfer. */
final class SpotifyDevicePicker {
    private final Context context;
    private final Dialog dialog;
    private final LinearLayout content, rows;
    private final ScrollView scroll;
    private final ImageView refresh;
    private final TextView message;
    private final List<View> controls = new ArrayList<>();
    private final List<DeviceRow> deviceRows = new ArrayList<>();
    private final SpotifyFade contentFade, listFade;
    private final Predicate<String> select;
    private final float density;
    private WeakReference<View> pressed = new WeakReference<>(null);
    private String pendingId = "";
    private SpotifyConnect.State latestState;
    private boolean backArmed, closing;

    SpotifyDevicePicker(Context context, Predicate<String> select, Runnable reload, boolean animations, boolean reducedMotion) {
        this.context = context;
        this.select = select;
        density = context.getResources().getDisplayMetrics().density;
        dialog = new Dialog(context, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.setCanceledOnTouchOutside(true);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(14));
        content.setBackground(background(0xFF1B1D22, 16));
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(17, Color.WHITE);
        title.setText(R.string.spotify_devices_title);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        refresh = new ImageView(context);
        refresh.setImageResource(R.drawable.ic_music_refresh);
        refresh.setScaleType(ImageView.ScaleType.CENTER);
        refresh.setImageTintList(ColorStateList.valueOf(0xFFD8DEDA));
        refresh.setContentDescription(context.getString(R.string.spotify_refresh));
        refresh.setBackground(null); refresh.setFocusable(true); refresh.setClickable(true);
        refresh.setOnFocusChangeListener((view, focused) -> {
            refresh.setImageTintList(ColorStateList.valueOf(focused ? 0xFF1ED760 : 0xFFD8DEDA));
            view.setScaleX(focused ? 1.15f : 1f); view.setScaleY(focused ? 1.15f : 1f);
        });
        heading.addView(refresh, new LinearLayout.LayoutParams(dp(32), dp(32)));
        refresh.setOnClickListener(v -> {
            if (latestState == null || !latestState.loading) reload.run();
        });
        content.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        TextView explanation = text(11, 0xFFC3C8CC);
        explanation.setText(R.string.spotify_devices_hint);
        explanation.setPadding(0, dp(9), 0, dp(10));
        content.addView(explanation, new LinearLayout.LayoutParams(-1, -2));
        message = text(11, 0xFFC3C8CC);
        message.setPadding(0, 0, 0, dp(8));
        content.addView(message, new LinearLayout.LayoutParams(-1, -2));
        scroll = new ScrollView(context);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(57)));
        TextView footer = text(10, 0xFFC3C8CC);
        footer.setText(R.string.spotify_devices_footer);
        footer.setPadding(0, dp(10), 0, 0);
        content.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        contentFade = new SpotifyFade(animations, reducedMotion, content);
        listFade = new SpotifyFade(animations, reducedMotion, rows);
        dialog.setContentView(content);
        dialog.setOnKeyListener((ignored, code, event) -> key(code, event));
        dialog.setOnDismissListener(ignored -> {
            pressed.clear(); backArmed = closing = false; pendingId = ""; contentFade.cancel(); listFade.cancel();
        });
    }

    void show(SpotifyConnect.State state) {
        if (dialog.isShowing()) {
            if (closing) { closing = false; contentFade.cancel(); }
            render(state); return;
        }
        pendingId = ""; closing = false;
        render(state);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setWindowAnimations(0);
            window.setLayout(Math.min(dp(390), context.getResources().getDisplayMetrics().widthPixels - dp(40)), -2);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = .48f;
            window.setAttributes(attributes);
        }
        View selected = null;
        for (View row : controls) if (state.selectedId.equals(row.getTag()) && row.isEnabled()) selected = row;
        if (selected == null) selected = controls.size() > 1 ? controls.get(1) : refresh;
        selected.requestFocus();
        contentFade.enter();
    }

    void render(SpotifyConnect.State state) {
        latestState = state;
        if (closing) return;
        if (!pendingId.isEmpty() && !state.busy && !state.confirmationPending) {
            if (pendingId.equals(state.selectedId) && state.error.isEmpty()) {
                pendingId = ""; dismissAnimated(); return;
            } else if (!state.error.isEmpty()) pendingId = "";
        }
        View focused = content.findFocus();
        Object focusedId = focused == null ? null : focused.getTag();
        boolean refreshFocused = focused == refresh;
        String feedback = !state.error.isEmpty() ? state.error : !pendingId.isEmpty() && !state.notice.isEmpty()
                ? state.notice : state.devices.isEmpty() ? context.getString(state.loading
                ? R.string.spotify_devices_loading : R.string.spotify_devices_empty) : "";
        if (!TextUtils.equals(message.getText(), feedback)) message.setText(feedback);
        message.setVisibility(feedback.isEmpty() ? View.GONE : View.VISIBLE);
        int height = Math.min(dp(224), Math.max(1, state.devices.size()) * (dp(52) + dp(5)));
        LinearLayout.LayoutParams viewport = (LinearLayout.LayoutParams) scroll.getLayoutParams();
        if (viewport.height != height) { viewport.height = height; scroll.setLayoutParams(viewport); }
        // Refresh remains focused while the read is pending; repeated clicks are ignored above.
        enable(refresh, !state.busy && !state.confirmationPending);
        refresh.setAlpha(state.loading ? .55f : refresh.isEnabled() ? 1f : .4f);
        Map<String, DeviceRow> reusable = new HashMap<>();
        for (DeviceRow row : deviceRows) reusable.put(row.id, row);
        boolean sameOrder = deviceRows.size() == state.devices.size();
        List<DeviceRow> next = new ArrayList<>(state.devices.size());
        controls.clear(); controls.add(refresh);
        for (int i = 0; i < state.devices.size(); i++) {
            SpotifyConnect.Device device = state.devices.get(i);
            DeviceRow row = reusable.remove(device.id);
            if (row == null) row = new DeviceRow(device.id);
            row.bind(device, state);
            if (sameOrder && deviceRows.get(i) != row) sameOrder = false;
            next.add(row);
            if (row.view.isEnabled()) controls.add(row.view);
        }
        if (!sameOrder) {
            rows.removeAllViews();
            for (DeviceRow row : next) {
                LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, dp(52));
                position.bottomMargin = dp(5); rows.addView(row.view, position);
            }
            if (dialog.isShowing()) listFade.enter();
        }
        deviceRows.clear(); deviceRows.addAll(next);
        if (dialog.isShowing() && (!sameOrder || focused == null || !focused.isEnabled())) {
            View restore = refreshFocused ? refresh : null;
            for (DeviceRow row : deviceRows) if (focusedId != null && focusedId.equals(row.id)) restore = row.view;
            if (restore == null || !restore.isEnabled()) restore = firstEnabled();
            if (restore != null) restore.requestFocus();
        }
    }

    private final class DeviceRow {
        final String id;
        final LinearLayout view;
        final TextView name, detail, mark;
        DeviceRow(String id) {
            this.id = id;
            view = new LinearLayout(context); view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(11), dp(7), dp(11), dp(7)); view.setTag(id);
            LinearLayout copy = new LinearLayout(context); copy.setOrientation(LinearLayout.VERTICAL);
            name = text(13, Color.WHITE); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(name, new LinearLayout.LayoutParams(-1, -2));
            detail = text(10, 0xFFC3C8CC);
            LinearLayout.LayoutParams detailPosition = new LinearLayout.LayoutParams(-1, -2);
            detailPosition.topMargin = dp(4); copy.addView(detail, detailPosition);
            view.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            mark = text(14, 0xFF1ED760); mark.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            view.addView(mark, new LinearLayout.LayoutParams(dp(24), -1));
            control(view);
            view.setOnClickListener(v -> {
                if (closing) return;
                pendingId = id;
                if (!select.test(id)) pendingId = "";
                else if (latestState != null) render(latestState);
            });
        }
        void bind(SpotifyConnect.Device device, SpotifyConnect.State state) {
            boolean selected = id.equals(state.selectedId);
            if (!TextUtils.equals(name.getText(), device.name)) name.setText(device.name);
            String label = device.type.isEmpty() ? "Spotify" : device.type;
            if (device.restricted) label += " · " + context.getString(R.string.spotify_device_restricted);
            else if (device.active) label += " · " + context.getString(R.string.spotify_device_active);
            if (!TextUtils.equals(detail.getText(), label)) detail.setText(label);
            String selectedMark = selected ? "✓" : "";
            if (!TextUtils.equals(mark.getText(), selectedMark)) mark.setText(selectedMark);
            enable(view, !device.restricted && !state.busy && !state.confirmationPending);
            view.setContentDescription(device.name + ", " + label + (selected ? ", " + context.getString(R.string.spotify_device_selected) : ""));
        }
    }

    void dismiss() {
        contentFade.cancel(); listFade.cancel(); closing = false;
        if (dialog.isShowing()) dialog.dismiss();
    }

    private void dismissAnimated() {
        if (!dialog.isShowing() || closing) return;
        closing = true; pressed.clear();
        contentFade.exit(this::dismiss, true);
    }

    private boolean key(int code, KeyEvent event) {
        if (closing) return true;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) backArmed = event.getRepeatCount() == 0 && !event.isCanceled();
            if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean close = backArmed && !event.isCanceled(); backArmed = false;
                if (close) dismissAnimated();
            }
            return true;
        }
        boolean activate = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (activate) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0 && !event.isCanceled()) pressed = new WeakReference<>(content.findFocus());
                else pressed.clear();
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                View target = pressed.get(); pressed.clear();
                if (!event.isCanceled() && target != null && target == content.findFocus() && target.isEnabled()) target.performClick();
            }
            return true;
        }
        int step = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT ? -1
                : code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        if (step == 0) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int current = controls.indexOf(content.findFocus());
            for (int index = current + step; index >= 0 && index < controls.size(); index += step) {
                if (controls.get(index).isEnabled()) { controls.get(index).requestFocus(); break; }
            }
        }
        return true;
    }

    private View firstEnabled() { for (View control : controls) if (control.isEnabled()) return control; return null; }
    private void control(View control) {
        control.setBackground(background(0xFF33363C, 8)); control.setFocusable(true); control.setClickable(true);
        control.setOnFocusChangeListener((view, focused) -> {
            GradientDrawable background = (GradientDrawable) view.getBackground();
            background.setColor(focused ? 0xFF43664E : 0xFF33363C);
            background.setStroke(focused ? dp(2) : 0, 0xFF1ED760);
        });
    }
    private void enable(View view, boolean enabled) { view.setEnabled(enabled); view.setFocusable(enabled); view.setAlpha(enabled ? 1 : .4f); }
    private TextView text(int size, int color) { TextView view = new TextView(context); view.setTextSize(size); view.setTextColor(color); view.setIncludeFontPadding(false); return view; }
    private GradientDrawable background(int color, int radius) { GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius)); return result; }
    private int dp(float value) { return Math.round(value * density); }
}
