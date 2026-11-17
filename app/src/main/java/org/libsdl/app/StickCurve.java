package org.libsdl.app;

import android.content.SharedPreferences;

/**
 * Camera (right) stick response for physical controllers, applied before SDL sees the value.
 * Full tilt still reaches full speed, but small and medium tilts move the camera much more gently
 * (a curve instead of a straight line), which fixes the "harsh" feel of Xbox-style sticks.
 *   0 Smooth (default)   1 Balanced   2 Original (no change)
 */
final class StickCurve {
    private StickCurve() { }

    static final String[] NAMES = {"Smooth", "Balanced", "Original"};
    private static final float[] GAMMA = {1.9f, 1.45f, 1f};
    private static final float[] DEAD = {0.10f, 0.08f, 0f};
    private static final float[] SPEED = {0.85f, 0.95f, 1f};

    static volatile int mode = 0;
    static volatile float speedScale = 1f;  // Camera Stick Speed row: 0.5 .. 1.0

    static void load(SharedPreferences p) {
        mode = Math.max(0, Math.min(2, p.getInt("cam_curve", 0)));
        speedScale = Math.max(0.5f, Math.min(1f, p.getFloat("cam_speed", 1f)));
    }

    /** v in -1..1 for one camera-stick axis. */
    static float apply(float v) {
        int m = mode;
        if (m == 2 && speedScale >= 0.999f) return v;
        float a = Math.abs(v), dz = DEAD[m];
        if (a <= dz) return 0f;
        float t = Math.min(1f, (a - dz) / (1f - dz));
        float out = (float) Math.pow(t, GAMMA[m]) * SPEED[m] * speedScale;
        return v < 0 ? -out : out;
    }
}
