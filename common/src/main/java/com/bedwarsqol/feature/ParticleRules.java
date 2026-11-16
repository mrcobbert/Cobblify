package com.bedwarsqol.feature;

/**
 * Particles, a Visuals module of the Ornithe build: off by default, with Always Crit and Hide Own Potion on, Hide
 * Block Break off and a Multiplier of 2x for crit and sharpness particles.
 */
public final class ParticleRules {

    public static final boolean DEFAULT_ENABLED = false;
    public static final boolean DEFAULT_ALWAYS_CRIT = true;
    public static final boolean DEFAULT_HIDE_OWN_POTION = true;
    public static final boolean DEFAULT_HIDE_BLOCK_BREAK = false;
    public static final int MIN_MULTIPLIER = 1;
    public static final int MAX_MULTIPLIER = 3;
    public static final int DEFAULT_MULTIPLIER = 2;

    private ParticleRules() {
    }

    /** Whether a row applies: only while its module is on. */
    public static boolean effective(boolean module, boolean row) {
        return module && row;
    }

    /** A saved Multiplier held to 1..3. */
    public static int clampMultiplier(int multiplier) {
        return multiplier < MIN_MULTIPLIER ? MIN_MULTIPLIER : Math.min(MAX_MULTIPLIER, multiplier);
    }
}
