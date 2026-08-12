package com.bedwarsqol.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Chat-derived roster state for the Cobblify lobby dashboard, kept apart from the game so it can live in
 * {@code common/}. Two independent pieces, both fed by {@code LobbyChatWatch}:
 *
 * <ul>
 *   <li><b>Your party</b> — the last {@code /party list} roster we parsed. Pinned by the launcher on
 *       every context, so it is retained across contexts until a newer roster replaces it.</li>
 *   <li><b>Queue typers</b> — the names that have <i>typed</i> in the current pregame queue. Hypixel
 *       obfuscates the queue tab list, so a typed line is the only reliable roster signal there. Keyed
 *       by the game-session id so a new queue starts empty.</li>
 * </ul>
 *
 * Reads (from the client tick) and writes (from chat) both happen on the client thread, but the fields
 * are volatile / the sets swapped atomically so nothing tears if that ever changes.
 */
public final class LobbyChatState {

    /** Cap on retained queue typers, so a busy queue can't grow the set without bound. */
    private static final int MAX_QUEUE_TYPERS = 32;

    private static volatile List<String> party = Collections.emptyList();

    private static volatile int queueSession = Integer.MIN_VALUE;
    private static volatile Set<String> queueTypers = Collections.emptySet();

    private LobbyChatState() {}

    /** Replace the retained {@code /party list} roster (names in line order). */
    public static void publishParty(List<String> members) {
        party = (members == null || members.isEmpty())
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(members));
    }

    /** The retained party roster; empty when none is known. Never null. */
    public static List<String> party() {
        return party;
    }

    /**
     * Record a name that typed in queue session {@code session}. A change of session clears the set
     * first, so each queue accumulates only its own typers. Ignored past the cap.
     */
    public static void recordQueueTyper(int session, String name) {
        if (name == null || name.isEmpty()) return;
        Set<String> next;
        if (session != queueSession) {
            queueSession = session;
            next = new LinkedHashSet<String>();
        } else {
            next = new LinkedHashSet<String>(queueTypers);
        }
        if (next.size() >= MAX_QUEUE_TYPERS && !next.contains(name)) return;
        next.add(name);
        queueTypers = Collections.unmodifiableSet(next);
    }

    /** The names that typed in queue session {@code session}; empty for any other session. Never null. */
    public static List<String> queueTypers(int session) {
        return session == queueSession
                ? new ArrayList<String>(queueTypers)
                : Collections.<String>emptyList();
    }

    /** Lower-cased key, for de-duping the party roster against queue typers if a caller wants to. */
    public static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
