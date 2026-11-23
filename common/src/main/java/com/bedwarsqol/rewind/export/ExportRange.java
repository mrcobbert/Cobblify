package com.bedwarsqol.rewind.export;

/**
 * The range an export covers, as its two timeline handles set it (G38; Rewind Export PLAN Solution 5, D3; TASK U3).
 * Times are microseconds from the game start, inside {@code [0, length]}.
 *
 * <ul>
 *   <li>The handles start {@value #BEFORE_US} us before and {@value #AFTER_US} us after a point ({@link #around}):
 *       the marker last jumped to in this viewing, else the playhead ({@link #forViewer}); from the gallery the first
 *       marker, else the game's first 15 s ({@link #forGallery}).</li>
 *   <li>Both stay inside the game, and the range is never shorter than {@value #MIN_US} us; dragging a handle past the
 *       other stops that far short of it. A game shorter than that is exported whole.</li>
 * </ul>
 *
 * Immutable.
 */
public final class ExportRange {

    public static final long BEFORE_US = 10_000_000L;
    public static final long AFTER_US = 5_000_000L;
    public static final long MIN_US = 1_000_000L;

    private final long lengthUs;
    private final long startUs;
    private final long endUs;

    /** A range on a game of {@code lengthUs}, brought inside the game and to at least {@link #MIN_US}. */
    public ExportRange(long lengthUs, long startUs, long endUs) {
        long len = Math.max(0L, lengthUs);
        this.lengthUs = len;
        if (len <= MIN_US) {
            this.startUs = 0;
            this.endUs = len;
            return;
        }
        long s = clamp(startUs, 0, len - MIN_US);
        long e = clamp(endUs, s + MIN_US, len);
        this.startUs = s;
        this.endUs = e;
    }

    /** From {@code centre - 10 s} to {@code centre + 5 s}, clamped to the game. */
    public static ExportRange around(long lengthUs, long centreUs) {
        long len = Math.max(0L, lengthUs);
        long c = clamp(centreUs, 0, len);
        return new ExportRange(len, c - BEFORE_US, Math.min(len, c + AFTER_US));
    }

    /** In the viewer: around {@code markerUs} (the marker last jumped to, -1 for none), else around the playhead. */
    public static ExportRange forViewer(long lengthUs, long markerUs, long playheadUs) {
        return around(lengthUs, markerUs >= 0 ? markerUs : playheadUs);
    }

    /** From the gallery: around the first marker ({@code firstMarkerUs}, -1 for none), else the first 15 s. */
    public static ExportRange forGallery(long lengthUs, long firstMarkerUs) {
        if (firstMarkerUs >= 0) {
            return around(lengthUs, firstMarkerUs);
        }
        return new ExportRange(lengthUs, 0, BEFORE_US + AFTER_US);
    }

    public long lengthUs() {
        return lengthUs;
    }

    public long startUs() {
        return startUs;
    }

    public long endUs() {
        return endUs;
    }

    /** The start handle moved to {@code us}: kept in the game and at least 1 s before the end. */
    public ExportRange dragStart(long us) {
        if (lengthUs <= MIN_US) return this;
        return new ExportRange(lengthUs, clamp(us, 0, endUs - MIN_US), endUs);
    }

    /** The end handle moved to {@code us}: kept in the game and at least 1 s after the start. */
    public ExportRange dragEnd(long us) {
        if (lengthUs <= MIN_US) return this;
        return new ExportRange(lengthUs, startUs, clamp(us, startUs + MIN_US, lengthUs));
    }

    /** Handle 0 (start) or 1 (end) moved to {@code us}. */
    public ExportRange drag(int handle, long us) {
        return handle == 0 ? dragStart(us) : handle == 1 ? dragEnd(us) : this;
    }

    private static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
