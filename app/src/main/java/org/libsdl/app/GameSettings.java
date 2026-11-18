package org.libsdl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Graphics options chosen in the start menu, written to the game's settings.ini before it starts. */
final class GameSettings {
    static final int DEF_FPS = 120, DEF_RES = 1, DEF_MSAA = 1, DEF_ASPECT = 1;

    private GameSettings() { }

    /**
     * Auto Cool-down level, set by MyGame from Android's thermal status and headroom forecast:
     * 0 = the player's settings; 1 = warming up: internal resolution 1x (the frame rate is kept);
     * 2 = warm: also no anti-aliasing and at most 60 fps; 3 = hot: 30 fps.
     */
    static volatile int thermalLevel = 0;

    /** Frame rate the game really runs at: Performance Mode caps it at 60, and a hot phone lowers it further. */
    static int effectiveFps(SharedPreferences p) {
        int fps = p.getInt("g_fps", DEF_FPS);
        if (p.getBoolean("g_perf", false)) fps = Math.min(fps, 60);
        if (thermalLevel >= 3) fps = Math.min(fps, 30);
        else if (thermalLevel >= 2) fps = Math.min(fps, 60);
        return fps;
    }

    /** Internal resolution: the player's choice, 1x in Performance Mode, and 1x above 60 fps
     *  (120 fps draws 4 pictures per game frame: at 2x that kept the GPU, the heat and the fan high). */
    static int effectiveRes(SharedPreferences p) {
        if (p.getBoolean("g_perf", false) || thermalLevel >= 1 || effectiveFps(p) > 60) return 1;
        return p.getInt("g_res", DEF_RES);
    }

    static int effectiveMsaa(SharedPreferences p) {
        // At most 2x: 4x MSAA on top of 2x+ resolution mostly added GPU heat. Old 4x settings run as 2x.
        return (p.getBoolean("g_perf", false) || thermalLevel >= 2) ? 1 : Math.min(2, p.getInt("g_msaa", DEF_MSAA));
    }

    private static File root(Context c) { return new File(c.getFilesDir(), "xdg"); }

    /** Where the engine keeps its shader/pipeline caches (Android may clear it when storage runs low). */
    static File cacheRoot(Context c) { return new File(c.getCacheDir(), "xdg_cache"); }

    /** Premium Deluxe keeps its saves apart from the normal game. */
    static File dlcDataRoot(Context c) { return new File(c.getFilesDir(), "xdg_dlc"); }

    static File dlcEngineDir(Context c) { return new File(dlcDataRoot(c), "Luigi's Mansion Recompiled"); }

    static File engineDir(Context c) { return new File(root(c), "Luigi's Mansion Recompiled"); }

    static File engineCacheDir(Context c) { return new File(cacheRoot(c), "Luigi's Mansion Recompiled"); }

    static File iniFile(Context c) { return new File(engineDir(c), "settings.ini"); }

    /**
     * The game finds settings.ini under $XDG_CONFIG_HOME, its saves/data under $XDG_DATA_HOME and its
     * shader caches under $XDG_CACHE_HOME. Android sets none of these (and no HOME), so without them the
     * engine falls back to the read-only current folder and cannot keep its shader cache between runs.
     * Call before the native library starts.
     */
    static void applyEnv(Context c) {
        try {
            root(c).mkdirs();
            cacheRoot(c).mkdirs();
            migrateOldData(c);
            Os.setenv("XDG_CONFIG_HOME", root(c).getAbsolutePath(), true);
            Os.setenv("XDG_DATA_HOME", root(c).getAbsolutePath(), true);
            Os.setenv("XDG_CACHE_HOME", cacheRoot(c).getAbsolutePath(), true);
            if (System.getenv("HOME") == null) Os.setenv("HOME", c.getFilesDir().getAbsolutePath(), true);
        } catch (Throwable ignored) {
        }
    }

