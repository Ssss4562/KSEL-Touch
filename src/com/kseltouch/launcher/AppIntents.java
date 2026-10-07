package com.kseltouch.launcher;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.util.Log;
import android.widget.Toast;

/**
 * Preserved 1:1 from legacy launcher (decompiled via dexdump).
 * Tries a list of intents in order, starts the first that resolves.
 * Shows "%1$s: no such application" toast if none resolves.
 */
public final class AppIntents {
    private static final String TAG = "AppIntents";

    private AppIntents() {}

    public static boolean launch(Context ctx, int labelResId, Intent... candidates) {
        return launch(ctx, ctx.getString(labelResId), candidates);
    }

    public static boolean launch(Context ctx, CharSequence label, Intent... candidates) {
        PackageManager pm = ctx.getPackageManager();
        for (Intent it : candidates) {
            if (it == null) continue;
            if (it.resolveActivity(pm) == null) continue;
            try {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
                return true;
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "launch: " + it + " failed: " + e);
            } catch (SecurityException e) {
                Log.w(TAG, "launch: " + it + " failed: " + e);
            }
        }
        String msg = ctx.getString(R.string.no_app, label);
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        return false;
    }

    public static Intent launcherFor(Context ctx, String pkg) {
        if (pkg == null) return null;
        try {
            return ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean dialer(Context ctx) {
        return launch(ctx, R.string.app_dialer,
                new Intent(Intent.ACTION_DIAL),
                launcherFor(ctx, "com.android.dialer"));
    }

    public static boolean dial(Context ctx, String number) {
        Uri uri = Uri.fromParts("tel", number, null);
        return launch(ctx, R.string.app_dialer,
                new Intent(Intent.ACTION_DIAL, uri),
                new Intent(Intent.ACTION_DIAL));
    }

    public static boolean contacts(Context ctx) {
        return launch(ctx, R.string.app_contacts,
                new Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS),
                launcherFor(ctx, "com.android.contacts"));
    }

    public static boolean messages(Context ctx) {
        String defSms = null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 19) {
                defSms = Telephony.Sms.getDefaultSmsPackage(ctx);
            }
        } catch (Throwable e) {
            Log.w(TAG, "getDefaultSmsPackage: " + e);
        }
        return launch(ctx, R.string.app_messages,
                launcherFor(ctx, defSms),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
                launcherFor(ctx, "com.android.messaging"));
    }

    public static boolean files(Context ctx) {
        Intent browse = new Intent("android.provider.action.BROWSE");
        try {
            browse.setDataAndType(Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/root");
        } catch (Exception e) {
            Log.w(TAG, "files browse intent: " + e);
        }
        return launch(ctx, R.string.files,
                launcherFor(ctx, "com.android.documentsui"),
                browse,
                new Intent("android.intent.action.VIEW_DOWNLOADS"));
    }

    public static boolean calendar(Context ctx) {
        return launch(ctx, R.string.app_calendar,
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR),
                new Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI),
                launcherFor(ctx, "com.android.calendar"));
    }

    public static boolean sound(Context ctx) {
        return launch(ctx, R.string.app_sound,
                new Intent("android.settings.SOUND_SETTINGS"),
                new Intent("android.settings.SETTINGS"));
    }

    public static boolean camera(Context ctx) {
        return launch(ctx, R.string.app_camera,
                new Intent("android.media.action.STILL_IMAGE_CAMERA"),
                launcherFor(ctx, "com.android.camera2"));
    }

    // ---- Extended targets (same launch() pattern, no new behaviour) ----

    public static boolean browser(Context ctx) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"));
        return launch(ctx, "Internet",
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
                view,
                launcherFor(ctx, "com.android.browser"));
    }

    public static boolean gallery(Context ctx) {
        return launch(ctx, "Video",
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
                new Intent(Intent.ACTION_VIEW, Uri.parse("content://media/internal/images/media")),
                launcherFor(ctx, "com.android.gallery3d"));
    }

    public static boolean music(Context ctx) {
        return launch(ctx, "Media",
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC),
                launcherFor(ctx, "com.android.music"));
    }

    public static boolean alarm(Context ctx) {
        Intent i = new Intent("android.intent.action.SHOW_ALARMS");
        return launch(ctx, "Alarms",
                i,
                launcherFor(ctx, "com.android.deskclock"));
    }

    public static boolean settings(Context ctx) {
        return launch(ctx, "Settings",
                new Intent("android.settings.SETTINGS"));
    }

    public static boolean radio(Context ctx) {
        // No standard RADIO intent; try FM-radio packages, else toast.
        String[] pkgs = {"com.android.fmradio", "com.android.fm", "com.caf.fmradio"};
        Intent[] c = new Intent[pkgs.length + 1];
        for (int k = 0; k < pkgs.length; k++) c[k] = launcherFor(ctx, pkgs[k]);
        c[pkgs.length] = new Intent("android.intent.action.VIEW", Uri.parse("file:///sdcard/Music"));
        return launch(ctx, "Radio", c);
    }

    public static boolean organizer(Context ctx) {
        // Organizer = calendar fallback chain (same as calendar but different label).
        return launch(ctx, "Organizer",
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR),
                new Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI),
                launcherFor(ctx, "com.android.calendar"));
    }
}
