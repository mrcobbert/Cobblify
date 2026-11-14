package com.bedwarsqol.hud;

import com.bedwarsqol.gui.StatRowSegments;

/**
 * Colours of a HUD stat row's runs ({@link StatRowSegments}): values white, {@code " / "} dark gray, labels in the
 * colour the module asks for (Map Info: the accent; Session Stats: {@link #LABEL_NEUTRAL}).
 */
public final class StatRowColors {

    public static final int LABEL_NEUTRAL = 0xFFAAAAAA;
    public static final int VALUE = 0xFFFFFFFF;
    public static final int SLASH = 0xFF666666;

    private StatRowColors() {
    }

    public static int of(StatRowSegments.Kind kind, int labelColor) {
        if (kind == StatRowSegments.Kind.VALUE) return VALUE;
        if (kind == StatRowSegments.Kind.SEPARATOR) return SLASH;
        return labelColor;
    }
}
