package org.libsdl.app;

import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import dev.encounter.aurora.AuroraSurface;

public class MyGame extends SDLActivity {
    static native void nativeBridgeInit(String settings);
    static native void nativeBridgeInput(int buttons,float lx,float ly,float rx,float ry,float lt,float rt);
    private TouchOverlay overlay;
    private android.app.Dialog optionsDialog;
    private android.widget.Button menuButton;
    private android.hardware.input.InputManager inputManager;
    private final android.hardware.input.InputManager.InputDeviceListener deviceListener = new android.hardware.input.InputManager.InputDeviceListener() {
        public void onInputDeviceAdded(int id) { updateMenuButton(); }
        public void onInputDeviceRemoved(int id) { updateMenuButton(); }
        public void onInputDeviceChanged(int id) { updateMenuButton(); }
    };
    /** buttons = bit mask of SDL gamepad buttons; buttons < 0 detaches the virtual pad. */
    static native void nativeTouchPad(int buttons, float lx, float ly, float rx, float ry, float lt, float rt);

    /** Intent extra: true = start Premium Deluxe (DLC engine libmain_pd.so + the DLC disc). */
    static final String EXTRA_DLC = "dlc";
    /** Which engine this app process has loaded ("main" or "main_pd"); Android can't unload it again. */
    static volatile String loadedEngine;

    private boolean dlc() { return getIntent() != null && getIntent().getBooleanExtra(EXTRA_DLC, false); }

    @Override
    protected String[] getLibraries() {
        String engine = dlc() ? Dlc.ENGINE : "main";
        loadedEngine = engine;
        return new String[] { engine };
    }

    /** Linked game file ("Play from where it is"): kept open for the whole run; the engine reads /proc/self/fd/<n>. */
    private android.os.ParcelFileDescriptor discFd;

