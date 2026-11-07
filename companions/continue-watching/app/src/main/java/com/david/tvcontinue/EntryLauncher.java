package com.david.tvcontinue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

/** Launches only the owning application with the same checks for both UIs. */
final class EntryLauncher {
    static void openEntry(Activity activity, ProgramReader.Entry entry) {
        try {
            Intent safe = resolveEntryIntent(activity, entry);
            if (safe == null) {
                openApp(activity, entry.owner, true);
                return;
            }
            activity.startActivity(safe);
        } catch (Exception ignored) {
            openApp(activity, entry.owner, true);
        }
    }

    static boolean canOpenEntry(Context context, ProgramReader.Entry entry) {
        try { return resolveEntryIntent(context, entry) != null; }
        catch (Exception invalid) { return false; }
    }

    private static Intent resolveEntryIntent(Context context, ProgramReader.Entry entry) throws Exception {
        Intent parsed = Intent.parseUri(entry.intent, Intent.URI_INTENT_SCHEME);
        Uri data = parsed.getData();
        String componentOwner = parsed.getComponent() == null ? null : parsed.getComponent().getPackageName();
        if (!EntryPolicy.safeLink(entry.owner, componentOwner, parsed.getPackage(), parsed.getAction(),
                data == null ? null : data.getScheme(), data != null)) {
            return null;
        }
        // New intent: no URI grants, selector, clip data, task flags, or browser fallback from provider text.
        Intent safe = new Intent(parsed.getAction() == null ? Intent.ACTION_VIEW : parsed.getAction());
        safe.setPackage(entry.owner);
        safe.setData(data);
        if (parsed.getComponent() != null) safe.setComponent(parsed.getComponent());
        if (parsed.getExtras() != null) safe.putExtras(parsed.getExtras());
        ResolveInfo target = context.getPackageManager().resolveActivity(safe, 0);
        if (target == null || target.activityInfo == null || !target.activityInfo.exported
                || !target.activityInfo.enabled || !entry.owner.equals(target.activityInfo.packageName)) {
            return null;
        }
        return safe;
    }

    static void openApp(Activity activity, String owner, boolean fallback) {
        if (!EntryPolicy.SOURCES.contains(owner)) return;
        Intent intent = activity.getPackageManager().getLeanbackLaunchIntentForPackage(owner);
        if (intent != null) {
            try {
                activity.startActivity(intent);
                if (fallback) Toast.makeText(activity, R.string.source_opened, Toast.LENGTH_SHORT).show();
                return;
            } catch (RuntimeException ignored) { }
        }
        Toast.makeText(activity, R.string.source_unavailable, Toast.LENGTH_SHORT).show();
    }

}
