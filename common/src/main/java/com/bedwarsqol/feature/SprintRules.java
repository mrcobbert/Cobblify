package com.bedwarsqol.feature;

/**
 * Toggle Sprint, an option of the Ornithe build (Lunar has its own). One press of its key (default G)
 * toggles sprint; while toggled, the build holds vanilla's sprint key for the player, so sprinting
 * starts and stops exactly as if the player held it (forward, enough food, not using an item).
 */
public final class SprintRules {

    public static final boolean DEFAULT_TOGGLE_SPRINT_ENABLED = true;
    /** LWJGL {@code Keyboard.KEY_G}. */
    public static final int DEFAULT_TOGGLE_SPRINT_KEY = 34;
    /** The vanilla key binding's name; options.txt stores it as {@code key_Toggle Sprint}. */
    public static final String TOGGLE_SPRINT_KEY_NAME = "Toggle Sprint";

    private SprintRules() {
    }

    /** The toggle after this tick: a press with no screen open flips it; turning the option off clears it. */
    public static boolean toggled(boolean toggled, boolean enabled, boolean pressed, boolean screenOpen) {
        if (!enabled) return false;
        return pressed && !screenOpen ? !toggled : toggled;
    }

    /** Whether to hold the sprint key this tick: toggled, in a world, no screen open. */
    public static boolean holdSprint(boolean toggled, boolean inWorld, boolean screenOpen) {
        return toggled && inWorld && !screenOpen;
    }
}
