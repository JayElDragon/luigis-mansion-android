package org.libsdl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PerformanceHintManager;
import android.os.PowerManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.List;

/**
 * Boost CPU (Options > Graphics, experimental, high-end phones only; other phones show
 * "Not accessible on this device"). While the game runs it finds the threads doing the most work
 * and helps the phone run them fast:
 *  - higher scheduling priority,
 *  - kept on the fastest CPU cores (native helper, liblmmods),
 *  - Android's performance hint (Android 12+): tells the system these threads must finish a frame on time.
 * It switches itself off for the session when the phone gets warm, and fully undoes everything when turned off.
 */
final class Boost {
    private Boost() { }

    static { try { System.loadLibrary("lmmods"); } catch (Throwable ignored) { } }

    static native int nativeSetAffinity(int tid, long mask);

    static boolean enabled(Context c, SharedPreferences p) {
        return p.getBoolean("boost_cpu", false) && !p.getBoolean("safe_mode", false) && DeviceTier.isHighEnd(c);
    }

    private static final List<Integer> boosted = new ArrayList<>();
    private static PerformanceHintManager.Session session;
    private static long targetNs;
    static volatile boolean pausedHot;

    /** Called every few seconds from the game's monitor thread with the latest per-thread CPU use. */
    static synchronized void update(Context c, SharedPreferences p, List<Diagnostics.ThreadUse> use) {
        boolean want = enabled(c, p) && !pausedHot;
        if (!want) { undo(); cool(c, p, use); return; }
        undoCool();
        if (!hot(c) && use.size() > 0) {
            // Busy game threads: anything using a quarter of a core or more (at most 4).
            List<Integer> pick = new ArrayList<>();
            for (Diagnostics.ThreadUse t : use) {
                if (t.percent < 25f || pick.size() >= 4) break;
                pick.add(t.tid);
            }
            if (!pick.isEmpty() && !pick.equals(boosted)) apply(c, p, pick);
        }
        if (hot(c)) { pausedHot = true; undo(); return; }
        // Performance hint: report how full the frame was (busiest thread's load as a share of the frame time).
        if (session != null && use.size() > 0) {
            float load = Math.min(1.2f, use.get(0).percent / 100f);
            try { session.reportActualWorkDuration(Math.max(1, (long) (targetNs * load))); } catch (Throwable ignored) { }
        }
    }

    private static boolean hot(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void apply(Context c, SharedPreferences p, List<Integer> tids) {
        undo();
        long mask = DeviceTier.bigCoreMask();
        for (int tid : tids) {
            try { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY); } catch (Throwable ignored) { }
            if (mask != 0) try { nativeSetAffinity(tid, mask); } catch (Throwable ignored) { }
            boosted.add(tid);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                PerformanceHintManager hm = (PerformanceHintManager) c.getSystemService(Context.PERFORMANCE_HINT_SERVICE);
                int fps = Math.max(30, GameSettings.effectiveFps(p));
                targetNs = 1_000_000_000L / fps;
                int[] arr = new int[tids.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = tids.get(i);
                if (hm != null) session = hm.createHintSession(arr, targetNs);
            } catch (Throwable ignored) { session = null; }
        }
    }

    /** Back to normal: default priority, all cores, no hint session. */
    static synchronized void undo() {
        for (int tid : boosted) {
            try { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT); } catch (Throwable ignored) { }
            try { nativeSetAffinity(tid, 0); } catch (Throwable ignored) { }
        }
        boosted.clear();
        if (session != null) {
            try { session.close(); } catch (Throwable ignored) { }
            session = null;
        }
    }

    // ---- Cool cores (default, when Boost CPU is off) ----
    // The game needs about one core of work spread over 3-4 threads. Left alone, the scheduler runs them on the
    // prime cores at their top clocks, which is where most of the heat (and the fan) comes from. Here the busy
    // game threads are kept on the next core class (still fast, far more efficient), and Android's
    // scheduler raises their clocks as needed (no clock hint: a rough one held them too low for 120 fps).
    private static final List<Integer> cooled = new ArrayList<>();
    private static PerformanceHintManager.Session coolSession;
    private static long coolTargetNs;

    private static void cool(Context c, SharedPreferences p, List<Diagnostics.ThreadUse> use) {
        long mask = DeviceTier.gameCoreMask();  // 3 efficient cores: what 120 fps needs plus headroom
        if (use.isEmpty()) return;
        List<Integer> pick = new ArrayList<>();
        for (Diagnostics.ThreadUse t : use) {
            if (t.percent < 10f || pick.size() >= 6) break;
            pick.add(t.tid);
        }
        if (!pick.isEmpty() && !cooled.containsAll(pick)) {
            for (int tid : pick) {
                if (cooled.contains(tid)) continue;
                if (mask != 0) try { nativeSetAffinity(tid, mask); } catch (Throwable ignored) { }
                cooled.add(tid);
            }
        }
    }

    static synchronized void undoCool() {
        for (int tid : cooled) try { nativeSetAffinity(tid, 0); } catch (Throwable ignored) { }
        cooled.clear();
        if (coolSession != null) {
            try { coolSession.close(); } catch (Throwable ignored) { }
            coolSession = null;
        }
    }

    static synchronized String status() {
        if (pausedHot) return "paused (phone warm)";
        return boosted.isEmpty() ? "idle" : "boosting " + boosted.size() + " threads";
    }
}
