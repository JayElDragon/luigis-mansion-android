package org.libsdl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Premium Deluxe DLC. The player's own USA disc + the Premium Deluxe .xdelta (or its GameBanana .zip) are turned
 * into the Premium Deluxe disc inside the app (about 220 MB). It runs on its own game engine (libmain_pd.so,
 * built from Premium Deluxe's code) with its own saves, and is started with Play > Play DLC.
 */
final class Dlc {
    private Dlc() { }

    static final String NAME = "Premium Deluxe 2.2.1";
    static final String ENGINE = "main_pd";
    /** SHA-1 of Premium Deluxe 2.2.1's main.dol: the patched disc must contain exactly the code the engine was built from. */
    static final String PD_DOL_SHA1 = "7d9e859bc88e35ec97351b9900af72e81e152af1";

    static File dir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "dlc");
        d.mkdirs();
        return d;
    }

    static File iso(Context c) { return new File(dir(c), "premium_deluxe.iso"); }

    static boolean installed(Context c, SharedPreferences p) { return p.getBoolean("dlc_pd", false) && iso(c).isFile(); }

    interface Progress { void update(long done, long total); }

    /** Builds the DLC disc. source = the player's USA disc (path, e.g. /proc/self/fd/N), patch = .xdelta or .zip. */
    static void install(Context c, SharedPreferences p, String sourcePath, Uri patch, Progress progress) throws IOException {
        String problem = DiscInstaller.check(new File(sourcePath), new File(sourcePath).length() > 0 ? new File(sourcePath).length() : Long.MAX_VALUE / 4);
        if (problem != null) throw new IOException("Your game file can't be used for the DLC: " + problem);
        File out = iso(c), tmp = new File(dir(c), "premium_deluxe.part");
        tmp.delete();
        final long expected = 221_642_752L;  // size of the Premium Deluxe 2.2.1 disc
        try (DiscSource src = DiscSource.open(sourcePath);   // .iso or .rvz
             InputStream raw = c.getContentResolver().openInputStream(patch);
             OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 20)) {
            if (raw == null) throw new IOException("Can't open the patch file.");
            InputStream delta = raw;
            ZipInputStream zip = null;
            byte[] head = new byte[4];
            java.io.PushbackInputStream pb = new java.io.PushbackInputStream(raw, 4);
            int n = pb.read(head);
            if (n > 0) pb.unread(head, 0, n);
            delta = pb;
            if (n == 4 && head[0] == 'P' && head[1] == 'K') {  // GameBanana zip: find the .xdelta inside
                zip = new ZipInputStream(pb);
                ZipEntry e;
                boolean found = false;
                while ((e = zip.getNextEntry()) != null)
                    if (!e.isDirectory() && e.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".xdelta")) { found = true; break; }
                if (!found) throw new IOException("No .xdelta patch was found in this zip.");
                delta = zip;
            }
            Vcdiff.apply(src, delta, os, done -> { if (progress != null) progress.update(done, expected); });
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        String check = verify(tmp);
        if (check != null) { tmp.delete(); throw new IOException(check); }
        out.delete();
        if (!tmp.renameTo(out)) { tmp.delete(); throw new IOException("Couldn't save the DLC (storage full?)."); }
        p.edit().putBoolean("dlc_pd", true).commit();
    }

    /** Null when the file is the Premium Deluxe disc the DLC engine was built for. */
    static String verify(File f) {
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            byte[] id = new byte[6];
            in.readFully(id);
            if (!new String(id, "US-ASCII").startsWith("GLME")) return "The patched disc is not Luigi's Mansion.";
            in.seek(0x420);
            long dolOff = in.readInt() & 0xffffffffL;
            in.seek(dolOff);
            byte[] hdr = new byte[0x100];
            in.readFully(hdr);
            long end = 0;
            for (int i = 0; i < 18; i++) {
                long off = be(hdr, i * 4), size = be(hdr, 0x90 + i * 4);
                if (size != 0) end = Math.max(end, off + size);
            }
            if (end <= 0 || end > 16L * 1024 * 1024) return "The patched disc has no valid game code.";
            byte[] dol = new byte[(int) end];
            in.seek(dolOff);
            in.readFully(dol);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(dol)) sb.append(String.format("%02x", b));
            if (!PD_DOL_SHA1.equals(sb.toString()))
                return "This patch is a different Premium Deluxe version. This app's DLC engine is made for " + NAME + ".";
            return null;
        } catch (Exception e) {
            return "Couldn't check the patched disc.";
        }
    }

    private static long be(byte[] b, int o) {
        return ((b[o] & 0xffL) << 24) | ((b[o + 1] & 0xffL) << 16) | ((b[o + 2] & 0xffL) << 8) | (b[o + 3] & 0xffL);
    }

    static void remove(Context c, SharedPreferences p) {
        iso(c).delete();
        p.edit().putBoolean("dlc_pd", false).commit();
    }
}
