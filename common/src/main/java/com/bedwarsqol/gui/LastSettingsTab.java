package com.bedwarsqol.gui;

/**
 * The settings tab the user last viewed, so the settings screen reopens on it. Kept in memory for the
 * play session only (static, never written to disk), so a client restart opens on the first tab again.
 */
public final class LastSettingsTab {

    private LastSettingsTab() {}

    private static int last = -1;

    /** Record the tab that was showing when the settings screen closed. */
    public static void remember(int section) {
        last = section;
    }

    /** The remembered tab if it is a valid index among {@code sectionCount} tabs, otherwise the first tab. */
    public static int restore(int sectionCount) {
        return last >= 0 && last < sectionCount ? last : 0;
    }
}
