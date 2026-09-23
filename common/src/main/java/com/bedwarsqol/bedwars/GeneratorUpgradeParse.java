package com.bedwarsqol.bedwars;

/**
 * The one rule for reading a generator tier off Hypixel's upgrade broadcast ("Diamond Generators have
 * been upgraded to Tier II"), so the same words in a teammate's message cannot re-anchor the
 * generator countdown: a line a player typed is never a broadcast.
 *
 * <p>Platform-neutral: both {@code GeneratorTracker} copies call in here with the sender the chat
 * parser found, so the rule exists once.
 */
public final class GeneratorUpgradeParse {

    private GeneratorUpgradeParse() {}

    /**
     * The tier {@code plain} (an unformatted chat line) announces, or {@code -1} when it announces
     * none. {@code sender} is the name of the player who typed the line and null for a server
     * broadcast; a non-null sender is refused outright. Otherwise the line must read as an upgrade of
     * a generator, and the tier is its last standalone roman numeral, else its last standalone 1-3.
     */
    public static int tier(String plain, String sender) {
        if (sender != null || plain == null) return -1;
        String lower = plain.toLowerCase();
        if (!lower.contains("upgrad") || !lower.contains("generator")) return -1;
        java.util.regex.Matcher rm =
                java.util.regex.Pattern.compile("\\b(IV|III|II|I)\\b").matcher(plain.toUpperCase());
        int tier = -1;
        while (rm.find()) tier = romanToken(rm.group(1)); // last standalone roman wins ("Diamond II")
        if (tier >= 1) return tier;
        java.util.regex.Matcher dm = java.util.regex.Pattern.compile("\\b([1-3])\\b").matcher(plain);
        while (dm.find()) {
            try { tier = Integer.parseInt(dm.group(1)); } catch (NumberFormatException ignored) { }
        }
        return tier;
    }

    private static int romanToken(String t) {
        if ("IV".equals(t)) return 4;
        if ("III".equals(t)) return 3;
        if ("II".equals(t)) return 2;
        if ("I".equals(t)) return 1;
        return -1;
    }
}
