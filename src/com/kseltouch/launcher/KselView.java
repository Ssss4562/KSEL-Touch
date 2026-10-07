package com.kseltouch.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * KSEL home + menu.
 *
 * HOME (standby): system wallpaper stays visible (only a light translucent
 * scrim is drawn), glass clock panel (glassmorphism), date + free RAM.
 * date in device locale. D-pad shortcuts restore the
 * original home bindings (messages/calendar/sound/notify/files/dial).
 *
 * MENU: 3x4 paginated grid of REAL installed apps (real icons + labels).
 * Selected icon x1.2 in a frosted-glass frame. Title strictly centered,
 * no underline bar.
 *
 * All user-visible strings come from resources (R.string.*).
 * Effects are time-based, zero allocation in onDraw.
 * anim_mode: 0 full / 1 reduced (motion only) / 2 off (static).
 */
public class KselView extends View {
    private static final String TAG = "KselView";

    // ================= ANIMATION / TIMING TABLE (tweak here) =================
    private static final long SEL_MOVE_MS = 200;
    private static final long TITLE_MS = 200;
    private static final long LAUNCH_MS = 180;      // launch zoom+fade (matches system)
    private static final long ENTER_MS = 600;
    private static final long ENTER_STAGGER = 25;
    private static final long SOFTKEY_MS = 180;
    private static final long PAGE_MS = 200;
    private static final long MODE_MS = 220;
    private static final long SET_ENTER_MS = 260;
    private static final long SET_STAGGER = 60;
    private static final long MEM_MS = 5000;          // RAM poll period
    private static final float SEL_SCALE = 1.2f;      // selected icon scale
    private static final float UNSCALE_DIM = 0.92f;
    private static final int UNSCALE_ALPHA = 235;
    private static final int PAGE_SIZE = 12;
    // ========================================================================

    private static final int MODE_HOME = 0;
    private static final int MODE_GRID = 1;
    private static final int MODE_SETTINGS = 2;
    private static final int MODE_APPS = 3;

    private final KselLauncher host;
    private final KselSound sound;
    private final Handler handler = new Handler();

    // reused gfx (no alloc in onDraw)
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFrame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBeam = new Paint();
    private final Paint pBmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint pDim = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint pBg = new Paint();
    private final Paint pVigTop = new Paint();
    private final Paint pVigBot = new Paint();
    private final Paint pClock = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGlass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGlassLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPanelBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pFrameBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pWash = new Paint();
    private final Paint pGlowBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rf = new RectF();
    private final RectF rf2 = new RectF();
    private final Rect ri = new Rect();
    private final Matrix mx = new Matrix();
    private final android.graphics.Path tmpPath = new android.graphics.Path();
    private final Calendar cal = Calendar.getInstance();
    private final StringBuilder sbT = new StringBuilder(8);

    // UI strings from resources (locale-aware)
    private String sSelect = "Select";
    private String sBack = "Back";
    private String sOptionsBtn = "Options";
    private String sChange = "Change";
    private String sMenu = "Menu";
    private String sFiles = "Files";
    private String sContacts = "Contacts";
    private String sOptions = "Options";
    private String sEmpty = "...";
    private String sHint = "";
    private final String[] setNames = new String[8];
    private final String[] setVals = new String[8];
    private String sFull = "Full";
    private String sSimple = "Reduced";
    private String sNone = "Off";
    private String sOn = "On";
    private String sOff = "Off";

    // home clock cache (rebuilt on minute change only)
    private String timeStr = "--:--";
    private String dateStr = "";
    private String memStr = "";
    private long dateKey = -1;
    private long lastMemPoll = 0;
    private java.text.SimpleDateFormat dateFmt;
    private ActivityManager am;
    private ActivityManager.MemoryInfo memInfo;

    private int iconPx = 64;
    private long startTime;
    private long enterStart;
    private boolean enterDone = false;

    private final float[] cellCX = new float[PAGE_SIZE];
    private final float[] cellCY = new float[PAGE_SIZE];

    private static boolean drawErrOnce = false;
    private int selected = 0;
    private int selFrom = 0;
    private volatile int appsSig = 0;
    private boolean firstEnter = true;
    private long selStart = 0;
    private float selX = 0, selY = 0, selFromX = 0, selFromY = 0;

    private String titleOld = "";
    private long titleStart = 0;

    private boolean launching = false;
    private long launchStart = 0;
    private int launchCell = -1;

    private long pageAnimStart = -10000;
    private int pageAnimDir = 1;
    private long pageIndStart = -10000;
    private int pageIndFrom = -1;
    private long homeEnterStart = -10000;
    private long modeAnimStart = -10000;
    private int modeAnimDir = 1;
    private boolean modeVertical = true;
    
    private long setEnterStart = -10000;

    private boolean lastBgDark = false;
    private int bgTintCur = 0xFF6A5ACD;
    private int bgTintTarget = 0xFF6A5ACD;

    // pre-rendered glass (real blur, built once — never in onDraw)
    private Bitmap panelBmp;
    private float panelM = 14f;
    private Bitmap frameBmp;
    private Bitmap glowBmp;

    private String softL = "Select", softLOld = "Select";
    private String softR = "Back", softROld = "Back";
    private long softStart = 0;

    private int mode = MODE_HOME;
    private final ArrayList<AppEntry> apps = new ArrayList<AppEntry>();
    private final ArrayList<AppEntry> vis = new ArrayList<AppEntry>();
    private final java.util.HashSet<String> hidden = new java.util.HashSet<String>();

    // app manager (hide apps)
    private int appSel = 0;
    private float appScroll = 0f;
    private float appScrollTarget = 0f;
    private long appScrollStart = 0;
    private float appScrollFrom = 0f;
    private String sAppsTitle = "Apps";
    private String sShowAll = "Show all";
    private String sHiddenTag = "hidden";
    private String sVWall = "Wallpaper";
    private String sVDark = "Dark";
    private String sVSettings = "Settings";
    private Bitmap settingsIcon;

    // wallpaper-adaptive chrome: dark text on light wallpapers
    private boolean chromeDark = false;
    private int cTitle = 0xFFBFD9FF;
    private int cSoft = 0xFFD8DCE2;
    private int cDim = 0xFF7A8494;
    private int cClock = 0xFFFFFFFF;

    private int setSel = 0;
    private int settingsFrom = MODE_GRID;

    private long lastFrame = 0;
    private float fpsAvg = 60f;
    private boolean lowQ = false;
    private int lowQFrames = 0;

    private final Runnable ticker = new Runnable() {
        public void run() {
            invalidate();
            postOnAnimationDelayed();
        }
    };
    private boolean ticking = false;

    // ---- cached prefs (never read SharedPreferences inside onDraw) ----
    private int prefAnim = 0;
    private boolean prefNumbers = false;
    private boolean prefDark = false;
    private boolean prefClock24 = true;