    @Override
    protected String[] getArguments() {
        if (dlc()) return new String[] {"--disc", Dlc.iso(this).getAbsolutePath()};
        android.content.SharedPreferences p = getSharedPreferences("lm", MODE_PRIVATE);
        if (discFd == null && DiscLink.has(p)) discFd = DiscLink.open(this, p);
        if (discFd != null) return new String[] {"--disc", DiscLink.path(discFd)};
        return new String[0];
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        return new AuroraSurface(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DlcInput.selectEdition(dlc());
        GameSettings.applyEnv(this);  // settings.ini location, before the game starts
        // The engine's own "last starts did not finish" start-up prompt is an SDL message box: while it is up,
        // SDL holds its activity lock, and the next window focus change on the UI thread waited on that lock
        // until Android closed the game (ANR). Answer it with "start normally"; the launcher already offers
        // Safe Mode and a log after a crash.
        try { android.system.Os.setenv("LM_RECOVERY", "0", true); } catch (Throwable ignored) { }
        Lang.load(this);
        // Hidden test switch: files/gpu_profile.txt (made by gpu_test.bat over USB) turns on the engine's own
        // GPU timer: every 5 s the log gets the GPU time of each drawing step (tag LMRecomp, "GPU profile").
        try {
            java.io.File ext = getExternalFilesDir(null);
            if (ext != null && new java.io.File(ext, "gpu_profile.txt").isFile()) {
                android.system.Os.setenv("AURORA_GPU_PROFILE", "5", true);
                android.system.Os.setenv("LM_PERF_LOG", "10", true);
            }
        } catch (Throwable ignored) { }
        if (dlc()) {
            // Premium Deluxe: its own saves (separate data folder) and the engine may start the modded disc.
            try {
                android.system.Os.setenv("LM_ALLOW_MOD_DOL", "1", true);
                java.io.File d = GameSettings.dlcDataRoot(this);
                d.mkdirs();
                android.system.Os.setenv("XDG_DATA_HOME", d.getAbsolutePath(), true);
            } catch (Throwable ignored) { }
        }
        Diagnostics.installJavaCrashHandler(this);
        Diagnostics.markRunning(this, dlc());
        StickCurve.load(getSharedPreferences("lm", MODE_PRIVATE));
        if (!dlc() && DiscLink.has(getSharedPreferences("lm", MODE_PRIVATE)))
            discFd = DiscLink.open(this, getSharedPreferences("lm", MODE_PRIVATE));
        GameSettings.write(this, getSharedPreferences("lm", MODE_PRIVATE));  // also hands player 1 back to "auto"
        super.onCreate(savedInstanceState);
        System.loadLibrary("lmbridge");
        TexturePacks.loadNative();
        // HD texture pack: give the engine a few seconds to finish starting, then point it at the chosen pack.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> TexturePacks.apply(MyGame.this, getSharedPreferences("lm", MODE_PRIVATE)), 6000);
        nativeBridgeInit(new java.io.File(getFilesDir(),"xdg/Luigi's Mansion Recompiled/settings.ini").getAbsolutePath());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(lp);
        applyDisplayRate();  // screen refresh rate that fits the frame rate (again whenever it changes)
        watchDisplay();
        setupPerformanceMode();
        overlay = new TouchOverlay(this);
        addContentView(overlay,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        menuButton = new android.widget.Button(this);
        menuButton.setText(Lang.t("MENU")); menuButton.setTextColor(0xFFFFFFFF);
        menuButton.setContentDescription(Lang.t("Open port options"));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xA0203030); bg.setCornerRadius(22); bg.setStroke(2,0xCCDCF6DC);
        menuButton.setBackground(bg);
        android.widget.FrameLayout.LayoutParams mp=new android.widget.FrameLayout.LayoutParams(150,64,android.view.Gravity.TOP|android.view.Gravity.CENTER_HORIZONTAL);
        mp.topMargin=22;
        addContentView(menuButton,mp);
        menuButton.setOnClickListener(v -> showPortOptions());
        inputManager=(android.hardware.input.InputManager)getSystemService(INPUT_SERVICE);
        inputManager.registerInputDeviceListener(deviceListener,null);
        updateMenuButton();
    }

    private void updateMenuButton() {
        boolean external=false;
        for(int id:android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice d=android.view.InputDevice.getDevice(id);
            if(d!=null && !d.isVirtual() && d.isExternal() &&
                    ((d.getSources() & android.view.InputDevice.SOURCE_GAMEPAD)==android.view.InputDevice.SOURCE_GAMEPAD ||
                    (d.getSources() & android.view.InputDevice.SOURCE_JOYSTICK)==android.view.InputDevice.SOURCE_JOYSTICK)) external=true;
        }
        if(menuButton!=null) menuButton.setVisibility(external?android.view.View.GONE:android.view.View.VISIBLE);
    }

    void showPortOptions() {
        if(optionsDialog!=null) return;
        overlay.releaseAll();
        android.app.Dialog dialog=new android.app.Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        optionsDialog=dialog;
        OptionsView view=new OptionsView(this,getSharedPreferences("lm",MODE_PRIVATE),()->dialog.dismiss());
        dialog.setContentView(view);
        dialog.setOnDismissListener(d->{
            view.release(); optionsDialog=null;
            nativeBridgeInput(-3,0,0,0,0,0,0);
            if(overlay!=null) overlay.reloadPrefs();
            setupPerformanceMode();
            applyDisplayRate();  // a new frame rate gets the matching screen refresh rate right away
            StickCurve.load(getSharedPreferences("lm",MODE_PRIVATE));
            TexturePacks.apply(this,getSharedPreferences("lm",MODE_PRIVATE));
        });
        dialog.show();
        dialog.getWindow().setLayout(-1,-1);
        dialog.getWindow().getDecorView().setSystemUiVisibility(5894);
        view.requestFocus();
    }

    // ---- crash marker + per-thread CPU monitor (Save Log) + Boost CPU ----

    private android.os.HandlerThread monitorThread;
    private android.os.Handler monitor;
    private int monitorTicks;
    private boolean boostHotShown;

    @Override protected void onResume() {
        super.onResume();
        Diagnostics.markRunning(this, dlc());
        startMonitor();
        if (mLayout != null) mLayout.postDelayed(this::applyDisplayRate, 400);  // the game's surface exists by then
    }

    // ---- screen refresh rate ----
    // Smooth play needs the screen to refresh at the frame rate (60 fps -> 60 Hz, 120 fps -> 120 Hz):
    // then VSync shows every frame exactly once. Before, the rate was only asked for when the game
    // started, so 120 fps chosen in the in-game menu stayed on a 60 Hz screen ("stuck at 60"), and the
    // game was never told when the screen changed speed (uneven frames = stutter).

    private android.hardware.display.DisplayManager.DisplayListener displayListener;
    private float sentHz;
    private boolean slowScreenShown;

    void applyDisplayRate() {
        try {
            int fps = GameSettings.effectiveFps(getSharedPreferences("lm", MODE_PRIVATE));
            final float want = DisplayRates.wantedHz(fps);
            Display d = getDisplay();
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            int id = DisplayRates.pickMode(d, want);
            boolean changed = false;
            if (id != 0 && lp.preferredDisplayModeId != id) { lp.preferredDisplayModeId = id; changed = true; }
            if (Math.abs(lp.preferredRefreshRate - want) > 0.1f) { lp.preferredRefreshRate = want; changed = true; }
            if (changed) getWindow().setAttributes(lp);
            if (mSurface != null) {
                android.view.Surface s = mSurface.getHolder().getSurface();
                if (s != null && s.isValid())
                    s.setFrameRate(want, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                            android.view.Surface.CHANGE_FRAME_RATE_ALWAYS);
            }
            android.util.Log.i("LMDisplay", "frame rate " + fps + " fps: asking the screen for " + want + " Hz (mode " + id
                    + "); screen now " + (d != null ? d.getRefreshRate() : -1) + " Hz, offers " + DisplayRates.refreshRates(d));
            sendRefreshToGame();
            // Check the screen really followed (battery saver or the phone's motion smoothness setting can hold it at 60 Hz).
            if (fps > 60 && mLayout != null) mLayout.postDelayed(() -> {
                Display dd = getDisplay();
                if (dd == null || isFinishing()) return;
                float now = dd.getRefreshRate();
                sendRefreshToGame();
                if (now < want - 1f && !slowScreenShown) {
                    slowScreenShown = true;
                    android.util.Log.w("LMDisplay", "screen stayed at " + now + " Hz (wanted " + want + ")");
                    Lang.toast(this, "The screen stayed at " + Math.round(now) + " Hz, so the game runs at "
                            + Math.round(now) + " fps. Battery saver or the phone's screen motion smoothness setting may limit it.",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }, 1500);
        } catch (Throwable t) {
            android.util.Log.w("LMDisplay", "applyDisplayRate: " + t);
        }
    }

    /** Tells the game (SDL) the screen's current refresh rate, so its frame pacing matches the real screen. */
    private void sendRefreshToGame() {
        try {
            Display d = getDisplay();
            if (d == null || mSurface == null) return;
            float hz = d.getRefreshRate();
            if (Math.abs(hz - sentHz) < 0.5f) return;
            if (mSurface.sendRefreshRate(hz)) {
                sentHz = hz;
                android.util.Log.i("LMDisplay", "game told: screen " + hz + " Hz");
            }
        } catch (Throwable ignored) { }
    }

    private void watchDisplay() {
        try {
            android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            displayListener = new android.hardware.display.DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { }
                @Override public void onDisplayRemoved(int id) { }
                @Override public void onDisplayChanged(int id) {
                    Display d = getDisplay();
                    if (d != null && d.getDisplayId() == id) sendRefreshToGame();
                }
            };
            dm.registerDisplayListener(displayListener, new android.os.Handler(getMainLooper()));
        } catch (Throwable ignored) { }
    }

    @Override protected void onPause() {
        Diagnostics.markStopped(this);  // left normally (home, recents, another app): not a crash
        stopMonitor();
        final android.content.SharedPreferences sp = getSharedPreferences("lm", MODE_PRIVATE);
        new Thread(() -> SaveBackup.backupIfChanged(MyGame.this, sp), "SaveBackup").start();
        super.onPause();
    }

    private void startMonitor() {
        if (monitorThread != null) return;
        monitorThread = new android.os.HandlerThread("LMMonitor", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        monitorThread.start();
        monitor = new android.os.Handler(monitorThread.getLooper());
        final android.content.SharedPreferences prefs = getSharedPreferences("lm", MODE_PRIVATE);
        monitor.post(new Runnable() {
            @Override public void run() {
                java.util.List<Diagnostics.ThreadUse> use = Diagnostics.sampleThreads();
                Boost.update(MyGame.this, prefs, use);
                evaluateThermal();
                if (Boost.pausedHot && !boostHotShown) {
                    boostHotShown = true;
                    runOnUiThread(() -> Lang.toast(MyGame.this,
                            "Phone is warm: Boost CPU paused for this session", android.widget.Toast.LENGTH_SHORT).show());
                }
                if (++monitorTicks % 8 == 0) Diagnostics.saveThreadSummary(MyGame.this);
                if (monitor != null) monitor.postDelayed(this, 4000);
            }
        });
    }

    private void stopMonitor() {
        if (monitorThread == null) return;
        Diagnostics.saveThreadSummary(this);
        Boost.undo();
        Boost.undoCool();
        monitor.removeCallbacksAndMessages(null);
        monitorThread.quitSafely();
        monitorThread = null;
        monitor = null;
    }

    // ---- Performance Mode: lighter settings + automatic cool-down when the phone gets hot ----
    // Auto Cool-down acts early and gives up as little as possible: warming up (Android's 10 s heat
    // forecast, or LIGHT) first drops the internal resolution to 1x and keeps the frame rate; only a warm
    // phone (MODERATE) goes to 60 fps, a hot one (SEVERE) to 30. It steps back up one level at a time,
    // and only after a minute of cooler readings, so it does not swing between settings.

    private android.os.PowerManager.OnThermalStatusChangedListener thermalListener;
    private volatile boolean thermalWatch;
    private volatile int thermalStatus;
    private long coolSince;  // uptime ms since readings allowed a lower level (0 = not cooler)
    private static final long COOL_HOLD_MS = 60_000;

    private void setupPerformanceMode() {
        android.content.SharedPreferences prefs = getSharedPreferences("lm", MODE_PRIVATE);
        boolean on = prefs.getBoolean("g_perf", false);
        // No automatic cool-down: frame rate, resolution and MSAA always stay as the player set them.
        boolean watch = false;
        thermalWatch = watch;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            // Only in Performance Mode: on the Odin 3 it holds the cores near 2 GHz, too slow for 120 fps.
            getWindow().setSustainedPerformanceMode(on && pm != null && pm.isSustainedPerformanceModeSupported());
            if (watch && thermalListener == null && pm != null) {
                thermalStatus = pm.getCurrentThermalStatus();
                thermalListener = status -> {
                    thermalStatus = status;
                    evaluateThermal();
                };
                pm.addThermalStatusListener(getMainExecutor(), thermalListener);
            } else if (!watch && thermalListener != null && pm != null) {
                pm.removeThermalStatusListener(thermalListener);
                thermalListener = null;
                if (GameSettings.thermalLevel != 0) setThermalLevel(0);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Level the readings ask for (headroom: Android's forecast, 1.0 = at the throttling point). */
    private static int thermalLevelFor(int status, float headroom) {
        if (status >= android.os.PowerManager.THERMAL_STATUS_SEVERE) return 3;
        if (status >= android.os.PowerManager.THERMAL_STATUS_MODERATE || headroom >= 0.95f) return 2;
        if (status >= android.os.PowerManager.THERMAL_STATUS_LIGHT || headroom >= 0.80f) return 1;
        return 0;
    }

    /** Called on thermal status changes and every monitor tick (4 s; headroom must not be polled faster than 1 s). */
    private synchronized void evaluateThermal() {
        if (!thermalWatch) return;
        float headroom = Float.NaN;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) headroom = pm.getThermalHeadroom(10);
        } catch (Throwable ignored) { }
        if (Float.isNaN(headroom)) headroom = 0;
        int st = thermalStatus;
        int cur = GameSettings.thermalLevel;
        int up = thermalLevelFor(st, headroom);
        if (up > cur) {
            coolSince = 0;
            setThermalLevel(up);
            return;
        }
        int down = thermalLevelFor(st, headroom + 0.1f);  // going down needs 0.1 of headroom below the thresholds
        long now = android.os.SystemClock.uptimeMillis();
        if (down < cur) {
            if (coolSince == 0) coolSince = now;
            else if (now - coolSince >= COOL_HOLD_MS) {
                coolSince = now;
                setThermalLevel(cur - 1);
            }
        } else {
            coolSince = 0;
        }
    }

    private void setThermalLevel(int level) {
        GameSettings.thermalLevel = level;
        android.content.SharedPreferences prefs = getSharedPreferences("lm", MODE_PRIVATE);
        GameSettings.write(this, prefs);
        nativeBridgeInput(-3, 0, 0, 0, 0, 0, 0);  // reload settings.ini
        android.util.Log.i("LMThermal", "cool-down level " + level + " (status " + thermalStatus + ")");
        final String msg = level == 0 ? "Phone cooled down: your settings are back"
                : level == 1 ? "Phone warming up: lighter graphics, same frame rate"
                : level == 2 ? "Phone is warm: frame rate lowered to 60 fps"
                : "Phone is hot: frame rate lowered to 30 fps";
        runOnUiThread(() -> {
            applyDisplayRate();
            Lang.toast(MyGame.this, msg, android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    // ---- touch layout editor and the engine's controller menu ----

    private boolean engineMenuOpen;

    /** Options > Controls > Edit Layout while playing: the live buttons become movable. */
    void startLayoutEdit() {
        if (overlay == null) return;
        if (menuButton != null) menuButton.setVisibility(android.view.View.GONE);
        overlay.startEdit(() -> updateMenuButton());
    }

    /** Options > Controls > Controller Settings: open the game's own settings menu (F1) for remapping, rumble, dead zones. */
    void toggleEngineMenu() {
        engineMenuOpen = !engineMenuOpen;
        if (overlay != null) {
            overlay.releaseAll();
            overlay.setVisibility(engineMenuOpen ? android.view.View.GONE : android.view.View.VISIBLE);
        }
        if (menuButton != null) {
            menuButton.setText(Lang.t(engineMenuOpen ? "CLOSE" : "MENU"));
            menuButton.setVisibility(android.view.View.VISIBLE);
            menuButton.setOnClickListener(engineMenuOpen ? v -> toggleEngineMenu() : v -> showPortOptions());
        }
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        onNativeKeyDown(android.view.KeyEvent.KEYCODE_F1);
        h.postDelayed(() -> onNativeKeyUp(android.view.KeyEvent.KEYCODE_F1), 80);
        if (!engineMenuOpen) updateMenuButton();
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if(overlay!=null && overlay.isEditing() && e.getKeyCode()==android.view.KeyEvent.KEYCODE_BACK) {
            if(e.getAction()==android.view.KeyEvent.ACTION_UP) overlay.cancelEdit();
            return true;
        }
        // Select, Menu and the Odin's Back button all open the port's Options menu
        if(e.getKeyCode()==android.view.KeyEvent.KEYCODE_BUTTON_SELECT || e.getKeyCode()==android.view.KeyEvent.KEYCODE_MENU
                || e.getKeyCode()==android.view.KeyEvent.KEYCODE_BACK) {
            if(e.getAction()==android.view.KeyEvent.ACTION_UP) showPortOptions();
            return true;
        }
        if(e.getDevice()!=null && !e.getDevice().isVirtual() && e.getAction()==android.view.KeyEvent.ACTION_DOWN)
            nativeBridgeInput(-2,0,0,0,0,0,0);
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        if((e.getSource() & android.view.InputDevice.SOURCE_JOYSTICK)==android.view.InputDevice.SOURCE_JOYSTICK &&
                (Math.abs(e.getAxisValue(android.view.MotionEvent.AXIS_X))>.2f || Math.abs(e.getAxisValue(android.view.MotionEvent.AXIS_Y))>.2f))
            nativeBridgeInput(-2,0,0,0,0,0,0);
        return super.dispatchGenericMotionEvent(e);
    }

    @Override protected void onDestroy() {
        if(inputManager!=null) inputManager.unregisterInputDeviceListener(deviceListener);
        try {
            if(thermalListener!=null) ((android.os.PowerManager)getSystemService(POWER_SERVICE)).removeThermalStatusListener(thermalListener);
        } catch(Throwable ignored) { }
        GameSettings.thermalLevel=0;
        stopMonitor();
        try {
            if (displayListener != null)
                ((android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE)).unregisterDisplayListener(displayListener);
        } catch (Throwable ignored) { }
        Diagnostics.markStopped(this);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Window w = getWindow();
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }
}
