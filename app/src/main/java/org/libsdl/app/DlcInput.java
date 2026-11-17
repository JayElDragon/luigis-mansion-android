package org.libsdl.app;

/**
 * Vertical aim with the C-stick: stick up looks up, stick down looks down, in both editions.
 * Tested on the Odin 3 (C-stick = axes Z/RZ): the base game reads the stick's Y as it comes,
 * Premium Deluxe reads it the other way round, so only Premium Deluxe is flipped.
 */
final class DlcInput {
    private DlcInput() { }

    private static volatile boolean premiumDeluxe;

    static void selectEdition(boolean premium) {
        premiumDeluxe = premium;
    }

    /** Apply once, at the physical or touch input boundary, to the right stick Y only. */
    static float rightStickY(float value) {
        return premiumDeluxe ? -value : value;
    }
}
