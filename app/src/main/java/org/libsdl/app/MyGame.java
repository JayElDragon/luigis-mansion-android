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

    @Override
    protected String[] getLibraries() {
        return new String[] { "main" };
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        return new AuroraSurface(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        GameSettings.applyEnv(this);  // settings.ini location, before the game starts
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
        // Ask for a screen refresh rate that fits the chosen frame rate (30/60 -> 60 Hz, 90/120 -> 120 Hz).
        try {
            int fps = GameSettings.effectiveFps(getSharedPreferences("lm", MODE_PRIVATE));
            float want = Math.max(60f, fps);
            Display d = getDisplay();
            if (d != null) {
                Display.Mode cur = d.getMode();
                float bestOk = Float.MAX_VALUE, maxHz = 0;
                int idOk = 0, idMax = 0;
                for (Display.Mode m : d.getSupportedModes()) {
                    if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight())
                        continue;
                    float hz = m.getRefreshRate();
                    if (hz >= want - 0.5f && hz < bestOk) { bestOk = hz; idOk = m.getModeId(); }
                    if (hz > maxHz) { maxHz = hz; idMax = m.getModeId(); }
                }
                int id = idOk != 0 ? idOk : idMax;
                if (id != 0) lp.preferredDisplayModeId = id;
            }
            lp.preferredRefreshRate = want;
        } catch (Throwable ignored) {
        }
        getWindow().setAttributes(lp);
        setupPerformanceMode();
        overlay = new TouchOverlay(this);
        addContentView(overlay,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        menuButton = new android.widget.Button(this);
        menuButton.setText("MENU"); menuButton.setTextColor(0xFFFFFFFF);
        menuButton.setContentDescription("Open port options");
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
            TexturePacks.apply(this,getSharedPreferences("lm",MODE_PRIVATE));
        });
        dialog.show();
        dialog.getWindow().setLayout(-1,-1);
        dialog.getWindow().getDecorView().setSystemUiVisibility(5894);
        view.requestFocus();
    }

    // ---- Performance Mode: lighter settings + automatic cool-down when the phone gets hot ----

    private android.os.PowerManager.OnThermalStatusChangedListener thermalListener;

    private void setupPerformanceMode() {
        android.content.SharedPreferences prefs = getSharedPreferences("lm", MODE_PRIVATE);
        boolean on = prefs.getBoolean("g_perf", false);
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            getWindow().setSustainedPerformanceMode(on && pm != null && pm.isSustainedPerformanceModeSupported());
            if (on && thermalListener == null && pm != null) {
                thermalListener = status -> {
                    int cap = status >= android.os.PowerManager.THERMAL_STATUS_SEVERE ? 30
                            : status >= android.os.PowerManager.THERMAL_STATUS_MODERATE ? 60 : 0;
                    if (cap == GameSettings.thermalCap) return;
                    GameSettings.thermalCap = cap;
                    GameSettings.write(MyGame.this, prefs);
                    nativeBridgeInput(-3, 0, 0, 0, 0, 0, 0);  // reload settings.ini
                    runOnUiThread(() -> android.widget.Toast.makeText(MyGame.this,
                            cap == 0 ? "Phone cooled down: frame rate restored"
                                     : "Phone is warm: frame rate lowered to " + cap + " fps",
                            android.widget.Toast.LENGTH_SHORT).show());
                };
                pm.addThermalStatusListener(getMainExecutor(), thermalListener);
            } else if (!on && thermalListener != null && pm != null) {
                pm.removeThermalStatusListener(thermalListener);
                thermalListener = null;
                if (GameSettings.thermalCap != 0) {
                    GameSettings.thermalCap = 0;
                    GameSettings.write(this, prefs);
                    nativeBridgeInput(-3, 0, 0, 0, 0, 0, 0);
                }
            }
        } catch (Throwable ignored) {
        }
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
            menuButton.setText(engineMenuOpen ? "CLOSE" : "MENU");
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
        if(e.getKeyCode()==android.view.KeyEvent.KEYCODE_BUTTON_SELECT || e.getKeyCode()==android.view.KeyEvent.KEYCODE_MENU) {
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
        GameSettings.thermalCap=0;
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
