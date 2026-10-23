package org.libsdl.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.io.InputStream;
import java.util.Random;

/** Start menu: Play / Options / Game Files / Quit, drawn from scratch. Touch or controller. */
public class MenuView extends View {
    public interface Listener { void onItem(int index); }

    private static final String[] LABELS = {"Play", "Options", "Game Files", "Quit"};

    private final RectF[] rects = new RectF[4];
    private final Shader[] btnShader = new Shader[4];
    private int sel = 0;
    private int downIdx = -1;
    private Listener listener;
    private Bitmap bg;
    private Bitmap logo;
    private Bitmap artwork;
    private TorchFire torchFire;
    // Real widescreen: a stored list of "low-importance" seams lets the painting grow sideways on wide phones
    // without stretching the logo, buttons, Luigi, torches or moon (they just slide as whole pieces).
    private int[] seams;              // seamCount x 941 original-art x positions
    private int seamCount, useN;
    private Bitmap wideArt;
    private float dBoo, dBeam, dT1, dT2, dFly, dLamp;
    private final Shader vacuumGlow = new RadialGradient(0,0,1,
            new int[]{0xA0A5FF91,0x7048FF30,0x003AFF20},new float[]{0,.40f,1},Shader.TileMode.CLAMP);
    private Bitmap ghostBackground;
    private final RectF ghostArea = new RectF();
    private final Paint ghostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ghostMask = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader ghostFeather = new RadialGradient(0,0,1,
            new int[]{0xFFFFFFFF,0xFFFFFFFF,0x00FFFFFF},new float[]{0,.78f,1},Shader.TileMode.CLAMP);
    private final Shader flashlightGlow = new RadialGradient(1200,735,235,0xA0FFF0C8,0x00FFF0C8,Shader.TileMode.CLAMP);
    private final Shader flashlightBeam = new RadialGradient(0,0,1,
            new int[]{0xA0FFF3D2,0x55FFF0C8,0x00FFF0C8},new float[]{0,.35f,1},Shader.TileMode.CLAMP);
    private final Shader flameHalo = new RadialGradient(0,0,1,
            new int[]{0x78BAF6FF,0x3038CBFF,0x0038CBFF},new float[]{0,.45f,1},Shader.TileMode.CLAMP);
    private final long animationStart = android.os.SystemClock.uptimeMillis();
    private final RectF artworkBounds = new RectF();

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();

