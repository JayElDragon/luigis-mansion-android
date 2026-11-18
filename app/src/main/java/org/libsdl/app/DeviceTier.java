package org.libsdl.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/** What kind of phone this is: used for first-launch defaults, the Boost CPU lock and the report. */
final class DeviceTier {
    private DeviceTier() { }

    static final int LOW = 0, MID = 1, HIGH = 2;

    /** Flagship chips Boost CPU is allowed on (Build.SOC_MODEL, Android 12+). Unknown chips stay locked. */
    private static final String[] HIGH_END_SOC = {
            // Qualcomm Snapdragon 8 Gen 1 / 8+ Gen 1 / 8 Gen 2 / 8 Gen 3 / 8 Elite / 8 Elite Gen 5
            "SM8450", "SM8475", "SM8550", "SM8650", "SM8750", "SM8850",
            // MediaTek Dimensity 9000 / 9200 / 9300 / 9400 / 9500 families
            "MT6983", "MT6985", "MT6989", "MT6991", "MT6993",
            // Samsung Exynos 2200 / 2400 / 2500
            "S5E9925", "S5E9945", "S5E9955",
            // Google Tensor G3 / G4 / G5
            "ZUMA", "ZUMAPRO", "LAGUNA", "TENSORG3", "TENSORG4", "TENSORG5",
    };

    static long totalRamBytes(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem;
        } catch (Throwable t) {
            return 0;
        }
    }

    static String socModel() {
        if (Build.VERSION.SDK_INT >= 31) {
            try { return Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL; } catch (Throwable ignored) { }
        }
        return Build.HARDWARE;
    }

    /** Highest frequency (kHz) each CPU core can reach; 0 when unknown. */
    static long[] coreMaxFreqs() {
        int n = Runtime.getRuntime().availableProcessors();
        long[] f = new long[n];
        for (int i = 0; i < n; i++) f[i] = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
        return f;
    }

    /** Bit mask of the fastest cores (within 85% of the top core), or 0 when the speeds are unknown. */
    static long bigCoreMask() {
        long[] f = coreMaxFreqs();
        long max = 0;
        for (long v : f) max = Math.max(max, v);
        if (max == 0) return 0;
        long mask = 0;
        for (int i = 0; i < f.length && i < 63; i++) if (f[i] >= max * 85 / 100) mask |= 1L << i;
        return mask;
    }

    /** Bit mask of the cores below the top speed class (e.g. the 6 performance cores of a 2 prime + 6 chip),
     *  or 0 when every core is the same class or the speeds are unknown. */
    static long coolCoreMask() {
        long[] f = coreMaxFreqs();
        long max = 0;
        for (long v : f) max = Math.max(max, v);
        if (max == 0) return 0;
        long mask = 0;
        int n = 0;
        for (int i = 0; i < f.length && i < 63; i++) if (f[i] > 0 && f[i] < max * 95 / 100) { mask |= 1L << i; n++; }
        return n >= 2 ? mask : 0;
    }

    /**
     * The game's cores: 3 cores of the fast classes just below the top one, never the small cores.
     * Measured on the Odin 3 (8 Elite: 6 x 3.53 GHz + 2 x 4.32 GHz) in the mansion at 120 fps: the game needs
     * about 1.5 cores of work and held 121.7 fps on 2 performance cores; the 3rd is headroom for heavy rooms.
     * Classes are taken from the top speed down, skipping the top class, and only while a class is at least 75%
     * of the top speed: on an 8 Gen 2 (AYN Thor: 3 x 2.0 + 4 x 2.8 + 1 x 3.36 GHz) that is 3 of the 2.8 GHz
     * cores. (Taking "the first 3 below the top class" there picked cpu0-2, the 2.0 GHz small cores: 20 fps.)
     * 0 (no pinning, the system decides) when there are not 3 such cores or the speeds are unknown.
     */
    static long gameCoreMask() {
        long[] f = coreMaxFreqs();
        long max = 0;
        for (long v : f) max = Math.max(max, v);
        if (max == 0) return 0;
        java.util.TreeSet<Long> classes = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        for (long v : f) if (v > 0) classes.add(v);
        long m = 0;
        int n = 0;
        boolean top = true;
        for (long cls : classes) {
            if (top) { top = false; continue; }                 // the hottest class stays free
            if (cls < max * 75 / 100) break;                    // small cores: never
            for (int i = 0; i < f.length && i < 63 && n < 3; i++) if (f[i] == cls) { m |= 1L << i; n++; }
            if (n >= 3) break;
        }
        return n >= 3 ? m : 0;
    }

    static long readLong(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(new File(path)))) {
            String s = r.readLine();
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Vulkan version the phone reports (e.g. 0x401000 = 1.1), 0 when none. */
    static int vulkanVersion(Context c) {
        try {
            FeatureInfo[] fs = c.getPackageManager().getSystemAvailableFeatures();
            int best = 0;
            if (fs != null) for (FeatureInfo fi : fs)
                if (PackageManager.FEATURE_VULKAN_HARDWARE_VERSION.equals(fi.name)) best = Math.max(best, fi.version);
            return best;
        } catch (Throwable t) {
            return 0;
        }
    }

    static String vulkanString(int v) {
        if (v == 0) return "none";
        return (v >> 22) + "." + ((v >> 12) & 0x3FF);
    }

    /** True only for known flagship chips with plenty of RAM on Android 12 or newer. */
    static boolean isHighEnd(Context c) {
        if (Build.VERSION.SDK_INT < 31) return false;
        if (totalRamBytes(c) < 7L * 1024 * 1024 * 1024) return false;
        if (Runtime.getRuntime().availableProcessors() < 8) return false;
        String model;
        try { model = Build.SOC_MODEL; } catch (Throwable t) { return false; }
        if (model == null) return false;
        String m = model.toUpperCase(Locale.ROOT).replace(" ", "");
        for (String s : HIGH_END_SOC) if (m.startsWith(s)) return true;
        return false;
    }

    static int tier(Context c) {
        if (isHighEnd(c)) return HIGH;
        long ram = totalRamBytes(c);
        long[] f = coreMaxFreqs();
        long max = 0;
        for (long v : f) max = Math.max(max, v);
        boolean vk11 = vulkanVersion(c) >= 0x401000;
        if (ram >= 5L * 1024 * 1024 * 1024 && max >= 2_600_000 && vk11) return MID;
        return LOW;
    }

    static String tierName(int t) { return t == HIGH ? "High-end" : t == MID ? "Mid-range" : "Entry-level"; }
}
