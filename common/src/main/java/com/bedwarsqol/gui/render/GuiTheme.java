package com.bedwarsqol.gui.render;

import java.util.Locale;

/**
 * GUI-only palette for the settings screen ({@code gui/SettingsGui}) redesign — a warm near-black
 * surface treatment plus a single, user-selectable <em>accent</em>. This class exists precisely so the
 * accent can never reach the HUD: {@link Theme} stays the neutral grayscale source of truth that the
 * custom scoreboard and styled tab list draw from, while everything here is consumed only by the
 * settings GUI. Keep it free of {@code net.minecraft} / LWJGL imports so it stays pure Java, importable
 * from {@code config}, and unit-testable.
 *
 * <p>Two families of colour live here:
 * <ul>
 *   <li>The {@link Accent} enum: the four selectable accents, each carrying a stable lowercase
 *       persistence token and a base ARGB. Every accent-sensitive GUI element (active-tab pill,
 *       toggle-on switch, card border, selected dropdown outline, selected-option indicator)
 *       resolves from the same {@link Accent} instance, so there is one source of truth per frame.</li>
 *   <li>The warm surface/text/divider constants below: the settings GUI's near-black panel, header,
 *       card fills/borders, dividers, text ramp, and neutral (off/disabled) switch colours. These are
 *       the current private {@code SettingsGui} greys nudged slightly warm (toward a brown/orange
 *       black) per the reference; Phase 2 will point {@code SettingsGui} at these names.</li>
 * </ul>
 *
 * <p><strong>All numeric values here are the tuning surface.</strong> The accent base hexes and warm
 * surface/text values were calibrated in Phase 3 against the reference screenshots; names are stable, so
 * only the hexes should move in any further in-game tuning.
 */
public final class GuiTheme {

    private GuiTheme() {
    }

    /**
     * The four selectable GUI accents. Each carries a stable lowercase {@code token} used for
     * persistence ({@code ClientSettings.guiAccent}) and a base ARGB. Accent-derived colours are exposed
     * as accessors so a Phase-3 tune touches only this enum.
     *
     * <p>Base hexes were tuned in Phase 3 against {@code reference-subsettings.jpeg}: ORANGE is sampled
     * from the reference toggle's warm, slightly-red orange; RED/BLUE/GREEN are kept as sensible siblings.
     */
    public enum Accent {
        ORANGE("orange", 0xFFF0770F),
        RED("red", 0xFFE5484D),
        BLUE("blue", 0xFF4A90E2),
        GREEN("green", 0xFF3FB950);

        private final String token;
        private final int base;

        Accent(String token, int base) {
            this.token = token;
            this.base = base;
        }

        /** Stable lowercase persistence token ({@code "orange"}/{@code "red"}/{@code "blue"}/{@code "green"}). */
        public String token() {
            return token;
        }

        /** Full-opacity base accent ARGB. All accessors below derive from this. */
        public int base() {
            return base;
        }

        /** Solid fill for the active top-tab pill (the base accent at full opacity). */
        public int pillFill() {
            return base;
        }

        /** Track colour of a toggle switch when it is ON. */
        public int toggleOnTrack() {
            return base;
        }

        /** Knob colour of a toggle switch when it is ON — a bright neutral that reads over the accent track. */
        public int toggleOnKnob() {
            return 0xFFFFFFFF;
        }

        /** Border of a settings card (the accent at reduced alpha so it reads as a keyline). */
        public int enabledCardBorder() {
            return withAlpha(base, 0xB0);
        }

        /** Accent outline around a selected/open dropdown pill. */
        public int dropdownOutline() {
            return withAlpha(base, 0xCC);
        }

        /** Small filled swatch/indicator marking the currently selected option. */
        public int selectedIndicator() {
            return base;
        }
    }

    /**
     * Coerce an arbitrary token to a known accent token. Trims + lowercases; null, empty, and unknown
     * all collapse to {@code "orange"} (the default accent). The only values this can return are
     * {@code "orange"}, {@code "red"}, {@code "blue"}, {@code "green"}.
     */
    public static String normalizeToken(String token) {
        if (token == null) {
            return "orange";
        }
        String t = token.trim().toLowerCase(Locale.US);
        switch (t) {
            case "orange":
            case "red":
            case "blue":
            case "green":
                return t;
            default:
                return "orange";
        }
    }

    /**
     * Resolve a token to its {@link Accent}. Trims + lowercases; null, empty, and unknown all resolve to
     * {@link Accent#ORANGE}.
     */
    public static Accent fromToken(String token) {
        switch (normalizeToken(token)) {
            case "red":
                return Accent.RED;
            case "blue":
                return Accent.BLUE;
            case "green":
                return Accent.GREEN;
            default:
                return Accent.ORANGE;
        }
    }

    /** Return {@code argb} with its alpha byte replaced by {@code alpha} (0–255). */
    public static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    // -------------------------------------------------------------------------------------------------
    // Warm surface / text / neutral-control palette (GUI only). Tuned in Phase 3 against
    // reference-subsettings.jpeg: a near-black surface with only a subtle warmth (R >= G >= B) rather than
    // the browner first pass, card fills nearly opaque so they read solid like the reference, and the text
    // ramp / neutral switch nudged toward the reference's white labels + medium-grey secondary text.
    // -------------------------------------------------------------------------------------------------

    /** Panel body fill: warm near-black, slightly translucent so the frosted blur still reads. */
    public static final int PANEL = 0xC0171410;
    /** Top header band fill: a touch darker/warmer than the panel. */
    public static final int HEADER_BG = 0xCC120F0C;

    /** Module card fill when the module is OFF/disabled (near-opaque so the card reads solid). */
    public static final int CARD_OFF_BG = 0xDE1C1814;
    /** Module card fill when the module is ON (a hair brighter/warmer than the off fill). */
    public static final int CARD_ON_BG = 0xDE241C12;
    /** Neutral keyline around an OFF card (barely-there, as in the reference). */
    public static final int CARD_OFF_BORDER = 0x18FFF2E4;
    /** Neutral fallback keyline around an ON card. The live enabled-card border comes from the accent
     *  ({@link Accent#enabledCardBorder()}); this is the neutral reference. */
    public static final int CARD_ON_BORDER = 0x3DFFF2E4;

    /** Faint internal divider line. */
    public static final int DIVIDER = 0x18FFF2E4;
    /** Even fainter row separator / zebra stripe. */
    public static final int SEPARATOR = 0x0DFFF2E4;

    /** Text ramp: high (near-white labels) / medium (muted secondary) / low (descriptions, placeholders),
     *  each nudged slightly warm to match the reference. */
    public static final int TEXT_HI = 0xFFF2EEE7;
    public static final int TEXT_MID = 0xFFA9A199;
    public static final int TEXT_LO = 0xFF8A827A;

    /** Toggle switch track/knob when OFF (neutral warm; the ON state comes from the accent). */
    public static final int TRACK_OFF = 0xFF383029;
    public static final int KNOB_OFF = 0xFFC4BCB2;
    /** Toggle switch track/knob when DISABLED (its parent module is off). */
    public static final int TRACK_DISABLED = 0xFF272320;
    public static final int KNOB_DISABLED = 0xFF5C544A;
}
