package org.libsdl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Options screen: a themed picture frame with live controls drawn on top.
 * All positions are in the picture's own coordinates (1672 x 941) and scaled to the screen.
 */
public class OptionsView extends View {
    public interface Listener { void onClose(); }

    static final int[] FPS = {30, 60, 90, 120};
    static final int[] RES = {1, 2, 3, 4};
    static final String[] RES_NAME = {"1x - Fast", "2x - Balanced", "3x - Sharp", "4x - Ultra"};
    static final String[] PRESET_NAME = {"Low-end", "Balanced", "Smooth", "High quality", "Maximum"};
    static final int[][] PRESET = {{30, 1, 1}, {60, 2, 1}, {120, 1, 1}, {60, 3, 4}, {60, 4, 4}};
    static final float[] VOL = {0f, 0.25f, 0.5f, 0.75f, 1f};

    // picture geometry: the file is the 1672 x 941 picture; on wider screens a thin band of it is
    // stretched sideways so everything right of the band slides right (true widescreen).
    private static final float PIC_W = 1672f, PIC_H = 941f, BAND0 = 1134f, BAND1 = 1146f;
    private static final float TAB_X0 = 148f, TAB_X1 = 432f, TAB_Y0 = 207f, TAB_PITCH = 102f, TAB_H = 92f;
    private static final float ROW_X0 = 482f, ROW_X1 = 1297f, ROW_C0 = 221f, ROW_PITCH = 82.5f, ROW_HALF = 37f;
    private static final String[] TAB_NAME = {"Graphics", "Controls", "HUD", "Audio", "Cheats"};
    private static final String[] TAB_ICON = {"gear", "pad", "hud", "audio", "spark"};

    private abstract class Row {
        final String icon, title, sub;
        final boolean toggle;
        Row(String icon, String title, String sub, boolean toggle) {
            this.icon = icon; this.title = title; this.sub = sub; this.toggle = toggle;
        }
        String value() { return ""; }
        boolean on() { return false; }
        abstract void change(int dir);
    }

    private final SharedPreferences prefs;
    private final Listener listener;
    private final List<List<Row>> tabs = new ArrayList<>();
    private final Map<String, Bitmap> icons = new HashMap<>();
    private Bitmap plate;

    private static final int VIS = 8;      // rows that fit inside the picture frame
    private int scroll = 0, scrollStart = 0;
    private boolean dragScrolling = false;
    private long resetArmedAt = 0, removeArmedAt = 0;
    private int tab = 0, sbSel = 0, rowSel = 0;
    private boolean inList = false;
    private String lastSelection = "";
    private long selectionTime;
    private float selectionPulse;
    private float downX, downY;
    private boolean moved = false;
    private int downHit = -99;  // hit code at touch down
    private boolean axisHeld = false;
    private final int slop;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private float scale = 1f, ox = 0f, oy = 0f;  // screen position of picture coordinate (0,0)
    private float extra = 0f;  // extra picture-units of width on wide screens
    private final android.graphics.Rect srcR = new android.graphics.Rect();

    public OptionsView(Context c, SharedPreferences prefs, Listener l) {
        super(c);
        this.prefs = prefs;
        this.listener = l;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        try (InputStream in = c.getAssets().open("opt_plate.jpg")) {
            plate = BitmapFactory.decodeStream(in);
        } catch (Throwable ignored) {
        }
        String[] names = {"gear", "pad", "hud", "audio", "restore", "wide", "cross", "fps", "layers", "run",
                "check", "spark", "sync"};
        for (String n : names) {
            try (InputStream in = c.getAssets().open("ic_" + n + ".png")) {
                icons.put(n, BitmapFactory.decodeStream(in));
            } catch (Throwable ignored) {
            }
        }
        build();
    }

    public void release() {
        if (plate != null) { plate.recycle(); plate = null; }
        for (Bitmap b : icons.values()) if (b != null) b.recycle();
        icons.clear();
    }

    /** Back: from the list to the side menu, or close the screen. */
    public void back() {
        if (inList) { inList = false; invalidate(); }
        else if (listener != null) listener.onClose();
    }

    // ---- rows ----

    private int geti(String k, int d) { return prefs.getInt(k, d); }
    private boolean getb(String k, boolean d) { return prefs.getBoolean(k, d); }

