package com.bedwarsqol.command;

import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure up-arrow-history scrub for {@code /bw statstoken}, extracted from {@link BedwarsQolCommand} so
 * it can be unit-tested headlessly (the command class pulls in Minecraft at class-init). The token
 * itself never reaches this helper - it only ever sees the raw history lines and returns a boolean
 * verdict. Mirrors {@link UrchinKeyScrub}.
 */
final class StatsTokenScrub {

    private StatsTokenScrub() {}

    private static final Pattern STATS_TOKEN_LINE = Pattern.compile(
            "^/(bw|bedwarsqol|hypixelclient|cobblify)\\s+statstoken\\b", Pattern.CASE_INSENSITIVE);

    static boolean matches(String line) {
        return line != null && STATS_TOKEN_LINE.matcher(line.trim()).find();
    }

    /**
     * Remove every {@code /(bw|bedwarsqol|hypixelclient|cobblify) statstoken ...} entry from
     * {@code sentMessages} and verify. Returns true ONLY when, after the removal attempt, no matching
     * entry remains (the removal is re-scanned to confirm it actually took effect). Fails closed -
     * returns false - on a null list, a mutation exception (an unmodifiable or concurrently-changing
     * list), or any surviving match. The caller must abort the token save on false.
     */
    static boolean scrubKeyEntries(List<String> sentMessages) {
        if (sentMessages == null) return false;
        try {
            Iterator<String> it = sentMessages.iterator();
            while (it.hasNext()) {
                if (matches(it.next())) it.remove();
            }
        } catch (Throwable t) {
            // Unmodifiable/throwing list: fall through to the verifying re-scan, which fails closed
            // if any matching entry survived (removal on such a list can throw without removing).
        }
        return !hasMatch(sentMessages);
    }

    /** Whether any entry still matches; a list that can't even be iterated fails closed (returns true). */
    private static boolean hasMatch(List<String> lines) {
        try {
            for (String s : lines) {
                if (matches(s)) return true;
            }
        } catch (Throwable t) {
            return true;
        }
        return false;
    }
}
