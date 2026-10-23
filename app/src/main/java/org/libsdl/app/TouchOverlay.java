package org.libsdl.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

/** GameCube-style glass touch controls drawn over the game; feeds a virtual gamepad (nativeTouchPad). */
public class TouchOverlay extends View implements InputManager.InputDeviceListener {
    // SDL gamepad button numbers (Aurora maps them to GameCube buttons)
    private static final int SOUTH = 0, EAST = 1, WEST = 2, NORTH = 3, BACK = 4, START = 6, RSHOULDER = 10,
            DPUP = 11, DPDOWN = 12, DPLEFT = 13, DPRIGHT = 14;
    private static final int ROUND = 0, BEAN = 1, TRIG = 2, STARTB = 4;

    private static final int GRAY_TOP = 0xFFD6D9DD, GRAY_BOT = 0xFF979BA2;

    private static class Ctl {
        int kind;
        String label;
        int top, bottom, textColor = 0xFFFFFFFF;
        int bit = -1;      // button bit, or -1
        int trigger = -1;  // 0 = L, 1 = R, or -1
        boolean mirror;
        float cx, cy, hx, hy, rot, ts;
        int pid = -1;
        Shader shader;
        Path path;
        boolean down() { return pid != -1; }
    }

    private static class Stick {
        boolean yellow;
        float cx, cy, R, dx, dy;
        int pid = -1;
        Shader knob;
        Path octGate, octKnob;
    }

    private final Ctl a = new Ctl(), b = new Ctl(), x = new Ctl(), y = new Ctl(),
            plus = new Ctl(), l = new Ctl(), r = new Ctl(), z = new Ctl();
    private final Ctl[] ctls = {a, b, x, y, plus, l, r, z};
    private final Stick ls = new Stick(), cs = new Stick();
    private float dpx, dpy, dpHx, dpT;
    private Path dpCross, dpArrow;
    private Shader dpShader;
    private int dpPid = -1, dpMask = 0;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    private final InputManager im;
    private boolean padPresent = false;
    private boolean sentDetach = false;
    private boolean touchEnabled = true;
    private boolean hideWithController;
    private boolean preview;

    // ---- movable / resizable layout ----
    private static final String[] LAYOUT_KEYS = {"ls", "cs", "dp", "a", "b", "x", "y", "st", "l", "r", "z"};
    private final java.util.HashMap<String, float[]> layout = new java.util.HashMap<>();
    private java.util.HashMap<String, float[]> editBackup;
    private boolean editing;
    private String sel;
    private Runnable editDone;
    private boolean dragging;
    private float editLx, editLy, pinchD0, pinchS0;
    private final RectF[] tb = new RectF[5];
    private static final String[] TB = {"Save", "Reset", "-", "+", "Cancel"};
    private android.content.SharedPreferences prefsRef;
    private int hapticLevel;
    private android.os.Vibrator vibrator;

    public TouchOverlay(Context c, boolean preview) {
        this(c);
        this.preview = preview;
        if (preview) { touchEnabled = true; hideWithController = false; setAlpha(1f); }
    }

