package com.bedwarsqol.gui.render;

import java.util.Locale;

/**
 * The user-selectable GUI <em>accent</em>. The settings sheet shades every accent surface from it in OKLab
 * ({@code gui/sheet/SheetColors}); the HUD uses it on purpose in two places only: Session Stats headers and a
 * pressed Keystrokes cap, so they follow the colour picked in the menu. {@link Theme} stays the neutral grayscale
 * source the custom scoreboard and styled tab list draw from. Keep this free of {@code net.minecraft} / LWJGL
 * imports so it stays pure Java, importable from {@code config}, and unit-testable.
 */
public final class GuiTheme {

    private GuiTheme() {
    }

    /**
     * The selectable GUI accents, in the settings sheet's swatch order: twelve hue columns in a pale, a vivid and a
     * deep row, then three neutrals. Each carries a stable lowercase {@code token} used for persistence
     * ({@code ClientSettings.guiAccent}), a sentence-case label and a base ARGB. Orange is the default; the original
     * ten keep their tokens and colours.
     */
    public enum Accent {
        // pale: one lightness (OKLab 0.885), the vivid hue at 45% of its chroma
        PALE_RED("pale-red", "Pale Red", 0xFFFFCAC7),
        PALE_ORANGE("pale-orange", "Pale Orange", 0xFFFFCDB0),
        PALE_YELLOW("pale-yellow", "Pale Yellow", 0xFFECD8A4),
        PALE_LIME("pale-lime", "Pale Lime", 0xFFC5E6A9),
        PALE_GREEN("pale-green", "Pale Green", 0xFFB7E8B9),
        PALE_TEAL("pale-teal", "Pale Teal", 0xFFADE7D8),
        PALE_AQUA("pale-aqua", "Pale Aqua", 0xFFB0E4EB),
        PALE_SKY("pale-sky", "Pale Sky", 0xFFB3E1FA),
        PALE_BLUE("pale-blue", "Pale Blue", 0xFFC0DCFF),
        PALE_PURPLE("pale-purple", "Pale Purple", 0xFFDAD2FF),
        PALE_MAGENTA("pale-magenta", "Pale Magenta", 0xFFF6C6FD),
        PALE_PINK("pale-pink", "Pale Pink", 0xFFFDC6E5),
        // vivid: the original ten, plus teal, sky and magenta in the widest hue gaps
        RED("red", "Red", 0xFFE5484D),
        ORANGE("orange", "Orange", 0xFFF0770F),
        YELLOW("yellow", "Yellow", 0xFFF5C63A),
        LIME("lime", "Lime", 0xFF9BE34A),
        GREEN("green", "Green", 0xFF3FB950),
        TEAL("teal", "Teal", 0xFF02CAAB),
        AQUA("aqua", "Aqua", 0xFF3FD0E0),
        SKY("sky", "Sky", 0xFF37B2E8),
        BLUE("blue", "Blue", 0xFF4A90E2),
        PURPLE("purple", "Purple", 0xFFA78BFA),
        MAGENTA("magenta", "Magenta", 0xFFCF66E0),
        PINK("pink", "Pink", 0xFFF27EC4),
        // deep: one luminance (0.14), so light text reads on them at 4.5:1 and their fills show on the sheet at 3:1
        DEEP_RED("deep-red", "Deep Red", 0xFFC33038),
        DEEP_ORANGE("deep-orange", "Deep Orange", 0xFFA75001),
        DEEP_YELLOW("deep-yellow", "Deep Yellow", 0xFF816503),
        DEEP_LIME("deep-lime", "Deep Lime", 0xFF477401),
        DEEP_GREEN("deep-green", "Deep Green", 0xFF017922),
        DEEP_TEAL("deep-teal", "Deep Teal", 0xFF047664),
        DEEP_AQUA("deep-aqua", "Deep Aqua", 0xFF03737E),
        DEEP_SKY("deep-sky", "Deep Sky", 0xFF007099),
        DEEP_BLUE("deep-blue", "Deep Blue", 0xFF2769B4),
        DEEP_PURPLE("deep-purple", "Deep Purple", 0xFF7357BA),
        DEEP_MAGENTA("deep-magenta", "Deep Magenta", 0xFFA03EAF),
        DEEP_PINK("deep-pink", "Deep Pink", 0xFFA94283),
        // neutrals
        WHITE("white", "White", 0xFFE6E6E6),
        SILVER("silver", "Silver", 0xFFB8B8B8),
        GREY("grey", "Grey", 0xFF8A8A8A);

        private final String token;
        private final String label;
        private final int base;

        Accent(String token, String label, int base) {
            this.token = token;
            this.label = label;
            this.base = base;
        }

        /** Stable lowercase persistence token, e.g. {@code "orange"}. */
        public String token() {
            return token;
        }

        /** Sentence-case name, e.g. {@code "Orange"}. */
        public String label() {
            return label;
        }

        /** Full-opacity base accent ARGB. */
        public int base() {
            return base;
        }
    }

    /**
     * Whether near-black text reads better than near-white on {@code rgb}: the higher WCAG contrast of the two.
     * Pale and vivid accents take dark text, the deep ones light text.
     */
    public static boolean darkTextOn(int rgb) {
        return contrast(rgb, 0x121212) >= contrast(rgb, 0xEDEDED);
    }

    /** WCAG contrast ratio between two RGB colours, 1 to 21. */
    public static double contrast(int a, int b) {
        double ya = luminance(a), yb = luminance(b);
        return (Math.max(ya, yb) + 0.05) / (Math.min(ya, yb) + 0.05);
    }

    private static double luminance(int rgb) {
        return 0.2126 * channel((rgb >> 16) & 255) + 0.7152 * channel((rgb >> 8) & 255) + 0.0722 * channel(rgb & 255);
    }

    private static double channel(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /**
     * Coerce an arbitrary token to a known accent token. Trims + lowercases; null, empty, and unknown all
     * collapse to {@code "orange"} (the default accent).
     */
    public static String normalizeToken(String token) {
        return fromToken(token).token();
    }

    /** Font tokens: the bundled Inter (the default) or Minecraft's own font, for the settings menu and the HUD. */
    public static final String FONT_MODERN = "modern";
    public static final String FONT_MINECRAFT = "minecraft";

    /** Coerce a font token: anything but {@link #FONT_MINECRAFT} is {@link #FONT_MODERN}. */
    public static String normalizeFont(String token) {
        return FONT_MINECRAFT.equals(token) ? FONT_MINECRAFT : FONT_MODERN;
    }

    /**
     * Resolve a token to its {@link Accent}. Trims + lowercases; null, empty, and unknown all resolve to
     * {@link Accent#ORANGE}.
     */
    public static Accent fromToken(String token) {
        if (token != null) {
            String t = token.trim().toLowerCase(Locale.US);
            for (Accent a : Accent.values()) {
                if (a.token.equals(t)) return a;
            }
        }
        return Accent.ORANGE;
    }
}
