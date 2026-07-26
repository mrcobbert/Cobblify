package com.bedwarsqol.feature;

import java.util.Locale;

/**
 * Pure party-epoch helper: bumps a generation counter only when Hypixel chat clearly means the
 * player's party membership ended (left / kicked / disbanded). Invite expiry and leadership
 * transfer do <b>not</b> bump — the party channel remains valid.
 */
public final class PartyEpoch {

    private int epoch;

    public int current() {
        return epoch;
    }

    public int bump() {
        return ++epoch;
    }

    /**
     * Inspect a colour-stripped Hypixel chat line. Returns true and bumps only for conservative
     * membership-ending signals.
     */
    public boolean observeChat(String plain) {
        if (plain == null) return false;
        String msg = plain.trim();
        if (msg.isEmpty()) return false;
        String lower = msg.toLowerCase(Locale.ROOT);

        // Exact / prefix membership ends. Avoid broad contains() that false-positive on party chat.
        if (lower.equals("the party was disbanded")
                || lower.startsWith("you left the party")
                || lower.startsWith("you have been kicked from the party")
                || lower.startsWith("you were kicked from the party")
                || isOtherDisbanded(lower)) {
            bump();
            return true;
        }
        return false;
    }

    /** {@code <name> has disbanded the party!} — name is 3–16 alnum/underscore. */
    private static boolean isOtherDisbanded(String lower) {
        // Ends with the stable Hypixel phrase; require a short name-like prefix.
        String suffix = " has disbanded the party!";
        if (!lower.endsWith(suffix) && !lower.endsWith(" has disbanded the party")) return false;
        int cut = lower.endsWith(suffix) ? lower.length() - suffix.length()
                : lower.length() - " has disbanded the party".length();
        if (cut < 3 || cut > 16) return false;
        for (int i = 0; i < cut; i++) {
            char c = lower.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) return false;
        }
        return true;
    }
}
