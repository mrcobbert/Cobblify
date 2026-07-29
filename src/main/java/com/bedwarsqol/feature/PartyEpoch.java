package com.bedwarsqol.feature;

import java.util.Locale;

/**
 * Pure party tracking: a generation counter that bumps only when Hypixel chat clearly means the
 * player's party membership ended (left / kicked / disbanded), plus a coarse "am I in a party"
 * flag used to gate {@code /pc} traffic. Invite expiry and leadership transfer do <b>not</b> bump
 * — the party channel remains valid.
 *
 * <p>{@link #inParty()} starts true and stays true while unknown: a false send costs one Hypixel
 * error line, which is itself the signal that corrects the state, whereas a false block silently
 * loses the report with no trace.
 */
public final class PartyEpoch {

    private int epoch;
    private boolean inParty = true;

    public int current() {
        return epoch;
    }

    public int bump() {
        return ++epoch;
    }

    /** Whether we may still be in a party — false only once Hypixel has told us we are not. */
    public boolean inParty() {
        return inParty;
    }

    /**
     * Inspect a colour-stripped Hypixel chat line. Returns true and bumps only for conservative
     * membership-ending signals.
     */
    public boolean observeChat(String plain) {
        if (plain == null) return false;
        String msg = plain.trim();
        if (msg.isEmpty()) return false;
        String lower = stripLeadingBrackets(msg.toLowerCase(Locale.ROOT));

        // Party chat proves membership. Checked first so a member typing a system-looking line
        // ("the party was disbanded") can never be read as one.
        if (lower.startsWith("party >")) {
            inParty = true;
            return false;
        }

        // We joined — the only self-join wording seen in logs.
        if (lower.startsWith("you have joined ") && lower.contains("party")) {
            inParty = true;
            return false;
        }

        // Rejected /pc. Hypixel has at least three wordings ("...not in a party.", "...not in a
        // party right now.", "...not currently in a party."), so match the stable head + subject.
        // Nothing changed — we only learned — so the epoch stands.
        if (lower.startsWith("you are not") && lower.contains("in a party")) {
            inParty = false;
            return false;
        }

        // Prefix membership ends. Avoid broad contains() that false-positive on party chat.
        if (lower.startsWith("the party was disbanded")
                || lower.startsWith("you left the party")
                || lower.startsWith("you have been kicked from the party")
                || lower.startsWith("you were kicked from the party")
                || isOtherDisbanded(lower)) {
            inParty = false;
            bump();
            return true;
        }
        return false;
    }

    /**
     * Drop leading {@code [...]} groups so a prefixed line still matches at position 0. Our own
     * ChatNameTags FKDR bracket lands ahead of the text ({@code [7.19] Party > name: msg} appears
     * in real logs), as would any future prefix.
     */
    private static String stripLeadingBrackets(String lower) {
        int i = 0;
        while (i < lower.length() && lower.charAt(i) == '[') {
            int close = lower.indexOf(']', i);
            if (close < 0) break;
            i = close + 1;
            while (i < lower.length() && lower.charAt(i) == ' ') i++;
        }
        return i == 0 ? lower : lower.substring(i);
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
