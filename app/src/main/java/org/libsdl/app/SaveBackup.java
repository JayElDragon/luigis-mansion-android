package org.libsdl.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Save backups that survive uninstalling / reinstalling the app.
 * After every play session (when the save changed) the memory card folder is zipped to
 *     Download/Luigi's Mansion Android/LM-saves.zip
 * which belongs to the phone, not the app. Options > Help > Restore Saves picks such a zip and puts it back
 * (the current save is kept aside first in the app as saves_before_restore).
 */
final class SaveBackup {
    private SaveBackup() { }

    static String folder(Context c) {
        return c.getPackageName().endsWith(".dev") ? "Download/Luigi's Mansion Android (Dev)/" : "Download/Luigi's Mansion Android/";
    }

    /** The game's save folders (memory cards) inside the engine data folder. */
    static File[] saveDirs(Context c) {
        File e = GameSettings.engineDir(c);
        return new File[] {new File(e, "USA"), new File(e, "EUR"), new File(e, "PAL")};
    }

    static long newestSave(Context c) {
        long t = 0;
        for (File d : saveDirs(c)) t = Math.max(t, newest(d));
        t = Math.max(t, newest(new File(GameSettings.dlcEngineDir(c), "USA")));  // Premium Deluxe saves
        return t;
    }

    private static long newest(File f) {
        if (!f.exists()) return 0;
        if (f.isFile()) return f.lastModified();
        long t = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) t = Math.max(t, newest(x));
        return t;
    }

    static boolean hasSaves(Context c) { return newestSave(c) > 0; }

    /** Zips the saves to Download/Luigi's Mansion Android/ if they changed since the last backup. Returns true if written. */
    static synchronized boolean backupIfChanged(Context c, SharedPreferences p) {
        long t = newestSave(c);
        if (t == 0 || t <= p.getLong("save_backup_time", 0)) return false;
        try {
            Uri uri = target(c, p);
            if (uri == null) return false;
            try (OutputStream os = c.getContentResolver().openOutputStream(uri, "wt")) {
                if (os == null) return false;
                writeZip(c, os);
            }
            p.edit().putLong("save_backup_time", t).putString("save_backup_uri", uri.toString()).apply();
            return true;
        } catch (Throwable e) {
            p.edit().remove("save_backup_uri").apply();  // e.g. the file was deleted by the player: make a new one next time
            return false;
        }
    }

    /** Our own backup entry in Downloads (created once; reused while it exists). */
    private static Uri target(Context c, SharedPreferences p) {
        String s = p.getString("save_backup_uri", null);
        if (s != null) return Uri.parse(s);
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, "LM-saves.zip");
        v.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        v.put(MediaStore.Downloads.RELATIVE_PATH, folder(c));
        return c.getContentResolver().insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
    }

    private static void writeZip(Context c, OutputStream os) throws IOException {
        File root = GameSettings.engineDir(c);
        try (ZipOutputStream z = new ZipOutputStream(os)) {
            ZipEntry info = new ZipEntry("LM-SAVE-BACKUP.txt");
            z.putNextEntry(info);
            z.write(("Luigi's Mansion Android save backup\nMade: "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())
                    + "\nRestore it with Options > Help > Restore Saves.\n").getBytes("UTF-8"));
            z.closeEntry();
            for (File d : saveDirs(c)) add(z, root, d, "");
            add(z, GameSettings.dlcEngineDir(c), new File(GameSettings.dlcEngineDir(c), "USA"), "DLC/");  // Premium Deluxe
        }
    }

    private static void add(ZipOutputStream z, File root, File f, String prefix) throws IOException {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File x : fs) add(z, root, x, prefix);
            return;
        }
        String rel = prefix + root.toURI().relativize(f.toURI()).getPath();
        z.putNextEntry(new ZipEntry(rel));
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) z.write(b, 0, n);
        }
        z.closeEntry();
    }

    /** Puts a backup zip back. Returns the number of save files restored. */
    static int restore(Context c, Uri zip) throws IOException {
        File root = GameSettings.engineDir(c);
        String canon = root.getCanonicalPath() + File.separator;
        // Keep the current save aside first.
        File aside = new File(c.getFilesDir(), "saves_before_restore");
        GameSettings.deleteTree(aside);
        for (File d : saveDirs(c)) if (d.exists()) copy(d, new File(aside, d.getName()));
        int count = 0;
        ContentResolver cr = c.getContentResolver();
        try (InputStream in = cr.openInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            boolean ours = false;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName().replace('\\', '/');
                if (n.equals("LM-SAVE-BACKUP.txt")) { ours = true; continue; }
                File base = root;
                String baseCanon = canon;
                if (n.startsWith("DLC/")) {  // Premium Deluxe saves
                    n = n.substring(4);
                    base = GameSettings.dlcEngineDir(c);
                    base.mkdirs();
                    baseCanon = base.getCanonicalPath() + File.separator;
                }
                if (e.isDirectory() || !(n.startsWith("USA/") || n.startsWith("EUR/") || n.startsWith("PAL/"))) continue;
                File out = new File(base, n);
                if (!out.getCanonicalPath().startsWith(baseCanon)) continue;  // no paths outside the save folder
                out.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    byte[] b = new byte[65536];
                    int k;
                    while ((k = z.read(b)) > 0) os.write(b, 0, k);
                }
                count++;
            }
            if (!ours && count == 0) throw new IOException("This zip is not a Luigi's Mansion save backup.");
        }
        return count;
    }

    private static void copy(File from, File to) throws IOException {
        if (from.isDirectory()) {
            to.mkdirs();
            File[] fs = from.listFiles();
            if (fs != null) for (File f : fs) copy(f, new File(to, f.getName()));
        } else {
            try (InputStream in = new FileInputStream(from); OutputStream os = new FileOutputStream(to)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) os.write(b, 0, n);
            }
        }
    }
}
