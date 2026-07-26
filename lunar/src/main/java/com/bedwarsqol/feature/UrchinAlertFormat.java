package com.bedwarsqol.feature;

import com.bedwarsqol.stats.UrchinTag;

/**
 * Pure Urchin chat-line formatters (Minecraft-free) so unit tests can pin option-A strings without
 * loading client classes.
 */
public final class UrchinAlertFormat {

    private UrchinAlertFormat() {}

    /**
     * Ordinary Urchin chat line, or {@code null} when the tag is not a cheater type (sniper/caution/
     * unknown — mute chat; badges unchanged).
     */
    public static String formatOrdinary(String nameColor, String name, UrchinTag tag) {
        String clause = cheaterClause(tag);
        if (clause == null) return null;
        String tc = nameColor == null || nameColor.isEmpty() ? "§e" : nameColor;
        return "§8[§6Urchin§8] " + tc + name + " §7is a " + clause;
    }

    /**
     * Fusion Urchin chat line (red prefix), or {@code null} when the tag is not a cheater type.
     * Highlight gating is separate — callers still mark fusion highlight for any displayable tag +
     * live AC.
     */
    public static String formatFusion(String nameColor, String name, UrchinTag tag) {
        String clause = cheaterClause(tag);
        if (clause == null) return null;
        String tc = nameColor == null || nameColor.isEmpty() ? "§e" : nameColor;
        return "§8[§cUrchin§8] " + tc + name + " §7is a " + clause + " §7+ §clive AC flags";
    }

    private static String cheaterClause(UrchinTag tag) {
        if (tag == null || !tag.isCheaterType()) return null;
        if ("confirmed_cheater".equals(tag.type)) return "confirmed cheater";
        if ("blatant_cheater".equals(tag.type)) return "blatant cheater";
        if ("closet_cheater".equals(tag.type)) return "closet cheater";
        return null;
    }
}