    /** Saves written by an older build under $HOME/.local/share are copied once into the data folder. */
    private static void migrateOldData(Context c) {
        try {
            File target = engineDir(c);
            if (new File(target, "USA").exists()) return;
            String home = System.getenv("HOME");
            File[] candidates = {
                    home == null ? null : new File(home, ".local/share/Luigi's Mansion Recompiled"),
                    new File(c.getFilesDir(), ".local/share/Luigi's Mansion Recompiled"),
            };
            for (File old : candidates) {
                if (old != null && new File(old, "USA").isDirectory()) {
                    copyTree(new File(old, "USA"), new File(target, "USA"));
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void copyTree(File from, File to) throws java.io.IOException {
        if (from.isDirectory()) {
            to.mkdirs();
            File[] fs = from.listFiles();
            if (fs != null) for (File f : fs) copyTree(f, new File(to, f.getName()));
        } else if (!to.exists()) {
            java.nio.file.Files.copy(from.toPath(), to.toPath());
        }
    }

    static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) n += sizeOf(x);
        return n;
    }

    static boolean deleteTree(File f) {
        if (f == null || !f.exists()) return true;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) deleteTree(x);
        return f.delete();
    }

    static final long CACHE_LIMIT = 600L * 1024 * 1024;

    /**
     * Old shader caches are removed before the game starts (never while playing): after an app update,
     * after a system update (new graphics driver: old caches can show black screens), or when they grow
     * past 600 MB. Saves, settings and the game file are never touched.
     */
    static String cleanCachesIfNeeded(Context c, SharedPreferences p) {
        String reason = null;
        String ver = Diagnostics.appVersion(c);
        String fp = android.os.Build.FINGERPRINT;
        if (!ver.equals(p.getString("cache_app_version", ver))) reason = "app updated";
        else if (!fp.equals(p.getString("cache_fingerprint", fp))) reason = "system / driver updated";
        else if (sizeOf(cacheRoot(c)) > CACHE_LIMIT) reason = "cache over 600 MB";
        if (reason != null) clearShaderCache(c);
        p.edit().putString("cache_app_version", ver).putString("cache_fingerprint", fp).apply();
        return reason;
    }

    static void clearShaderCache(Context c) {
        File[] fs = cacheRoot(c).listFiles();
        if (fs != null) for (File f : fs) deleteTree(f);
        // Caches an older build may have left next to the settings.
        File[] old = engineDir(c).listFiles();
        if (old != null) for (File f : old) {
            String n = f.getName();
            if (n.startsWith("pipeline_cache.") || n.startsWith("dawn_cache.")) f.delete();
        }
    }

    static String folderSummary(Context c) {
        StringBuilder sb = new StringBuilder();
        File e = engineDir(c);
        sb.append("  data: ").append(e.getAbsolutePath()).append("  (").append(sizeOf(e) / 1024).append(" KB)\n");
        sb.append("  saves present: ").append(new File(e, "USA").exists() ? "yes" : "no").append("\n");
        File cache = engineCacheDir(c);
        sb.append("  shader cache: ").append(sizeOf(cacheRoot(c)) / (1024 * 1024)).append(" MB");
        for (String n : new String[] {"pipeline_cache.db", "dawn_cache.db"}) {
            File f = new File(cache, n);
            sb.append("  ").append(n).append('=').append(f.isFile() ? (f.length() / 1024) + "KB" : "none");
        }
        sb.append("\n");
        return sb.toString();
    }

    /** First launch: settings that suit this phone (only if the player has not chosen any yet). */
    static void applyTierDefaults(Context c, SharedPreferences p) {
        // v1.2: VSync back on once for everyone (with it off, frames are timed unevenly on phones = stutter).
        if (!p.getBoolean("vsync_reset_12", false))
            p.edit().putBoolean("vsync_reset_12", true).putBoolean("g_vsync", true).commit();
        if (p.getBoolean("tier_set", false)) return;
        SharedPreferences.Editor e = p.edit().putBoolean("tier_set", true);
        if (!p.contains("g_fps") && !p.contains("g_res") && !p.contains("g_msaa") && !p.contains("g_perf")) {
            int t = DeviceTier.tier(c);
            if (t == DeviceTier.HIGH) e.putInt("g_fps", 60).putInt("g_res", 2).putInt("g_msaa", 1);
            else if (t == DeviceTier.MID) e.putInt("g_fps", 60).putInt("g_res", 1).putInt("g_msaa", 1);
            else e.putInt("g_fps", 30).putInt("g_res", 1).putInt("g_msaa", 1).putBoolean("a_reverb", false).putBoolean("g_perf", true);
        }
        e.commit();
    }

    static final String[] SAFE_KEYS = {"g_fps", "g_res", "g_msaa", "g_perf", "tp_on", "boost_cpu", "a_reverb"};

    /** Safe Mode after a crash: lightest settings; the player's own are kept to restore later. */
    static void enterSafeMode(Context c, SharedPreferences p) {
        SharedPreferences.Editor e = p.edit();
        if (!p.getBoolean("safe_mode", false)) {
            java.util.Map<String, ?> all = p.getAll();
            for (String k : SAFE_KEYS) {
                Object v = all.get(k);
                if (v instanceof Integer) e.putInt("safe_bak_" + k, (Integer) v);
                else if (v instanceof Boolean) e.putBoolean("safe_bak_" + k, (Boolean) v);
                else e.remove("safe_bak_" + k);
                e.putBoolean("safe_had_" + k, v != null);
            }
        }
        e.putBoolean("safe_mode", true).putInt("g_fps", 30).putInt("g_res", 1).putInt("g_msaa", 1)
                .putBoolean("g_perf", true).putBoolean("tp_on", false).putBoolean("boost_cpu", false)
                .putBoolean("a_reverb", false).commit();
        clearShaderCache(c);
        write(c, p);
    }

    static void leaveSafeMode(Context c, SharedPreferences p) {
        if (!p.getBoolean("safe_mode", false)) return;
        java.util.Map<String, ?> all = p.getAll();
        SharedPreferences.Editor e = p.edit();
        for (String k : SAFE_KEYS) {
            Object v = all.get("safe_bak_" + k);
            if (!p.getBoolean("safe_had_" + k, false)) e.remove(k);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            e.remove("safe_bak_" + k).remove("safe_had_" + k);
        }
        e.putBoolean("safe_mode", false).commit();
        write(c, p);
    }

    /** Options > Help > Reset Settings: graphics/audio/control choices back to the phone's defaults. */
    static void resetAll(Context c, SharedPreferences p) {
        java.util.Map<String, ?> all = p.getAll();
        SharedPreferences.Editor e = p.edit();
        for (String k : all.keySet())
            if (k.startsWith("g_") || k.startsWith("a_") || k.startsWith("touch_") || k.startsWith("safe_")
                    || k.equals("boost_cpu") || k.equals("menu_music") || k.equals("tier_set")) e.remove(k);
        e.commit();
        applyTierDefaults(c, p);
        iniFile(c).delete();
        write(c, p);
    }

    private static void put(Map<String, LinkedHashMap<String, String>> ini, String sec, String key, String val) {
        LinkedHashMap<String, String> m = ini.get(sec);
        if (m == null) { m = new LinkedHashMap<>(); ini.put(sec, m); }
        m.put(key, val);
    }

    static void write(Context c, SharedPreferences p) {
        try {
            File dir = engineDir(c);
            dir.mkdirs();
            File f = new File(dir, "settings.ini");
            LinkedHashMap<String, LinkedHashMap<String, String>> ini = new LinkedHashMap<>();
            if (f.isFile()) {
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String line, sec = "";
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
                        if (line.startsWith("[")) {
                            int e = line.indexOf(']');
                            sec = e > 0 ? line.substring(1, e) : line.substring(1);
                            continue;
                        }
                        int eq = line.indexOf('=');
                        if (eq > 0) put(ini, sec, line.substring(0, eq), line.substring(eq + 1));
                    }
                } catch (Exception ignored) {
                }
            }
            // The on-screen buttons are a virtual pad (SDL bus 0xFF: GUID "ff00..."). If the last session left it as
            // player 1, a real controller was ignored until the screen was touched again: start on "auto".
            LinkedHashMap<String, String> ctl = ini.get("controls");
            if (ctl != null) {
                String dev = ctl.get("port1_device");
                if (dev != null && dev.startsWith("pad:ff00")) ctl.put("port1_device", "auto");
            }
            boolean perf = p.getBoolean("g_perf", false);
            put(ini, "video", "target_fps", String.valueOf(effectiveFps(p)));
            put(ini, "video", "unlimited_fps", "false");
            put(ini, "video", "match_display_refresh", "false");
            put(ini, "video", "resolution_scale", String.valueOf(effectiveRes(p)));
            put(ini, "video", "msaa", String.valueOf(effectiveMsaa(p)));
            put(ini, "video", "vsync", (perf || p.getBoolean("g_vsync", true)) ? "true" : "false");
            // Sharp Picture: without the console's vertical TV filter, which only blurs at 2x and above.
            boolean sharp = p.getBoolean("g_sharp", true);
            put(ini, "rendering", "display_copy_filter", sharp ? "false" : "true");
            put(ini, "rendering", "texture_copy_filter", sharp ? "false" : "true");
            // Phone rendering: the PC build's hardware-accuracy passes change the picture by about one
            // colour level (invisible on a phone screen) but cost GPU time every frame, which at 60/120 fps
            // (2-4 drawn frames per game frame) is heat. gpu_keep_warm feeds filler GPU work over menus so a
            // desktop card keeps its clocks; on a phone that is only heat.
            put(ini, "rendering", "gpu_keep_warm", "false");
            put(ini, "rendering", "integer_tev", "false");
            put(ini, "rendering", "efb_color_depth", "false");
            put(ini, "rendering", "efb_blend_6bit", "false");
            put(ini, "rendering", "early_z_alpha_depth", "false");
            put(ini, "rendering", "shadow_any_distance", "false");
            // More engine defaults that cost GPU/CPU every frame for little on a phone screen:
            // the shadow map at the console's own size instead of the 2x+ internal resolution,
            // GPU copies kept on the GPU (no read-back into memory each frame),
            // and the PC-only lighting and CRT effects explicitly off.
            put(ini, "rendering", "shadow_render_resolution", "false");
            put(ini, "rendering", "efb_copy_to_ram", "false");
            put(ini, "enhanced_lighting", "enabled", "false");
            put(ini, "crt", "enabled", "false");
            put(ini, "video", "aspect", String.valueOf(p.getInt("g_aspect", DEF_ASPECT)));
            put(ini, "video", "hud_keep_4x3", p.getBoolean("g_hud43", true) ? "true" : "false");
            put(ini, "audio", "master_volume", String.valueOf(p.getFloat("a_master", 1f)));
            put(ini, "audio", "music_volume", String.valueOf(p.getFloat("a_music", 1f)));
            put(ini, "audio", "sfx_volume", String.valueOf(p.getFloat("a_sfx", 1f)));
            put(ini, "audio", "reverb", (!perf && p.getBoolean("a_reverb", true)) ? "true" : "false");
            put(ini, "enhancements", "show_fps", p.getBoolean("g_showfps", false) ? "true" : "false");
            put(ini, "gameplay", "damage_scale", p.getBoolean("cheat_no_damage", false) ? "0.0" : "1.0");
            put(ini, "gameplay", "health_scale", p.getBoolean("cheat_health", false) ? "5.0" : "1.0");
            // Fast Boo Capture: the engine has no Boo-only switch, but its Poltergust strength drains every captured
            // ghost (Boos included) and ghost health covers them too, so it uses both at their strongest.
            boolean fastBoo = p.getBoolean("cheat_fast_boo", false);
            put(ini, "gameplay", "ghost_health_scale", (fastBoo || p.getBoolean("cheat_weak_ghosts", false)) ? "0.25" : "1.0");
            put(ini, "gameplay", "vacuum_strength", (fastBoo || p.getBoolean("cheat_vacuum", false)) ? "4.0" : "1.0");
            put(ini, "gameplay", "always_ride", p.getBoolean("cheat_ride", false) ? "true" : "false");
            put(ini, "gameplay", "mirror_world", p.getBoolean("cheat_mirror", false) ? "true" : "false");
            put(ini, "enhancements", "skip_cutscenes", p.getBoolean("cheat_skip", false) ? "true" : "false");

            File tmp = new File(dir, "settings.ini.tmp");
            try (FileWriter w = new FileWriter(tmp)) {
                for (Map.Entry<String, LinkedHashMap<String, String>> s : ini.entrySet()) {
                    if (!s.getKey().isEmpty()) w.write("[" + s.getKey() + "]\n");
                    for (Map.Entry<String, String> kv : s.getValue().entrySet())
                        w.write(kv.getKey() + "=" + kv.getValue() + "\n");
                }
            }
            f.delete();
            tmp.renameTo(f);
        } catch (Throwable ignored) {
        }
    }
}