    private static int idx(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return 0;
    }

    private int currentPreset() {
        int f = geti("g_fps", GameSettings.DEF_FPS), r = geti("g_res", GameSettings.DEF_RES),
                m = geti("g_msaa", GameSettings.DEF_MSAA);
        for (int i = 0; i < PRESET.length; i++)
            if (PRESET[i][0] == f && PRESET[i][1] == r && PRESET[i][2] == m) return i;
        return -1;
    }

    private static int volIdx(float v) {
        int best = 0;
        for (int i = 0; i < VOL.length; i++) if (Math.abs(VOL[i] - v) < Math.abs(VOL[best] - v)) best = i;
        return best;
    }

    private Row volumeRow(final String title, final String sub, final String key) {
        return new Row("audio", title, sub, false) {
            @Override String value() { return Math.round(VOL[volIdx(prefs.getFloat(key, 1f))] * 100) + "%"; }
            @Override void change(int dir) {
                int i = (volIdx(prefs.getFloat(key, 1f)) + dir + VOL.length) % VOL.length;
                prefs.edit().putFloat(key, VOL[i]).commit();
            }
        };
    }

    private Row boolRow(String icon, String title, String sub, final String key, final boolean def) {
        return new Row(icon, title, sub, true) {
            @Override boolean on() { return getb(key, def); }
            @Override void change(int dir) { prefs.edit().putBoolean(key, !on()).commit(); }
        };
    }

    private Row hudRow() {
        return new Row("cross", "HUD Position", "Position of on-screen elements", false) {
            @Override String value() { return getb("g_hud43", true) ? "Centered (Original)" : "Screen edges"; }
            @Override void change(int dir) { prefs.edit().putBoolean("g_hud43", !getb("g_hud43", true)).commit(); }
        };
    }

