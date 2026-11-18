package org.libsdl.app;

/**
 * Vertical aim with the C-stick: stick up looks up, stick down looks down.
 * Tested on the Odin 3 (C-stick = axes Z/RZ): both the base game and Premium Deluxe need the stick's Y
 * flipped for that. The edition is still kept in case one of them needs its own direction later.
 */
final class DlcInput {
    private DlcInput() { }

    private static volatile boolean premiumDeluxe;

    static void selectEdition(boolean premium) {
        premiumDeluxe = premium;
    }

    /** Apply once, at the physical or touch input boundary, to the right stick Y only. */
    static float rightStickY(float value) {
        return -value;
    }
}
