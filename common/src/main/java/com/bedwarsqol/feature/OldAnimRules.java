package com.bedwarsqol.feature;

/**
 * 1.7 Animations, a Visuals module of the Ornithe build: off by default, with each of its rows (Block Hit, Item
 * Position, Smooth Sneak, Hearts, Red Armor) on, so switching the module on gives every 1.7 animation.
 */
public final class OldAnimRules {

    public static final boolean DEFAULT_ENABLED = false;
    public static final boolean DEFAULT_BLOCK_HIT = true;
    public static final boolean DEFAULT_ITEM_POSITION = true;
    public static final boolean DEFAULT_SMOOTH_SNEAK = true;
    public static final boolean DEFAULT_HEARTS = true;
    public static final boolean DEFAULT_RED_ARMOR = true;

    private OldAnimRules() {
    }

    /** Whether a row applies: only while its module is on. */
    public static boolean effective(boolean module, boolean row) {
        return module && row;
    }
}
