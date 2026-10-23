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
    private final String[] labels = {"Install game (ISO / RVZ)", "Remove installed game", "Back"};
    private final String[] details = {"Choose a disc to copy into the port's storage", "Remove the installed copy from this app", "Return to the main menu"};
    private boolean installing;
    private long installedBytes, totalBytes;
    void setInstallProgress(long done,long total) {
        installing=true; installedBytes=done; totalBytes=total; invalidate();
    }
    private final Paint p = new Paint(3);
    private final RectF[] buttons = new RectF[3];
    private Bitmap plate;
    private float scale = 1, ox, oy;
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
        for (int i = 0; i < buttons.length; i++) buttons[i] = new RectF(490, 330 + i * 145, 1295, 445 + i * 145);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        scale = Math.min(w / 1672f, h / 941f);
        ox = (w - 1672 * scale) / 2;
        oy = (h - 941 * scale) / 2;
    }

    private void text(Canvas c, String value, float x, float y, float size, int color) {
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
        if (plate != null) c.drawBitmap(plate, null, new RectF(0, 0, 1672, 941), p);
        p.setColor(0xFF175D26);
        c.drawRoundRect(new RectF(148, 207, 432, 299), 12, 12, p);
        text(c, "Game Files", 185, 264, 31, 0xFFE6E8F6);
        text(c, "Current disc", 510, 236, 34, 0xFFFBEEDC);
        String name = current;
        p.setTextSize(28);
        while (name.length() > 1 && p.measureText(name) > 730) name = name.substring(0, name.length() - 1);
        if (!name.equals(current)) name += "…";
        text(c, name, 510, 281, 28, 0xFF9AB3C4);
        if(installing) {
            text(c,"Installing…",525,390,40,0xFFFBEEDC);
            text(c,"Copying your disc into the port's storage",525,445,25,0xFF9AB3C4);
            p.setColor(0xFF10272E); c.drawRoundRect(525,490,1260,535,18,18,p);
            float fraction=totalBytes>0?Math.min(.99f,installedBytes/(float)totalBytes):0;
            p.setColor(0xFF74DB65);
            if(totalBytes>0) c.drawRoundRect(525,490,525+735*fraction,535,18,18,p);
            else { float x=525+(now%1800)/1800f*595; c.drawRoundRect(x,490,x+140,535,18,18,p); }
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
        if (x > 1490 && y > 860) return 2;
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