    private float[] starX, starY, starR;
    private boolean[] lit = new boolean[40];
    private Shader leftShade, vignette, moonGlow, skyShader;
    private boolean axisHeld = false;
    private final RectF focusRing = new RectF();
    private long lastFrame;
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MenuView(Context c) {
        super(c);
        for (int i = 0; i < rects.length; i++) rects[i] = new RectF();
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC));
        loadCustomBackground(c);
    }

    public void setListener(Listener l) { listener = l; }

    private void loadCustomBackground(Context c) {
        try (InputStream in = c.getAssets().open("menu_ghost_background.png")) {
            ghostBackground = BitmapFactory.decodeStream(in);
        } catch (java.io.IOException ignored) { }
        ghostMask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        try (InputStream in = c.getAssets().open("menu_art.png")) {
            artwork = BitmapFactory.decodeStream(in);
        } catch (java.io.IOException ignored) { }
        try (InputStream in = c.getAssets().open("menu_bg.jpg")) {
            bg = BitmapFactory.decodeStream(in);
        } catch (Throwable ignored) {
        }
        if(artwork!=null) torchFire=new TorchFire(artwork);
        try (InputStream in = c.getAssets().open("menu_seams.bin")) {
            byte[] b = in.readAllBytes();
            int n = b.length / 2;
            if (n >= 941) {
                seams = new int[n];
                for (int i = 0; i < n; i++) seams[i] = (b[2 * i] & 255) | ((b[2 * i + 1] & 255) << 8);
                seamCount = n / 941;
            }
        } catch (Throwable ignored) { seams = null; seamCount = 0; }
        try (InputStream in = c.getAssets().open("menu_logo.png")) {
            logo = BitmapFactory.decodeStream(in);
        } catch (Throwable ignored) {
        }
        try {
            File ld = c.getExternalFilesDir(null);
            File lf = ld == null ? null : new File(ld, "menu_logo.png");
            if (lf != null && lf.isFile()) {
                Bitmap lb = BitmapFactory.decodeFile(lf.getPath());
                if (lb != null) logo = lb;
            }
        } catch (Throwable ignored) {
        }
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            String[] names = {"menu_bg.png", "menu_bg.jpg", "menu_bg.jpeg", "menu_bg.webp"};
            for (String n : names) {
                File f = new File(dir, n);
                if (!f.isFile()) continue;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                o.inSampleSize = Math.max(1, o.outWidth / 2400);
                o.inJustDecodeBounds = false;
                bg = BitmapFactory.decodeFile(f.getPath(), o);
                if (bg != null) return;
            }
        } catch (Throwable ignored) {
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float u = h;
        Random rnd = new Random(7);
        starX = new float[90]; starY = new float[90]; starR = new float[90];
        for (int i = 0; i < starX.length; i++) {
            starX[i] = rnd.nextFloat() * w;
            starY[i] = rnd.nextFloat() * 0.6f * u;
            starR[i] = (0.0015f + rnd.nextFloat() * 0.0035f) * u;
        }
        for (int i = 0; i < lit.length; i++) lit[i] = rnd.nextFloat() < 0.78f;
        skyShader = new LinearGradient(0, 0, 0, h, 0xFF060C24, 0xFF18386A, Shader.TileMode.CLAMP);
        leftShade = new LinearGradient(0, 0, w * 0.6f, 0, 0xB8000000, 0x00000000, Shader.TileMode.CLAMP);
        vignette = new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f, 0x00000000, 0xAA000000,
                Shader.TileMode.CLAMP);
        moonGlow = new RadialGradient(w * 0.86f, 0.21f * u, 0.38f * u, 0x66BFD8FF, 0x00BFD8FF,
                Shader.TileMode.CLAMP);
        float y = 0.455f * u;
        for (int i = 0; i < 4; i++) {
            float bh = (i == 0 ? 0.118f : 0.108f) * u;
            rects[i].set(0.08f * u, y, 0.78f * u, y + bh);
            y += bh + 0.013f * u;
            int top = i == 0 ? 0xFF6CDB55 : 0xFF4A8BE6;
            int bot = i == 0 ? 0xFF2A9227 : 0xFF1B4AA0;
            btnShader[i] = new LinearGradient(0, rects[i].top, 0, rects[i].bottom, top, bot, Shader.TileMode.CLAMP);
        }
        if (artwork != null) {
            float s = Math.min(w / 1672f, h / 941f);
            useN = 0; wideArt = null;
            if (seams != null && h / 941f < w / 1672f) {          // phone wider than 16:9
                float sh = h / 941f;
                int n = Math.min(seamCount, (int) Math.floor(w / sh - 1672f));
                if (n > 0) {
                    useN = n;
                    wideArt = buildWide(n);
                    if (wideArt != null) s = sh; else useN = 0;
                }
            }
            float aw = (1672f + useN) * s;
            float x = (w - aw) / 2f, top = (h - 941f * s) / 2f;
            artworkBounds.set(x, top, x + aw, top + 941f * s);
            dBoo = shiftAt(100f, 250f); dBeam = shiftAt(1245f, 711f); dT1 = shiftAt(1067f, 620f);
            dT2 = shiftAt(1436f, 639f); dFly = shiftAt(1150f, 500f); dLamp = shiftAt(766f, 794f);
            buildSideFill(w, h);
            float[] ys = {430f, 553f, 674f, 790f};
            float[] bottoms = {542f, 657f, 776f, 892f};
            for (int i = 0; i < 4; i++) {
                float dx = shiftAt(390f, (ys[i] + bottoms[i]) / 2f);
                rects[i].set(x + (76f + dx) * s, top + ys[i] * s, x + (710f + dx) * s, top + bottoms[i] * s);
            }
        }
    }

    // ---- drawing ----

    /** Keep animating whenever the view is on screen. (Does not wait for window focus, which can arrive late or never on some builds/devices.) */
    private boolean framePending;
    private final Runnable frameTick = () -> { framePending = false; invalidate(); };
    private void scheduleFrame(long delayMs) {
        if (framePending) return;
        framePending = true;
        postDelayed(frameTick, delayMs);
    }
    @Override protected void onDetachedFromWindow() { removeCallbacks(frameTick); framePending = false; super.onDetachedFromWindow(); }

    private boolean alive() {
        return isAttachedToWindow() && isShown() && getWindowVisibility() == View.VISIBLE;
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); invalidate(); }
    @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); if (v == View.VISIBLE) invalidate(); }
    @Override protected void onVisibilityChanged(View changed, int v) { super.onVisibilityChanged(changed, v); if (v == View.VISIBLE) invalidate(); }

    @Override protected void onDraw(Canvas cv) {
        if (artwork != null) {
            cv.drawColor(0xFF02080E);
            p.setShader(null);
            p.setFilterBitmap(true);
            cv.drawBitmap(wideArt != null ? wideArt : artwork, null, artworkBounds, p);
            drawSideFill(cv);
            long now = android.os.SystemClock.uptimeMillis();
            float dt = Math.min(100, now - lastFrame); lastFrame = now;
            float f = 1f - (float)Math.exp(-dt / 65f);
            RectF target = rects[sel];
            if (focusRing.isEmpty()) focusRing.set(target);
            focusRing.set(focusRing.left + (target.left-focusRing.left)*f,
                    focusRing.top + (target.top-focusRing.top)*f,
                    focusRing.right + (target.right-focusRing.right)*f,
                    focusRing.bottom + (target.bottom-focusRing.bottom)*f);
            float pulse = .5f + .5f*(float)Math.sin(now / 340.0);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(getHeight() * .014f);
            p.setColor(0x7052FF63);
            cv.drawRoundRect(focusRing, 18, 18, p);
            p.setStrokeWidth(getHeight() * .0045f);
            p.setColor(android.graphics.Color.argb(210+(int)(45*pulse), 220, 255, 190));
            cv.drawRoundRect(focusRing, 18, 18, p);
            p.setStyle(Paint.Style.FILL);
            drawAtmosphere(cv, now);
            drawPortCredit(cv);
            p.setColor(0xFFFFFFFF);
            if (alive()) scheduleFrame(
                    getContext().getSharedPreferences("lm",Context.MODE_PRIVATE).getInt("g_fps",60)<=30?66:33);
            return;
        }
        int w = getWidth(), h = getHeight();
        float u = h;
        if (bg != null) {
            float s = Math.max((float) w / bg.getWidth(), (float) h / bg.getHeight());
            int sw = (int) (w / s), sh = (int) (h / s);
            int sx = (bg.getWidth() - sw) / 2, sy = (bg.getHeight() - sh) / 2;
            cv.drawBitmap(bg, new Rect(sx, sy, sx + sw, sy + sh), new Rect(0, 0, w, h), null);
        } else {
            drawScene(cv, w, h);
        }
        int shade = bg != null ? 95 : 255;
        p.setShader(leftShade);
        p.setAlpha(shade);
        cv.drawRect(0, 0, w, h, p);
        p.setShader(vignette);
        p.setAlpha(bg != null ? 120 : 255);
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setAlpha(255);

        drawTitle(cv, u);
        for (int i = 0; i < 4; i++) drawButton(cv, i, u);
        drawHints(cv, w, u);
        drawCredit(cv, w, u);
    }

    /** How far (in art pixels) a point of the original painting slides right once the first useN seams are inserted. */
    private float shiftAt(float x, float y) {
        if (seams == null || useN == 0) return 0f;
        int yy = Math.max(0, Math.min(940, Math.round(y)));
        int c = 0;
        for (int k = 0; k < useN; k++) if (seams[k * 941 + yy] < x) c++;
        return c;
    }

    private Bitmap buildWide(int n) {
        try {
            int aw = artwork.getWidth(), ah = artwork.getHeight();
            if (aw != 1672 || ah != 941) return null;
            int[] src = new int[aw * ah];
            artwork.getPixels(src, 0, aw, 0, 0, aw, ah);
            int nw = aw + n;
            int[] out = new int[nw * ah];
            byte[] cnt = new byte[aw];
            for (int y = 0; y < ah; y++) {
                java.util.Arrays.fill(cnt, (byte) 0);
                for (int k = 0; k < n; k++) cnt[seams[k * 941 + y]]++;
                int xo = y * nw, row = y * aw;
                for (int x = 0; x < aw; x++) {
                    int a = src[row + x];
                    out[xo++] = a;
                    if (cnt[x] != 0) {
                        int b = src[row + Math.min(aw - 1, x + 1)];
                        out[xo++] = ((((a >>> 24) + (b >>> 24)) >> 1) << 24)
                                | (((((a >> 16) & 255) + ((b >> 16) & 255)) >> 1) << 16)
                                | (((((a >> 8) & 255) + ((b >> 8) & 255)) >> 1) << 8)
                                | ((((a & 255) + (b & 255)) >> 1));
                    }
                }
            }
            return Bitmap.createBitmap(out, nw, ah, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) { return null; }
    }

    // ---- widescreen fill (only used for any leftover space on extremely wide screens): the painted art keeps its exact shape and position; on phones wider than
    // 16:9 the empty side strips are filled with a soft, darkened continuation of the art's own edge colours.

    private Bitmap fillLeft, fillRight;
    private int fillFeather;

    private void buildSideFill(int w, int h) {
        fillLeft = null; fillRight = null;
        if (artwork == null || artworkBounds.top > 1f) return;
        fillFeather = Math.max(2, Math.round(48f * artworkBounds.height() / 941f));
        try {
            if (artworkBounds.left >= 1f) fillLeft = makeFill(false, (int) Math.ceil(artworkBounds.left), h);
            if (w - artworkBounds.right >= 1f) fillRight = makeFill(true, (int) Math.ceil(w - artworkBounds.right), h);
        } catch (Throwable ignored) { fillLeft = null; fillRight = null; }
    }

    private Bitmap makeFill(boolean right, int bar, int h) {
        Bitmap srcBmp = wideArt != null ? wideArt : artwork;
        int aw = srcBmp.getWidth(), ah = srcBmp.getHeight();
        int sample = Math.max(4, aw / 120);
        int[] px = new int[sample * ah];
        srcBmp.getPixels(px, 0, sample, right ? aw - sample : 0, 0, sample, ah);
        // average the edge strip into one colour per row, then smooth it vertically
        float[] r = new float[ah], g = new float[ah], b = new float[ah];
        for (int y = 0; y < ah; y++) {
            float sr = 0, sg = 0, sb = 0;
            for (int x = 0; x < sample; x++) {
                int c = px[y * sample + x];
                sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            r[y] = sr / sample; g[y] = sg / sample; b[y] = sb / sample;
        }
        int rad = Math.max(2, ah / 40);
        int[] col = new int[ah];
        for (int y = 0; y < ah; y++) {
            float sr = 0, sg = 0, sb = 0; int n = 0;
            for (int k = -rad; k <= rad; k++) {
                int yy = Math.min(ah - 1, Math.max(0, y + k));
                sr += r[yy]; sg += g[yy]; sb += b[yy]; n++;
            }
            col[y] = 0xFF000000 | (Math.round(sr / n) << 16) | (Math.round(sg / n) << 8) | Math.round(sb / n);
        }
        Bitmap column = Bitmap.createBitmap(col, 1, ah, Bitmap.Config.ARGB_8888);
        int tw = bar + fillFeather;
        Bitmap out = Bitmap.createBitmap(tw, (int) Math.ceil(artworkBounds.height()), Bitmap.Config.ARGB_8888);
        Canvas oc = new Canvas(out);
        Paint fp = new Paint(Paint.FILTER_BITMAP_FLAG);
        oc.drawBitmap(column, null, new Rect(0, 0, tw, out.getHeight()), fp);
        // gently darken toward the screen edge, like the vignette already in the art
        Paint dp = new Paint();
        float edgeX = right ? tw : 0f, inX = right ? fillFeather : bar;
        dp.setShader(new LinearGradient(edgeX, 0, inX, 0, 0x99000000, 0x00000000, Shader.TileMode.CLAMP));
        oc.drawRect(0, 0, tw, out.getHeight(), dp);
        // feather the edge that overlaps the art so there is no visible seam
        Paint mp = new Paint();
        mp.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        float solid = right ? fillFeather : bar;       // fully opaque from the screen edge up to here
        float clear = right ? 0f : tw;                 // fully transparent at the far end inside the art
        mp.setShader(new LinearGradient(solid, 0, clear, 0, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        oc.drawRect(0, 0, tw, out.getHeight(), mp);
        return out;
    }

    private void drawSideFill(Canvas cv) {
        if (fillLeft != null) cv.drawBitmap(fillLeft, 0f, artworkBounds.top, null);
        if (fillRight != null) cv.drawBitmap(fillRight, artworkBounds.right - fillFeather, artworkBounds.top, null);
    }

    private void drawAtmosphere(Canvas cv, long now) {
        float t = (now-animationStart) / 1000f;
        cv.save();
        cv.translate(artworkBounds.left, artworkBounds.top);
        float s = artworkBounds.height()/941f;
        cv.scale(s,s);
        // Sequential fades: first Boo at 1–4 seconds, second at 6–9 seconds.
        // Their windows never overlap; at least one Boo always stays fully visible.
        cv.save(); cv.translate(dBoo,0);
        if(ghostBackground!=null) for(int i=0;i<2;i++) {
            float phase=t%12f-(i==0?1f:6f);
            if(phase<0 || phase>3) continue;
            float fade=phase<1?phase:phase>2?3-phase:1;
            fade=fade*fade*(3-2*fade);
            ghostArea.set(0,i==0?70:270,214,i==0?270:430);
            int layer=cv.saveLayer(ghostArea,null);
            cv.clipRect(ghostArea);
            ghostPaint.setAlpha(Math.round(fade*255));
            cv.drawBitmap(ghostBackground,null,new RectF(0,0,1672,941),ghostPaint);
            cv.save();
            cv.translate(ghostArea.centerX(),ghostArea.centerY());
            cv.scale(ghostArea.width()/2,ghostArea.height()/2);
            ghostMask.setShader(ghostFeather); cv.drawRect(-1,-1,1,1,ghostMask);
            cv.restore();
            cv.restoreToCount(layer);
        }
        cv.restore();
        cv.save(); cv.translate(dBeam,0);
        // Preserve the complete painted flashlight and beam. Only add a feathered
        // exposure pulse along its existing direction: no moving spot or masking.
        float breath=.5f+.5f*(float)Math.sin(t*1.35f);
        glow.setBlendMode(android.graphics.BlendMode.SCREEN);
        glow.setShader(flashlightBeam);
        glow.setAlpha(100+(int)(80*breath));
        cv.save();
        float lightTravel=(float)Math.sin(t*.8f);
        cv.translate(1245+18*lightTravel,711-12*lightTravel);
        cv.rotate(-34f+3f*(float)Math.sin(t*.65f));
        cv.scale(238,76);
        cv.drawCircle(0,0,1,glow);
        cv.restore();
        glow.setShader(null);
        glow.setBlendMode(android.graphics.BlendMode.SRC_OVER);
        glow.setAlpha(255);
        // Drifting illuminated dust gives the intact beam motion without changing its shape.
        cv.save(); cv.translate(1093,805); cv.rotate(-34f);
        for(int i=0;i<16;i++) {
            float age=(t*(.075f+(i%4)*.008f)+i*.618034f)%1f;
            float dx=35+age*265;
            float dy=(float)Math.sin(i*2.37+t*.35f)*(9+age*24);
            float visible=(float)Math.sin(age*Math.PI);
            float twinkle=.55f+.45f*(float)Math.sin(t*2.1f+i*1.7f);
            int alpha=(int)(175*visible*twinkle);
            glow.setColor(android.graphics.Color.argb(alpha/5,255,241,197));
            cv.drawCircle(dx,dy,4.3f,glow);
            glow.setColor(android.graphics.Color.argb(alpha,255,248,221));
            cv.drawCircle(dx,dy,1f+(i%3)*.3f,glow);
        }
        cv.restore();
        cv.restore();   // end of flashlight/dust shift
        if(torchFire!=null) torchFire.draw(cv,t,dT1,dT2);
        float[] torches = {1067,601,1436,616};
        for(int j=0;j<torches.length;j+=2) {
            float x=torches[j], y=torches[j+1];
            float flicker=.75f+.13f*(float)Math.sin(t*7.3+j)+.12f*(float)Math.sin(t*11.7+j*2);
            cv.save(); cv.translate(j==0?dT1:dT2,0);
            cv.save(); cv.translate(x,y); cv.scale(32,45);
            glow.setShader(flameHalo); glow.setAlpha((int)(220*flicker));
            cv.drawCircle(0,0,1,glow); cv.restore();
            glow.setShader(null); glow.setAlpha(255);
            for(int i=0;i<3;i++) {
                float age=(t*.7f+i/3f+j*.19f)%1f;
                glow.setColor(android.graphics.Color.argb((int)(100*(1-age)),135,235,255));
                cv.drawCircle(x+5*(float)Math.sin(age*5+i),y-12-age*32,1.5f*(1-age),glow);
            }
            cv.restore();
        }
        // spread the drifting particles over the widened scene
        final float wf=(840f+useN)/840f, fl=dLamp;


        for(int i=0;i<12;i++) {
            float x=fl+750f+(60+(i*79)%760+8*(float)Math.sin(t+i))*wf;
            float y=850-((t*14+i*61)%530);
            glow.setColor(android.graphics.Color.argb(30+(int)(25*(1+Math.sin(t*2+i))),145,225,255));
            cv.drawCircle(x,y,1.7f,glow);
        }
        // Sparse fireflies, each with its own flight path and dark interval.
        for(int i=0;i<48;i++) {
            float phase=t*(.65f+(i%3)*.13f)+i*2.17f;
            float light=Math.max(0f,(float)Math.sin(phase));
            light=light*light*light;
            if(light<.015f) continue;
            float x=i<30?800+(i*113)%790:750+((i-30)*47)%475;
            float y=i<30?150+(i*97)%650:300+((i-30)*71)%330;
            x=fl+750f+(x-750f)*wf;
            x+=23*(float)Math.sin(t*.42f+i*1.8f);
            y+=18*(float)Math.sin(t*.53f+i*2.6f);
            glow.setColor(android.graphics.Color.argb((int)(14*light),205,255,125));
            cv.drawCircle(x,y,9,glow);
            glow.setColor(android.graphics.Color.argb((int)(45*light),221,255,155));
            cv.drawCircle(x,y,4.5f,glow);
            glow.setColor(android.graphics.Color.argb((int)(235*light),246,255,189));
            cv.drawCircle(x,y,1.65f,glow);
        }
        // Poltergust status lamp: a living green pulse centered on the painted lens.
        float energy=.5f+.5f*(float)Math.sin(t*2.1f);
        cv.save(); cv.translate(766+dLamp,794); cv.rotate(-28);
        cv.scale(53+7*energy,59+7*energy);
        glow.setShader(vacuumGlow); glow.setAlpha(105+(int)(115*energy));
        cv.drawCircle(0,0,1,glow);
        cv.restore(); glow.setShader(null); glow.setAlpha(255);
        cv.restore();
    }

    private void drawPortCredit(Canvas cv) {
        float unit=getHeight()/941f;
        tp.setTypeface(Typeface.DEFAULT);
        tp.setTextAlign(Paint.Align.RIGHT);
        tp.setTextSize(17f*unit);
        tp.setColor(0xFFE7F4F6);
        tp.setShadowLayer(4f*unit,0,unit,0xFF000000);
        cv.drawText("Unofficial port by MsBunnana & JayElDragon",
                artworkBounds.right-20f*unit,artworkBounds.bottom-16f*unit,tp);
        tp.clearShadowLayer();
        tp.setTextAlign(Paint.Align.CENTER);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if(focused) { lastFrame=0; invalidate(); }
    }

    private void drawCredit(Canvas cv, int w, float u) {
        tp.setTypeface(Typeface.DEFAULT);
        tp.setLetterSpacing(0.02f);
        tp.setStyle(Paint.Style.FILL);
        tp.setShader(null);
        tp.setTextAlign(Paint.Align.RIGHT);
        tp.setTextSize(0.033f * u);
        tp.setColor(0xE6FFFFFF);
        tp.setShadowLayer(0.008f * u, 0, 0.003f * u, 0xCC000000);
        cv.drawText("Unofficial port by MsBunnana & JayElDragon", w - 0.045f * u, 0.965f * u, tp);
        tp.setShadowLayer(0, 0, 0, 0);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setLetterSpacing(0f);
    }

    private void rect(Canvas cv, float l, float t, float r, float b, int color) {
        p.setColor(color);
        cv.drawRect(l, t, r, b, p);
    }

    private void poly(Canvas cv, int color, float... pts) {
        path.reset();
        path.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) path.lineTo(pts[i], pts[i + 1]);
        path.close();
        p.setColor(color);
        cv.drawPath(path, p);
    }

    private void drawScene(Canvas cv, int w, int h) {
        float u = h;
        p.setShader(skyShader);
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // stars
        p.setColor(0xCCFFFFFF);
        for (int i = 0; i < starX.length; i++) cv.drawCircle(starX[i], starY[i], starR[i], p);
        // moon
        p.setShader(moonGlow);
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0xFFE9F1FF);
        cv.drawCircle(w * 0.86f, 0.21f * u, 0.115f * u, p);
        p.setColor(0x22708098);
        cv.drawCircle(w * 0.86f - 0.04f * u, 0.18f * u, 0.022f * u, p);
        cv.drawCircle(w * 0.86f + 0.03f * u, 0.24f * u, 0.03f * u, p);
        cv.drawCircle(w * 0.86f + 0.045f * u, 0.16f * u, 0.015f * u, p);

        // ground
        rect(cv, 0, 0.9f * u, w, u, 0xFF050B10);

        // generic haunted mansion silhouette
        float cx = w * 0.68f;
        int body = 0xFF0D1A24, wing = 0xFF11202B, roof = 0xFF09121A;
        rect(cv, cx - 0.56f * u, 0.58f * u, cx - 0.28f * u, 0.92f * u, wing);
        poly(cv, roof, cx - 0.60f * u, 0.58f * u, cx - 0.42f * u, 0.46f * u, cx - 0.24f * u, 0.58f * u);
        rect(cv, cx + 0.28f * u, 0.58f * u, cx + 0.56f * u, 0.92f * u, wing);
        poly(cv, roof, cx + 0.24f * u, 0.58f * u, cx + 0.42f * u, 0.46f * u, cx + 0.60f * u, 0.58f * u);
        rect(cv, cx - 0.30f * u, 0.40f * u, cx + 0.30f * u, 0.92f * u, body);
        poly(cv, roof, cx - 0.34f * u, 0.40f * u, cx - 0.22f * u, 0.27f * u, cx + 0.22f * u, 0.27f * u,
                cx + 0.34f * u, 0.40f * u);
        rect(cv, cx - 0.10f * u, 0.12f * u, cx + 0.10f * u, 0.28f * u, body);
        poly(cv, roof, cx - 0.13f * u, 0.12f * u, cx, 0.03f * u, cx + 0.13f * u, 0.12f * u);
        rect(cv, cx - 0.22f * u, 0.30f * u, cx - 0.19f * u, 0.38f * u, roof);
        rect(cv, cx + 0.19f * u, 0.30f * u, cx + 0.22f * u, 0.38f * u, roof);

        // windows
        int n = 0;
        float[] rows = {0.46f, 0.60f, 0.73f};
        for (int rI = 0; rI < rows.length; rI++) {
            for (int cI = -2; cI <= 2; cI++) {
                if (rI == 2 && cI == 0) continue;  // door
                window(cv, cx + cI * 0.12f * u, rows[rI] * u, 0.05f * u, 0.075f * u, lit[n++ % lit.length]);
            }
        }
        window(cv, cx, 0.19f * u, 0.045f * u, 0.07f * u, true);
        for (int s = -1; s <= 1; s += 2) {
            window(cv, cx + s * 0.35f * u, 0.69f * u, 0.045f * u, 0.07f * u, lit[n++ % lit.length]);
            window(cv, cx + s * 0.48f * u, 0.78f * u, 0.045f * u, 0.07f * u, lit[n++ % lit.length]);
        }
        // door
        p.setColor(0x33FFB347);
        cv.drawCircle(cx, 0.86f * u, 0.12f * u, p);
        rf.set(cx - 0.05f * u, 0.78f * u, cx + 0.05f * u, 0.86f * u);
        p.setColor(0xFFFFC857);
        cv.drawArc(rf, 180, 180, true, p);
        rect(cv, cx - 0.05f * u, 0.82f * u, cx + 0.05f * u, 0.92f * u, 0xFFFFC857);
        // steps
        poly(cv, 0xFF16262F, cx - 0.14f * u, 0.92f * u, cx + 0.14f * u, 0.92f * u, cx + 0.22f * u, u, cx - 0.22f * u, u);

        // fence
        p.setColor(0xFF04080C);
        for (float fx = cx - 0.75f * u; fx < w; fx += 0.026f * u) {
            cv.drawRect(fx, 0.85f * u, fx + 0.006f * u, 0.97f * u, p);
            poly(cv, 0xFF04080C, fx - 0.003f * u, 0.85f * u, fx + 0.003f * u, 0.82f * u, fx + 0.009f * u, 0.85f * u);
        }
        rect(cv, cx - 0.75f * u, 0.89f * u, w, 0.897f * u, 0xFF04080C);

        // bare trees on the left
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFF03070B);
        p.setStrokeWidth(0.045f * u);
        cv.drawLine(0.10f * u, u, 0.14f * u, 0.40f * u, p);
        p.setStrokeWidth(0.02f * u);
        cv.drawLine(0.14f * u, 0.40f * u, 0.36f * u, 0.22f * u, p);
        cv.drawLine(0.14f * u, 0.40f * u, 0.02f * u, 0.18f * u, p);
        cv.drawLine(0.13f * u, 0.55f * u, 0.30f * u, 0.50f * u, p);
        p.setStrokeWidth(0.008f * u);
        cv.drawLine(0.36f * u, 0.22f * u, 0.46f * u, 0.18f * u, p);
        cv.drawLine(0.36f * u, 0.22f * u, 0.40f * u, 0.10f * u, p);
        cv.drawLine(0.02f * u, 0.18f * u, 0.10f * u, 0.06f * u, p);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);

        // mist
        p.setColor(0x16CFE6FF);
        rf.set(w * 0.25f, 0.88f * u, w * 1.05f, 1.02f * u);
        cv.drawOval(rf, p);
        rf.set(w * 0.40f, 0.82f * u, w * 1.10f, 0.96f * u);
        cv.drawOval(rf, p);
    }

    private void window(Canvas cv, float cx, float cy, float ww, float wh, boolean on) {
        if (on) {
            p.setColor(0x22FFB347);
            cv.drawCircle(cx, cy, wh * 1.3f, p);
        }
        p.setColor(on ? 0xFFFFC654 : 0xFF14252F);
        rf.set(cx - ww / 2, cy - wh / 2, cx + ww / 2, cy + wh / 2);
        cv.drawRoundRect(rf, ww * 0.35f, ww * 0.35f, p);
    }

    private void drawTitle(Canvas cv, float u) {
        if (logo != null) {
            float maxW = 0.74f * u, maxH = 0.35f * u;
            float sc = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
            float lw = logo.getWidth() * sc, lh = logo.getHeight() * sc;
            float cx = 0.43f * u, top = 0.09f * u;
            rf.set(cx - lw / 2, top, cx + lw / 2, top + lh);
            Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            cv.drawBitmap(logo, null, rf, lp);
            return;
        }
        tp.setTypeface(Typeface.DEFAULT);
        tp.setLetterSpacing(0.16f);
        tp.setStyle(Paint.Style.FILL);
        tp.setShader(null);
        tp.setShadowLayer(0, 0, 0, 0);
        tp.setColor(0xFFE8EEF8);
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTextSize(0.032f * u);
        cv.drawText("LUIGI'S MANSION RECOMPILED  /  ANDROID", 0.08f * u, 0.07f * u, tp);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setLetterSpacing(0f);
        tp.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC));
        float mid = 0.43f * u;
        bigText(cv, "Luigi's", mid, 0.225f * u, 0.17f * u);
        bigText(cv, "Mansion", mid, 0.355f * u, 0.17f * u);
        bigText(cv, "RECOMPILED", mid, 0.435f * u, 0.078f * u);
    }

    private void bigText(Canvas cv, String s, float x, float y, float size) {
        tp.setTextSize(size);
        tp.setShader(null);
        tp.setStyle(Paint.Style.STROKE);
        tp.setStrokeJoin(Paint.Join.ROUND);
        tp.setStrokeWidth(size * 0.13f);
        tp.setColor(0xFF0B2F78);
        tp.setShadowLayer(size * 0.22f, 0, 0, 0xFF3DA0FF);
        cv.drawText(s, x, y, tp);
        tp.setShadowLayer(0, 0, 0, 0);
        tp.setStyle(Paint.Style.FILL);
        tp.setShader(new LinearGradient(0, y - size * 0.8f, 0, y, 0xFFB4DCFF, 0xFF3F88F2, Shader.TileMode.CLAMP));
        cv.drawText(s, x, y, tp);
        tp.setShader(null);
    }

    private void drawButton(Canvas cv, int i, float u) {
        RectF r = rects[i];
        boolean on = sel == i;
        float rad = r.height() * 0.2f;
        boolean play = i == 0;
        // shadow
        p.setShader(null);
        p.setColor(0x66000000);
        rf.set(r.left, r.top + 0.008f * u, r.right, r.bottom + 0.008f * u);
        cv.drawRoundRect(rf, rad, rad, p);
        // body
        p.setShader(btnShader[i]);
        if (on) p.setShadowLayer(0.03f * u, 0, 0, play ? 0xFF7CFF6A : 0xFF7DB8FF);
        cv.drawRoundRect(r, rad, rad, p);
        p.clearShadowLayer();
        p.setShader(null);
        // gloss on the upper half
        p.setColor(0x22FFFFFF);
        rf.set(r.left + 0.01f * u, r.top + 0.008f * u, r.right - 0.01f * u, r.centerY());
        cv.drawRoundRect(rf, rad * 0.8f, rad * 0.8f, p);
        // border
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(on ? 0.009f * u : 0.006f * u);
        p.setColor(on ? (play ? 0xFFB8FFA8 : 0xFFBFDDFF) : (play ? 0xFF1E6E1C : 0xFF123674));
        cv.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
        // label
        float size = (play ? 0.078f : 0.07f) * u;
        tp.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC));
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(size);
        tp.setShader(null);
        tp.setStyle(Paint.Style.STROKE);
        tp.setStrokeWidth(size * 0.16f);
        tp.setStrokeJoin(Paint.Join.ROUND);
        tp.setColor(play ? 0xFF14500F : 0xFF0A2C70);
        float ty = r.centerY() + size * 0.34f;
        cv.drawText(LABELS[i], r.centerX(), ty, tp);
        tp.setStyle(Paint.Style.FILL);
        tp.setColor(0xFFFFFFFF);
        cv.drawText(LABELS[i], r.centerX(), ty, tp);
    }

    private void drawHints(Canvas cv, int w, float u) {
        float y = 0.071f * u, rad = 0.032f * u;
        hint(cv, w - 0.363f * u, y, rad, 0xFF2F9A3E, "A", w - 0.318f * u, "Select", u);
        hint(cv, w - 0.170f * u, y, rad, 0xFFC4262C, "B", w - 0.126f * u, "Back", u);
    }

    private void hint(Canvas cv, float cx, float cy, float rad, int color, String letter, float tx, String label,
                      float u) {
        p.setColor(0x66000000);
        cv.drawCircle(cx, cy + 0.004f * u, rad, p);
        p.setColor(color);
        cv.drawCircle(cx, cy, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.004f * u);
        p.setColor(0xFFE8F5E8);
        cv.drawCircle(cx, cy, rad, p);
        p.setStyle(Paint.Style.FILL);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setStyle(Paint.Style.FILL);
        tp.setShader(null);
        tp.setLetterSpacing(0f);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setColor(0xFFFFFFFF);
        tp.setTextSize(rad * 1.2f);
        cv.drawText(letter, cx, cy + rad * 0.42f, tp);
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTextSize(0.036f * u);
        cv.drawText(label, tx, cy + 0.013f * u, tp);
        tp.setTextAlign(Paint.Align.CENTER);
    }

    // ---- input ----

    private void activate(int i) {
        if (listener != null) listener.onItem(i);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
            int idx = hit(e.getX(), e.getY());
            if (act == MotionEvent.ACTION_DOWN) downIdx = idx;
            if (idx >= 0) { sel = idx; invalidate(); }
            return true;
        }
        if (act == MotionEvent.ACTION_UP) {
            int idx = hit(e.getX(), e.getY());
            if (idx >= 0 && idx == downIdx) activate(idx);
            downIdx = -1;
            return true;
        }
        return true;
    }

    private int hit(float x, float y) {
        for (int i = 0; i < rects.length; i++) if (rects[i].contains(x, y)) return i;
        return -1;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                sel = (sel + 3) % 4; invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                sel = (sel + 1) % 4; invalidate(); return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                activate(sel); return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float v = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(v) < 0.5f) v = e.getAxisValue(MotionEvent.AXIS_Y);
            if (Math.abs(v) > 0.6f) {
                if (!axisHeld) {
                    axisHeld = true;
                    sel = v < 0 ? (sel + 3) % 4 : (sel + 1) % 4;
                    invalidate();
                }
            } else {
                axisHeld = false;
            }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }
}





