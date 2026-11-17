package org.libsdl.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * HD texture packs. Packs live in  <app files>/texturepacks/<pack name>/  and use the Dolphin naming style
 * (tex1_WxH_hash_fmt.png or .dds). The game engine's own texture-replacement code does the swapping;
 * the small native helper (liblmmods.so) just points it at the chosen pack folder.
 */
final class TexturePacks {
    static native int nativeApply(String dir);   // "" = unload

    private static boolean nativeLoaded, nativeOk, applied;

    interface Progress { void update(int files); }

    static synchronized boolean loadNative() {
        if (!nativeLoaded) {
            nativeLoaded = true;
            try { System.loadLibrary("lmmods"); nativeOk = true; } catch (Throwable t) { nativeOk = false; }
        }
        return nativeOk;
    }

    static File root(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File r = new File(base, "texturepacks");
        //noinspection ResultOfMethodCallIgnored
        r.mkdirs();
        return r;
    }

    static List<String> list(Context c) {
        List<String> out = new ArrayList<>();
        File[] fs = root(c).listFiles();
        if (fs != null) {
            Arrays.sort(fs);
            for (File f : fs) if (f.isDirectory() && !f.getName().startsWith(".")) out.add(f.getName());
        }
        return out;
    }

    /** The folder to load right now, or null when custom textures are off / no pack is chosen. */
    static File activeDir(Context c, SharedPreferences p) {
        if (!p.getBoolean("tp_on", false)) return null;
        String name = p.getString("tp_pack", "");
        if (name == null || name.isEmpty()) return null;
        File d = new File(root(c), name);
        return d.isDirectory() ? d : null;
    }

    /** Tell the engine which pack to use (call once the game is running, and after options change). */
    static void apply(Context c, SharedPreferences p) {
        File d = activeDir(c, p);
        if (d == null && !applied) return;          // nothing to load and nothing to unload
        if (!loadNative()) return;
        try {
            nativeApply(d == null ? "" : d.getAbsolutePath());
            applied = d != null;
        } catch (Throwable ignored) { }
    }

    static String safeName(String zipName) {
        String n = zipName == null ? "pack" : zipName;
        if (n.toLowerCase(Locale.ROOT).endsWith(".zip")) n = n.substring(0, n.length() - 4);
        n = n.replaceAll("[^A-Za-z0-9 _.\\-]", "_").trim();
        if (n.startsWith(".")) n = "_" + n;
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isEmpty() ? "pack" : n;
    }

    /** Unzips a pack into texturepacks/<name>. Returns the number of tex1_* texture files found. */
    static int install(Context c, InputStream source, String zipName, Progress progress) throws IOException {
        return install(root(c), source, zipName, progress);
    }

