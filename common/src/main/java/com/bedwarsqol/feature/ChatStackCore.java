package com.bedwarsqol.feature;

import java.util.List;

/**
 * Pure rules for Stack Spam Messages: which lines may be stacked, when a repeat still counts, and
 * the newest-first list arithmetic that swaps a message's wrapped rows in place. No Minecraft types
 * — unit-tested directly; the Forge {@code GuiNewChatMixin} is the thin runtime wrapper.
 *
 * <p>Vocabulary. A <b>blank</b> line is whitespace-only once formatting codes are stripped; the
 * Ignore Blank Lines toggle decides whether it is transparent to the stacker (neither a target nor a
 * chain-breaker) or an ordinary stackable line. A <b>decorative</b> line is non-blank but carries no
 * letter or digit — Hypixel's full-width {@code §m} separator bars — and is never a target: a counter
 * would only push the bar onto a second row, so it prints as vanilla and breaks the chain like any
 * other different line.
 */
public final class ChatStackCore {

    private ChatStackCore() {}

    /** What {@code setChatLine} should do with a freshly printed, non-deletable line. */
    public enum Capture {
        /** Invisible to the stacker: neither remembered nor chain-breaking. */
        TRANSPARENT,
        /** Remember it as the new chain target. */
        CAPTURE,
        /** Forget the chain; this line is never a target. */
        RESET
    }

    /** {@code formatted} with every {@code §x} code removed (the platform-neutral strip). */
    public static String plain(String formatted) {
        if (formatted == null) return null;
        if (formatted.indexOf('§') < 0) return formatted;
        StringBuilder sb = new StringBuilder(formatted.length());
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '§') {
                i++; // skip the code character too (a trailing lone § is dropped)
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Whitespace-only (or null). */
    public static boolean blank(String plain) {
        return plain == null || plain.trim().isEmpty();
    }

    /** Non-blank but without a single letter or digit: separator bars and the like. */
    public static boolean decorative(String plain) {
        if (blank(plain)) return false;
        for (int i = 0; i < plain.length(); i++) {
            if (Character.isLetterOrDigit(plain.charAt(i))) return false;
        }
        return true;
    }

    /** The capture decision for a printed line, given the Ignore Blank Lines toggle. */
    public static Capture captureAction(String plain, boolean ignoreBlanks) {
        if (blank(plain)) return ignoreBlanks ? Capture.TRANSPARENT : Capture.CAPTURE;
        if (decorative(plain)) return Capture.RESET;
        return Capture.CAPTURE;
    }

    /** The configured window in milliseconds; never negative. */
    public static long windowMs(float windowSec) {
        long ms = (long) (windowSec * 1000f);
        return ms < 0 ? 0 : ms;
    }

    /**
     * Whether {@code candidate} (the formatted text of the line about to print) repeats {@code base}
     * (the live formatted text of the remembered line, counter detached). With time-based stacking
     * on, the repeat must arrive within {@code windowMs} of the last stack; the window slides.
     */
    public static boolean shouldStack(String base, String candidate, boolean timeBased,
                                      long windowMs, long lastStackMs, long nowMs) {
        if (base == null || candidate == null || !base.equals(candidate)) return false;
        return !timeBased || nowMs - lastStackMs <= windowMs;
    }

    /**
     * Index in the newest-first drawn list where message {@code idx}'s rows begin: the rows of every
     * newer message ({@code 0..idx-1}) come first.
     */
    public static int rowStart(int[] rowsPerMessage, int idx) {
        int start = 0;
        for (int i = 0; i < idx && i < rowsPerMessage.length; i++) start += rowsPerMessage[i];
        return start;
    }

    /**
     * True iff the target's whole row range {@code [start, start+oldRows)} lies inside a drawn list
     * of {@code drawnSize} rows. The two vanilla lists are capped independently (messages vs rows),
     * so a message can outlive its rows; then the repeat must print normally.
     */
    public static boolean targetPresent(int start, int oldRows, int drawnSize) {
        return start >= 0 && oldRows > 0 && start + oldRows <= drawnSize;
    }

    /**
     * Replace the rows {@code [start, start+oldRows)} of {@code drawn} with {@code fresh}, clamped to
     * the list bounds as a second guard behind {@link #targetPresent}. Returns how many rows were
     * actually removed.
     */
    public static <T> int replaceRows(List<T> drawn, int start, int oldRows, List<T> fresh) {
        int size = drawn.size();
        if (start < 0) start = 0;
        if (start > size) start = size;
        int end = Math.min(size, start + Math.max(0, oldRows));
        int removed = end - start;
        for (int i = 0; i < removed; i++) drawn.remove(start);
        drawn.addAll(start, fresh);
        return removed;
    }

    /**
     * Vanilla scrolls one row per added row while the reader is scrolled up, so the view keeps its
     * place; it can, because a new message always lands at index 0, below every row the reader
     * scrolled past. The in-place swap changes rows at {@code [start, start+oldRows)}: only when that
     * whole range sits below the viewport ({@code start + oldRows <= scrollPos}) do the visible rows
     * shift, by the net change. A target inside or above the viewport moves nothing the reader sees
     * past, so no compensation is due.
     */
    public static int scrollDelta(boolean chatOpen, int scrollPos, int start, int oldRows, int newRows) {
        if (!chatOpen || scrollPos <= 0) return 0;
        if (start + oldRows > scrollPos) return 0;
        return newRows - oldRows;
    }
}