    private void build() {
        tabs.clear();
        List<Row> g = new ArrayList<>();
        g.add(new Row("wide", "Screen Format", "Original, widescreen or ultra-wide", false) {
            final String[] formats = {"4:3", "Auto", "16:9", "16:10", "21:9", "32:9"};
            @Override String value() { return formats[Math.max(0, Math.min(5, geti("g_aspect", GameSettings.DEF_ASPECT)))]; }
            @Override void change(int dir) { prefs.edit().putInt("g_aspect", (geti("g_aspect", GameSettings.DEF_ASPECT)+dir+6)%6).commit(); }
        });
        g.add(hudRow());
        g.add(boolRow("fps", "Show FPS Counter", "Display frames per second", "g_showfps", false));
        g.add(new Row("layers", "Quality Preset", "Adjust overall graphics quality", false) {
            @Override String value() { int c = currentPreset(); return c < 0 ? "Custom" : PRESET_NAME[c]; }
            @Override void change(int dir) {
                int c = currentPreset(), n = PRESET.length;
                int i = c < 0 ? (dir > 0 ? 0 : n - 1) : (c + dir + n) % n;
                prefs.edit().putInt("g_fps", PRESET[i][0]).putInt("g_res", PRESET[i][1])
                        .putInt("g_msaa", PRESET[i][2]).commit();
            }
        });
        g.add(new Row("run", "Frame Rate", "Target frame rate limit", false) {
            @Override String value() { return geti("g_fps", GameSettings.DEF_FPS) + " fps"; }
            @Override void change(int dir) {
                int i = (idx(FPS, geti("g_fps", GameSettings.DEF_FPS)) + dir + FPS.length) % FPS.length;
                prefs.edit().putInt("g_fps", FPS[i]).commit();
            }
        });
        g.add(new Row("check", "Render Resolution", "Internal rendering resolution", false) {
            @Override String value() { return RES_NAME[idx(RES, geti("g_res", GameSettings.DEF_RES))]; }
            @Override void change(int dir) {
                int i = (idx(RES, geti("g_res", GameSettings.DEF_RES)) + dir + RES.length) % RES.length;
                prefs.edit().putInt("g_res", RES[i]).commit();
            }
        });
        g.add(new Row("spark", "Anti-aliasing", "Smooth edges (MSAA)", false) {
            @Override String value() { int m = geti("g_msaa", GameSettings.DEF_MSAA); return m > 1 ? m + "x" : "Off"; }
            @Override void change(int dir) {
                prefs.edit().putInt("g_msaa", geti("g_msaa", GameSettings.DEF_MSAA) > 1 ? 1 : 4).commit();
            }
        });
        g.add(boolRow("sync", "VSync", "Synchronize with display refresh rate", "g_vsync", true));
        g.add(boolRow("run", "Performance Mode", "Cooler phone: 60 fps, 1x, no AA, no reverb", "g_perf", false));
        g.add(boolRow("layers", "Custom Textures", "Load HD texture packs (PNG / DDS)", "tp_on", false));
        g.add(new Row("spark", "Texture Pack", "Which installed pack to use", false) {
            @Override String value() {
                List<String> l = TexturePacks.list(getContext());
                if (l.isEmpty()) return "None installed";
                String a = prefs.getString("tp_pack", "");
                if (a == null || !l.contains(a)) return "None";
                return a.length() > 16 ? a.substring(0, 15) + "…" : a;
            }
            @Override void change(int dir) {
                List<String> l = new ArrayList<>();
                l.add("");
                l.addAll(TexturePacks.list(getContext()));
                String a = prefs.getString("tp_pack", "");
                int i = Math.max(0, l.indexOf(a));
                i = (i + dir + l.size()) % l.size();
                prefs.edit().putString("tp_pack", l.get(i)).commit();
            }
        });
        g.add(new Row("sync", "Install Texture Pack", "Add a .zip pack from your phone", false) {
            @Override String value() { return "Choose…"; }
            @Override void change(int dir) {
                if (getContext() instanceof LauncherActivity) ((LauncherActivity) getContext()).installTexturePack();
                else Toast.makeText(getContext(), "Install packs from the main menu, then pick them here", Toast.LENGTH_LONG).show();
            }
        });
        g.add(new Row("cross", "Remove Texture Pack", "Delete the selected pack from this app", false) {
            @Override String value() {
                return android.os.SystemClock.uptimeMillis() - removeArmedAt < 3000 ? "Tap again" : "Remove";
            }
            @Override void change(int dir) {
                String a = prefs.getString("tp_pack", "");
                if (a == null || a.isEmpty() || !TexturePacks.list(getContext()).contains(a)) {
                    Toast.makeText(getContext(), "No texture pack selected", Toast.LENGTH_SHORT).show();
                    return;
                }
                long now = android.os.SystemClock.uptimeMillis();
                if (now - removeArmedAt < 3000) {
                    removeArmedAt = 0;
                    TexturePacks.remove(getContext(), a);
                    prefs.edit().putString("tp_pack", "").commit();
                    Toast.makeText(getContext(), "Texture pack removed", Toast.LENGTH_SHORT).show();
                } else {
                    removeArmedAt = now;
                    postInvalidateDelayed(3050);
                }
            }
        });
        tabs.add(g);

        List<Row> c = new ArrayList<>();
        c.add(boolRow("pad", "On-screen Controls", "Show the touch buttons", "touch_enabled", true));
        c.add(boolRow("pad", "Hide with Controller", "Hide touch buttons when a gamepad is connected", "touch_hide_with_controller", false));
        c.add(new Row("pad", "Preview Controls", "Try the touch layout without starting the game", false) {
            @Override String value() { return "Open"; }
            @Override void change(int dir) {
                if(getContext() instanceof LauncherActivity) ((LauncherActivity)getContext()).previewControls();
                else Toast.makeText(getContext(),"Close options to use the touch controls",Toast.LENGTH_SHORT).show();
            }
        });
        c.add(new Row("layers", "Control Opacity", "How see-through the buttons are", false) {
            @Override String value() { return Math.round(prefs.getFloat("touch_alpha", 1f) * 100) + "%"; }
            @Override void change(int dir) {
                float a = prefs.getFloat("touch_alpha", 1f);
                float[] v = {1f, 0.7f, 0.45f};
                int i = 0;
                for (int k = 0; k < v.length; k++) if (Math.abs(v[k] - a) < 0.06f) i = k;
                i = (i + dir + v.length) % v.length;
                prefs.edit().putFloat("touch_alpha", v[i]).commit();
            }
        });
        c.add(new Row("pad", "Touch Haptics", "Vibrate when a touch button is pressed", false) {
            final String[] names = {"Off", "Light", "Medium", "Strong"};
            @Override String value() { return names[Math.max(0, Math.min(3, geti("touch_haptic", 0)))]; }
            @Override void change(int dir) {
                prefs.edit().putInt("touch_haptic", (geti("touch_haptic", 0) + dir + 4) % 4).commit();
                if (getContext() instanceof MyGame || getContext() instanceof LauncherActivity)
                    try {
                        android.os.VibratorManager vm = (android.os.VibratorManager) getContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                        int lv = geti("touch_haptic", 0);
                        if (lv > 0 && vm != null) vm.getDefaultVibrator().vibrate(android.os.VibrationEffect.createOneShot(
                                new int[] {0, 12, 18, 26}[lv], new int[] {0, 60, 130, 230}[lv]));
                    } catch (Throwable ignored) { }
            }
        });
        c.add(new Row("layers", "Edit Layout", "Move and resize the on-screen buttons", false) {
            @Override String value() { return "Open"; }
            @Override void change(int dir) {
                if (getContext() instanceof LauncherActivity) ((LauncherActivity) getContext()).editLayout();
                else if (getContext() instanceof MyGame) {
                    if (listener != null) listener.onClose();
                    ((MyGame) getContext()).startLayoutEdit();
                }
            }
        });
        c.add(new Row("restore", "Reset Layout", "Back to the layout that came with the app", false) {
            @Override String value() {
                return android.os.SystemClock.uptimeMillis() - resetArmedAt < 3000 ? "Tap again" : "Reset";
            }
            @Override void change(int dir) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - resetArmedAt < 3000) {
                    resetArmedAt = 0;
                    TouchOverlay.resetSavedLayout(prefs);
                    Toast.makeText(getContext(), "Layout reset to default", Toast.LENGTH_SHORT).show();
                } else {
                    resetArmedAt = now;
                    postInvalidateDelayed(3050);
                }
            }
        });
        c.add(new Row("pad", "Controller Settings", "Remap buttons, rumble, dead zones, Xbox / PlayStation pads", false) {
            @Override String value() { return "Open"; }
            @Override void change(int dir) {
                if (getContext() instanceof MyGame) {
                    if (listener != null) listener.onClose();
                    ((MyGame) getContext()).toggleEngineMenu();
                } else Toast.makeText(getContext(), "Start the game, then open Options with the MENU button", Toast.LENGTH_LONG).show();
            }
        });
        tabs.add(c);

        List<Row> h = new ArrayList<>();
        h.add(hudRow());
        h.add(boolRow("fps", "Show FPS Counter", "Display frames per second", "g_showfps", false));
        tabs.add(h);

        List<Row> a = new ArrayList<>();
        a.add(volumeRow("Master Volume", "Overall loudness", "a_master"));
        a.add(volumeRow("Music Volume", "Background music", "a_music"));
        a.add(volumeRow("Effects Volume", "Sound effects and voices", "a_sfx"));
        a.add(boolRow("audio", "Reverb", "Echo effects (turn off for speed)", "a_reverb", true));
        a.add(boolRow("audio", "Menu Music", "Title screen music", "menu_music", true));
        tabs.add(a);
        List<Row> cheats = new ArrayList<>();
        cheats.add(boolRow("check", "No Damage", "Luigi takes no damage · applies next launch", "cheat_no_damage", false));
        cheats.add(boolRow("cross", "Extra Health", "5x starting and maximum health", "cheat_health", false));
        cheats.add(boolRow("spark", "Weaker Ghosts", "Ghost health reduced to 25%", "cheat_weak_ghosts", false));
        cheats.add(boolRow("run", "Super Poltergust", "4x vacuum strength", "cheat_vacuum", false));
        cheats.add(boolRow("pad", "Poltergust Ride", "Glide with momentum throughout the mansion", "cheat_ride", false));
        cheats.add(boolRow("wide", "Mirror Mansion", "Mirror the world and its controls", "cheat_mirror", false));
        cheats.add(boolRow("run", "Skip Cutscenes", "Press START during a cutscene to skip", "cheat_skip", false));
        tabs.add(cheats);
    }

    private List<Row> rows() { return tabs.get(tab); }

    /** Every change is written to disk right away (prefs + the game's settings.ini). */
    private void save() {
        try { GameSettings.write(getContext(), prefs); } catch (Throwable ignored) { }
    }

    private void restoreDefaults() {
        SharedPreferences.Editor e = prefs.edit();
        for (String k : new String[] {"g_fps", "g_res", "g_msaa", "g_vsync", "g_aspect", "g_hud43", "g_showfps",
                "a_master", "a_music", "a_sfx", "a_reverb", "menu_music", "touch_enabled", "touch_alpha", "touch_hide_with_controller", "g_perf", "touch_haptic"})
            e.remove(k);
        e.commit();
        save();
        sbSel = tab;  // keep the highlight on the real tab, not on the button just pressed
        Toast.makeText(getContext(), "Defaults restored", Toast.LENGTH_SHORT).show();
        invalidate();
    }

    // ---- drawing ----

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float s = Math.min(h / PIC_H, w / PIC_W);
        scale = s;
        extra = Math.max(0f, w / s - PIC_W);
        ox = 0f;
        oy = (h - PIC_H * s) / 2f;
        if (extra == 0f) ox = (w - PIC_W * s) / 2f;
    }

    /** Keep animating whenever the view is on screen. (Does not wait for window focus, which can arrive late or never on some builds/devices.) */
    private boolean alive() {
        return isAttachedToWindow() && isShown() && getWindowVisibility() == View.VISIBLE;
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); invalidate(); }
    @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); if (v == View.VISIBLE) invalidate(); }
    @Override protected void onVisibilityChanged(View changed, int v) { super.onVisibilityChanged(changed, v); if (v == View.VISIBLE) invalidate(); }

    @Override protected void onDraw(Canvas cv) {
        Row selectedRow = rows().get(Math.min(rowSel, rows().size()-1));
        String selection = tab+":"+sbSel+":"+rowSel+":"+inList+":"+selectedRow.value()+":"+selectedRow.on();
        long now = android.os.SystemClock.uptimeMillis();
        if (!selection.equals(lastSelection)) { lastSelection=selection; selectionTime=now; }
        float progress = Math.min(1f, (now-selectionTime)/260f);
        selectionPulse = (float)Math.sin(progress*Math.PI);
        cv.drawColor(0xFF04080C);
        if (plate != null) {
            float k = plate.getWidth() / PIC_W;
            float top = oy, bot = oy + PIC_H * scale;
            float[] sx = {0f, BAND0, BAND1, PIC_W};
            float[] dx = {0f, BAND0, BAND1 + extra, PIC_W + extra};
            for (int i = 0; i < 3; i++) {
                srcR.set(Math.round(sx[i] * k), 0, Math.round(sx[i + 1] * k), plate.getHeight());
                rf.set(ox + dx[i] * scale, top, ox + dx[i + 1] * scale, bot);
                cv.drawBitmap(plate, srcR, rf, ip);
            }
        }
        cv.save();
        cv.translate(ox, oy);
        cv.scale(scale, scale);
        for (int i = 0; i < TAB_NAME.length; i++) drawTab(cv, i);
        List<Row> rs = rows();
        for (int i = scroll; i < Math.min(rs.size(), scroll + VIS); i++) drawRow(cv, rs.get(i), i);
        if (rs.size() > VIS) {  // thin scroll bar, only on tabs with more rows than fit
            float top = ROW_C0 - ROW_HALF, bot = ROW_C0 + (VIS - 1) * ROW_PITCH + ROW_HALF, tx = ROW_X1 + extra + 9f;
            float th = (bot - top) * VIS / rs.size(), ty = top + (bot - top) * scroll / rs.size();
            p.setColor(0x33FFFFFF);
            rf.set(tx - 2f, top, tx + 2f, bot);
            cv.drawRoundRect(rf, 2f, 2f, p);
            p.setColor(0xCC6EE064);
            rf.set(tx - 2f, ty, tx + 2f, ty + th);
            cv.drawRoundRect(rf, 2f, 2f, p);
        }
        cv.restore();
        if (progress < 1f && alive()) postInvalidateOnAnimation();
    }

    private float tabTop(int i) { return TAB_Y0 + i * TAB_PITCH; }
    private float rowCy(int i) { return ROW_C0 + (i - scroll) * ROW_PITCH; }

    private void ensureVisible() {
        int n = rows().size();
        if (rowSel < scroll) scroll = rowSel;
        if (rowSel >= scroll + VIS) scroll = rowSel - VIS + 1;
        scroll = Math.max(0, Math.min(Math.max(0, n - VIS), scroll));
    }

    private void tint(Canvas cv, String name, float cx, float cy, float size, int color) {
        Bitmap b = icons.get(name);
        if (b == null) return;
        ip.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        rf.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        cv.drawBitmap(b, null, rf, ip);
        ip.setColorFilter(null);
    }

    private void text(Canvas cv, String s, float x, float y, float size, Typeface tf, Paint.Align al, int color) {
        tp.setTypeface(tf);
        tp.setTextAlign(al);
        tp.setTextSize(size);
        tp.setStyle(Paint.Style.FILL);
        tp.setColor(0x99000000);
        cv.drawText(s, x + 1.5f, y + 2f, tp);
        tp.setColor(color);
        cv.drawText(s, x, y, tp);
    }

    private void drawTab(Canvas cv, int i) {
        boolean selected = i == tab;
        boolean focus = !inList && sbSel == i;
        float t = tabTop(i), b = t + TAB_H;
        cv.save();
        if (focus) cv.scale(1f+.018f*selectionPulse,1f+.018f*selectionPulse,(TAB_X0+TAB_X1)/2,(t+b)/2);
        float rad = 12f;
        rf.set(TAB_X0, t, TAB_X1, b);
        if (selected) {
            p.setShader(new LinearGradient(0, t, 0, b, 0xFF2E8C34, 0xFF145A1E, Shader.TileMode.CLAMP));
            p.setShadowLayer(22f, 0, 0, 0xFF55F060);
        } else {
            p.setShader(new LinearGradient(0, t, 0, b, 0xFF1C2C42, 0xFF0F1A2A, Shader.TileMode.CLAMP));
            if (focus) p.setShadowLayer(16f, 0, 0, 0xFF7DB8FF);
        }
        cv.drawRoundRect(rf, rad, rad, p);
        p.clearShadowLayer();
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setColor(selected ? 0xFF6EE064 : focus ? 0xFFA8D0FF : 0xFF4C6482);
        cv.drawRoundRect(rf, rad, rad, p);
        p.setStrokeWidth(1.5f);
        p.setColor(selected ? 0x66C8FFC0 : 0x44A8C0E0);
        rf.set(TAB_X0 + 6, t + 6, TAB_X1 - 6, b - 6);
        cv.drawRoundRect(rf, rad - 4, rad - 4, p);
        p.setStyle(Paint.Style.FILL);
        // rivets
        p.setColor(selected ? 0xFFBFFFB0 : 0xFF8A9CB4);
        float[] rx = {TAB_X0 + 13, TAB_X1 - 13}, ry = {t + 13, b - 13};
        for (float x : rx) for (float y : ry) cv.drawCircle(x, y, 3.2f, p);
        float cy = (t + b) / 2;
        tint(cv, TAB_ICON[i], 197f, cy, 58f, selected ? 0xFF8CF078 : 0xFFB4BACB);
        text(cv, TAB_NAME[i], 256f, cy + 11f, 31f, Typeface.DEFAULT_BOLD, Paint.Align.LEFT,
                selected ? 0xFFFFFFFF : 0xFFE6E8F6);
        if (selected) {  // pointer towards the list
            path.reset();
            path.moveTo(TAB_X1 + 1, cy - 24);
            path.lineTo(TAB_X1 + 1, cy + 24);
            path.lineTo(TAB_X1 + 27, cy);
            path.close();
            p.setColor(inList ? 0xFF8CF078 : 0xFF6EE064);
            p.setShadowLayer(14f, 0, 0, 0xFF55F060);
            cv.drawPath(path, p);
            p.clearShadowLayer();
        }
        cv.restore();
    }

    private void drawRow(Canvas cv, Row r, int i) {
        float cy = rowCy(i), t = cy - ROW_HALF, b = cy + ROW_HALF;
        boolean focus = inList && rowSel == i;
        cv.save();
        if (focus) cv.translate(5f*selectionPulse,0);
        float rad = 30f;
        rf.set(ROW_X0, t, ROW_X1 + extra, b);
        p.setShader(new LinearGradient(0, t, 0, b, 0xF0123238, 0xF00A2228, Shader.TileMode.CLAMP));
        if (focus) p.setShadowLayer(18f, 0, 0, 0xFF55F060);
        cv.drawRoundRect(rf, rad, rad, p);
        p.clearShadowLayer();
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(focus ? 4f : 3f);
        p.setColor(focus ? 0xFF6EE064 : 0xFF35586A);
        cv.drawRoundRect(rf, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
        tint(cv, r.icon, 537f, cy, 58f, 0xFFC9CFD6);
        text(cv, r.title, 602f, cy - 5f, 29f, Typeface.DEFAULT_BOLD, Paint.Align.LEFT, 0xFFFBEEDC);
        text(cv, r.sub, 602f, cy + 23f, 21f, Typeface.DEFAULT, Paint.Align.LEFT, 0xFF8AA2BC);
        cv.save();
        cv.translate(extra, 0f);
        if (r.toggle) drawToggle(cv, cy, r.on());
        else drawCycle(cv, cy, r.value());
        cv.restore();
        cv.restore();
    }

    private void drawToggle(Canvas cv, float cy, boolean on) {
        float x0 = 1133f, x1 = 1267f, h = 52f;
        rf.set(x0, cy - h / 2, x1, cy + h / 2);
        if (on) {
            p.setShader(new LinearGradient(0, cy - h / 2, 0, cy + h / 2, 0xFF1D6A2C, 0xFF124C1E, Shader.TileMode.CLAMP));
            p.setShadowLayer(14f, 0, 0, 0xAA40FF50);
        } else {
            p.setShader(new LinearGradient(0, cy - h / 2, 0, cy + h / 2, 0xFF2A353D, 0xFF1A2229, Shader.TileMode.CLAMP));
        }
        cv.drawRoundRect(rf, h / 2, h / 2, p);
        p.clearShadowLayer();
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3.5f);
        p.setColor(on ? 0xFF3FD04E : 0xFF56646E);
        cv.drawRoundRect(rf, h / 2, h / 2, p);
        p.setStyle(Paint.Style.FILL);
        text(cv, on ? "ON" : "OFF", on ? 1177f : 1223f, cy + 9f, 26f, Typeface.DEFAULT_BOLD, Paint.Align.CENTER,
                on ? 0xFFC4FA9A : 0xFF8E9AA4);
        float kx = on ? 1237f : 1163f;
        p.setColor(0x66000000);
        cv.drawCircle(kx, cy + 2.5f, 21.5f, p);
        p.setShader(new LinearGradient(0, cy - 22, 0, cy + 22, on ? 0xFFB6F6A0 : 0xFFA4B0BA,
                on ? 0xFF8EDC78 : 0xFF74808A, Shader.TileMode.CLAMP));
        cv.drawCircle(kx, cy, 21.5f, p);
        p.setShader(null);
    }

    private void drawCycle(Canvas cv, float cy, String val) {
        float x0 = 977f, x1 = 1273f, h = 62f, a0 = 1027f, a1 = 1223f;
        rf.set(x0, cy - h / 2, x1, cy + h / 2);
        p.setColor(0xFF152B33);
        cv.drawRoundRect(rf, 22f, 22f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        p.setColor(0xFF3A5C6E);
        cv.drawRoundRect(rf, 22f, 22f, p);
        p.setStyle(Paint.Style.FILL);
        rf.set(a0, cy - 28f, a1, cy + 28f);
        p.setColor(0xFF050C10);
        cv.drawRoundRect(rf, 8f, 8f, p);
        chevron(cv, 1002f, cy, -1);
        chevron(cv, 1248f, cy, 1);
        float size = 24f;
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextSize(size);
        while (tp.measureText(val) > (a1 - a0 - 16f) && size > 14f) { size -= 1f; tp.setTextSize(size); }
        text(cv, val, (a0 + a1) / 2, cy + size * 0.35f, size, Typeface.DEFAULT_BOLD, Paint.Align.CENTER, 0xFFFFFFFF);
    }

    private void chevron(Canvas cv, float cx, float cy, int dir) {
        float s = 12f;
        path.reset();
        path.moveTo(cx - dir * s * 0.55f, cy - s);
        path.lineTo(cx + dir * s * 0.55f, cy);
        path.lineTo(cx - dir * s * 0.55f, cy + s);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(6.5f);
        p.setColor(0xFFD6CCBC);
        cv.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    // ---- input ----

    // hit codes: 0..4 side buttons, 100+i list rows, 200 = Back hint, -99 none
    private int hitAt(float sx, float sy) {
        float x = (sx - ox) / scale, y = (sy - oy) / scale;
        if (x >= TAB_X0 && x <= TAB_X1)
            for (int i = 0; i < TAB_NAME.length; i++)
                if (y >= tabTop(i) && y <= tabTop(i) + TAB_H) return i;
        if (x >= ROW_X0 && x <= ROW_X1 + extra) {
            List<Row> rs = rows();
            for (int i = scroll; i < Math.min(rs.size(), scroll + VIS); i++) if (Math.abs(y - rowCy(i)) <= ROW_HALF) return 100 + i;
        }
        if (x >= 1500 + extra && x <= 1650 + extra && y >= 860 && y <= 925) return 200;
        return -99;
    }

    private void activateTab(int i) {
        sbSel = i;
        tab = i;
        rowSel = 0;
        scroll = 0;
        inList = false;
        invalidate();
    }

    private void tapRow(int i, float sx) {
        Row r = rows().get(i);
        inList = true;
        rowSel = i;
        float x = (sx - ox) / scale - extra;
        int dir = 1;
        if (!r.toggle && x >= 960f && x < 1030f) dir = -1;
        r.change(dir);
        save();
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); moved = false;
                scrollStart = scroll; dragScrolling = false;
                downHit = hitAt(downX, downY);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) moved = true;
                if (rows().size() > VIS && (downHit >= 100 || downHit == -99)) {
                    float dy = e.getY() - downY;
                    if (dragScrolling || (Math.abs(dy) > slop * 1.5f && Math.abs(dy) > Math.abs(e.getX() - downX))) {
                        dragScrolling = true;
                        int steps = Math.round(dy / (ROW_PITCH * scale));
                        scroll = Math.max(0, Math.min(rows().size() - VIS, scrollStart - steps));
                        invalidate();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP: {
                int hit = hitAt(e.getX(), e.getY());
                if (!moved && hit == downHit && hit != -99) {
                    if (hit < 5) activateTab(hit);
                    else if (hit >= 100 && hit < 200) tapRow(hit - 100, e.getX());
                    else if (hit == 200) {
                        if (listener != null) listener.onClose();
                    }
                }
                downHit = -99;
                return true;
            }
            default:
                return true;
        }
    }

    private void moveSel(int dir) {
        if (inList) {
            int n = rows().size();
            rowSel = (rowSel + dir + n) % n;
            ensureVisible();
        } else {
            sbSel = (sbSel + dir + 5) % 5;
            if (sbSel < tabs.size()) { tab = sbSel; rowSel = 0; scroll = 0; }
        }
        invalidate();
    }

    private void left() {
        if (inList) { rows().get(rowSel).change(-1); save(); invalidate(); }
    }

    private void right() {
        if (inList) { rows().get(rowSel).change(1); save(); }
        else if (sbSel < tabs.size()) { inList = true; rowSel = 0; scroll = 0; }
        invalidate();
    }

    private void accept() {
        if (inList) { rows().get(rowSel).change(1); save(); invalidate(); }
        else { inList = true; rowSel = 0; scroll = 0; invalidate(); }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: moveSel(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: moveSel(1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: left(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right(); return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                accept(); return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                back(); return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float v = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(v) < 0.5f) v = e.getAxisValue(MotionEvent.AXIS_Y);
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            if (Math.abs(hx) < 0.5f) hx = e.getAxisValue(MotionEvent.AXIS_X);
            if (Math.abs(v) > 0.6f || Math.abs(hx) > 0.6f) {
                if (!axisHeld) {
                    axisHeld = true;
                    if (Math.abs(v) >= Math.abs(hx)) moveSel(v < 0 ? -1 : 1);
                    else if (hx < 0) left();
                    else right();
                }
            } else {
                axisHeld = false;
            }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }
}