    static int install(File root, InputStream source, String zipName, Progress progress) throws IOException {
        if (source == null) throw new IOException("Cannot open the selected file.");
        String name = safeName(zipName);
        File tmp = new File(root, ".install-" + System.nanoTime());
        if (!tmp.mkdirs()) throw new IOException("App storage is unavailable.");
        String tmpPath = tmp.getCanonicalPath() + File.separator;
        int textures = 0, files = 0;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(source, 1 << 16))) {
            byte[] buf = new byte[1 << 16];
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String entry = e.getName().replace('\\', '/');
                String leaf = new File(entry).getName();
                String lower = leaf.toLowerCase(Locale.ROOT);
                if (entry.contains("__MACOSX") || leaf.startsWith("._")) continue;
                if (!lower.endsWith(".png") && !lower.endsWith(".dds")) continue;
                File out = new File(tmp, entry);
                if (!out.getCanonicalPath().startsWith(tmpPath)) continue;   // never write outside the pack folder
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("App storage is unavailable.");
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) fo.write(buf, 0, n);
                }
                files++;
                if (lower.startsWith("tex1_")) textures++;
                if (progress != null && (files & 15) == 0) progress.update(files);
            }
            if (textures == 0)
                throw new IOException("No texture files named like tex1_…_.png or .dds were found in this zip.");
            File dest = new File(root, name);
            if (dest.exists()) deleteTree(dest);
            if (!tmp.renameTo(dest)) throw new IOException("Could not save the texture pack.");
            if (progress != null) progress.update(files);
            return textures;
        } finally {
            if (tmp.exists()) deleteTree(tmp);
        }
    }

    static void remove(Context c, String name) {
        if (name == null || name.isEmpty()) return;
        File d = new File(root(c), name);
        if (d.isDirectory()) deleteTree(d);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---- Cooler packs: smaller textures + mipmaps (NOT USED: turned off in v17b; packs stay exactly as made) ----
    // The engine only uses mipmaps (small copies for far-away surfaces) when the pack ships them as
    // tex1_..._mip1.png, _mip2.png ... and most HD packs don't: then every far-away wall is read from
    // the full HD picture, which keeps the GPU busy (heat, shimmer). optimize() writes those mip
    // files, and first shrinks pictures bigger than the chosen size (Options > Texture Pack Size).

    static final int[] SIZES = {1024, 512, 2048, 0};          // 0 = full size (still gets mipmaps)
    static final String[] SIZE_NAMES = {"1024 (balanced)", "512 (coolest)", "2048 (sharp)", "Full (hottest)"};

    static int maxSize(Context c, SharedPreferences p) {
        int def = DeviceTier.tier(c) == DeviceTier.HIGH ? 1024 : 512;
        return p.getInt("tp_max", def);
    }

    private static File marker(File dir) { return new File(dir, ".lm_optimized"); }

    /** Size the pack was optimized for: -1 = not yet, 0 = full size. */
    static int optimizedSize(File dir) {
        try {
            String s = Diagnostics.readText(marker(dir), 32);
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (Throwable t) { return -1; }
    }

    /** True when the active pack still needs the one-time optimize for the chosen size. */
    static boolean needsOptimize(Context c, SharedPreferences p) {
        File d = activeDir(c, p);
        if (d == null) return false;
        int done = optimizedSize(d), want = maxSize(c, p);
        if (done < 0) return true;
        if (want == 0) return false;                  // full size wanted: can't grow back, keep what we have
        return done == 0 || want < done;              // a smaller size than before
    }

    private static boolean isMip(String lowerLeaf) { return lowerLeaf.matches(".*_mip\\d+\\.png$"); }

    private static void collect(File d, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else {
                String l = f.getName().toLowerCase(Locale.ROOT);
                if (l.startsWith("tex1_") && l.endsWith(".png") && !isMip(l)) out.add(f);
            }
        }
    }

    /** Shrinks pictures above maxSize (0 = keep) and writes mip files for every PNG texture. Returns textures done. */
    static int optimize(File dir, int maxSize, Progress progress) {
        List<File> files = new ArrayList<>();
        collect(dir, files);
        int done = 0;
        for (File f : files) {
            try { optimizeOne(f, maxSize); } catch (Throwable ignored) { }  // a bad file keeps its original
            done++;
            if (progress != null && (done & 7) == 0) progress.update(done);
        }
        Diagnostics.writeText(marker(dir), String.valueOf(maxSize));
        if (progress != null) progress.update(done);
        return done;
    }

    private static void optimizeOne(File f, int maxSize) throws IOException {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        int w = o.outWidth, h = o.outHeight;
        if (w <= 0 || h <= 0) return;
        int sample = 1;
        if (maxSize > 0) while (Math.max(w / sample, h / sample) > maxSize && (w / sample) % 2 == 0 && (h / sample) % 2 == 0) sample *= 2;
        String stem = f.getName().substring(0, f.getName().length() - 4);
        File parent = f.getParentFile();
        if (sample == 1 && new File(parent, stem + "_mip1.png").isFile()) return;   // the pack already has mips
        android.graphics.BitmapFactory.Options d = new android.graphics.BitmapFactory.Options();
        d.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
        d.inSampleSize = sample;     // big pictures are decoded already shrunk (less memory)
        android.graphics.Bitmap base = android.graphics.BitmapFactory.decodeFile(f.getPath(), d);
        if (base == null) return;
        try {
            int bw = w / sample, bh = h / sample;
            if (base.getWidth() != bw || base.getHeight() != bh) {
                android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(base, bw, bh, true);
                if (s != base) { base.recycle(); base = s; }
            }
            if (sample > 1) writePng(base, f);               // the smaller picture replaces the big one
            // mip chain: each level exactly half (rounded down, at least 1) of the base, as the engine expects
            android.graphics.Bitmap prev = base;
            for (int level = 1; ; level++) {
                int mw = Math.max(1, bw >> level), mh = Math.max(1, bh >> level);
                android.graphics.Bitmap m = android.graphics.Bitmap.createScaledBitmap(prev, mw, mh, true);
                writePng(m, new File(parent, stem + "_mip" + level + ".png"));
                if (prev != base) prev.recycle();
                prev = m;
                if (mw == 1 && mh == 1) break;
            }
            if (prev != base) prev.recycle();
        } finally {
            base.recycle();
        }
    }

    private static void writePng(android.graphics.Bitmap b, File out) throws IOException {
        File tmp = new File(out.getPath() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fo);
        }
        if (!tmp.renameTo(out)) {
            if (out.exists() && out.delete() && tmp.renameTo(out)) return;
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not write " + out.getName());
        }
    }
}
