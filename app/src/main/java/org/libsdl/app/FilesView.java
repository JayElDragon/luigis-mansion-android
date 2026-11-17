package org.libsdl.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.io.InputStream;

/** File actions in the same illustrated frame as the settings screens. */
public class FilesView extends View {
    public interface Listener { void onAction(int action); }
    private final Listener listener;
    private final String current;
    private final String[] labels = {"Install game (ISO / RVZ)", "Install DLC (Premium Deluxe)", "Back"};
    private final String[] details = {"Choose your USA disc (ISO / RVZ)", "Add Premium Deluxe from its .xdelta or GameBanana .zip", "Return to the main menu"};
    private String dlcStatus;  // shown under the current disc, e.g. "DLC installed: Premium Deluxe 2.2.1"
    private String progressTitle = "Installing…", progressSub = "Copying your disc into the port's storage";

    /** Game Files with the DLC row: after the game is set up the second button is Install DLC (no Remove). */
    public FilesView(Context c, String current, boolean gameReady, String dlcStatus, Listener listener) {
        this(c, current, listener);
        this.dlcStatus = dlcStatus;
        if (!gameReady) details[1] = "Set up your USA game first, then add the DLC";
        else if (dlcStatus != null) details[1] = "Installed ✓  ·  choose a patch again to reinstall";
    }

    void setProgressText(String title, String sub) { progressTitle = title; progressSub = sub; }
    private boolean installing;
    private long installedBytes, totalBytes;
    void setInstallProgress(long done,long total) {
        installing=true; installedBytes=done; totalBytes=total; invalidate();
    }
    private final Paint p = new Paint(3);
    private final RectF[] buttons = new RectF[3];
    private Bitmap plate;
    private float scale = 1, ox, oy;
    // Widescreen like Options: the picture fills the height and a thin band of the frame (x 1134..1146)
    // stretches sideways, so the panel and everything right of the band slide right by "extra".
    private static final float BAND0 = 1134f, BAND1 = 1146f;
    private float extra = 0f;
    private final RectF[] baseButtons = new RectF[3];
    private int selected, pressed = -1;
    private boolean axisHeld;
    private int previousSelection=-1;
    private long selectionStarted;

