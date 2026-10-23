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
}
