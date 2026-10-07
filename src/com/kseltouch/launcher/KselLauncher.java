package com.kseltouch.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * KSEL Touch home activity.
 * Wallpaper is decoded only when it actually changed (broadcast + 5 min safety
 * refresh), not on every resume - getWallpaper() is expensive on low-end phones.
 */
public class KselLauncher extends Activity {
    private static final long WALLPAPER_MAX_AGE_MS = 5 * 60 * 1000L;

    private KselView ksel;
    private boolean wallpaperDirty = true;
    private long wallpaperChecked = 0;

    private final BroadcastReceiver wallpaperReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            wallpaperDirty = true;
        }
    };

    public void onCreate(Bundle b) {
        super.onCreate(b);
        ksel = new KselView(this);
        ksel.setFocusable(true);
        ksel.requestFocus();
        try {
            registerReceiver(wallpaperReceiver, new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED));
        } catch (Exception e) {
            Log.w("Launcher", "wallpaper receiver: " + e);
        }
        refreshWallpaper();
        applyEndKeyDefault();
        switchToHome();
    }

    /** Re-reads the system wallpaper (window background + light/dark chrome). */
    private void refreshWallpaper() {
        wallpaperDirty = false;
        wallpaperChecked = SystemClock.uptimeMillis();
        try {
            Drawable wp = getWallpaper();
            getWindow().setBackgroundDrawable(wp);
            ksel.setChromeDark(wallpaperLuminance(wp) * 0.62f > 0.5f);
        } catch (Exception e) {
            Log.w("Launcher", "getWallpaper: " + e);
        } catch (OutOfMemoryError e) {
            Log.w("Launcher", "getWallpaper OOM");
        }
    }

    private static float wallpaperLuminance(Drawable d) {
        try {
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                android.graphics.Bitmap b =
                        ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                if (b != null && !b.isRecycled()) {
                    int w = b.getWidth(), h = b.getHeight();
                    int step = Math.max(1, Math.min(w, h) / 48);
                    long sum = 0;
                    long n = 0;
                    for (int y = 0; y < h; y += step) {
                        for (int x = 0; x < w; x += step) {
                            int p = b.getPixel(x, y);
                            int r = (p >> 16) & 255;
                            int g = (p >> 8) & 255;
                            int bl = p & 255;
                            sum += (r * 2126L + g * 7152L + bl * 722L) / 10000L;
                            n++;
                        }
                    }
                    if (n > 0) return sum / (float) (n * 255);
                }
            }
        } catch (Exception e) {
            // fall through
        } catch (OutOfMemoryError e) {
            // fall through
        }
        return 0.3f;
    }

    private void applyEndKeyDefault() {
        try {
            String cur = Settings.System.getString(getContentResolver(), "end_button_behavior");
            if (cur == null) {
                Settings.System.putInt(getContentResolver(), "end_button_behavior", 3);
            }
        } catch (Exception e) {
            Log.w("Launcher", "END_BUTTON_BEHAVIOR default: " + e);
        }
    }

    public void switchToHome() {
        // Re-adding the view would detach it (releasing bitmaps and the sound pool).
        if (ksel.getParent() == null) setContentView(ksel);
        ksel.requestFocus();
        ksel.setAlpha(0f);
        try {
            ksel.animate().alpha(1f).setDuration(350).start();
        } catch (Exception e) {
            ksel.setAlpha(1f);
        }
        ksel.onResumeView();
    }

    public void switchToApps() {
        if (ksel.getParent() == null) setContentView(ksel);
        ksel.requestFocus();
        ksel.goToFirst();
    }

    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        switchToHome();
    }

    protected void onResume() {
        super.onResume();
        if (ksel != null && ksel.getParent() != null) {
            if (wallpaperDirty
                    || SystemClock.uptimeMillis() - wallpaperChecked > WALLPAPER_MAX_AGE_MS) {
                refreshWallpaper();
            }
            ksel.onResumeView();
        }
    }

    protected void onPause() {
        if (ksel != null) ksel.onPauseView();
        super.onPause();
    }

    protected void onDestroy() {
        try {
            unregisterReceiver(wallpaperReceiver);
        } catch (Exception e) {
            // ignore
        }
        super.onDestroy();
    }

    /** A launcher never finishes on BACK (touch phones deliver it from the nav bar). */
    public void onBackPressed() {
        // intentionally empty
    }
}
