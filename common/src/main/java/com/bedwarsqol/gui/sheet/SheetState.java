package com.bedwarsqol.gui.sheet;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the sheet shows, apart from the settings themselves. One instance lives for the play session, so reopening
 * the sheet keeps the category, open modules, search and scroll.
 */
public final class SheetState {

    /** The play session's state. */
    public static final SheetState SESSION = new SheetState();

    public String category = "hud";
    public final Set<String> open = new LinkedHashSet<String>();
    /** Modules whose search hit shows every row ("Show all N settings in X"). */
    public final Set<String> showAll = new LinkedHashSet<String>();
    public String query = "";
    /** The key row waiting for a key, or null. */
    public String capture;
    /** The action row waiting for its second press, or null, and when that wait ends (ms). */
    public String confirm;
    public long confirmUntil;
    /** The colour row whose picker is open, or null; one at a time. */
    public String picker;
    /**
     * The open picker's hue, saturation and brightness (0..1) and the colour they stand for. Kept apart from the
     * stored RGB so the hue survives a drag through grey or black.
     */
    public float pickH, pickS, pickV;
    public int pickRgb = -1;
    /** Scroll target in sheet units; the drawn scroll eases toward it. */
    public float scroll;
    public float scrollShown;

    public boolean searching() {
        return !query.trim().isEmpty();
    }

    /** Where Esc backs out to, one step at a time: capture, then confirm, then search, then close. */
    public static final int ESC_CAPTURE = 1, ESC_CONFIRM = 2, ESC_SEARCH = 3, ESC_CLOSE = 4;
}