    public FilesView(Context c, String current, Listener listener) {
        super(c);
        this.current = current;
        this.listener = listener;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        try (InputStream in = c.getAssets().open("files_plate.png")) {
            plate = BitmapFactory.decodeStream(in);
        } catch (java.io.IOException ignored) { }
        for (int i = 0; i < buttons.length; i++) {
            baseButtons[i] = new RectF(490, 330 + i * 145, 1295, 445 + i * 145);
            buttons[i] = new RectF(baseButtons[i]);
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        scale = Math.min(w / 1672f, h / 941f);
        extra = Math.max(0f, w / scale - 1672f);
        ox = extra == 0f ? (w - 1672 * scale) / 2 : 0f;
        oy = (h - 941 * scale) / 2;
        for (int i = 0; i < buttons.length; i++) buttons[i].set(baseButtons[i].left, baseButtons[i].top,
                baseButtons[i].right + extra, baseButtons[i].bottom);
    }

    private void text(Canvas c, String value, float x, float y, float size, int color) {
        value = Lang.t(value);
        p.setShader(null);
        p.setColor(color);
        p.setTypeface(size <= 28 ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        p.setTextSize(size);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x99000000);
        c.drawText(value, x + 1.5f, y + 2f, p);
        p.setColor(color);
        c.drawText(value, x, y, p);
    }

    /** Keep animating whenever the view is on screen. (Does not wait for window focus, which can arrive late or never on some builds/devices.) */
    private boolean alive() {
        return isAttachedToWindow() && isShown() && getWindowVisibility() == View.VISIBLE;
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); invalidate(); }
    @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); if (v == View.VISIBLE) invalidate(); }
    @Override protected void onVisibilityChanged(View changed, int v) { super.onVisibilityChanged(changed, v); if (v == View.VISIBLE) invalidate(); }

    @Override protected void onDraw(Canvas c) {
        long now=android.os.SystemClock.uptimeMillis();
        if(previousSelection!=selected) { previousSelection=selected; selectionStarted=now; }
        float progress=Math.min(1f,(now-selectionStarted)/260f);
        float pulse=(float)Math.sin(progress*Math.PI);
        c.drawColor(0xFF04080C);
        c.save(); c.translate(ox, oy); c.scale(scale, scale);
        p.setColor(0xFFFFFFFF);
        if (plate != null) {
            float k = plate.getWidth() / 1672f;
            float[] sx = {0f, BAND0, BAND1, 1672f};
            float[] dx = {0f, BAND0, BAND1 + extra, 1672f + extra};
            for (int i = 0; i < 3; i++)
                c.drawBitmap(plate, new Rect(Math.round(sx[i] * k), 0, Math.round(sx[i + 1] * k), plate.getHeight()),
                        new RectF(dx[i], 0, dx[i + 1], 941), p);
        }
        p.setColor(0xFF175D26);
        c.drawRoundRect(new RectF(148, 207, 432, 299), 12, 12, p);
        if (Lang.es) {  // Spanish title and button hints painted in the frame's own style
            Bitmap t = Lang.art(getContext(), "es_files_title.png"), h = Lang.art(getContext(), "es_files_hints.png");
            if (t != null) c.drawBitmap(t, null, new RectF(620, 48, 620 + t.getWidth(), 48 + t.getHeight()), p);
            if (h != null) c.drawBitmap(h, null, new RectF(1432 + extra, 874, 1432 + extra + h.getWidth(), 874 + h.getHeight()), p);
        }
        text(c, "Game Files", 185, 264, 31, 0xFFE6E8F6);
        text(c, "Current disc", 510, 236, 34, 0xFFFBEEDC);
        String full = Lang.t(current);
        String name = full;
        p.setTextSize(28);
        while (name.length() > 1 && p.measureText(name) > 730 + extra) name = name.substring(0, name.length() - 1);
        if (!name.equals(full)) name += "…";
        text(c, name, 510, 281, 28, 0xFF9AB3C4);
        if (dlcStatus != null) text(c, dlcStatus, 1000, 236, 26, 0xFF92EC75);
        if(installing) {
            text(c,progressTitle,525,390,40,0xFFFBEEDC);
            text(c,progressSub,525,445,25,0xFF9AB3C4);
            p.setColor(0xFF10272E); c.drawRoundRect(525,490,1260+extra,535,18,18,p);
            float fraction=totalBytes>0?Math.min(.99f,installedBytes/(float)totalBytes):0;
            p.setColor(0xFF74DB65);
            if(totalBytes>0) c.drawRoundRect(525,490,525+(735+extra)*fraction,535,18,18,p);
            else { float x=525+(now%1800)/1800f*(595+extra); c.drawRoundRect(x,490,x+140,535,18,18,p); }
            String amount=String.format(java.util.Locale.US,"%d MB",installedBytes>>20);
            if(totalBytes>0) amount=String.format(java.util.Locale.US,"%d%%   ·   %d / %d MB",(int)(fraction*100),installedBytes>>20,totalBytes>>20);
            text(c,amount,525,595,29,0xFFFBEEDC);
            text(c,"Keep this screen open until installation finishes.",525,655,23,0xFF9AB3C4);
            text(c,"Your original file and saved games are kept.",525,700,23,0xFF9AB3C4);
            c.restore();
            if(totalBytes<=0 && alive()) postInvalidateDelayed(50);
            return;
        }
        for (int i = 0; i < buttons.length; i++) {
            c.save();
            if(i==selected) c.translate(6*pulse,0);
            RectF r = buttons[i];
            p.setColor(i == selected ? 0xFF184D2A : 0xFF10272E);
            c.drawRoundRect(r, 22, 22, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3);
            p.setColor(i == selected ? 0xFF92EC75 : 0xFF3A5C6E);
            c.drawRoundRect(r, 22, 22, p);
            text(c, labels[i], 525, r.top + 46, 29, 0xFFFBEEDC);
            text(c, details[i], 525, r.top + 84, 21, 0xFF8AA2BC);
            c.restore();
        }
        c.restore();
        if(progress<1 && alive()) postInvalidateOnAnimation();
    }

    private int hit(float x, float y) {
        x = (x - ox) / scale; y = (y - oy) / scale;
        for (int i = 0; i < buttons.length; i++) if (buttons[i].contains(x, y)) return i;
        if (x > 1490 + extra && y > 860) return 2;
        return -1;
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if(installing) return true;
        int hit = hit(e.getX(), e.getY());
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            pressed = hit; if (hit >= 0) selected = hit; invalidate();
        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            if (hit >= 0 && pressed == hit) listener.onAction(hit);
            pressed = -1;
        } else if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) pressed = -1;
        return true;
    }
    @Override public boolean onKeyDown(int key, KeyEvent e) {
        if(installing) return true;
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            selected = (selected + (key == KeyEvent.KEYCODE_DPAD_UP ? 2 : 1)) % 3; invalidate(); return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DPAD_CENTER) {
            listener.onAction(selected); return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_B) { listener.onAction(2); return true; }
        return super.onKeyDown(key, e);
    }
    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if(installing) return true;
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0) {
            float y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(y) < 0.5f) y = e.getAxisValue(MotionEvent.AXIS_Y);
            if (Math.abs(y) > 0.6f) {
                if (!axisHeld) { selected = (selected + (y < 0 ? 2 : 1)) % 3; invalidate(); }
                axisHeld = true;
            } else axisHeld = false;
            return true;
        }
        return super.onGenericMotionEvent(e);
    }
}
