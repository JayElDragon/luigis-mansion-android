package org.libsdl.app;

/**
 * Vertical aim: stick up looks up, stick down looks down, in both editions.
 * The base game reads the right stick's Y the other way round, so it is flipped there;
 * Premium Deluxe already reads it the opposite way from the base game, so it is passed through.
 */
final class DlcInput {
    private DlcInput() { }

    private static volatile boolean premiumDeluxe;

    static void selectEdition(boolean premium) {
        premiumDeluxe = premium;
    }

    /** Apply once, at the physical or touch input boundary, to the right stick Y only. */
    static float rightStickY(float value) {
        return premiumDeluxe ? value : -value;
    }
}
