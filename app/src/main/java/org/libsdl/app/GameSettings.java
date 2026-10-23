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

    /** 0 = no limit; otherwise the frame rate cap set while the phone is running hot (Performance Mode only). */
    static volatile int thermalCap = 0;

    /** Frame rate the game really runs at: Performance Mode caps it at 60, and a hot phone lowers it further. */
    static int effectiveFps(SharedPreferences p) {
        int fps = p.getInt("g_fps", DEF_FPS);
        if (p.getBoolean("g_perf", false)) {
            fps = Math.min(fps, 60);
            if (thermalCap > 0) fps = Math.min(fps, thermalCap);
        }
        return fps;
    }

    private static File root(Context c) { return new File(c.getFilesDir(), "xdg"); }

    /** The game finds settings.ini under $XDG_CONFIG_HOME; call before the native library starts. */
    static void applyEnv(Context c) {
        try {
            root(c).mkdirs();
            Os.setenv("XDG_CONFIG_HOME", root(c).getAbsolutePath(), true);
        } catch (Throwable ignored) {
        }
    }

    private static void put(Map<String, LinkedHashMap<String, String>> ini, String sec, String key, String val) {
        LinkedHashMap<String, String> m = ini.get(sec);
        if (m == null) { m = new LinkedHashMap<>(); ini.put(sec, m); }
        m.put(key, val);
    }

    static void write(Context c, SharedPreferences p) {
        try {
            File dir = new File(root(c), "Luigi's Mansion Recompiled");
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
            boolean perf = p.getBoolean("g_perf", false);
            put(ini, "video", "target_fps", String.valueOf(effectiveFps(p)));
            put(ini, "video", "unlimited_fps", "false");
            put(ini, "video", "match_display_refresh", "false");
            put(ini, "video", "resolution_scale", String.valueOf(perf ? 1 : p.getInt("g_res", DEF_RES)));
            put(ini, "video", "msaa", String.valueOf(perf ? 1 : p.getInt("g_msaa", DEF_MSAA)));
            put(ini, "video", "vsync", (perf || p.getBoolean("g_vsync", true)) ? "true" : "false");
            put(ini, "video", "aspect", String.valueOf(p.getInt("g_aspect", DEF_ASPECT)));
            put(ini, "video", "hud_keep_4x3", p.getBoolean("g_hud43", true) ? "true" : "false");
            put(ini, "audio", "master_volume", String.valueOf(p.getFloat("a_master", 1f)));
            put(ini, "audio", "music_volume", String.valueOf(p.getFloat("a_music", 1f)));
            put(ini, "audio", "sfx_volume", String.valueOf(p.getFloat("a_sfx", 1f)));
            put(ini, "audio", "reverb", (!perf && p.getBoolean("a_reverb", true)) ? "true" : "false");
            put(ini, "enhancements", "show_fps", p.getBoolean("g_showfps", false) ? "true" : "false");
            put(ini, "gameplay", "damage_scale", p.getBoolean("cheat_no_damage", false) ? "0.0" : "1.0");
            put(ini, "gameplay", "health_scale", p.getBoolean("cheat_health", false) ? "5.0" : "1.0");
            put(ini, "gameplay", "ghost_health_scale", p.getBoolean("cheat_weak_ghosts", false) ? "0.25" : "1.0");
            put(ini, "gameplay", "vacuum_strength", p.getBoolean("cheat_vacuum", false) ? "4.0" : "1.0");
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