    public TouchOverlay(Context c) {
        super(c);
        final int DARK = 0xFF3C4046;
        a.kind = ROUND; a.label = "A"; a.top = 0xFF2FB872; a.bottom = 0xFF138A4C; a.bit = SOUTH; a.textColor = 0xFFBFF3D6;
        b.kind = ROUND; b.label = "B"; b.top = 0xFFEF3F44; b.bottom = 0xFFB3141A; b.bit = WEST; b.textColor = 0xFFFFC7C7;
        x.kind = BEAN; x.label = "X"; x.top = GRAY_TOP; x.bottom = GRAY_BOT; x.bit = EAST; x.textColor = DARK;
        y.kind = BEAN; y.label = "Y"; y.top = GRAY_TOP; y.bottom = GRAY_BOT; y.bit = NORTH; y.textColor = DARK;
        plus.kind = STARTB; plus.label = "START/PAUSE"; plus.top = 0xFFF1F2F4; plus.bottom = 0xFF9A9EA5; plus.bit = START;
        l.kind = TRIG; l.label = "L"; l.top = GRAY_TOP; l.bottom = GRAY_BOT; l.trigger = 0; l.textColor = DARK;
        r.kind = TRIG; r.label = "R"; r.top = GRAY_TOP; r.bottom = GRAY_BOT; r.trigger = 1; r.mirror = true; r.textColor = DARK;
        cs.yellow = true;
        z.kind = BEAN; z.label = "Z"; z.top=0xFF9292D8; z.bottom=0xFF48487A; z.bit=RSHOULDER;
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        ring.setStyle(Paint.Style.STROKE);
        im = (InputManager) c.getSystemService(Context.INPUT_SERVICE);
        android.content.SharedPreferences sp = c.getSharedPreferences("lm", Context.MODE_PRIVATE);
        prefsRef = sp;
        for (int i = 0; i < tb.length; i++) tb[i] = new RectF();
        loadLayout(sp);
        hapticLevel = sp.getInt("touch_haptic", 0);
        try {
            android.os.VibratorManager vm = (android.os.VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) vibrator = vm.getDefaultVibrator();
        } catch (Throwable ignored) { }
        touchEnabled = sp.getBoolean("touch_enabled", true);
        hideWithController = sp.getBoolean("touch_hide_with_controller", false);
        l.top = r.top = 0xFF888581;
        l.bottom = r.bottom = 0xFF454340;
        l.textColor = r.textColor = 0xFFFFFFFF;
        setAlpha(sp.getFloat("touch_alpha", 1f));
        setClickable(false);
        setFocusable(false);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (im != null) im.registerInputDeviceListener(this, null);
        refreshPads();
    }

    @Override protected void onDetachedFromWindow() {
        releaseAll();
        if (im != null) im.unregisterInputDeviceListener(this);
        super.onDetachedFromWindow();
    }

    @Override public void onInputDeviceAdded(int id) { refreshPads(); }
    @Override public void onInputDeviceRemoved(int id) { refreshPads(); }
    @Override public void onInputDeviceChanged(int id) { refreshPads(); }

