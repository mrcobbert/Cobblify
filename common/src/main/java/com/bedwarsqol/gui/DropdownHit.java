package com.bedwarsqol.gui;

/**
 * The one hit rule for the open dropdown's option list, so the row the cursor highlights is always
 * the row a click applies. The draw laid its rows out from a 2px top pad while the click subtracted
 * 3px, so the first pixel of a row highlighted it and applied the one above; the draw's per-row
 * containment test was inclusive at both ends, so that pixel also painted two rows at once.
 *
 * <p>Platform-neutral: both {@code SettingsGui} copies call in here for the hover index and the
 * clicked index, so there is exactly one answer per pixel.
 */
public final class DropdownHit {

    private DropdownHit() {}

    /** The y of row {@code index}'s top edge, given the list's top edge and its top pad. */
    public static float rowTop(float listTop, float padY, float itemH, int index) {
        return listTop + padY + index * itemH;
    }

    /**
     * The row {@code mouseY} belongs to: the last row whose {@link #rowTop} is at or above it, clamped
     * into {@code [0, count - 1]} so the pad above the first row and the pad below the last one answer
     * with the nearest row (the caller has already decided the cursor is inside the list). Returns
     * {@code -1} when there are no rows, and the first row when there is no row height to divide by.
     */
    public static int indexAt(float mouseY, float listTop, float padY, float itemH, int count) {
        if (count <= 0) return -1;
        if (itemH <= 0f) return 0;
        int idx = (int) Math.floor((mouseY - (listTop + padY)) / itemH);
        return Math.max(0, Math.min(count - 1, idx));
    }
}
