package org.libsdl.app;

import android.util.Log;

/**
 * Options > Cheats > Weaker Boos: every Boo's health is kept at 25 or less, in the base game and Premium Deluxe.
 * The game's live characters are the 128 pointers at 0x803D48A0 (slot 0 = Luigi); a character's type is the
 * word at +0x3C and its health the s16 at +0xFC. Type names come from the game's own type table (0x8033B590,
 * 0x1C bytes per type, name pointer at +0x18; same address in Premium Deluxe) and are read at run time:
 * Boos are the types named "telesa", "telesa1", "btelesa", "bmtelesa" and "dltelesa" (King Boo "kptelesa",
 * Bowser "kopatele" and the cutscene "demotele" keep their health). Checked 4 times a second.
 */
final class WeakerBoos {
    private WeakerBoos() { }

    private static final String TAG = "LMWeakerBoos";
    private static final int SLOTS = 0x803D48A0, SLOT_COUNT = 128;
    private static final int TYPE_TABLE = 0x8033B590, TYPE_SIZE = 0x1C, TYPE_COUNT = 0x29C8 / 0x1C;
    private static final int HEALTH = 25;
    private static final java.util.Set<String> BOO_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "telesa", "telesa1", "btelesa", "bmtelesa", "dltelesa"));

    private static volatile Thread thread;

    static void start() {
        if (thread != null) return;
        Thread t = new Thread(WeakerBoos::run, "WeakerBoos");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    static void stop() {
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    private static boolean inMem1(long a) { return a >= 0x80000000L && a < 0x81800000L; }

    private static String readName(long p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i += 4) {
            long w = GuestMem.nativeRead32((int) (p + i));
            if (w < 0) return null;
            for (int s = 24; s >= 0; s -= 8) {
                int c = (int) ((w >> s) & 0xFF);
                if (c == 0) return sb.toString();
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    private static void run() {
        boolean[] boo = null;
        int capped = 0;
        try {
            while (thread == Thread.currentThread()) {
                Thread.sleep(250);
                if (boo == null) {   // the type table, once the game's memory is there
                    boolean[] b = new boolean[TYPE_COUNT];
                    int found = 0;
                    for (int t = 0; t < TYPE_COUNT; t++) {
                        long p = GuestMem.nativeRead32(TYPE_TABLE + t * TYPE_SIZE + 0x18);
                        if (p < 0) { b = null; break; }
                        if (!inMem1(p)) continue;
                        String n = readName(p);
                        if (n != null && BOO_NAMES.contains(n)) { b[t] = true; found++; }
                    }
                    if (b == null || found == 0) continue;
                    boo = b;
                    Log.i(TAG, "Boo types found: " + found);
                }
                for (int i = 1; i < SLOT_COUNT; i++) {
                    long a = GuestMem.nativeRead32(SLOTS + 4 * i);
                    if (!inMem1(a)) continue;
                    long type = GuestMem.nativeRead32((int) (a + 0x3C));
                    if (type < 0 || type >= TYPE_COUNT || !boo[(int) type]) continue;
                    long w = GuestMem.nativeRead32((int) (a + 0xFC));   // s16 health in the high half
                    if (w < 0) continue;
                    int hp = (short) (w >>> 16);
                    if (hp > HEALTH) {
                        GuestMem.nativeWrite16((int) (a + 0xFC), HEALTH);
                        if (capped++ < 20) Log.i(TAG, "Boo (type " + type + ") health " + hp + " -> " + HEALTH);
                    }
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            Log.w(TAG, "stopped: " + t);
        }
    }
}
