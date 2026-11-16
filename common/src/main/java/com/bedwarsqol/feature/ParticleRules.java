package com.bedwarsqol.feature;

/**
 * Particles, a Visuals module of the Ornithe build: off by default, with Always Crit and Hide Own Potion on, Hide
 * Block Break off and a Multiplier of 2x for crit and sharpness particles.
 *
 * <p>Every decision the spawn-time hooks make. Each {@code on} argument is the row's
 * {@link #effective effective} state (module and row both on; the Multiplier has no switch, so its
 * {@code on} is the module); with it false every rule returns vanilla's answer.
 */
public final class ParticleRules {

    public static final boolean DEFAULT_ENABLED = false;
    public static final boolean DEFAULT_ALWAYS_CRIT = true;
    public static final boolean DEFAULT_HIDE_OWN_POTION = true;
    public static final boolean DEFAULT_HIDE_BLOCK_BREAK = false;
    public static final int MIN_MULTIPLIER = 1;
    public static final int MAX_MULTIPLIER = 3;
    public static final int DEFAULT_MULTIPLIER = 2;

    /** Vanilla 1.8.9 {@code EnumParticleTypes.CRIT} particle id ("crit"). */
    public static final int CRIT_ID = 9;
    /** Vanilla 1.8.9 {@code EnumParticleTypes.CRIT_MAGIC} particle id ("magicCrit", the sharpness burst). */
    public static final int CRIT_MAGIC_ID = 10;

    /** {@link #extraHitParticles} bit: add the crit burst. */
    public static final int EXTRA_CRIT = 1;
    /** {@link #extraHitParticles} bit: add the sharpness (magic crit) burst. */
    public static final int EXTRA_MAGIC = 2;

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

    /**
     * Always Crit: which bursts to add on a hit you land, given which ones vanilla already showed for it.
     * A burst vanilla showed is never added again. Off: none.
     */
    public static int extraHitParticles(boolean vanillaCrit, boolean vanillaMagic, boolean on) {
        if (!on) return 0;
        return (vanillaCrit ? 0 : EXTRA_CRIT) | (vanillaMagic ? 0 : EXTRA_MAGIC);
    }

    /** Multiplier: how many of a spawned particle to make; the Multiplier for crit and sharpness, else 1. Off: 1. */
    public static int copies(int particleId, int multiplier, boolean on) {
        if (!on || (particleId != CRIT_ID && particleId != CRIT_MAGIC_ID)) return 1;
        return clampMultiplier(multiplier);
    }

    /** Hide Own Potion: skip a potion swirl only when it is yours and you are in first person. */
    public static boolean hideOwnPotion(boolean isLocalPlayer, boolean firstPerson, boolean on) {
        return on && isLocalPlayer && firstPerson;
    }

    /** Hide Block Break: skip every block-destroy and block-hit particle while on. */
    public static boolean hideBlockBreak(boolean on) {
        return on;
    }
}
