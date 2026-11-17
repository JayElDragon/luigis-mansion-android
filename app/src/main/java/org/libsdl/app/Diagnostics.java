package org.libsdl.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;
import android.view.Display;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Crash detection, logs and the report (Options > Help > Save Log): saved to Download, with a plain-English "What happened".
 *
 * Run marker: "running" while the game is on screen, "stopped" when it is left normally (or put in
 * the background). If the next start still finds "running", the game closed unexpectedly: the log
 * of that run is saved before Android drops it, and the start menu offers Safe Mode and Save Log.
 */
final class Diagnostics {
    private Diagnostics() { }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "reports");
        d.mkdirs();
        return d;
    }

    /** Copy players can find with a file manager: Android/data/<app>/files/reports/. */
    static File publicDir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) return null;
        File d = new File(base, "reports");
        d.mkdirs();
        return d;
    }

    private static File marker(Context c) { return new File(dir(c), "run_state.txt"); }

    /** "running <time> <normal|dlc>" while the game is on screen. */
    static void markRunning(Context c, boolean dlc) {
        writeText(marker(c), "running " + System.currentTimeMillis() + " " + (dlc ? "dlc" : "normal"));
    }

    static void markStopped(Context c) { writeText(marker(c), "stopped " + System.currentTimeMillis()); }

    /** True once per crash: the last game run never reached a normal stop. */
    static boolean consumeCrash(Context c) {
        String s = readText(marker(c), 200);
        if (s == null || !s.startsWith("running")) return false;
        markStopped(c);
        saveLog(c, "last_crash_log.txt");  // our own app's lines from the run that died
        writeText(new File(dir(c), "last_crash_info.txt"), s.trim() + "\nfound " + System.currentTimeMillis());
        return true;
    }

    static boolean hasCrashLog(Context c) { return new File(dir(c), "last_crash_log.txt").isFile(); }

    /** Java exceptions on any thread go to java_crash.txt before the app closes. */
    static void installJavaCrashHandler(final Context app) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof Handler) return;
        Thread.setDefaultUncaughtExceptionHandler(new Handler(app.getApplicationContext(), prev));
    }

    private static final class Handler implements Thread.UncaughtExceptionHandler {
        final Context c;
        final Thread.UncaughtExceptionHandler prev;
        Handler(Context c, Thread.UncaughtExceptionHandler prev) { this.c = c; this.prev = prev; }
        @Override public void uncaughtException(Thread t, Throwable e) {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                writeText(new File(dir(c), "java_crash.txt"), stamp() + " thread " + t.getName() + "\n" + sw);
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        }
    }

    /** This app's log lines (logcat, own app only) into reports/<name>. */
    static void saveLog(Context c, String name) {
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "4000").redirectErrorStream(true).start();
            File out = new File(dir(c), name);
            try (InputStreamReader in = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8);
                 Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
                char[] buf = new char[8192];
                int n;
                while ((n = in.read(buf)) > 0) w.write(buf, 0, n);
            }
            p.waitFor();
        } catch (Throwable ignored) { }
    }

    static void clearLogs(Context c) {
        for (File d : new File[] {dir(c), publicDir(c)}) {
            if (d == null) continue;
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) if (!f.getName().equals("run_state.txt")) f.delete();
        }
    }

    // ---- per-thread CPU use while playing (shows a single-core bottleneck) ----

    static final class ThreadUse {
        final int tid; final String name; final float percent;
        ThreadUse(int tid, String name, float percent) { this.tid = tid; this.name = name; this.percent = percent; }
    }

    private static final Map<Integer, Long> lastTicks = new HashMap<>();
    private static long lastSampleNs;
    static volatile List<ThreadUse> latest = Collections.emptyList();
    private static final Map<String, Float> peaks = new HashMap<>();

    /** Call every few seconds while the game runs: CPU % of one core per thread since the last call. */
    static synchronized List<ThreadUse> sampleThreads() {
        long now = System.nanoTime();
        double secs = lastSampleNs == 0 ? 0 : (now - lastSampleNs) / 1e9;
        lastSampleNs = now;
        List<ThreadUse> out = new ArrayList<>();
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) return out;
        Map<Integer, Long> cur = new HashMap<>();
        for (File t : tasks) {
            try {
                int tid = Integer.parseInt(t.getName());
                String stat = readText(new File(t, "stat"), 1024);
                if (stat == null) continue;
                int l = stat.indexOf('('), r = stat.lastIndexOf(')');
                String name = stat.substring(l + 1, r);
                String[] f = stat.substring(r + 2).split(" ");
                long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]);  // utime + stime
                cur.put(tid, ticks);
                Long prev = lastTicks.get(tid);
                if (prev != null && secs > 0) {
                    float pct = (float) ((ticks - prev) / 100.0 / secs * 100.0);  // USER_HZ = 100
                    if (pct >= 1f) out.add(new ThreadUse(tid, name, pct));
                    Float pk = peaks.get(name);
                    if (pk == null || pct > pk) peaks.put(name, pct);
                }
            } catch (Throwable ignored) { }
        }
        lastTicks.clear();
        lastTicks.putAll(cur);
        Collections.sort(out, (a, b) -> Float.compare(b.percent, a.percent));
        latest = out;
        return out;
    }

    static String threadSummary() {
        StringBuilder sb = new StringBuilder();
        List<ThreadUse> l = latest;
        if (l.isEmpty()) sb.append("  (no sample yet: start the game first)\n");
        for (int i = 0; i < Math.min(8, l.size()); i++)
            sb.append(String.format(Locale.ROOT, "  %-16s %5.0f%% of one core\n", l.get(i).name, l.get(i).percent));
        if (!peaks.isEmpty()) {
            List<Map.Entry<String, Float>> e = new ArrayList<>(peaks.entrySet());
            Collections.sort(e, (a, b) -> Float.compare(b.getValue(), a.getValue()));
            sb.append("  peaks:");
            for (int i = 0; i < Math.min(5, e.size()); i++)
                sb.append(String.format(Locale.ROOT, " %s %.0f%%", e.get(i).getKey(), e.get(i).getValue()));
            sb.append('\n');
        }
        return sb.toString();
    }

    static void saveThreadSummary(Context c) { writeText(new File(dir(c), "cpu_threads.txt"), stamp() + "\n" + threadSummary()); }

    // ---- the report ----

    static String appVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    static String deviceSummary(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Chip: ").append(DeviceTier.socModel()).append("   Tier: ").append(DeviceTier.tierName(DeviceTier.tier(c)))
                .append(DeviceTier.isHighEnd(c) ? " (Boost CPU available)" : "").append('\n');
        long[] f = DeviceTier.coreMaxFreqs();
        sb.append("CPU cores: ").append(f.length).append("  max MHz:");
        for (long v : f) sb.append(' ').append(v / 1000);
        sb.append('\n');
        sb.append("RAM: ").append(DeviceTier.totalRamBytes(c) / (1024 * 1024)).append(" MB\n");
        sb.append("Vulkan: ").append(DeviceTier.vulkanString(DeviceTier.vulkanVersion(c))).append('\n');
        try {
            Display d = c.getDisplay();
            if (d != null) {
                Display.Mode m = d.getMode();
                sb.append("Screen: ").append(m.getPhysicalWidth()).append('x').append(m.getPhysicalHeight())
                        .append(" @ ").append(Math.round(m.getRefreshRate())).append(" Hz (ratio ")
                        .append(aspectName(m.getPhysicalWidth(), m.getPhysicalHeight())).append(")  modes:");
                for (Display.Mode x : d.getSupportedModes())
                    if (x.getPhysicalWidth() == m.getPhysicalWidth()) sb.append(' ').append(Math.round(x.getRefreshRate()));
                sb.append('\n');
            }
        } catch (Throwable ignored) { }
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            sb.append("Battery saver: ").append(pm.isPowerSaveMode() ? "ON" : "off")
                    .append("   Thermal status: ").append(pm.getCurrentThermalStatus()).append('\n');
        } catch (Throwable ignored) { }
        try {
            StatFs s = new StatFs(c.getFilesDir().getAbsolutePath());
            sb.append("Free storage: ").append(s.getAvailableBytes() / (1024 * 1024)).append(" MB\n");
        } catch (Throwable ignored) { }
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append('\n');
        return sb.toString();
    }

    /** "19.5:9" style name for a screen shape (landscape). */
    static String aspectName(int w, int h) {
        int a = Math.max(w, h), b = Math.min(w, h);
        if (b == 0) return "?";
        double r = (double) a / b * 9.0;
        double rounded = Math.round(r * 2) / 2.0;
        String s = rounded == Math.floor(rounded) ? String.valueOf((int) rounded) : String.valueOf(rounded);
        return s + ":9";
    }

    static File writeReport(Context c, SharedPreferences prefs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Luigi's Mansion Android - report\n");
        sb.append("Made: ").append(stamp()).append("\nApp version: ").append(appVersion(c)).append("\n\n");
        saveLog(c, "current_log.txt");
        sb.append(diagnose(c));
        sb.append("== Device ==\n").append(deviceSummary(c)).append('\n');
        sb.append("== Game file ==\n");
        File disc = LauncherActivity.findDiscStatic(c);
        if (DiscLink.has(prefs)) {
            String pr = DiscLink.check(c, prefs);
            sb.append("  linked in place: ").append(DiscLink.name(prefs)).append("  ")
                    .append(DiscLink.size(c, prefs) / (1024 * 1024)).append(" MB  ").append(pr == null ? "OK" : pr).append('\n');
        }
        if (disc == null) sb.append(DiscLink.has(prefs) ? "" : "  none installed\n");
        else {
            String id = DiscInstaller.gameId(disc);
            sb.append("  ").append(disc.getName()).append("  ").append(disc.length() / (1024 * 1024)).append(" MB  id ")
                    .append(id).append("  ").append(DiscInstaller.check(disc, disc.length()) == null ? "OK"
                            : DiscInstaller.check(disc, disc.length())).append('\n');
        }
        sb.append("\n== App settings ==\n");
        Map<String, ?> all = prefs.getAll();
        List<String> keys = new ArrayList<>(all.keySet());
        Collections.sort(keys);
        for (String k : keys) if (!k.startsWith("layout_")) sb.append("  ").append(k).append(" = ").append(all.get(k)).append('\n');
        sb.append("\n== settings.ini ==\n");
        String ini = readText(GameSettings.iniFile(c), 20000);
        sb.append(ini == null ? "  (not written yet)\n" : ini);
        sb.append("\n== Engine folders ==\n").append(GameSettings.folderSummary(c));
        sb.append("  save backup: ").append(prefs.getString("save_backup_uri", "none yet")).append('\n');
        sb.append("\n== Busiest threads (last game run) ==\n");
        String cpu = readText(new File(dir(c), "cpu_threads.txt"), 4000);
        sb.append(cpu != null ? cpu : threadSummary());
        String jc = readText(new File(dir(c), "java_crash.txt"), 20000);
        if (jc != null) sb.append("\n== Java crash ==\n").append(jc);
        String key = keyLines(logToScan(c), 200);
        if (key != null) sb.append("\n== Important log lines (errors and the game engine) ==\n").append(key);
        String crash = tail(new File(dir(c), "last_crash_log.txt"), 1500);
        if (crash != null) sb.append("\n== Log of the run that closed unexpectedly (last lines) ==\n").append(crash);
        String now = tail(new File(dir(c), "current_log.txt"), 800);
        if (now != null) sb.append("\n== Log now (last lines) ==\n").append(now);

        String name = "LM-report-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt";
        File out = new File(dir(c), "report.txt");
        writeText(out, sb.toString());
        File pub = publicDir(c);
        if (pub != null) writeText(new File(pub, name), sb.toString());
        lastSavedUri = saveToDownloads(c, name, sb.toString());
        lastSavedName = name;
        return out;
    }

    static volatile Uri lastSavedUri;
    static volatile String lastSavedName;

    static String logsFolder(Context c) { return SaveBackup.folder(c) + "Logs/"; }

    /** Saves the report into the phone's Download folder (stays after uninstalling; any file manager opens it). */
    static Uri saveToDownloads(Context c, String name, String text) {
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
            v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, logsFolder(c));
            Uri uri = c.getContentResolver().insert(
                    android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) return null;
            try (java.io.OutputStream os = c.getContentResolver().openOutputStream(uri, "wt")) {
                if (os == null) return null;
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return uri;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- "What happened": plain-English diagnosis of the last run ----

    private static File logToScan(Context c) {
        File crash = new File(dir(c), "last_crash_log.txt");
        return crash.isFile() ? crash : new File(dir(c), "current_log.txt");
    }

    /** Lines worth reading: errors, warnings and everything the game engine printed. */
    static String keyLines(File f, int max) {
        String all = tail(f, 4000);
        if (all == null) return null;
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
        for (String l : all.split("\n")) {
            boolean engine = l.contains("LMRecomp") || l.contains("LMDisplay") || l.contains("recomp") || l.contains(" SDL") || l.contains("Aurora");
            boolean bad = l.matches("^\\S+ \\S+\\s+\\d+\\s+\\d+ [EF] .*") || l.contains(" DEBUG ") || l.contains("Fatal signal")
                    || l.contains("AndroidRuntime") || l.toLowerCase(Locale.ROOT).contains("vulkan");
            if (engine || bad) { q.add(l); if (q.size() > max) q.removeFirst(); }
        }
        if (q.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String l : q) sb.append(l).append('\n');
        return sb.toString();
    }

    /** First line of the diagnosis, for the "log saved" message. */
    static volatile String lastHeadline;

    static String diagnose(Context c) {
        StringBuilder sb = new StringBuilder("== What happened ==\n");
        List<String> found = new ArrayList<>();
        String headline = null;

        // The last game run (from the run marker).
        String info = readText(new File(dir(c), "last_crash_info.txt"), 400);
        boolean crashed = hasCrashLog(c);
        if (info != null) {
            String[] w = info.split("\\s+");
            try {
                long start = Long.parseLong(w[1]);
                String mode = w.length > 2 && w[2].equals("dlc") ? "Premium Deluxe" : "Luigi's Mansion";
                sb.append("Last game run: ").append(mode).append(", started ")
                        .append(new SimpleDateFormat("MMM d HH:mm", Locale.ROOT).format(new Date(start))).append('\n');
            } catch (Throwable ignored) { }
        }

        // What Android says ended the app (Android 11+ keeps this history).
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ApplicationExitInfo> ex = am.getHistoricalProcessExitReasons(null, 0, 8);
            for (android.app.ApplicationExitInfo e : ex) {
                if (!c.getPackageName().equals(e.getProcessName())) continue;  // skip the restart helper
                String why = exitReason(e);
                sb.append("Android's record of how the app last closed: ").append(why)
                        .append(" (").append(new SimpleDateFormat("MMM d HH:mm:ss", Locale.ROOT).format(new Date(e.getTimestamp())))
                        .append(", memory used ").append(e.getPss() / 1024).append(" MB");
                if (e.getDescription() != null) sb.append(", \"").append(e.getDescription()).append('"');
                sb.append(")\n");
                if (crashed) headline = why;
                break;
            }
        } catch (Throwable ignored) { }

        // What the log shows.
        String log = tail(logToScan(c), 4000);
        String jc = readText(new File(dir(c), "java_crash.txt"), 2000);
        if (log != null) {
            String fatal = null, sig = null, noHle = null, jump = null, vk = null, oom = null, disc = null, lastEngine = null;
            List<String> frames = new ArrayList<>();
            for (String l : log.split("\n")) {
                if (l.contains("recomp FATAL")) fatal = l.substring(l.indexOf("recomp FATAL"));
                if (l.contains("has no HLE")) noHle = l.substring(Math.max(0, l.indexOf("SDK function")));
                if (l.contains("no function starts there")) jump = l;
                if (l.contains("Fatal signal")) sig = l.substring(l.indexOf("Fatal signal"));
                if (l.matches(".*#\\d\\d pc .*") && frames.size() < 12) frames.add(l.substring(l.indexOf('#')));
                String lo = l.toLowerCase(Locale.ROOT);
                if ((lo.contains("vk_error") || (lo.contains("vulkan") && (lo.contains("fail") || lo.contains("error")))) && vk == null) vk = l;
                if (lo.contains("bad_alloc") || lo.contains("out of memory")) oom = l;
                if (lo.contains("disc") && (lo.contains("not found") || lo.contains("could not") || lo.contains("failed") || lo.contains("wrong"))) disc = l;
                if (l.contains("LMRecomp")) lastEngine = l;
            }
            if (noHle != null) found.add("The game used a system function the Android version doesn't support yet. A fix is needed in the port.\n    " + noHle);
            else if (jump != null) found.add("The game jumped to a part of its code the port doesn't know about yet. A fix is needed in the port.\n    " + fatal);
            else if (fatal != null) found.add("The game engine stopped on an error.\n    " + fatal);
            if (oom != null) found.add("The phone ran out of memory. Close other apps, or use the Low-end preset / Safe Mode.\n    " + oom.trim());
            if (vk != null) found.add("The graphics driver reported a problem. Try Safe Mode; updating the phone can help too.\n    " + vk.trim());
            if (disc != null) found.add("There was a problem reading the game file. Open Game Files and choose it again.\n    " + disc.trim());
            if (sig != null && fatal == null) found.add(signalText(sig) + "\n    " + sig.trim());
            if (!frames.isEmpty()) {
                StringBuilder f = new StringBuilder("Where it crashed (for the developer):");
                for (String x : frames) f.append("\n    ").append(x.trim());
                found.add(f.toString());
            }
            if (lastEngine != null) found.add("Last thing the game engine logged:\n    " + lastEngine.trim());
        }
        if (jc != null) found.add("The app itself hit an error:\n    " + jc.split("\n")[Math.min(1, jc.split("\n").length - 1)].trim());

        if (found.isEmpty()) {
            found.add(crashed ? "The game closed without leaving an error in the log. Android's record above is the best clue."
                    : "No crash was found. Everything in the last run looks normal.");
        }
        if (headline == null) headline = found.get(0).split("\n")[0];
        lastHeadline = headline;
        for (String x : found) sb.append("• ").append(x).append('\n');
        sb.append('\n');
        return sb.toString();
    }

    private static String signalText(String l) {
        if (l.contains("SIGSEGV")) return "The game crashed reading or writing memory it shouldn't (SIGSEGV).";
        if (l.contains("SIGABRT")) return "The game stopped itself after an error (SIGABRT).";
        if (l.contains("SIGBUS")) return "The game crashed on a bad memory access (SIGBUS).";
        if (l.contains("SIGILL")) return "The phone's CPU couldn't run an instruction (SIGILL).";
        return "The game crashed.";
    }

    static String exitReason(android.app.ApplicationExitInfo e) {
        switch (e.getReason()) {
            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: return "the game engine crashed";
            case android.app.ApplicationExitInfo.REASON_CRASH: return "the app crashed (Java error)";
            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: return "Android closed it because the phone ran out of memory";
            case android.app.ApplicationExitInfo.REASON_ANR: return "it froze and Android closed it (not responding)";
            case android.app.ApplicationExitInfo.REASON_EXIT_SELF: return "the game closed itself (code " + e.getStatus() + ")";
            case android.app.ApplicationExitInfo.REASON_SIGNALED: return "it was stopped by a signal (" + e.getStatus() + ")";
            case android.app.ApplicationExitInfo.REASON_USER_REQUESTED: return "closed by the player (Force stop or cleared)";
            case android.app.ApplicationExitInfo.REASON_USER_STOPPED: return "closed by the player";
            case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "Android stopped it for using too much power";
            case android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "it failed to start";
            case android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "a system service it needed stopped";
            case android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "a permission changed";
            case android.app.ApplicationExitInfo.REASON_FREEZER: return "Android froze it in the background";
            default: return "other (" + e.getReason() + ")";
        }
    }

    /**
     * Save Log: saves the report to Download/Luigi's Mansion Android/Logs/ and says what happened.
     * Sharing (Discord, email, Reddit...) and opening it are offered after, never forced.
     */
    static void share(final Activity a, final SharedPreferences prefs) {
        Lang.toast(a, "Saving log…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            writeReport(a, prefs);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                final Uri saved = lastSavedUri;
                String where = saved != null ? "Saved to " + logsFolder(a) + lastSavedName
                        : "Saved in the app's files (Android/data/" + a.getPackageName() + "/files/reports/).";
                Lang.dialog(a, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("Log saved")
                        .setMessage(where + "\n\nWhat happened: " + (lastHeadline != null ? lastHeadline : "see the report")
                                + "\n\nThe top of the file explains the problem in plain words.")
                        .setPositiveButton("Done", null)
                        .setNeutralButton("Share", (d, w) -> sendTo(a, saved))
                        .setNegativeButton(saved != null ? "Open" : "Close", (d, w) -> { if (saved != null) open(a, saved); })
                        .show();
            });
        }, "SaveLog").start();
    }

    private static void sendTo(Activity a, Uri saved) {
        try {
            Uri uri = saved != null ? saved : Uri.parse("content://" + a.getPackageName() + ".reports/report.txt");
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "Luigi's Mansion Android report (" + Build.MODEL + ")");
            send.putExtra(Intent.EXTRA_TEXT, "Luigi's Mansion Android report from a " + Build.MANUFACTURER + " " + Build.MODEL
                    + " (" + DeviceTier.socModel() + ", Android " + Build.VERSION.RELEASE + "). The full report is attached.");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(send, "Share log"));
        } catch (Throwable t) {
            Lang.toast(a, "Couldn't open sharing. The log is in Download/Luigi's Mansion Android/Logs.",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static void open(Activity a, Uri saved) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(saved, "text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Throwable t) {
            Lang.toast(a, "No text viewer found. Open it with a file manager: Download/Luigi's Mansion Android/Logs.",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // ---- small file helpers ----

    static String stamp() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()); }

    static void writeText(File f, String s) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(s);
        } catch (Throwable ignored) { }
    }

    static String readText(File f, int max) {
        if (f == null || !f.isFile()) return null;
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < max) sb.append(buf, 0, Math.min(n, max - sb.length()));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    static String tail(File f, int lines) {
        if (f == null || !f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
            String l;
            while ((l = r.readLine()) != null) {
                q.add(l);
                if (q.size() > lines) q.removeFirst();
            }
            StringBuilder sb = new StringBuilder();
            for (String s : q) sb.append(s).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
