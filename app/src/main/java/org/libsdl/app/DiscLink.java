package org.libsdl.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * "Play from where it is": the game reads your own ISO/RVZ straight from where it is on the phone
 * (Download, SD card, ...) instead of a 1.4 GB copy inside the app. Removing the game in Game Files,
 * updating or uninstalling the app never touches that file. Android keeps the permission across app
 * updates; after a reinstall you pick the file once more (no copying, takes a second).
 *
 * The game engine opens files by path, so the app opens the file and hands the engine
 * /proc/self/fd/<n>, a path that stays valid while the game runs.
 */
final class DiscLink {
    private DiscLink() { }

    static final String KEY_URI = "disc_uri", KEY_NAME = "disc_name";

    static boolean has(SharedPreferences p) { return p.getString(KEY_URI, null) != null; }

    static String name(SharedPreferences p) { return p.getString(KEY_NAME, "game file"); }

    /** Keeps read access to the picked file across restarts and app updates. */
    static void link(Context c, SharedPreferences p, Uri uri, String name) {
        c.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        unlink(c, p);
        p.edit().putString(KEY_URI, uri.toString()).putString(KEY_NAME, name).commit();
    }

    /** Forgets the file (the file itself stays on the phone). */
    static void unlink(Context c, SharedPreferences p) {
        String s = p.getString(KEY_URI, null);
        if (s != null) {
            try { c.getContentResolver().releasePersistableUriPermission(Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Throwable ignored) { }
        }
        p.edit().remove(KEY_URI).remove(KEY_NAME).commit();
    }

    /** Opens the linked file; null when it was moved/deleted or the permission is gone. Caller keeps it open. */
    static ParcelFileDescriptor open(Context c, SharedPreferences p) {
        String s = p.getString(KEY_URI, null);
        if (s == null) return null;
        try { return c.getContentResolver().openFileDescriptor(Uri.parse(s), "r"); }
        catch (Throwable t) { return null; }
    }

    static String path(ParcelFileDescriptor pfd) { return "/proc/self/fd/" + pfd.getFd(); }

    /** Null when the linked file is a playable disc; otherwise the problem for the player. */
    static String check(Context c, SharedPreferences p) {
        try (ParcelFileDescriptor pfd = open(c, p)) {
            if (pfd == null) return "The game file \"" + name(p) + "\" can't be opened any more (moved, renamed or deleted). "
                    + "Open Game Files and choose it again.";
            return DiscInstaller.check(new File(path(pfd)), pfd.getStatSize());
        } catch (Throwable t) {
            return "The game file can't be read. Open Game Files and choose it again.";
        }
    }

    static long size(Context c, SharedPreferences p) {
        try (ParcelFileDescriptor pfd = open(c, p)) { return pfd == null ? -1 : pfd.getStatSize(); }
        catch (Throwable t) { return -1; }
    }
}
