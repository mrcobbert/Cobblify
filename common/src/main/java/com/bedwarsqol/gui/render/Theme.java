package com.bedwarsqol.gui.render;

/**
 * The single source of truth for the mod's GUI look — the minimal grayscale panel palette used by the
 * settings screen ({@code gui/SettingsGui}). HUD elements that should "match the GUI exactly" (the
 * custom scoreboard and styled tab list) draw their background, border, dividers and text straight
 * from these constants, so the whole mod stays visually consistent and a palette tweak lands everywhere
 * at once. Grayscale by design: depth comes from luminance + hairlines, never colour.
 */
public final class Theme {

    private Theme() {
    }

    /** Panel body fill (near-black, slightly translucent over the world). Used by the settings screen. */
    public static final int PANEL = 0xF01A1A1A;
    /** More transparent panel fill for HUD overlays (scoreboard, tab list) so gameplay shows through. */
    public static final int PANEL_HUD = 0xB01A1A1A;
    /** Bright whiteish keyline around the panel. */
    public static final int PANEL_BORDER = 0xCCFFFFFF;
    /** Faint internal divider line (8% white). */
    public static final int DIVIDER = 0x14FFFFFF;
    /** Even fainter row separator / zebra stripe (5% white). */
    public static final int SEPARATOR = 0x0DFFFFFF;
    /** Subtle row highlight (6% white). */
    public static final int ROW_HOVER = 0x0FFFFFFF;

    /** Text ramp: high / medium / low contrast (labels, secondary, hints). */
    public static final int TEXT_HI = 0xFFEDEDED;
    public static final int TEXT_MID = 0xFFB0B0B0;
    public static final int TEXT_LO = 0xFF7A7A7A;

    /** Panel corner radius (px) and the hairline border/outline thickness. Kept tight for a sleek,
     *  near-square modern look (only the settings-GUI panel consumes this). */
    public static final int PANEL_R = 3;
    /** GUI module-card corner radius (px). HUD background panels reuse this so they read as the same
     *  family as the settings cards. */
    public static final int CARD_R = 2;
    public static final float BORDER_THICK = 0.75f;
}