    /** Hide the overlay while a real controller is connected. */
    private void refreshPads() {
        boolean found = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                found = true;
                break;
            }
        }
        padPresent = found;
        post(new Runnable() {
            @Override public void run() {
                boolean hide = !editing && ((padPresent && hideWithController) || !touchEnabled);
                setVisibility(hide ? View.GONE : View.VISIBLE);
                if (hide) releaseAll();
            }
        });
    }

    void releaseAll() {
        for (Ctl c : ctls) c.pid = -1;
        ls.pid = cs.pid = dpPid = -1;
        ls.dx = ls.dy = cs.dx = cs.dy = 0;
        dpMask = 0;
        if (!sentDetach) {
            sentDetach = true;
            if (!preview) MyGame.nativeBridgeInput(-1, 0, 0, 0, 0, 0, 0);
        }
        invalidate();
    }

    // ---- saved layout, editor and haptics ----

    private void loadLayout(android.content.SharedPreferences sp) {
        layout.clear();
        for (String k : LAYOUT_KEYS) {
            if (sp.contains("tl_" + k + "_x") || sp.contains("tl_" + k + "_y") || sp.contains("tl_" + k + "_s"))
                layout.put(k, new float[] {sp.getFloat("tl_" + k + "_x", 0f), sp.getFloat("tl_" + k + "_y", 0f),
                        sp.getFloat("tl_" + k + "_s", 1f)});
        }
    }

    /** Back to the layout that ships with the app. */
    static void resetSavedLayout(android.content.SharedPreferences sp) {
        android.content.SharedPreferences.Editor ed = sp.edit();
        for (String k : LAYOUT_KEYS) ed.remove("tl_" + k + "_x").remove("tl_" + k + "_y").remove("tl_" + k + "_s");
        ed.commit();
    }

    /** Re-read the options (opacity, haptics, layout...) after the options screen was used. */
    void reloadPrefs() {
        android.content.SharedPreferences sp = prefsRef;
        if (sp == null) return;
        touchEnabled = sp.getBoolean("touch_enabled", true);
        hideWithController = sp.getBoolean("touch_hide_with_controller", false);
        hapticLevel = sp.getInt("touch_haptic", 0);
        if (!editing) {
            setAlpha(preview ? 1f : sp.getFloat("touch_alpha", 1f));
            loadLayout(sp);
            buildLayout(getWidth(), getHeight());
            refreshPads();
        }
        invalidate();
    }

    boolean isEditing() { return editing; }

    void startEdit(Runnable done) {
        releaseAll();
        editBackup = new java.util.HashMap<>();
        for (String k : LAYOUT_KEYS) { float[] a = q(k); editBackup.put(k, a.clone()); }
        editing = true; sel = null; dragging = false; editDone = done;
        setVisibility(View.VISIBLE);
        setAlpha(1f);
        invalidate();
    }

    private void endEdit() {
        editing = false; sel = null; dragging = false;
        Runnable r = editDone; editDone = null;
        reloadPrefs();
        if (r != null) r.run();
    }

    void saveEdit() {
        android.content.SharedPreferences.Editor ed = prefsRef.edit();
        for (String k : LAYOUT_KEYS) {
            float[] a = q(k);
            boolean def = Math.abs(a[0]) < 1e-4f && Math.abs(a[1]) < 1e-4f && Math.abs(a[2] - 1f) < 1e-4f;
            if (def) ed.remove("tl_" + k + "_x").remove("tl_" + k + "_y").remove("tl_" + k + "_s");
            else ed.putFloat("tl_" + k + "_x", a[0]).putFloat("tl_" + k + "_y", a[1]).putFloat("tl_" + k + "_s", a[2]);
        }
        ed.commit();
        endEdit();
    }

    void cancelEdit() {
        if (!editing) return;
        if (editBackup != null) {
            layout.clear();
            for (java.util.Map.Entry<String, float[]> e : editBackup.entrySet()) layout.put(e.getKey(), e.getValue().clone());
        }
        buildLayout(getWidth(), getHeight());
        endEdit();
    }

    private void buzz() {
        if (hapticLevel <= 0 || vibrator == null) return;
        try {
            int[] ms = {0, 12, 18, 26}, amp = {0, 60, 130, 230};
            int lv = Math.min(3, hapticLevel);
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(ms[lv],
                    vibrator.hasAmplitudeControl() ? amp[lv] : android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable ignored) { }
    }

    private int pressedCount() {
        int n = 0;
        for (Ctl c : ctls) if (c.down()) n++;
        if (ls.pid != -1) n++;
        if (cs.pid != -1) n++;
        if (dpPid != -1) n++;
        return n;
    }

    /** {centre x, centre y, half width, half height} of a control, for the editor. */
    private float[] extent(String k) {
        switch (k) {
            case "ls": return new float[] {ls.cx, ls.cy, ls.R, ls.R};
            case "cs": return new float[] {cs.cx, cs.cy, cs.R, cs.R};
            case "dp": return new float[] {dpx, dpy, dpHx, dpHx};
            case "a": return new float[] {a.cx, a.cy, a.hx, a.hy};
            case "b": return new float[] {b.cx, b.cy, b.hx, b.hy};
            case "x": return new float[] {x.cx, x.cy, x.hx, x.hy};
            case "y": return new float[] {y.cx, y.cy, y.hx, y.hy};
            case "st": return new float[] {plus.cx, plus.cy, plus.hx * 1.4f, plus.hx * 1.4f};
            case "l": return new float[] {l.cx, l.cy, l.hx, l.hy};
            case "r": return new float[] {r.cx, r.cy, r.hx, r.hy};
            default: return new float[] {z.cx, z.cy, z.hx, z.hy};
        }
    }

    private String pick(float px, float py) {
        String best = null;
        float bestScore = 1f;
        for (String k : LAYOUT_KEYS) {
            float[] e = extent(k);
            float nx = (px - e[0]) / (e[2] * 1.2f), ny = (py - e[1]) / (e[3] * 1.2f);
            float sc = (float) Math.sqrt(nx * nx + ny * ny);
            if (sc <= bestScore) { best = k; bestScore = sc; }
        }
        return best;
    }

    private void setScale(String k, float sc) {
        q(k)[2] = Math.max(0.5f, Math.min(2.0f, sc));
        buildLayout(getWidth(), getHeight());
    }

    private int tbHit(float px, float py) {
        for (int i = 0; i < tb.length; i++) if (tb[i].contains(px, py)) return i;
        return -1;
    }

    private void toolbar(int i) {
        switch (i) {
            case 0: saveEdit(); break;
            case 1: layout.clear(); buildLayout(getWidth(), getHeight()); break;
            case 2: if (sel != null) setScale(sel, q(sel)[2] / 1.1f); break;
            case 3: if (sel != null) setScale(sel, q(sel)[2] * 1.1f); break;
            default: cancelEdit();
        }
    }

    private boolean editTouch(MotionEvent e) {
        int act = e.getActionMasked();
        switch (act) {
            case MotionEvent.ACTION_DOWN: {
                float px = e.getX(), py = e.getY();
                int hit = tbHit(px, py);
                if (hit >= 0) { toolbar(hit); invalidate(); return true; }
                String k = pick(px, py);
                if (k != null) { sel = k; dragging = true; editLx = px; editLy = py; }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (sel != null && e.getPointerCount() >= 2) {
                    dragging = false;
                    pinchD0 = dist(e.getX(0), e.getY(0), e.getX(1), e.getY(1));
                    pinchS0 = q(sel)[2];
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (sel == null) return true;
                if (e.getPointerCount() >= 2) {
                    if (pinchD0 > 1f) setScale(sel, pinchS0 * dist(e.getX(0), e.getY(0), e.getX(1), e.getY(1)) / pinchD0);
                } else if (dragging) {
                    float px = e.getX(), py = e.getY();
                    float[] ex = extent(sel);
                    int w = getWidth(), h = getHeight();
                    float ncx = Math.max(0.03f * w, Math.min(0.97f * w, ex[0] + (px - editLx)));
                    float ncy = Math.max(0.03f * h, Math.min(0.97f * h, ex[1] + (py - editLy)));
                    float[] t = q(sel);
                    t[0] += (ncx - ex[0]) / h;
                    t[1] += (ncy - ex[1]) / h;
                    editLx = px; editLy = py;
                    buildLayout(w, h);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false; pinchD0 = 0;
                return true;
            default:
                return true;
        }
    }

    private void drawEdit(Canvas cv, float u) {
        int w = getWidth(), h = getHeight();
        fill.setShader(null);
        fill.setColor(0x33000000);
        cv.drawRect(0, 0, w, h, fill);
        if (sel != null) {
            float[] e = extent(sel);
            float pad = 0.012f * u;
            ring.setColor(0xFFFFE066);
            ring.setStrokeWidth(u * 0.006f);
            ring.setPathEffect(new android.graphics.DashPathEffect(new float[] {u * 0.02f, u * 0.012f}, 0));
            rf.set(e[0] - e[2] - pad, e[1] - e[3] - pad, e[0] + e[2] + pad, e[1] + e[3] + pad);
            cv.drawRoundRect(rf, u * 0.02f, u * 0.02f, ring);
            ring.setPathEffect(null);
            ring.setStrokeWidth(u * 0.0045f);
        }
        float bw = 0.16f * u, bh = 0.075f * u, gap = 0.02f * u;
        float x0 = (w - (5 * bw + 4 * gap)) / 2f, y0 = h - 0.03f * u - bh;
        for (int i = 0; i < tb.length; i++) {
            tb[i].set(x0 + i * (bw + gap), y0, x0 + i * (bw + gap) + bw, y0 + bh);
            fill.setColor(i == 0 ? 0xD0206030 : 0xB0203030);
            cv.drawRoundRect(tb[i], bh / 2, bh / 2, fill);
            ring.setColor(0xCCDCF6DC);
            cv.drawRoundRect(tb[i], bh / 2, bh / 2, ring);
            text.setColor(0xFFFFFFFF);
            text.setTextSize(0.034f * u);
            cv.drawText(TB[i], tb[i].centerX(), tb[i].centerY() + text.getTextSize() * 0.36f, text);
        }
        text.setColor(0xFFFFFFFF);
        text.setTextSize(0.03f * u);
        cv.drawText(sel == null ? "Tap a button, then drag it. Pinch or use - / + to resize."
                : "Drag to move. Pinch or use - / + to resize.", w / 2f, y0 - 0.025f * u, text);
        fill.setAlpha(255);
    }

    // ---- layout (units are fractions of the screen height; right-side parts hug the right edge) ----

    private static Path octagon(float rad) {
        Path p = new Path();
        for (int i = 0; i < 8; i++) {
            double ang = Math.toRadians(22.5 + 45.0 * i);
            float px = (float) (Math.cos(ang) * rad), py = (float) (Math.sin(ang) * rad);
            if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
        }
        p.close();
        return p;
    }

    private static Shader vgrad(float hy, int top, int bottom) {
        return new LinearGradient(0, -hy, 0, hy, top, bottom, Shader.TileMode.CLAMP);
    }

    private void place(Ctl c, float cx, float cy, float hx, float hy, float rot) {
        c.cx = cx; c.cy = cy; c.hx = hx; c.hy = hy; c.rot = rot;
        c.shader = vgrad(hy, c.top, c.bottom);
        if (c.kind == TRIG) {
            Path p = new Path();
            p.moveTo(-hx * 0.96f, hy * 0.92f);
            p.cubicTo(-hx * 1.08f, hy * 0.2f, -hx * 0.72f, -hy * 0.45f, -hx * 0.2f, -hy * 0.7f);
            p.cubicTo(hx * 0.15f, -hy * 0.9f, hx * 0.48f, -hy * 1.15f, hx * 0.67f, -hy * 0.92f);
            p.cubicTo(hx * 0.86f, -hy * 0.72f, hx * 1.02f, -hy * 0.2f, hx * 0.98f, hy * 0.06f);
            p.cubicTo(hx * 0.94f, hy * 0.26f, hx * 0.45f, hy * 0.2f, hx * 0.08f, hy * 0.4f);
            p.cubicTo(-hx * 0.3f, hy * 0.58f, -hx * 0.75f, hy * 0.92f, -hx * 0.96f, hy * 0.92f);
            p.close();
            if (c.mirror) {
                Matrix m = new Matrix();
                m.setScale(-1f, 1f);
                p.transform(m);
            }
            c.path = p;
        }
    }

    private void placeStick(Stick s, float cx, float cy, float R) {
        s.cx = cx; s.cy = cy; s.R = R;
        float kr = s.yellow ? R * 0.53f : R * 0.46f;
        s.octGate = octagon(R * (s.yellow ? 0.76f : 0.68f));
        s.octKnob = octagon(kr * 1.02f);
        if (s.yellow)
            s.knob = new LinearGradient(0, -kr, 0, kr, 0xFFF8D23E, 0xFFD6A100, Shader.TileMode.CLAMP);
        else
            s.knob = new RadialGradient(0, -kr * 0.3f, kr * 1.3f, 0xFFE6E8EB, 0xFF9FA3A9, Shader.TileMode.CLAMP);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { buildLayout(w, h); }

    /** Saved layout: per control {dx, dy (fractions of screen height), scale}. Missing = the default layout. */
    private float[] q(String k) {
        float[] a = layout.get(k);
        if (a == null) { a = new float[] {0f, 0f, 1f}; layout.put(k, a); }
        return a;
    }

    private void buildLayout(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float u = h;
        float[] t;
        t = q("ls"); placeStick(ls, 0.20f * u + t[0] * u, 0.72f * u + t[1] * u, 0.177f * u * t[2]);
        t = q("cs"); placeStick(cs, w - 0.183f * u + t[0] * u, 0.763f * u + t[1] * u, 0.146f * u * t[2]);
        t = q("dp");
        dpx = 0.168f * u + t[0] * u; dpy = 0.384f * u + t[1] * u; dpHx = 0.115f * u * t[2]; dpT = 0.039f * u * t[2];
        dpShader = vgrad(dpHx, 0xFF898782, 0xFF504E4A);
        dpCross = new Path();
        Path p1 = new Path(), p2 = new Path();
        p1.addRoundRect(new RectF(-dpHx, -dpT, dpHx, dpT), dpT * 0.45f, dpT * 0.45f, Path.Direction.CW);
        p2.addRoundRect(new RectF(-dpT, -dpHx, dpT, dpHx), dpT * 0.45f, dpT * 0.45f, Path.Direction.CW);
        dpCross.op(p1, p2, Path.Op.UNION);
        dpArrow = new Path();
        float s = dpT * 0.50f;
        dpArrow.moveTo(0, -s); dpArrow.lineTo(s, s * 0.7f); dpArrow.lineTo(-s, s * 0.7f); dpArrow.close();

        t = q("l");  place(l, 0.191f * u + t[0] * u, 0.103f * u + t[1] * u, 0.140f * u * t[2], 0.082f * u * t[2], 0); l.ts = 0.055f * u * t[2];
        t = q("r");  place(r, w - 0.192f * u + t[0] * u, 0.103f * u + t[1] * u, 0.140f * u * t[2], 0.082f * u * t[2], 0); r.ts = 0.055f * u * t[2];
        t = q("y");  place(y, w - 0.270f * u + t[0] * u, 0.295f * u + t[1] * u, 0.096f * u * t[2], 0.053f * u * t[2], -12); y.ts = 0.060f * u * t[2];
        t = q("x");  place(x, w - 0.085f * u + t[0] * u, 0.418f * u + t[1] * u, 0.053f * u * t[2], 0.096f * u * t[2], -15); x.ts = 0.060f * u * t[2];
        t = q("a");  place(a, w - 0.231f * u + t[0] * u, 0.463f * u + t[1] * u, 0.089f * u * t[2], 0.098f * u * t[2], 0); a.ts = 0.090f * u * t[2];
        t = q("b");  place(b, w - 0.396f * u + t[0] * u, 0.524f * u + t[1] * u, 0.058f * u * t[2], 0.058f * u * t[2], 0); b.ts = 0.060f * u * t[2];
        t = q("st"); place(plus, w * .5f - .17f * u + t[0] * u, 0.100f * u + t[1] * u, 0.035f * u * t[2], 0.035f * u * t[2], 0); plus.ts = 0.021f * u * t[2];
        t = q("z");  place(z, w - .44f * u + t[0] * u, .20f * u + t[1] * u, .082f * u * t[2], .045f * u * t[2], 0); z.ts = .056f * u * t[2];
    }

    // ---- drawing ----

    @Override protected void onDraw(Canvas cv) {
        float u = getHeight();
        ring.setStrokeWidth(u * 0.0045f);
        text.setShadowLayer(u * 0.004f, 0, u * 0.003f, 0x66000000);
        drawStick(cv, ls, u);
        drawStick(cv, cs, u);
        drawDpad(cv, u);
        for (Ctl c : ctls) drawCtl(cv, c, u);
        if (editing) drawEdit(cv, u);
    }

    private void drawStick(Canvas cv, Stick s, float u) {
        float kr = s.yellow ? s.R * 0.53f : s.R * 0.46f;
        // outer dark ring
        fill.setShader(new RadialGradient(s.cx, s.cy, s.R,
                new int[] {0x66252525, 0x77343434, 0xC27B7B7B, 0x8A262626},
                new float[] {0f, 0.76f, 0.91f, 1f}, Shader.TileMode.CLAMP));
        cv.drawCircle(s.cx, s.cy, s.R, fill);
        fill.setShader(null);
        ring.setColor(0xAAB7BBC1);
        cv.drawCircle(s.cx, s.cy, s.R, ring);
        // octagon gate
        cv.save();
        cv.translate(s.cx, s.cy);
        fill.setColor(0x55787C82);
        cv.drawPath(s.octGate, fill);
        ring.setColor(0x88C9CCD1);
        cv.drawPath(s.octGate, ring);
        cv.restore();
        // knob
        float kx = s.cx + s.dx * (s.R - kr * 0.55f), ky = s.cy + s.dy * (s.R - kr * 0.55f);
        cv.save();
        cv.translate(kx, ky);
        boolean on = s.pid != -1;
        fill.setShader(s.knob);
        fill.setAlpha(on ? 255 : 230);
        if (s.yellow) {
            cv.drawPath(s.octKnob, fill);
            fill.setShader(null);
            ring.setColor(0xCCFFF2B0);
            cv.drawPath(s.octKnob, ring);
            text.setColor(0xFF3B2B00);
            text.setTextSize(0.41f * s.R);
            cv.drawText("C", 0, text.getTextSize() * 0.36f, text);
        } else {
            cv.drawCircle(0, 0, kr, fill);
            fill.setShader(null);
            ring.setColor(0xCCFFFFFF);
            cv.drawCircle(0, 0, kr, ring);
            ring.setColor(0x66FFFFFF);
            cv.drawCircle(0, 0, kr * 0.68f, ring);
        }
        cv.restore();
        fill.setShader(null);
        fill.setAlpha(255);
    }

    private void drawDpad(Canvas cv, float u) {
        cv.save();
        cv.translate(dpx, dpy);
        fill.setShader(dpShader);
        fill.setAlpha(0xA8);
        cv.drawPath(dpCross, fill);
        fill.setShader(null);
        ring.setColor(0xAAEDEFF1);
        cv.drawPath(dpCross, ring);
        float arm = dpHx * 0.68f;
        drawArrow(cv, 0, -arm, 0, (dpMask & (1 << DPUP)) != 0);
        drawArrow(cv, 0, arm, 180, (dpMask & (1 << DPDOWN)) != 0);
        drawArrow(cv, -arm, 0, 270, (dpMask & (1 << DPLEFT)) != 0);
        drawArrow(cv, arm, 0, 90, (dpMask & (1 << DPRIGHT)) != 0);
        cv.restore();
        fill.setAlpha(255);
    }

    private void drawArrow(Canvas cv, float ax, float ay, float rot, boolean on) {
        cv.save();
        cv.translate(ax, ay);
        cv.rotate(rot);
        fill.setColor(on ? 0xFFFFFFFF : 0xCCE9EBEE);
        cv.drawPath(dpArrow, fill);
        cv.restore();
    }

    private void drawShape(Canvas cv, Ctl c, Paint p) {
        switch (c.kind) {
            case ROUND:
            case STARTB:
                rf.set(-c.hx, -c.hy, c.hx, c.hy);
                cv.drawOval(rf, p);
                break;
            case BEAN:
                rf.set(-c.hx, -c.hy, c.hx, c.hy);
                cv.drawOval(rf, p);
                break;
            default:
                cv.drawPath(c.path, p);
        }
    }

    private void drawCtl(Canvas cv, Ctl c, float u) {
        boolean on = c.down();
        float k = on ? 0.94f : 1f;
        if (c.kind == STARTB) {
            cv.save();
            cv.translate(c.cx, c.cy);
            cv.scale(k, k);
            fill.setShader(null);
            fill.setColor(0xCC26282C);
            cv.drawCircle(0, 0, c.hx, fill);
            ring.setColor(0xCCB7BBC1);
            cv.drawCircle(0, 0, c.hx, ring);
            fill.setShader(new LinearGradient(0, -c.hx, 0, c.hx, c.top, c.bottom, Shader.TileMode.CLAMP));
            fill.setAlpha(on ? 255 : 0xF0);
            cv.drawCircle(0, 0, c.hx * 0.68f, fill);
            fill.setShader(null);
            fill.setAlpha(255);
            text.setColor(0xFFFFFFFF);
            text.setTextSize(c.ts);
            cv.drawText(c.label, 0, -c.hx - c.ts * 1.05f, text);
            cv.restore();
            return;
        }
        cv.save();
        cv.translate(c.cx, c.cy);
        cv.rotate(c.rot);
        cv.scale(k, k);
        fill.setShader(c.shader);
        fill.setAlpha(on ? 255 : c.kind == ROUND ? 0xC8 : 0xA8);
        drawShape(cv, c, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        // glossy highlight on the upper half of round buttons
        if (c.kind == ROUND) {
            fill.setColor(0x26FFFFFF);
            rf.set(-c.hx * 0.78f, -c.hy * 0.92f, c.hx * 0.78f, -c.hy * 0.05f);
            cv.drawOval(rf, fill);
        }
        ring.setColor(on ? 0xFFFFFFFF : 0xB0F2F4F6);
        drawShape(cv, c, ring);
        cv.rotate(-c.rot);
        {
            text.setColor(c.textColor);
            text.setTextSize(c.ts);
            cv.drawText(c.label, 0, c.ts * 0.36f, text);
        }
        cv.restore();
    }

    // ---- touch ----

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (getVisibility() != View.VISIBLE) return false;
        if (editing) return editTouch(e);
        int act = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            {
                int before = pressedCount();
                press(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                if (pressedCount() > before) buzz();
            }
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++)
                    move(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                release(e.getPointerId(idx));
                break;
            case MotionEvent.ACTION_CANCEL:
                for (Ctl c : ctls) c.pid = -1;
                ls.pid = cs.pid = dpPid = -1;
                ls.dx = ls.dy = cs.dx = cs.dy = 0;
                dpMask = 0;
                break;
            default:
                return true;
        }
        send();
        invalidate();
        return true;  // consume everything so the game does not see touch-as-mouse clicks
    }

    private void press(int pid, float px, float py) {
        if (ls.pid == -1 && dist(px, py, ls.cx, ls.cy) <= ls.R * 1.35f) { ls.pid = pid; stickMove(ls, px, py); return; }
        if (cs.pid == -1 && dist(px, py, cs.cx, cs.cy) <= cs.R * 1.35f) { cs.pid = pid; stickMove(cs, px, py); return; }
        if (dpPid == -1 && Math.abs(px - dpx) <= dpHx * 1.25f && Math.abs(py - dpy) <= dpHx * 1.25f) {
            dpPid = pid; dpMove(px, py); return;
        }
        Ctl best = null;
        float bestScore = 1.0f;
        for (Ctl c : ctls) {
            if (c.down()) continue;
            double rad = Math.toRadians(-c.rot);
            float dx = px - c.cx, dy = py - c.cy;
            float lx = (float) (dx * Math.cos(rad) - dy * Math.sin(rad));
            float ly = (float) (dx * Math.sin(rad) + dy * Math.cos(rad));
            float s;
            if (c.kind == TRIG || c.kind == BEAN) {
                s = Math.max(Math.abs(lx) / (c.hx * 1.2f), Math.abs(ly) / (c.hy * 1.35f));
            } else {
                float ex = lx / (c.hx * 1.3f), ey = ly / (c.hy * 1.3f);
                s = (float) Math.sqrt(ex * ex + ey * ey);
            }
            if (s <= bestScore) { best = c; bestScore = s; }
        }
        if (best != null) best.pid = pid;
    }

    private void move(int pid, float px, float py) {
        if (ls.pid == pid) stickMove(ls, px, py);
        else if (cs.pid == pid) stickMove(cs, px, py);
        else if (dpPid == pid) dpMove(px, py);
    }

    private void release(int pid) {
        for (Ctl c : ctls) if (c.pid == pid) c.pid = -1;
        if (ls.pid == pid) { ls.pid = -1; ls.dx = ls.dy = 0; }
        if (cs.pid == pid) { cs.pid = -1; cs.dx = cs.dy = 0; }
        if (dpPid == pid) { dpPid = -1; dpMask = 0; }
    }

    private void stickMove(Stick s, float px, float py) {
        float dx = (px - s.cx) / s.R, dy = (py - s.cy) / s.R;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1f) { dx /= len; dy /= len; }
        s.dx = dx; s.dy = dy;
    }

    private void dpMove(float px, float py) {
        float dx = px - dpx, dy = py - dpy, t = dpHx * 0.28f;
        int m = 0;
        if (dx < -t) m |= 1 << DPLEFT;
        if (dx > t) m |= 1 << DPRIGHT;
        if (dy < -t) m |= 1 << DPUP;
        if (dy > t) m |= 1 << DPDOWN;
        dpMask = m;
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void send() {
        int buttons = dpMask;
        float lt = 0, rt = 0;
        for (Ctl c : ctls) {
            if (!c.down()) continue;
            if (c.bit >= 0) buttons |= 1 << c.bit;
            if (c.trigger == 0) lt = 1;
            if (c.trigger == 1) rt = 1;
        }
        sentDetach = false;
        if (!preview) MyGame.nativeBridgeInput(buttons, ls.dx, ls.dy, cs.dx, cs.dy, lt, rt);
    }
}