    private static final String[] KEY_LABELS =
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "*", "#"};
    private String[] pageLbl = null;
    private int pageLblPages = -1;

    // ---- app loading ----
    private int loadGen = 0;
    private boolean loadPending = false;

    // ---- grid geometry (for touch hit-testing) ----
    private float gridTopY = 0f;
    private float gridCellH = 1f;

    // ---- touch ----
    private boolean touchDevice = false;
    private int slop = 8;
    private int longPressMs = 500;
    private boolean tDown = false, tMoved = false, tLong = false;
    private int tAxis = 0; // 0 undecided, 1 horizontal, 2 vertical
    private float tDownX, tDownY, tScrollFrom;

    static class AppEntry {
        String name;
        Intent intent;
        android.graphics.drawable.Drawable drawable;
        Bitmap icon;
        int theme = 0xFF6A5ACD;
    }

    public KselView(KselLauncher host) {
        super(host.getApplicationContext());
        this.host = host;
        this.sound = new KselSound(getContext());
        setFocusable(true);
        setFocusableInTouchMode(false);
        startTime = SystemClock.uptimeMillis();
        enterStart = startTime + 60;
        try {
            dateFmt = new java.text.SimpleDateFormat("EEEE, d MMMM",
                    java.util.Locale.getDefault());
        } catch (Exception e) {
            dateFmt = null;
        }
        try {
            am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        } catch (Exception e) {
            am = null;
        }
        memInfo = new ActivityManager.MemoryInfo();
        pGlass.setColor(0x55FFFFFF);
        pGlassLine.setColor(0x99FFFFFF);
        pGlassLine.setStrokeWidth(1.5f);
        try {
            ViewConfiguration vc = ViewConfiguration.get(getContext());
            slop = vc.getScaledTouchSlop();
            longPressMs = ViewConfiguration.getLongPressTimeout();
            touchDevice = getContext().getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        } catch (Exception e) {
            touchDevice = false;
        }
        initPaints();
        loadUIStrings();
        loadHidden();
        refreshSettingsStrings();
        softL = sFiles; softLOld = sFiles;
        softR = sContacts; softROld = sContacts;
    }

    private void initPaints() {
        pText.setColor(Color.WHITE);
        pText.setTextAlign(Paint.Align.CENTER);
        pTitle.setColor(0xFFBFD9FF);
        pTitle.setTextAlign(Paint.Align.CENTER);
        pSoft.setColor(0xFFD8DCE2);
        pSoft.setTextAlign(Paint.Align.LEFT);
        pFrame.setStyle(Paint.Style.STROKE);
        pFrame.setColor(0xAAFFFFFF);
        pGlow.setStyle(Paint.Style.FILL);
        pBmp.setFilterBitmap(true);
        pDim.setFilterBitmap(true);
        pClock.setColor(Color.WHITE);
        pClock.setTextAlign(Paint.Align.CENTER);
        pClock.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        pDate.setColor(0xE6FFFFFF);
        pDate.setTextAlign(Paint.Align.CENTER);
    }

    private void rr(Canvas c, RectF r, float rx, float ry, Paint p) {
        tmpPath.reset();
        try {
            tmpPath.addRoundRect(r, rx, ry, android.graphics.Path.Direction.CW);
        } catch (Exception e) {
            c.drawRect(r, p);
            return;
        }
        c.drawPath(tmpPath, p);
    }

    /** All visible strings from resources: UI follows device locale. */
    private void loadUIStrings() {
        try {
            Context ctx = getContext();
            sSelect = ctx.getString(R.string.soft_select);
            sBack = ctx.getString(R.string.soft_back);
            sOptionsBtn = ctx.getString(R.string.soft_options);
            sChange = ctx.getString(R.string.soft_change);
            sMenu = ctx.getString(R.string.soft_menu);
            sContacts = ctx.getString(R.string.soft_contacts);
            sFiles = ctx.getString(R.string.files);
            sOptions = ctx.getString(R.string.title_options);
            sEmpty = ctx.getString(R.string.empty_apps);
            sHint = ctx.getString(R.string.settings_hint);
            setNames[0] = ctx.getString(R.string.set_anim);
            setNames[1] = ctx.getString(R.string.set_sounds);
            setNames[2] = ctx.getString(R.string.set_numbers);
            setNames[3] = ctx.getString(R.string.set_vib);
            setNames[4] = ctx.getString(R.string.set_clock);
            setNames[5] = ctx.getString(R.string.set_bg);
            setNames[6] = ctx.getString(R.string.set_apps);
            sAppsTitle = ctx.getString(R.string.title_apps);
            sShowAll = ctx.getString(R.string.show_all);
            sHiddenTag = ctx.getString(R.string.hidden_tag);
            sVWall = ctx.getString(R.string.v_wall);
            sVDark = ctx.getString(R.string.v_dark);
            sVSettings = ctx.getString(R.string.set_launcher);
            sFull = ctx.getString(R.string.v_full);
            sSimple = ctx.getString(R.string.v_simple);
            sNone = ctx.getString(R.string.v_none);
            sOn = ctx.getString(R.string.v_on);
            sOff = ctx.getString(R.string.v_off);
        } catch (Exception e) {
            // keep defaults
        }
    }

    // ---------- lifecycle ----------
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startTime = SystemClock.uptimeMillis();
        if (!enterDone) enterStart = startTime + 60;
        new Thread(new Runnable() {
            public void run() {
                sound.preload();
                post(new Runnable() {
                    public void run() {
                        sound.open();
                    }
                });
            }
        }).start();
        loadAllAppsAsync(false);
        startTicker();
    }

    protected void onDetachedFromWindow() {
        stopTicker();
        removeCallbacks(longPressRun);
        try {
            if (panelBmp != null) panelBmp.recycle();
        } catch (Exception e) { }
        panelBmp = null;
        try {
            if (frameBmp != null) frameBmp.recycle();
        } catch (Exception e) { }
        frameBmp = null;
        try {
            if (glowBmp != null) glowBmp.recycle();
        } catch (Exception e) { }
        glowBmp = null;
        if (settingsIcon != null) {
            try { settingsIcon.recycle(); } catch (Exception e) { }
            settingsIcon = null;
        }
        sound.release();
        super.onDetachedFromWindow();
    }

    public void onPauseView() {
        stopTicker();
    }

    public void onResumeView() {
        launching = false;
        loadUIStrings();
        refreshSettingsStrings();
        loadAllAppsAsync(false);
        startTicker();
        // visual re-enter on return (quiet: no whoosh spam), home standby
        enterStart = SystemClock.uptimeMillis() + 30;
        enterDone = false;
        setMode(MODE_HOME);
        invalidate();
    }

    public void goHome() {
        setMode(MODE_HOME);
        invalidate();
    }

    public void goToFirst() {
        setMode(MODE_GRID);
        if (!vis.isEmpty()) setSelection(0, false);
        enterStart = SystemClock.uptimeMillis() + 30;
        enterDone = false;
        invalidate();
    }

    private void startTicker() {
        if (!ticking) {
            ticking = true;
            post(ticker);
        }
    }

    private void stopTicker() {
        ticking = false;
        removeCallbacks(ticker);
    }

    private void postOnAnimationDelayed() {
        try {
            if (!foregroundAnimating()) {
                // nothing is moving: 1 fps in static mode (clock only),
                // ~25 fps for the slow ambient background drift otherwise
                postDelayed(ticker, animMode() == 2 ? 1000 : 40);
                return;
            }
            postOnAnimation(ticker);
        } catch (Exception e) {
            postDelayed(ticker, 16);
        }
    }

    private boolean foregroundAnimating() {
        if (launching || !enterDone || tDown) return true;
        long now = SystemClock.uptimeMillis();
        return now - modeAnimStart < MODE_MS
                || now - titleStart < TITLE_MS
                || now - softStart < SOFTKEY_MS
                || now - pageAnimStart < PAGE_MS
                || now - selStart < SEL_MOVE_MS
                || now - homeEnterStart < 350
                || now - setEnterStart < SET_ENTER_MS + 7 * SET_STAGGER
                || now - appScrollStart < 220;
    }

    // ---------- layout / icon cache ----------
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeCells(w, h);
        int oldPx = iconPx;
        computeIconPx(w, h);
        if (settingsIcon != null) {
            try { settingsIcon.recycle(); } catch (Exception ex) { }
            settingsIcon = null;
        }
        ensureSettingsIcon();
        if (loadPending || (iconPx != oldPx && !apps.isEmpty())) {
            loadPending = false;
            loadAllAppsAsync(true);
        }
        float titlePx = h * 0.055f;
        if (titlePx < 14) titlePx = 14;
        pTitle.setTextSize(titlePx);
        pTitle.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        pText.setTextSize(h * 0.028f);
        pSoft.setTextSize(Math.max(14, h * 0.042f));
        pSoft.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        pFrame.setStrokeWidth(Math.max(1.5f, w * 0.004f));
        pClock.setTextSize(h * 0.15f);
        pClock.setShadowLayer(3, 0, 2, 0xAA000000);
        pDate.setTextSize(h * 0.055f);
        pDate.setShadowLayer(2, 0, 1, 0xAA000000);
        pRam.setColor(0xD8FFFFFF);
        pRam.setTextAlign(Paint.Align.CENTER);
        pRam.setTextSize(h * 0.048f);
        pRam.setShadowLayer(2, 0, 1, 0xAA000000);
        pText.setShadowLayer(2, 0, 1, 0xAA000000);
        pVigTop.setShader(new LinearGradient(0, 0, 0, h * 0.14f,
                0x30000000, 0x00000000, Shader.TileMode.CLAMP));
        pVigBot.setShader(new LinearGradient(0, h, 0, h * 0.80f,
                0x38000000, 0x00000000, Shader.TileMode.CLAMP));
        pBg.setDither(true);
        pWash.setDither(true);
        pGlowBmp.setDither(true);
        pVigTop.setDither(true);
        pVigBot.setDither(true);
        buildPanelBmp(w, h);
        buildFrameBmp();
        buildGlowBmp();
    }

    /** Pre-rendered home glass: soft blurred shadow + light glass + rim. */
    private void buildPanelBmp(int w, int h) {
        try {
            if (panelBmp != null) {
                try { panelBmp.recycle(); } catch (Exception e) { }
                panelBmp = null;
            }
            float pw = w * 0.88f;
            float ph = h * 0.34f;
            float rad = ph * 0.22f;
            float m = Math.max(10f, w * 0.05f);
            panelM = m;
            int bw = (int) (pw + 2 * m);
            int bh = (int) (ph + 2 * m);
            if (bw < 8 || bh < 8) return;
            panelBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c2 = new Canvas(panelBmp);
            android.graphics.Path path = new android.graphics.Path();
            RectF r = new RectF();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            // soft drop shadow
            p.setColor(0x5A000000);
            p.setMaskFilter(new BlurMaskFilter(9f, BlurMaskFilter.Blur.NORMAL));
            r.set(m, m + 2, m + pw, m + ph + 2);
            path.reset();
            path.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c2.drawPath(path, p);
            // light glass body
            p.setMaskFilter(null);
            p.setShader(null);
            p.setColor(0x30FFFFFF);
            r.set(m, m, m + pw, m + ph);
            path.reset();
            path.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c2.drawPath(path, p);
            // top gloss gradient
            p.setShader(new LinearGradient(0, m, 0, m + ph / 2f,
                    0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c2.save();
            c2.clipRect(m, m, m + pw, m + ph / 2f);
            path.reset();
            path.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c2.drawPath(path, p);
            c2.restore();
            // bright thin rim
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            p.setColor(0xC8FFFFFF);
            path.reset();
            path.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c2.drawPath(path, p);
        } catch (Exception e) {
            Log.w(TAG, "panel: " + e);
        } catch (OutOfMemoryError e) {
            panelBmp = null;
        }
    }

    /** Pre-rendered menu frame: blurred glow + glass + rim, white/neutral. */
    private void buildFrameBmp() {
        try {
            if (frameBmp != null) {
                try { frameBmp.recycle(); } catch (Exception e) { }
                frameBmp = null;
            }
            float sz = iconPx * SEL_SCALE;
            if (sz < 8) return;
            float pad = sz * 0.16f;
            float glow = pad * 0.9f;
            float m = 14f;
            float half = sz / 2f + pad + glow + m;
            int s = (int) (half * 2f);
            if (s < 8) return;
            frameBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c2 = new Canvas(frameBmp);
            float c0 = s / 2f;
            float rad = sz * 0.18f;
            android.graphics.Path path = new android.graphics.Path();
            RectF r = new RectF();
            RectF rg = new RectF();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            // soft outer glow
            p.setColor(0x55FFFFFF);
            p.setMaskFilter(new BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL));
            rg.set(c0 - sz / 2f - pad - glow, c0 - sz / 2f - pad - glow,
                    c0 + sz / 2f + pad + glow, c0 + sz / 2f + pad + glow);
            path.reset();
            path.addRoundRect(rg, rad + glow, rad + glow, android.graphics.Path.Direction.CW);
            c2.drawPath(path, p);
            // soft shadow below
            p.setColor(0x4A000000);
            p.setMaskFilter(new BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL));
            r.set(c0 - sz / 2f - pad, c0 - sz / 2f - pad,
                    c0 + sz / 2f + pad, c0 + sz / 2f + pad);
            path.reset();
            path.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c2.drawPath(path, p);
            // glass body
            p.setMaskFilter(null);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x30FFFFFF);
            path.reset();
            path.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c2.drawPath(path, p);
            // top gloss
            p.setShader(new LinearGradient(0, r.top, 0, (r.top + r.bottom) / 2f,
                    0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c2.save();
            c2.clipRect(r.left, r.top, r.right, (r.top + r.bottom) / 2f);
            path.reset();
            path.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c2.drawPath(path, p);
            c2.restore();
            // rim
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            p.setColor(0xC8FFFFFF);
            path.reset();
            path.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c2.drawPath(path, p);
        } catch (Exception e) {
            Log.w(TAG, "frame: " + e);
        } catch (OutOfMemoryError e) {
            frameBmp = null;
        }
    }

    private void computeCells(int w, int h) {
        float gridTop = h * 0.105f;
        float gridBot = h * 0.875f;
        float cw = w / 3f;
        float ch = (gridBot - gridTop) / 4f;
        gridTopY = gridTop;
        gridCellH = ch;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int r = i / 3, c = i % 3;
            cellCX[i] = c * cw + cw / 2f;
            cellCY[i] = gridTop + r * ch + ch / 2f;
        }
        float tx = cellCX[selectedCell()];
        float ty = cellCY[selectedCell()];
        if (selStart == 0) {
            selX = tx;
            selY = ty;
            selFromX = tx;
            selFromY = ty;
        }
    }

    private int selectedCell() {
        int total = gridTotal();
        if (total <= 0) return 0;
        int c = selected % PAGE_SIZE;
        int pageBase = (selected / PAGE_SIZE) * PAGE_SIZE;
        if (pageBase + c >= total) c = (total - 1) % PAGE_SIZE;
        return c;
    }

    private void computeIconPx(int w, int h) {
        if (w <= 0) w = 240;
        if (h <= 0) h = 320;
        float cw = w / 3f;
        float ch = (h * 0.77f) / 4f;
        iconPx = (int) (Math.min(cw, ch) * 0.72f);
        if (iconPx < 32) iconPx = 32;
        if (iconPx > 128) iconPx = 128;
    }

    /** Always returns a NEW bitmap (never the drawable's own one), so it is safe to recycle. */
    private static Bitmap iconBitmap(Drawable d, int px) {
        if (d == null) return null;
        Bitmap out;
        try {
            out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
        try {
            Canvas c = new Canvas(out);
            d.setFilterBitmap(true);
            d.setBounds(0, 0, px, px);
            d.draw(c);
        } catch (Exception e) {
            // keep whatever was drawn
        }
        return out;
    }

    /** Gear icon for the grid shortcut (generated by tools/MakeSettingsIcon.java). */
    private void ensureSettingsIcon() {
        if (settingsIcon != null && !settingsIcon.isRecycled()) return;
        settingsIcon = null;
        Drawable sd = null;
        try {
            sd = getContext().getResources().getDrawable(R.drawable.settings_gear);
        } catch (Exception e) {
            sd = null;
        }
        if (sd == null) {
            try {
                sd = getContext().getResources().getDrawable(R.drawable.icon);
            } catch (Exception e) {
                sd = null;
            }
        }
        if (sd == null) return;
        settingsIcon = iconBitmap(sd, iconPx);
    }

    private static int averageColor(Bitmap b) {
        try {
            int w = b.getWidth(), h = b.getHeight();
            int step = Math.max(1, Math.min(w, h) / 16);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < h; y += step) {
                for (int x = 0; x < w; x += step) {
                    int p = b.getPixel(x, y);
                    if ((p >>> 24) < 128) continue;
                    r += (p >> 16) & 255;
                    g += (p >> 8) & 255;
                    bl += p & 255;
                    n++;
                }
            }
            if (n == 0) return 0xFF6A5ACD;
            return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (bl / n);
        } catch (Exception e) {
            return 0xFF6A5ACD;
        }
    }

    private void recycleIcons() {
        // NOTE: panelBmp/frameBmp are size-dependent, NOT data-dependent -
        // they are rebuilt only in onSizeChanged (or lazily in onDraw).
        for (int i = 0; i < apps.size(); i++) {
            try {
                AppEntry e = apps.get(i);
                if (e != null && e.icon != null) e.icon.recycle();
            } catch (Exception e) { }
        }
    }

    // ---------- prefs ----------
    private void loadPrefs() {
        try {
            SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getContext());
            prefAnim = p.getInt("anim_mode", 0);
            prefNumbers = p.getBoolean("show_numbers", false);
            prefDark = p.getBoolean("dark_bg", false);
            boolean h24 = p.getBoolean("clock24", true);
            if (h24 != prefClock24) dateKey = -1; // redraw clock string right away
            prefClock24 = h24;
        } catch (Exception e) {
            // keep previous values
        }
        sound.reloadPrefs();
    }

    private int animMode() { return prefAnim; }

    private boolean showNumbers() { return prefNumbers; }

    private boolean darkBg() { return prefDark; }

    private void refreshSettingsStrings() {
        loadPrefs();
        try {
            SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getContext());
            int a = p.getInt("anim_mode", 0);
            setVals[0] = a == 0 ? sFull : (a == 1 ? sSimple : sNone);
            setVals[1] = p.getBoolean("snd_nav", true) ? sOn : sOff;
            setVals[2] = p.getBoolean("show_numbers", false) ? sOn : sOff;
            setVals[3] = p.getBoolean("vib_launch", true) ? sOn : sOff;
            setVals[4] = p.getBoolean("clock24", true) ? "24" : "12";
            setVals[5] = p.getBoolean("dark_bg", false) ? sVDark : sVWall;
            setVals[6] = hidden.isEmpty() ? "" : String.valueOf(hidden.size());
        } catch (Exception e) {
            setVals[0] = sFull; setVals[1] = sOn; setVals[2] = sOff; setVals[3] = sOn;
        }
    }

    /** Wallpaper-adaptive chrome: call with wallpaper luminance 0..1. */
    public void setChromeDark(boolean dark) {
        if (chromeDark == dark) return;
        chromeDark = dark;
        if (dark) {
            cTitle = 0xFF16283F;
            cSoft = 0xFF1A2333;
            cDim = 0xFF3A4350;
            cClock = 0xFF16283F;
        } else {
            cTitle = 0xFFBFD9FF;
            cSoft = 0xFFD8DCE2;
            cDim = 0xFF7A8494;
            cClock = 0xFFFFFFFF;
        }
        invalidate();
    }

    private static String compKey(AppEntry e) {
        try {
            if (e != null && e.intent != null && e.intent.getComponent() != null) {
                return e.intent.getComponent().flattenToShortString();
            }
        } catch (Exception ex) {
            // ignore
        }
        return null;
    }

    private void loadHidden() {
        hidden.clear();
        try {
            SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getContext());
            java.util.Set<String> s = p.getStringSet("hidden_apps", null);
            if (s != null) hidden.addAll(s);
        } catch (Exception e) {
            // ignore
        }
    }

    private void saveHidden() {
        try {
            SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getContext());
            SharedPreferences.Editor e = p.edit();
            e.putStringSet("hidden_apps", new java.util.HashSet<String>(hidden));
            e.apply();
        } catch (Exception ex) {
            // ignore
        }
    }

    /** Grid cells: visible apps + launcher-settings shortcut as the last tile. */
    private int gridTotal() {
        return vis.size() + 1;
    }

    private boolean isShortcutCell(int idx) {
        return idx == vis.size();
    }

    /** Rebuild visible list, keeping selection on the same app when possible. */
    private void rebuildVis() {
        String keep = null;
        try {
            if (selected >= 0 && selected < vis.size()) keep = compKey(vis.get(selected));
        } catch (Exception e) {
            // ignore
        }
        vis.clear();
        for (int i = 0; i < apps.size(); i++) {
            AppEntry e = apps.get(i);
            if (e == null) continue;
            String k = compKey(e);
            if (k != null && hidden.contains(k)) continue;
            vis.add(e);
        }
        selected = 0;
        if (keep != null) {
            for (int i = 0; i < vis.size(); i++) {
                if (keep.equals(compKey(vis.get(i)))) {
                    selected = i;
                    break;
                }
            }
        }
        if (selected < 0) selected = 0;
        if (selected > vis.size()) selected = vis.size();
    }

    // ---------- real app list ----------
    /**
     * Cheap pass first (component list + signature, no labels/icons); the heavy
     * pass (labels, icons, sorting) runs only when the set of apps changed.
     * Icons are rendered in the background thread at the final size.
     */
    private void loadAllAppsAsync(final boolean force) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            loadPending = true; // size unknown yet: onSizeChanged will start the load
            return;
        }
        final int gen = ++loadGen;
        final int px = iconPx;
        final boolean first = firstEnter;
        Thread t = new Thread(new Runnable() {
            public void run() {
                ArrayList<AppEntry> built = null;
                int sg = 0;
                try {
                    PackageManager pm = getContext().getPackageManager();
                    List<ResolveInfo> rs = queryLauncherActivities(pm);
                    sg = signature(rs);
                    if (!force && !first && sg == appsSig) {
                        post(new Runnable() {
                            public void run() { invalidate(); }
                        });
                        return;
                    }
                    built = buildEntries(pm, rs, px);
                } catch (Throwable e) {
                    Log.w(TAG, "loadApps: " + e);
                    return;
                }
                final ArrayList<AppEntry> outF = built;
                final int sgF = sg;
                post(new Runnable() {
                    public void run() { applyApps(gen, sgF, outF); }
                });
            }
        }, "ksel-apps");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void applyApps(int gen, int sig, ArrayList<AppEntry> out) {
        if (gen != loadGen) return; // a newer request superseded this one
        firstEnter = false;
        recycleIcons();
        apps.clear();
        apps.addAll(out);
        appsSig = sig;
        rebuildVis();
        try {
            bgTintTarget = (!vis.isEmpty() && selected < vis.size())
                    ? vis.get(selected).theme : 0xFF6A5ACD;
        } catch (Exception e) {
            bgTintTarget = 0xFF6A5ACD;
        }
        enterStart = SystemClock.uptimeMillis() + 30;
        enterDone = false;
        invalidate();
    }

    private List<ResolveInfo> queryLauncherActivities(PackageManager pm) {
        Intent q = new Intent(Intent.ACTION_MAIN);
        q.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> rs = pm.queryIntentActivities(q, 0);
        ArrayList<ResolveInfo> out = new ArrayList<ResolveInfo>(rs.size());
        String self = getContext().getPackageName();
        for (ResolveInfo r : rs) {
            if (r.activityInfo == null) continue;
            if (self.equals(r.activityInfo.packageName)) continue;
            out.add(r);
        }
        return out;
    }

    /** Order-independent signature of the launchable component set. */
    private static int signature(List<ResolveInfo> rs) {
        int sum = rs.size();
        for (int i = 0; i < rs.size(); i++) {
            ResolveInfo r = rs.get(i);
            sum += r.activityInfo.packageName.hashCode() * 31 + r.activityInfo.name.hashCode();
        }
        return sum;
    }

    private ArrayList<AppEntry> buildEntries(PackageManager pm, List<ResolveInfo> rs, int px) {
        ArrayList<AppEntry> out = new ArrayList<AppEntry>(rs.size());
        for (ResolveInfo r : rs) {
            try {
                AppEntry e = new AppEntry();
                e.name = String.valueOf(r.loadLabel(pm));
                Intent it = new Intent(Intent.ACTION_MAIN);
                it.addCategory(Intent.CATEGORY_LAUNCHER);
                it.setClassName(r.activityInfo.packageName, r.activityInfo.name);
                it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                e.intent = it;
                try {
                    e.icon = iconBitmap(r.loadIcon(pm), px);
                    if (e.icon != null) e.theme = averageColor(e.icon);
                } catch (Exception ex) {
                    e.icon = null;
                }
                out.add(e);
            } catch (Exception ex) {
                // skip one broken entry
            }
        }
        Collections.sort(out, new Comparator<AppEntry>() {
            public int compare(AppEntry a, AppEntry b) {
                if (a.name == null) return -1;
                if (b.name == null) return 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    // ---------- selection / paging ----------
    /** 2D grid walk: arrows move inside the page; at a row/column edge
     *  the neighbouring page flips in (same row/column); at the very
     *  first/last app we stop dead (edge stop, no wrap). */
    private void moveSelection(int dRow, int dCol) {
        if (launching) return;
        if (mode != MODE_GRID) return;
        int n = gridTotal();
        if (n <= 0) return;
        int cell = selected - (selected / PAGE_SIZE) * PAGE_SIZE;
        int row = cell / 3;
        int col = cell % 3;
        int pageBase = (selected / PAGE_SIZE) * PAGE_SIZE;
        int nr = row + dRow;
        int nc = col + dCol;
        int nb = pageBase;
        if (nc < 0) {
            if (pageBase == 0) return;
            nb = pageBase - PAGE_SIZE;
            nc = 2;
        } else if (nc > 2) {
            nb = pageBase + PAGE_SIZE;
            if (nb >= n) return;
            nc = 0;
        }
        if (nr < 0) {
            if (pageBase == 0 && nb == pageBase) return;
            if (nb == pageBase) nb = pageBase - PAGE_SIZE;
            if (nb < 0) return;
            nr = 3;
        } else if (nr > 3) {
            if (nb == pageBase) nb = pageBase + PAGE_SIZE;
            if (nb >= n) return;
            nr = 0;
        }
        int idx = nb + nr * 3 + nc;
        if (idx < 0) return;
        if (idx >= n) {
            if (nb >= n) return; // no such page: stop
            idx = n - 1; // incomplete row: land on the page's last app
        }
        setSelection(idx, true);
    }

    private void setSelection(int idx, boolean user) {
        int total = gridTotal();
        if (total <= 0) return;
        if (idx < 0) idx = 0;
        if (idx >= total) idx = total - 1;
        if (idx == selected && selStart != 0) return;
        int prev = selected;
        int oldPage = prev / PAGE_SIZE;
        int newPage = idx / PAGE_SIZE;
        titleOld = (selected >= 0 && selected < vis.size()) ? vis.get(selected).name : sOptions;
        selFrom = selected;
        selFromX = selX;
        selFromY = selY;
        selected = idx;
        long now = SystemClock.uptimeMillis();
        selStart = now;
        titleStart = now;
        if (newPage != oldPage) {
            pageAnimDir = idx > prev ? 1 : -1;
            pageAnimStart = now;
            pageIndFrom = oldPage;
            pageIndStart = now;
            // snap the frame: no cross-screen slide, the page fly-in carries motion
            float ntx = cellCX[idx - newPage * PAGE_SIZE];
            float nty = cellCY[idx - newPage * PAGE_SIZE];
            selX = ntx;
            selY = nty;
            selFromX = ntx;
            selFromY = nty;
        }
        try {
            bgTintTarget = (selected >= 0 && selected < vis.size())
                    ? vis.get(selected).theme : 0xFF6A5ACD;
        } catch (Exception e) {
            // keep
        }
        if (user) sound.tick();
        invalidate();
    }

    private void jumpToCell(int cell) {
        if (launching) return;
        int total = gridTotal();
        if (total <= 0) return;
        int pageBase = (selected / PAGE_SIZE) * PAGE_SIZE;
        int idx = pageBase + cell;
        if (idx >= total) return;
        setSelection(idx, true);
    }

    /** Touch swipe: move to the neighbouring page, keeping the same cell. */
    private void flipPage(int dir) {
        if (launching || mode != MODE_GRID) return;
        int n = gridTotal();
        int pageBase = (selected / PAGE_SIZE) * PAGE_SIZE;
        int cell = selected - pageBase;
        int nb = pageBase + dir * PAGE_SIZE;
        if (nb < 0 || nb >= n) return;
        int idx = nb + cell;
        if (idx >= n) idx = n - 1;
        setSelection(idx, true);
    }

    private void openMenu() {
        setMode(MODE_GRID);
        sound.open();
        invalidate();
    }

    private void setSoftkeys(String l, String r) {
        if (l != null && !l.equals(softL)) {
            softLOld = softL;
            softL = l;
            softStart = SystemClock.uptimeMillis();
        }
        if (r != null && !r.equals(softR)) {
            softROld = softR;
            softR = r;
            softStart = SystemClock.uptimeMillis();
        }
    }

    public void setMode(int m) {
        if (mode == m) return;
        int from = mode;
        mode = m;
        long now = SystemClock.uptimeMillis();
        modeAnimStart = now;
        if (m == MODE_HOME) {
            modeVertical = true;
            homeEnterStart = now;
            setSoftkeys(sFiles, sContacts);
        } else if (m == MODE_GRID) {
            if (from == MODE_HOME) {
                // SE-style menu open: content rises from below
                modeVertical = true;
            } else {
                modeVertical = false;
                modeAnimDir = -1;
            }
            enterStart = now;
            enterDone = false;
            setSoftkeys(sSelect, sBack);
        } else if (m == MODE_APPS) {
            modeVertical = false;
            modeAnimDir = 1;
            setEnterStartAlias(now);
            setSoftkeys(sChange, sBack);
        } else {
            modeVertical = false;
            modeAnimDir = 1;
            setEnterStartAlias(now);
            setSoftkeys(sChange, sBack);
        }
        invalidate();
    }

    private void setEnterStartAlias(long now) {
        setEnterStart = now;
    }

    private void expandNotifications() {
        try {
            Object sb = getContext().getSystemService("statusbar");
            if (sb == null) return;
            java.lang.reflect.Method m = sb.getClass().getMethod("expandNotificationsPanel");
            m.invoke(sb);
        } catch (Throwable t) {
            Log.w(TAG, "expand: " + t);
        }
    }

    // ---------- touch ----------
    private final Runnable longPressRun = new Runnable() {
        public void run() {
            if (!tDown || tMoved || launching) return;
            tLong = true;
            try {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            } catch (Exception e) {
                // ignore
            }
            // long press = the MENU key: launcher settings
            if (mode == MODE_HOME || mode == MODE_GRID) {
                settingsFrom = mode;
                setMode(MODE_SETTINGS);
                sound.select();
            }
        }
    };

    /**
     * Touch model (keys keep working as before):
     *  tap        - launch icon / toggle row / softkey zone at the bottom
     *  swipe L/R  - flip grid page; swipe right in settings = back
     *  swipe up   - home: open menu;   swipe down - home: notifications, grid: back home
     *  drag       - scroll the app manager list
     *  long press - launcher settings
     */
    public boolean onTouchEvent(MotionEvent ev) {
        final int W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return false;
        switch (ev.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                tDown = true;
                tMoved = false;
                tLong = false;
                tAxis = 0;
                tDownX = ev.getX();
                tDownY = ev.getY();
                tScrollFrom = appScrollTarget;
                removeCallbacks(longPressRun);
                if (!launching) postDelayed(longPressRun, longPressMs);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!tDown || tLong) return true;
                float dx = ev.getX() - tDownX;
                float dy = ev.getY() - tDownY;
                if (!tMoved) {
                    if (Math.abs(dx) < slop && Math.abs(dy) < slop) return true;
                    tMoved = true;
                    tAxis = Math.abs(dx) > Math.abs(dy) ? 1 : 2;
                    removeCallbacks(longPressRun);
                }
                if (mode == MODE_APPS && tAxis == 2) dragAppList(dy, H);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPressRun);
                if (!tDown) return true;
                tDown = false;
                if (tLong || launching) return true;
                if (!tMoved) {
                    onTap(ev.getX(), ev.getY(), W, H);
                } else {
                    onSwipe(ev.getX() - tDownX, ev.getY() - tDownY, W, H);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRun);
                tDown = false;
                return true;
        }
        return super.onTouchEvent(ev);
    }

    private void pressKey(int code) {
        onKeyUp(code, new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private void onTap(float x, float y, int W, int H) {
        if (y > H * 0.90f) { // softkey zone
            softTap(x < W / 2f);
            return;
        }
        if (mode == MODE_HOME) {
            openMenu();
        } else if (mode == MODE_GRID) {
            if (y < gridTopY) return;
            int col = (int) (x / (W / 3f));
            int row = (int) ((y - gridTopY) / gridCellH);
            if (col < 0 || col > 2 || row < 0 || row > 3) return;
            int idx = (selected / PAGE_SIZE) * PAGE_SIZE + row * 3 + col;
            if (idx >= gridTotal()) return;
            setSelection(idx, false);
            launchSelected();
        } else if (mode == MODE_SETTINGS) {
            float top = H * 0.15f, rowH = H * 0.09f;
            if (y < top) return;
            int i = (int) ((y - top) / rowH);
            if (i < 0 || i > 6) return;
            setSel = i;
            pressKey(KeyEvent.KEYCODE_DPAD_CENTER);
        } else if (mode == MODE_APPS) {
            float top = H * 0.17f;
            float rowH = Math.max(24f, H * 0.05f);
            if (y < top) return;
            int i = (int) Math.floor((y - top) / rowH + appScroll);
            if (i < 0 || i >= appRows()) return;
            appSel = i;
            toggleApp();
        }
    }

    private void softTap(boolean left) {
        Context ctx = getContext();
        if (mode == MODE_HOME) {
            if (left) AppIntents.files(ctx); else AppIntents.contacts(ctx);
            sound.select();
        } else if (mode == MODE_GRID) {
            if (left) launchSelected(); else pressKey(KeyEvent.KEYCODE_SOFT_RIGHT);
        } else {
            pressKey(left ? KeyEvent.KEYCODE_DPAD_CENTER : KeyEvent.KEYCODE_SOFT_RIGHT);
        }
    }

    private void onSwipe(float dx, float dy, int W, int H) {
        float minX = Math.max(slop * 3f, W * 0.15f);
        float minY = Math.max(slop * 3f, H * 0.10f);
        if (tAxis == 1 && Math.abs(dx) >= minX) {
            if (mode == MODE_GRID) {
                flipPage(dx < 0 ? 1 : -1);
            } else if ((mode == MODE_SETTINGS || mode == MODE_APPS) && dx > 0) {
                pressKey(KeyEvent.KEYCODE_SOFT_RIGHT); // back
            }
        } else if (tAxis == 2 && Math.abs(dy) >= minY) {
            if (mode == MODE_HOME) {
                if (dy < 0) openMenu(); else expandNotifications();
            } else if (mode == MODE_GRID && dy > 0) {
                setMode(MODE_HOME);
                sound.back();
            }
        }
    }

    private void dragAppList(float dy, int H) {
        float rowH = Math.max(24f, H * 0.05f);
        float visRows = (H * 0.80f) / rowH;
        float max = Math.max(0f, appRows() - visRows);
        float v = tScrollFrom - dy / rowH;
        if (v < 0f) v = 0f;
        if (v > max) v = max;
        appScrollTarget = v;
        appScrollFrom = v;
        appScroll = v;
        appScrollStart = 0; // animation finished: draw exactly at `v`
        invalidate();
    }

    // ---------- keys ----------
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try { event.startTracking(); } catch (Exception e) { }
        if (mode == MODE_GRID) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP: moveSelection(-1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: moveSelection(1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: moveSelection(0, -1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: moveSelection(0, 1); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_SOFT_LEFT:
                    return true;
            }
        } else if (mode == MODE_SETTINGS) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    setSel = (setSel + 6) % 7; sound.tick(); invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    setSel = (setSel + 1) % 7; sound.tick(); invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_SOFT_LEFT:
                    return true;
            }
        } else if (mode == MODE_APPS) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveApp(-1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveApp(1); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_SOFT_LEFT:
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private int appRows() {
        return apps.size() + 1; // row 0 = show all, then every app
    }

    private void moveApp(int d) {
        int n = appRows();
        if (n <= 0) return;
        int idx = appSel + d;
        if (idx < 0 || idx >= n) return; // edge stop
        appSel = idx;
        appScrollFrom = appScroll;
        appScrollTarget = appSel;
        appScrollStart = SystemClock.uptimeMillis();
        sound.tick();
        invalidate();
    }

    private void toggleApp() {
        if (appSel == 0) {
            if (hidden.isEmpty()) {
                sound.error();
                return;
            }
            hidden.clear();
            saveHidden();
            rebuildVis();
            refreshSettingsStrings();
            sound.select();
            invalidate();
            return;
        }
        int ai = appSel - 1;
        if (ai < 0 || ai >= apps.size()) return;
        String k = compKey(apps.get(ai));
        if (k == null) return;
        if (hidden.contains(k)) hidden.remove(k);
        else hidden.add(k);
        saveHidden();
        rebuildVis();
        refreshSettingsStrings();
        sound.select();
        invalidate();
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Context ctx = getContext();
        if (mode == MODE_SETTINGS) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
                if (setSel == 6) {
                    appSel = 0;
                    appScroll = 0f;
                    appScrollTarget = 0f;
                    setMode(MODE_APPS);
                } else {
                    toggleSetting(setSel);
                }
                sound.select();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
                setMode(settingsFrom);
                sound.back();
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }
        if (mode == MODE_APPS) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
                toggleApp();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
                setMode(MODE_SETTINGS);
                sound.back();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_HOME) {
                setMode(MODE_HOME);
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }
        if (mode == MODE_HOME) {
            // original 1.03 home bindings
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_SOFT_LEFT:
                    openMenu();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    // touch phones: BACK is a navigation key, not the right softkey
                    if (touchDevice) return true;
                    // fall through
                case KeyEvent.KEYCODE_SOFT_RIGHT:
                    AppIntents.contacts(ctx);
                    sound.select();
                    return true;
                case KeyEvent.KEYCODE_HOME:
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_CALL:
                    AppIntents.dialer(ctx);
                    sound.select();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    // left softkey on button phones sends MENU and opens files
                    AppIntents.files(ctx);
                    sound.select();
                    return true;
                case KeyEvent.KEYCODE_CAMERA:
                    AppIntents.camera(ctx);
                    sound.select();
                    return true;
                case KeyEvent.KEYCODE_ENDCALL:
                    return true;
                case KeyEvent.KEYCODE_1: case KeyEvent.KEYCODE_2: case KeyEvent.KEYCODE_3:
                case KeyEvent.KEYCODE_4: case KeyEvent.KEYCODE_5: case KeyEvent.KEYCODE_6:
                case KeyEvent.KEYCODE_7: case KeyEvent.KEYCODE_8: case KeyEvent.KEYCODE_9:
                    AppIntents.dial(ctx, String.valueOf(keyCode - KeyEvent.KEYCODE_1 + 1));
                    return true;
                case KeyEvent.KEYCODE_0:
                    AppIntents.dial(ctx, "0");
                    return true;
                case KeyEvent.KEYCODE_STAR:
                    AppIntents.dial(ctx, "*");
                    return true;
                case KeyEvent.KEYCODE_POUND:
                    AppIntents.dial(ctx, "#");
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    AppIntents.messages(ctx);
                    sound.select();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    AppIntents.calendar(ctx);
                    sound.select();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    AppIntents.sound(ctx);
                    sound.select();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    expandNotifications();
                    return true;
                default:
                    return super.onKeyUp(keyCode, event);
            }
        }
        // ---- grid mode ----
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SOFT_LEFT:
                launchSelected();
                return true;
            case KeyEvent.KEYCODE_SOFT_RIGHT:
            case KeyEvent.KEYCODE_BACK:
                setMode(MODE_HOME);
                sound.back();
                return true;
            case KeyEvent.KEYCODE_HOME:
                setMode(MODE_HOME);
                return true;
            case KeyEvent.KEYCODE_CALL:
                AppIntents.dialer(ctx);
                sound.select();
                return true;
            case KeyEvent.KEYCODE_MENU:
                settingsFrom = MODE_GRID;
                setMode(MODE_SETTINGS);
                sound.select();
                return true;
            case KeyEvent.KEYCODE_CAMERA:
                AppIntents.camera(ctx);
                sound.select();
                return true;
            case KeyEvent.KEYCODE_ENDCALL:
                return true;
            case KeyEvent.KEYCODE_1: case KeyEvent.KEYCODE_2: case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4: case KeyEvent.KEYCODE_5: case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7: case KeyEvent.KEYCODE_8: case KeyEvent.KEYCODE_9:
                jumpToCell(keyCode - KeyEvent.KEYCODE_1);
                return true;
            case KeyEvent.KEYCODE_0:
                jumpToCell(9);
                return true;
            case KeyEvent.KEYCODE_STAR:
                jumpToCell(10);
                return true;
            case KeyEvent.KEYCODE_POUND:
                jumpToCell(11);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    private void toggleSetting(int idx) {
        try {
            SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getContext());
            SharedPreferences.Editor e = p.edit();
            if (idx == 0) {
                int a = p.getInt("anim_mode", 0);
                e.putInt("anim_mode", (a + 1) % 3);
            } else if (idx == 1) {
                e.putBoolean("snd_nav", !p.getBoolean("snd_nav", true));
            } else if (idx == 2) {
                e.putBoolean("show_numbers", !p.getBoolean("show_numbers", false));
            } else if (idx == 3) {
                e.putBoolean("vib_launch", !p.getBoolean("vib_launch", true));
            } else if (idx == 4) {
                e.putBoolean("clock24", !p.getBoolean("clock24", true));
            } else if (idx == 5) {
                e.putBoolean("dark_bg", !p.getBoolean("dark_bg", false));
            } else if (idx == 6) {
                // Open app manager
                appSel = 0;
                appScroll = 0f;
                appScrollTarget = 0f;
                setMode(MODE_APPS);
                sound.select();
                return;
            }
            e.apply();
        } catch (Exception ex) {
            // ignore
        }
        refreshSettingsStrings();
        invalidate();
    }

    // ---------- launch (real intent) ----------
    private void launchSelected() {
        if (launching) return;
        int total = gridTotal();
        if (selected < 0 || selected >= total) return;
        launchCell = selectedCell();
        int am = animMode();
        sound.vibrateLaunch();
        if (am == 2) {
            doLaunch(selected);
            return;
        }
        launching = true;
        launchStart = SystemClock.uptimeMillis();
        sound.whoosh();
        invalidate();
        handler.postDelayed(new Runnable() {
            public void run() {
                launching = false;
                doLaunch(selected);
                invalidate();
            }
        }, LAUNCH_MS);
    }

    private void doLaunch(int idx) {
        if (isShortcutCell(idx)) {
            settingsFrom = MODE_GRID;
            setMode(MODE_SETTINGS);
            sound.select();
            return;
        }
        if (idx < 0 || idx >= vis.size()) return;
        AppEntry e = vis.get(idx);
        try {
            getContext().startActivity(e.intent);
        } catch (Exception ex) {
            sound.error();
            try {
                String msg = getContext().getString(R.string.no_app, e.name);
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            } catch (Exception t) {
                // ignore
            }
        }
    }

    // ---------- interpolators (no alloc) ----------
    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static float easeOutCubic(float t) {
        t = clamp01(t);
        float u = 1 - t;
        return 1 - u * u * u;
    }

    private static int lerpColor(int a, int b, float t) {
        t = clamp01(t);
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ---------- draw ----------
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        if (lastFrame != 0) {
            float dt = (now - lastFrame);
            if (dt > 0 && dt < 250) {
                float f = 1000f / dt;
                fpsAvg = fpsAvg * 0.95f + f * 0.05f;
                if (fpsAvg < 24) {
                    if (++lowQFrames > 40 && !lowQ) lowQ = true;
                } else if (fpsAvg > 30) {
                    lowQFrames = 0;
                    if (lowQ && fpsAvg > 40) lowQ = false;
                }
            }
        }
        lastFrame = now;

        int W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;

        int am = animMode();
        boolean reduced = (am == 1) || lowQ;
        boolean off = (am == 2);
        boolean full = (am == 0) && !lowQ;

        drawBackground(c, W, H, now, off, reduced, full);

        float modeOffX = 0f;
        float modeOffY = 0f;
        if (!off && now - modeAnimStart < MODE_MS) {
            float k = (now - modeAnimStart) / (float) MODE_MS;
            float me = easeOutCubic(k);
            if (modeVertical) {
                modeOffY = H * 0.10f * (1 - me);
            } else {
                modeOffX = modeAnimDir * W * (1 - me);
            }
        }
        c.save();
        c.translate(modeOffX, modeOffY);
        if (mode == MODE_HOME) {
            drawHome(c, W, H, now, off, full);
        } else if (mode == MODE_SETTINGS) {
            drawTitle(c, W, H, now, sOptions, "", off);
            drawSettings(c, W, H, now, off, reduced);
        } else if (mode == MODE_APPS) {
            drawTitle(c, W, H, now, sAppsTitle, "", off);
            drawApps(c, W, H, now, off, reduced);
        } else {
            String cur;
            if (isShortcutCell(selected)) cur = sVSettings;
            else if (selected >= 0 && selected < vis.size()) cur = vis.get(selected).name;
            else cur = sEmpty;
            drawTitle(c, W, H, now, cur, titleOld, off);
            drawPageIndicator(c, W, H);
            drawGrid(c, W, H, now, off, reduced, full);
        }
        c.restore();

        c.drawRect(0, 0, W, H * 0.14f, pVigTop);
        c.drawRect(0, H * 0.80f, W, H, pVigBot);

        drawSoftkeys(c, W, H, now, off);

        if (!enterDone && now - enterStart > ENTER_MS + PAGE_SIZE * ENTER_STAGGER + 300) {
            enterDone = true;
        }
    }

    /**
     * System wallpaper stays visible: light scrim + flat theme wash +
     * two soft drifting light blobs (pre-rendered radial sprite — smooth,
     * no stripe banding on 16-bit screens).
     */
    private void drawBackground(Canvas c, int W, int H, long now,
                                boolean off, boolean reduced, boolean full) {
        boolean dark = darkBg();
        if (pBg.getShader() == null || lastBgDark != dark) {
            pBg.setShader(dark
                    ? new LinearGradient(0, 0, 0, H, 0xFF06060E, 0xFF141428, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, H, 0x50080810, 0x70080810, Shader.TileMode.CLAMP));
            lastBgDark = dark;
        }
        c.drawRect(0, 0, W, H, pBg);
        if (off) return;
        bgTintCur = lerpColor(bgTintCur, bgTintTarget, 0.06f);
        float t = (now - startTime) / 1000f;
        // uniform theme wash (flat = no banding)
        if (glowBmp == null || glowBmp.isRecycled()) buildGlowBmp();
        if (glowBmp == null) return;
        // blob A (upper)
        float w1 = W * 1.15f;
        float x1 = W * 0.5f + (float) Math.sin(t * 0.21f) * W * 0.07f;
        float y1 = H * 0.32f + (float) Math.cos(t * 0.16f) * H * 0.05f;
        int a1 = (reduced ? 16 : 24) + (int) (8 * Math.sin(t * 0.9f));
        if (a1 < 8) a1 = 8;
        pGlowBmp.setAlpha(a1);
        rf.set(x1 - w1 / 2f, y1 - w1 / 2f, x1 + w1 / 2f, y1 + w1 / 2f);
        try {
            c.drawBitmap(glowBmp, null, rf, pGlowBmp);
        } catch (Exception e) {
            // ignore
        }
        // blob B (lower)
        float w2 = W * 0.8f;
        float x2 = W * 0.5f + (float) Math.sin(t * 0.13f + 2.1f) * W * 0.08f;
        float y2 = H * 0.74f + (float) Math.cos(t * 0.19f + 1.2f) * H * 0.05f;
        int a2 = (reduced ? 10 : 16) + (int) (6 * Math.sin(t * 0.7f + 2f));
        if (a2 < 6) a2 = 6;
        pGlowBmp.setAlpha(a2);
        rf.set(x2 - w2 / 2f, y2 - w2 / 2f, x2 + w2 / 2f, y2 + w2 / 2f);
        try {
            c.drawBitmap(glowBmp, null, rf, pGlowBmp);
        } catch (Exception e) {
            // ignore
        }
        pGlowBmp.setAlpha(255);
    }

    /** One soft white radial sprite, rendered once. */
    private void buildGlowBmp() {
        try {
            if (glowBmp != null) return;
            int s = 128;
            glowBmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c2 = new Canvas(glowBmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setDither(true);
            p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f,
                    0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c2.drawRect(0, 0, s, s, p);
        } catch (Exception e) {
            glowBmp = null;
        } catch (OutOfMemoryError e) {
            glowBmp = null;
        }
    }

    /** Standby: glass clock panel + date + free RAM over system wallpaper. */
    private void drawHome(Canvas c, int W, int H, long now, boolean off, boolean full) {
        updateClock(now);
        int shadow = chromeDark ? 0x88FFFFFF : 0xAA000000;
        pClock.setColor(cClock);
        pClock.setShadowLayer(3, 0, 2, shadow);
        pDate.setColor((cClock & 0x00FFFFFF) | 0xE6000000);
        pDate.setShadowLayer(2, 0, 1, shadow);
        pRam.setColor((cClock & 0x00FFFFFF) | 0xD8000000);
        pRam.setShadowLayer(2, 0, 1, shadow);

        // gentle enter: fade + slight rise when coming home
        float ha = 1f;
        float rise = 0f;
        if (!off && now - homeEnterStart < 350) {
            float k = clamp01((now - homeEnterStart) / 350f);
            float e = easeOutCubic(k);
            ha = e;
            rise = (1 - e) * H * 0.02f;
        }
        c.save();
        c.translate(0, rise);

        float pw = W * 0.88f;
        float ph = H * 0.34f;
        float px = W / 2f;
        float py = H * 0.5f;
        // pre-rendered glass panel (soft shadow baked in)
        if (panelBmp == null || panelBmp.isRecycled()) buildPanelBmp(W, H);
        if (panelBmp != null) {
            float m = panelM;
            rf.set(px - pw / 2f - m, py - ph / 2f - m,
                    px + pw / 2f + m, py + ph / 2f + m);
            pPanelBmp.setAlpha((int) (255 * ha));
            try {
                c.drawBitmap(panelBmp, null, rf, pPanelBmp);
            } catch (Exception e) {
                // ignore
            }
            pPanelBmp.setAlpha(255);
        }

        pClock.setAlpha((int) (255 * ha));
        // center all 3 lines (clock, date, RAM) vertically inside the panel
        float clockH = pClock.descent() - pClock.ascent();
        float dateH = pDate.descent() - pDate.ascent();
        float ramH = pRam.descent() - pRam.ascent();
        float totalH = clockH + dateH + ramH;
        float startY = py - totalH / 2f;
        c.drawText(timeStr, px, startY - pClock.ascent(), pClock);
        pDate.setAlpha((int) (255 * ha));
        c.drawText(dateStr, px, startY + clockH - pDate.ascent(), pDate);
        if (memStr.length() > 0) {
            pRam.setAlpha((int) (255 * ha));
            c.drawText(memStr, px, startY + clockH + dateH - pRam.ascent(), pRam);
        }
        c.restore();
    }

    private void updateClock(long now) {
        cal.setTimeInMillis(System.currentTimeMillis());
        long key = System.currentTimeMillis() / 60000L;
        if (key != dateKey) {
            dateKey = key;
            boolean h24 = prefClock24;
            int hh;
            if (h24) {
                hh = cal.get(Calendar.HOUR_OF_DAY);
            } else {
                hh = cal.get(Calendar.HOUR);
                if (hh == 0) hh = 12;
            }
            int mm = cal.get(Calendar.MINUTE);
            sbT.setLength(0);
            if (hh < 10) sbT.append('0');
            sbT.append(hh).append(':');
            if (mm < 10) sbT.append('0');
            sbT.append(mm);
            timeStr = sbT.toString();
            try {
                dateStr = (dateFmt != null) ? dateFmt.format(cal.getTime()) : "";
            } catch (Exception e) {
                dateStr = "";
            }
        }
        if (now - lastMemPoll > MEM_MS) {
            lastMemPoll = now;
            try {
                if (am != null && memInfo != null) {
                    am.getMemoryInfo(memInfo);
                    long mb = memInfo.availMem / 1048576L;
                    sbT.setLength(0);
                    sbT.append(mb).append(' ').append(getContext().getString(R.string.ram));
                    memStr = sbT.toString();
                }
            } catch (Exception e) {
                // keep old
            }
        }
    }

    /** Title: strictly centered, no underline bar. */
    private void drawTitle(Canvas c, int W, int H, long now, String cur, String old,
                           boolean off) {
        float y = H * 0.075f;
        pTitle.setColor(cTitle);
        pTitle.setTextSize(Math.max(14, H * 0.055f));
        pTitle.setTextAlign(Paint.Align.CENTER);
        String cc = cur != null ? cur : "";
        if (cc.length() > 20) cc = cc.substring(0, 19) + "...";
        String oo = old != null ? old : "";
        if (oo.length() > 20) oo = oo.substring(0, 19) + "...";
        if (off || now - titleStart > TITLE_MS || oo.length() == 0 || oo.equals(cc)) {
            c.drawText(cc, W / 2f, y, pTitle);
            return;
        }
        float k = clamp01((now - titleStart) / (float) TITLE_MS);
        float e = easeOutCubic(k);
        pTitle.setAlpha((int) (255 * (1 - e)));
        c.drawText(oo, W / 2f - e * W * 0.06f, y - e * H * 0.005f, pTitle);
        pTitle.setAlpha((int) (255 * e));
        c.drawText(cc, W / 2f + (1 - e) * W * 0.06f, y + (1 - e) * H * 0.005f, pTitle);
        pTitle.setAlpha(255);
    }

    private String pageLabel(int page, int pages) {
        if (pageLbl == null || pageLblPages != pages) {
            pageLbl = new String[pages];
            pageLblPages = pages;
        }
        if (page < 0 || page >= pages) return "";
        String s = pageLbl[page];
        if (s == null) {
            s = (page + 1) + "/" + pages;
            pageLbl[page] = s;
        }
        return s;
    }

    private void drawPageIndicator(Canvas c, int W, int H) {
        int pages = (gridTotal() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (pages < 2) return;
        int page = selected / PAGE_SIZE;
        long now = SystemClock.uptimeMillis();
        pText.setTextSize(H * 0.024f);
        pText.setTextAlign(Paint.Align.RIGHT);
        pText.setColor(cDim);
        if (now - pageIndStart < TITLE_MS && pageIndFrom >= 0 && pageIndFrom != page
                && pageIndFrom < pages) {
            float k = clamp01((now - pageIndStart) / (float) TITLE_MS);
            pText.setAlpha((int) (255 * (1 - k)));
            c.drawText(pageLabel(pageIndFrom, pages), W * 0.96f, H * 0.035f, pText);
            pText.setAlpha((int) (255 * k));
        } else {
            pText.setAlpha(255);
        }
        c.drawText(pageLabel(page, pages), W * 0.96f, H * 0.035f, pText);
        pText.setAlpha(255);
        pText.setTextAlign(Paint.Align.CENTER);
    }

    private void drawGrid(Canvas c, int W, int H, long now,
                          boolean off, boolean reduced, boolean full) {
        int pageBase = (selected / PAGE_SIZE) * PAGE_SIZE;
        int selCell = selected - pageBase;
        float tx = cellCX[selCell], ty = cellCY[selCell];
        if (!off && selStart != 0) {
            float k = (now - selStart) / (float) SEL_MOVE_MS;
            if (k >= 1) {
                selX = tx; selY = ty;
            } else {
                float e = easeOutCubic(k);
                selX = selFromX + (tx - selFromX) * e;
                selY = selFromY + (ty - selFromY) * e;
            }
        } else {
            selX = tx; selY = ty;
        }

        float pageOff = 0f;
        float pageAlpha = 1f;
        if (!off && now - pageAnimStart < PAGE_MS) {
            float k = (now - pageAnimStart) / (float) PAGE_MS;
            float e = easeOutCubic(k);
            pageOff = pageAnimDir * W * 0.35f * (1 - e);
            pageAlpha = 0.25f + 0.75f * e;
        }

        float cw = W / 3f;
        float base = iconPx;

        for (int cell = 0; cell < PAGE_SIZE; cell++) {
            int idx = pageBase + cell;
            if (idx >= gridTotal()) continue;
            boolean isShortcut = isShortcutCell(idx);
            AppEntry e = isShortcut ? null : vis.get(idx);
            Bitmap bmp;
            int th;
            if (isShortcut) {
                if (settingsIcon == null || settingsIcon.isRecycled()) ensureSettingsIcon();
                bmp = settingsIcon;
                th = 0xFF6A5ACD;
            } else {
                if (e == null || e.icon == null) continue;
                bmp = e.icon;
                th = e.theme;
            }
            if (bmp == null) continue;
            float cx = cellCX[cell] + pageOff;
            float cy = cellCY[cell];
            // self-healing: a dead bitmap is rebuilt on the spot, never skipped
            if (!isShortcut && (e.icon == null || e.icon.isRecycled())) {
                try {
                    if (e.drawable != null) {
                        e.icon = iconBitmap(e.drawable, iconPx);
                        if (e.icon != null) e.theme = averageColor(e.icon);
                    }
                } catch (Exception ex) {
                    // ignore
                }
                if (e.icon == null) continue;
                bmp = e.icon;
                th = e.theme;
            }
            if (!off) {
                float dx = cx - selX, dy = cy - selY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > 1 && dist < cw * 1.6f) {
                    float push = (1 - dist / (cw * 1.6f)) * base * 0.10f;
                    cx += dx / dist * push;
                    cy += dy / dist * push;
                }
            }
            boolean isSel = (idx == selected);
            float scale;
            int alpha;
            if (isSel) {
                if (!off && selStart != 0 && now - selStart < SEL_MOVE_MS) {
                    float k = easeOutCubic((now - selStart) / (float) SEL_MOVE_MS);
                    scale = UNSCALE_DIM + (SEL_SCALE - UNSCALE_DIM) * k;
                } else {
                    scale = SEL_SCALE;
                }
                alpha = 255;
            } else {
                scale = UNSCALE_DIM;
                alpha = UNSCALE_ALPHA;
            }
            // old selection shrinks smoothly back while the frame glides away
            if (!isSel && idx == selFrom && selFrom != selected
                    && !off && now - selStart < SEL_MOVE_MS) {
                float sk = easeOutCubic((now - selStart) / (float) SEL_MOVE_MS);
                scale = SEL_SCALE + (UNSCALE_DIM - SEL_SCALE) * sk;
                alpha = 255;
            }
            float enterA = 1f;
            float ey = 0f;
            if (!off && !enterDone) {
                long d = now - enterStart - cell * ENTER_STAGGER;
                if (d < 0) {
                    enterA = 0f;
                    ey = H * 0.08f;
                } else if (d < ENTER_MS) {
                    float k = d / (float) ENTER_MS;
                    enterA = easeOutCubic(k);
                    ey = (1 - enterA) * H * 0.08f;
                }
            }
            if (enterA <= 0) continue;
            float sz = base * scale;
            if (isSel) {
                drawSelFrame(c, selX + pageOff, selY + ey, base * SEL_SCALE,
                        now, off, reduced, th);
            }
            pBmp.setAlpha((int) (alpha * enterA * pageAlpha));
            float dw = sz;
            if (launching && cell == launchCell) {
                float k = clamp01((now - launchStart) / (float) LAUNCH_MS);
                dw = sz * (1 + k * 1.6f);
                pBmp.setAlpha((int) (alpha * enterA * (1 - k * 0.4f)));
            } else if (launching) {
                float k = clamp01((now - launchStart) / (float) LAUNCH_MS);
                pBmp.setAlpha((int) (alpha * enterA * pageAlpha * (1 - k * 0.7f)));
            }
            ri.set((int) (cx - dw / 2f), (int) (cy + ey - dw / 2f),
                    (int) (cx + dw / 2f), (int) (cy + ey + dw / 2f));
            // soft drop shadow under the selected icon (static, elegant)
            if (isSel && !launching && enterA > 0.5f) {
                float shA = enterA * pageAlpha;
                rf2.set(cx - dw * 0.34f, cy + ey + dw * 0.30f,
                        cx + dw * 0.34f, cy + ey + dw * 0.44f);
                pGlow.setColor(Color.argb((int) (36 * shA), 0, 0, 0));
                c.drawOval(rf2, pGlow);
                rf2.set(cx - dw * 0.24f, cy + ey + dw * 0.33f,
                        cx + dw * 0.24f, cy + ey + dw * 0.41f);
                pGlow.setColor(Color.argb((int) (60 * shA), 0, 0, 0));
                c.drawOval(rf2, pGlow);
            }
            try {
                c.drawBitmap(bmp, null, ri, pBmp);
            } catch (Exception ex) {
                // ignore
            }
            if (!off && launching && cell == launchCell) {
                float k = clamp01((now - launchStart) / (float) LAUNCH_MS);
                // system-like: scale up + fade out
                float sk = 1.0f + k * 2.5f;
                float ak = 1.0f - k;
                dw = sz * sk;
                ri.set((int) (cx - dw / 2f), (int) (cy + ey - dw / 2f),
                        (int) (cx + dw / 2f), (int) (cy + ey + dw / 2f));
                pBmp.setAlpha((int) (alpha * enterA * ak));
                // white flash at start
                if (k < 0.3f) {
                    pGlow.setColor(Color.argb((int) (120 * (1 - k / 0.3f)), 255, 255, 255));
                    c.drawRect(cx - dw / 2f, cy + ey - dw / 2f, cx + dw / 2f, cy + ey + dw / 2f, pGlow);
                }
            }
            if (showNumbers()) {
                pText.setTextSize(H * 0.022f);
                pText.setTextAlign(Paint.Align.LEFT);
                pText.setColor((cSoft & 0x00FFFFFF) | 0xCC000000);
                String n = KEY_LABELS[cell];
                c.drawText(n, cx - dw / 2f + 2, cy - dw / 2f + ey + H * 0.022f, pText);
                pText.setTextAlign(Paint.Align.CENTER);
            }
            pBmp.setAlpha(255);
        }
    }

    /** Frosted-glass frame: pre-rendered bitmap (soft blur baked in). */
    private void drawSelFrame(Canvas c, float cx, float cy, float sz, long now,
                              boolean off, boolean reduced, int theme) {
        if (frameBmp == null || frameBmp.isRecycled()) buildFrameBmp();
        if (frameBmp == null) return;
        float half = frameBmp.getWidth() / 2f;
        rf.set(cx - half, cy - half, cx + half, cy + half);
        try {
            c.drawBitmap(frameBmp, null, rf, pFrameBmp);
        } catch (Exception e) {
            // ignore
        }
    }

    private void drawSettings(Canvas c, int W, int H, long now, boolean off, boolean reduced) {
        float top = H * 0.15f;
        float rowH = H * 0.09f;
        pText.setTextSize(rowH * 0.5f);
        for (int i = 0; i < 7; i++) {
            float y = top + i * rowH + rowH / 2f;
            float rowOff = 0f;
            if (!off && now - setEnterStart < SET_ENTER_MS + 7 * SET_STAGGER) {
                long d = now - setEnterStart - i * SET_STAGGER;
                if (d < 0) continue;
                if (d < SET_ENTER_MS) {
                    rowOff = W * 0.25f * (1 - easeOutCubic(d / (float) SET_ENTER_MS));
                }
            }
            boolean sel = (i == setSel);
            if (sel) {
                rf.set(W * 0.04f + rowOff, y - rowH * 0.46f, W * 0.96f + rowOff, y + rowH * 0.46f);
                Paint body = pBeam;
                body.setColor(0x44FFFFFF);
                rr(c, rf, 8, 8, body);
                pFrame.setColor(0xAAFFFFFF);
                rr(c, rf, 8, 8, pFrame);
            }
            pText.setTextAlign(Paint.Align.LEFT);
            pText.setColor(sel ? 0xFFFFFFFF : 0xFFC8D0DC);
            c.drawText(setNames[i] != null ? setNames[i] : "", W * 0.08f + rowOff, y + rowH * 0.14f, pText);
            pText.setTextAlign(Paint.Align.RIGHT);
            pText.setColor(0xFF8FD0FF);
            c.drawText(setVals[i] != null ? setVals[i] : "", W * 0.92f + rowOff, y + rowH * 0.14f, pText);
            pText.setTextAlign(Paint.Align.CENTER);
        }
    }

    /** Manual app manager: row 0 = show all, then every app with hide checkbox. */
    private void drawApps(Canvas c, int W, int H, long now, boolean off, boolean reduced) {
        float top = H * 0.17f;
        float rowH = H * 0.05f;
        if (rowH < 24) rowH = 24;
        int n = appRows();
        if (n <= 0) return;
        if (appSel >= n) appSel = n - 1;
        float visRows = (H * 0.80f) / rowH;
        // appScroll = top edge of visible area (not center)
        float maxScroll = Math.max(0, n - visRows);
        if (appScrollTarget > maxScroll) appScrollTarget = maxScroll;
        if (appScrollTarget < 0) appScrollTarget = 0;
        if (!off) {
            float k = clamp01((now - appScrollStart) / 220f);
            appScroll = appScrollFrom + (appScrollTarget - appScrollFrom) * easeOutCubic(k);
        } else {
            appScroll = appScrollTarget;
        }
        if (appScroll > maxScroll) appScroll = maxScroll;
        if (appScroll < 0) appScroll = 0;
        int i0 = Math.max(0, (int) Math.floor(appScroll) - 1);
        int i1 = Math.min(n - 1, (int) Math.ceil(appScroll + visRows) + 1);
        pText.setTextSize(rowH * 0.44f);
        for (int i = i0; i <= i1; i++) {
            float y = top + (i - appScroll) * rowH + rowH / 2f;
            if (y < top - rowH || y > H * 0.90f) continue;
            boolean sel = (i == appSel);
            if (sel) {
                rf.set(W * 0.04f, y - rowH * 0.46f, W * 0.96f, y + rowH * 0.46f);
                Paint body = pBeam;
                body.setColor(0x44FFFFFF);
                rr(c, rf, 8, 8, body);
                pFrame.setColor(0xAAFFFFFF);
                rr(c, rf, 8, 8, pFrame);
            }
            if (i == 0) {
                pText.setTextAlign(Paint.Align.LEFT);
                pText.setColor(sel ? 0xFFFFFFFF : 0xFFC8D0DC);
                c.drawText(sShowAll, W * 0.10f, y + rowH * 0.14f, pText);
                pText.setTextAlign(Paint.Align.RIGHT);
                pText.setColor(0xFF8FD0FF);
                c.drawText(hidden.isEmpty() ? "" : String.valueOf(hidden.size()),
                        W * 0.90f, y + rowH * 0.14f, pText);
                pText.setTextAlign(Paint.Align.CENTER);
                continue;
            }
            AppEntry e = apps.get(i - 1);
            String nm = (e != null && e.name != null) ? e.name : "?";
            if (nm.length() > 18) nm = nm.substring(0, 17) + "...";
            boolean isHidden = false;
            try {
                String k = compKey(e);
                isHidden = k != null && hidden.contains(k);
            } catch (Exception ex) {
                // ignore
            }
            float cb = rowH * 0.34f;
            float cx0 = W * 0.09f;
            rf.set(cx0, y - cb, cx0 + cb * 2f, y + cb);
            pFrame.setColor(sel ? 0xFFFFFFFF : 0xFF9AA2B0);
            rr(c, rf, 3, 3, pFrame);
            if (isHidden) {
                pText.setColor(0xFF8FD0FF);
                pText.setTextAlign(Paint.Align.CENTER);
                c.drawText("x", cx0 + cb, y + rowH * 0.16f, pText);
            }
            pText.setTextAlign(Paint.Align.LEFT);
            pText.setColor(isHidden ? 0xFF7A8494 : (sel ? 0xFFFFFFFF : 0xFFC8D0DC));
            c.drawText(nm, W * 0.26f, y + rowH * 0.14f, pText);
            if (isHidden) {
                pText.setTextAlign(Paint.Align.RIGHT);
                pText.setColor(0xFF7A8494);
                pText.setTextSize(rowH * 0.34f);
                c.drawText(sHiddenTag, W * 0.92f, y + rowH * 0.12f, pText);
                pText.setTextSize(rowH * 0.44f);
            }
            pText.setTextAlign(Paint.Align.CENTER);
        }
    }

    private void drawSoftkeys(Canvas c, int W, int H, long now, boolean off) {
        float y = H * 0.965f;
        pSoft.setColor(cSoft);
        pSoft.setTextSize(Math.max(14, H * 0.042f));
        if (off || now - softStart > SOFTKEY_MS) {
            pSoft.setAlpha(255);
            pSoft.setTextAlign(Paint.Align.LEFT);
            c.drawText(softL, W * 0.04f, y, pSoft);
        } else {
            float k = clamp01((now - softStart) / (float) SOFTKEY_MS);
            pSoft.setTextAlign(Paint.Align.LEFT);
            pSoft.setAlpha((int) (255 * (1 - k)));
            c.drawText(softLOld, W * 0.04f, y, pSoft);
            pSoft.setAlpha((int) (255 * k));
            c.drawText(softL, W * 0.04f, y, pSoft);
        }
        if (off || now - softStart > SOFTKEY_MS) {
            pSoft.setAlpha(255);
            pSoft.setTextAlign(Paint.Align.RIGHT);
            c.drawText(softR, W * 0.96f, y, pSoft);
        } else {
            float k = clamp01((now - softStart) / (float) SOFTKEY_MS);
            pSoft.setTextAlign(Paint.Align.RIGHT);
            pSoft.setAlpha((int) (255 * (1 - k)));
            c.drawText(softROld, W * 0.96f, y, pSoft);
            pSoft.setAlpha((int) (255 * k));
            c.drawText(softR, W * 0.96f, y, pSoft);
        }
        pSoft.setAlpha(255);
        pSoft.setTextAlign(Paint.Align.LEFT);
    }
}
