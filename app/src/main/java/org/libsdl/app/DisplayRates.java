package org.libsdl.app;

import android.content.Context;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

/**
 * The screen refresh rates this phone offers, and the frame rates that run smoothly on them.
 * A frame rate is smooth when the screen can refresh at exactly that rate (each frame shown once,
 * paced by VSync). 30 fps is the game's own rate (shown every other refresh) and always smooth.
 */
final class DisplayRates {
    private DisplayRates() { }

    /** Distinct refresh rates (rounded) at the current resolution, lowest first. */
    static List<Integer> refreshRates(Display d) {
        List<Integer> out = new ArrayList<>();
        if (d == null) return out;
        Display.Mode cur = d.getMode();
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
            int hz = Math.round(m.getRefreshRate());
            if (!out.contains(hz)) out.add(hz);
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Frame-rate choices for Options: 30, 60 and every faster rate the screen has (90, 120, 144...). */
    static int[] fpsChoices(Context c) {
        List<Integer> l = new ArrayList<>();
        l.add(30);
        l.add(60);
        Display d = null;
        try { d = c.getDisplay(); } catch (Throwable ignored) { }
        List<Integer> rates = refreshRates(d);
        for (int hz : rates) if (hz > 61 && hz <= 240 && !l.contains(hz)) l.add(hz);
        // Screen not readable: keep the old choices. A screen that only does 60 Hz gets no 120 fps
        // (before, it was offered whenever no faster rate was found, and could only run at 60 anyway).
        if (rates.isEmpty()) l.add(120);
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    /** Refresh rate to ask for: exactly the frame rate when the screen has it (30 fps -> 60 Hz). */
    static float wantedHz(int fps) { return Math.max(60f, fps); }

    /** Display mode id for that rate: the exact rate, else the nearest faster one, else the fastest. 0 = none. */
    static int pickMode(Display d, float want) {
        if (d == null) return 0;
        Display.Mode cur = d.getMode();
        float bestOk = Float.MAX_VALUE, maxHz = 0;
        int idOk = 0, idMax = 0;
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
            float hz = m.getRefreshRate();
            if (hz >= want - 0.5f && hz < bestOk) { bestOk = hz; idOk = m.getModeId(); }
            if (hz > maxHz) { maxHz = hz; idMax = m.getModeId(); }
        }
        return idOk != 0 ? idOk : idMax;
    }
}
