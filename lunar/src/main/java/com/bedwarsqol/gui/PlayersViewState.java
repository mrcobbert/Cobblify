package com.bedwarsqol.gui;

import java.util.UUID;

/**
 * In-memory view state for the Players tab, retained across GUI open/close within a play session and
 * cleared on client restart (static, never persisted to disk). Holds where the user was: the selected
 * settings section, the selected player, the list scroll, the player-search query, and any active
 * remote-lookup name.
 */
public final class PlayersViewState {

    private PlayersViewState() {}

    /** Last selected settings section index, so a normal reopen returns to the same tab. */
    public static int selectedSection = -1;
    /** The player whose detail panel is open, or null for none. */
    public static UUID selectedUuid;
    /** The selected player's tab-list display name, captured at click time so the Urchin exact-pair gate
     *  (name, uuid) works even before the stats fetch resolves. */
    public static String selectedName;
    /** Smooth-scroll offset of the player list. */
    public static float listScroll;
    /** Current player-search query (independent of the module search box). */
    public static String playerQuery = "";
    /** Exact name of an in-progress remote lookup (search matched nobody present), or null. */
    public static String lookupName;

    /** Set the player-search query. Any change clears the transient remote lookup (its detail must not
     *  outlive the query that opened it) and resets the list scroll. A selected live-row player is left
     *  untouched. Returns true when the query actually changed. */
    public static boolean setQuery(String q) {
        String next = q == null ? "" : q;
        if (next.equals(playerQuery)) return false;
        playerQuery = next;
        lookupName = null;
        listScroll = 0;
        return true;
    }
}
