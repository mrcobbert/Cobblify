package com.bedwarsqol.feature;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure party tracking: a generation counter that bumps only when Hypixel chat clearly means the
 * player's party membership ended (left / kicked / disbanded), plus a coarse "am I in a party"
 * flag used to gate {@code /pc} traffic. Invite expiry and leadership transfer do <b>not</b> bump
 * — the party channel remains valid.
 *
 * <p>{@link #inParty()} starts true and stays true while unknown: a false send costs one Hypixel
 * error line, which is itself the signal that corrects the state, whereas a false block silently
 * loses the report with no trace. Once false, it is restored by any line that proves a party
 * exists again: party chat, our own join, a member joining ({@code [MVP+] Alex joined the party.},
 * which is what the leader sees), or an invite going out (Hypixel creates the party on the first
 * invite: "disbanded because all invites expired and the party was empty" is the matching end).
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

        // Someone joined our party (leader-side and other-member broadcast), or an invite went out
        // (ours or another member's). Both prove a party exists. The head must be a bare name at
        // position 0 — a lobby line quoting these words has a "name:" head there instead.
        if (MEMBER_JOINED.matcher(lower).matches()
                || lower.startsWith("you invited ") && INVITE_TAIL.matcher(lower).find()
                || OTHER_INVITED.matcher(lower).matches()) {
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

    /** {@code <name> joined the party.} / {@code <name> has joined the party!}, brackets already stripped. */
    private static final Pattern MEMBER_JOINED =
            Pattern.compile("^[a-z0-9_]{3,16} (?:has )?joined the party[.!]?$");
    /** {@code to the party!} optionally followed by the "60 seconds to accept" sentence. */
    private static final Pattern INVITE_TAIL =
            Pattern.compile(" to the party!?(?: they have \\d+ seconds to accept\\.?)?$");
    /** {@code <leader> invited [RANK] <name> to the party!}, brackets kept in the middle. */
    private static final Pattern OTHER_INVITED = Pattern.compile(
            "^[a-z0-9_]{3,16} invited (?:\\[[^\\]]*\\] )*[a-z0-9_]{3,16} to the party!?"
            + "(?: they have \\d+ seconds to accept\\.?)?$");

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
