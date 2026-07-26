package com.bedwarsqol.feature;

import com.bedwarsqol.stats.SeraphTag;

/**
 * Pure Seraph chat-line formatters (Minecraft-free) so unit tests can pin option-A strings without
 * loading client classes. Ignores subtype and threatLevel.
 */
public final class SeraphAlertFormat {

    private SeraphAlertFormat() {}

    /**
     * Seraph ordinary chat line, or {@code null} for safelist / unknown kinds.
     */
    public static String formatOrdinary(String nameColor, String name, SeraphTag tag) {
        if (tag == null) return null;
        String clause;
        if ("blacklist".equals(tag.kind)) {
            clause = tag.verified ? "is blacklisted (verified)" : "is blacklisted";
        } else if ("bot".equals(tag.kind)) {
            clause = "is a bot";
        } else if ("annoy".equals(tag.kind)) {
            clause = "is on the annoylist";
        } else {
            return null; // safelist + unknown
        }
        String tc = nameColor == null || nameColor.isEmpty() ? "§e" : nameColor;
        return "§8[§6Seraph§8] " + tc + name + " §7" + clause;
    }
}
