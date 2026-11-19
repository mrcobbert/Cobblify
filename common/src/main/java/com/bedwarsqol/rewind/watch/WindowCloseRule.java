package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;

/**
 * When a recorded window the player closed stops being drawn (PLAN "Recorded screens", review B3; TASK B10). The
 * server's close (S2E), a new window and a boundary end a window on every recording; those are the holder's own
 * packets. What this decides is the close the player made (E or Esc), which sends C0D and needs no answer from the
 * server: a {@link Source}, asked once per world tick while a window is drawn. Pure; allocates nothing per call.
 *
 * <ul>
 *   <li><b>v1</b> ({@link Heuristic}, a labelled heuristic): a v1 recording stored no C0D, so a window closes at the
 *       first world tick whose head is at or after a CAMERA sample more than {@link #CAMERA_GRACE_US} after the open,
 *       or at the first swing start in or after the open's tick. Vanilla turns the view only while no screen is open
 *       ({@code EntityRenderer.updateCameraAndRender} guards the mouse turn with {@code inGameHasFocus}, which
 *       {@code displayGuiScreen} clears) and swings only from {@code clickMouse}, which does not run under a
 *       container screen. Measured on the real recordings: the first camera change after an open comes a median
 *       1.13 s later, against a sample spacing of 8-26 ms. Known error (TASK B10): the window stays up after the
 *       player's close until the mouse next moves; a server teleport that turns the view closes it early.</li>
 *   <li><b>v2</b> ({@link RecordedView}): the recorder samples the open screen every frame, so the latest VIEW
 *       record stamped from the head of the open's tick to the head of tick k says whether the window is still up;
 *       a screen kind other than {@code SCREEN_CHEST} or {@code SCREEN_OTHER_CONTAINER} closes it. No heuristic close
 *       fires on such a recording. Phase 12 owns the exact (frame-time) close and the inventory screen.</li>
 * </ul>
 *
 * Both answer from the tick index only (not from what was asked before), so a seek gets the answers straight play
 * got.
 */
public final class WindowCloseRule {

    /** A CAMERA sample must be more than this long after the open to count as the player turning the view. */
    public static final long CAMERA_GRACE_US = 100_000L;

    /** Where the player's close of a drawn window comes from. */
    public interface Source {
        /**
         * True if the window that was opened in the feed of tick {@code openTick} (its packet stamped
         * {@code openUs}) has been closed by the player by world tick {@code k} ({@code k >= openTick}).
         */
        boolean closedBy(long openTick, long openUs, long k);
    }

    /** No close source (no recording, no tick table). */
    public static final Source NEVER = new Source() {
        @Override
        public boolean closedBy(long openTick, long openUs, long k) {
            return false;
        }
    };

    private WindowCloseRule() {
    }

    /** The recorded screen kind when the recording has VIEW records, else the v1 heuristic. */
    public static Source forRecording(SelfTrack track, ExtRecords ext, TickTable table) {
        if (table == null) {
            return NEVER;
        }
        if (ext != null && ext.has(RewindFormat.TAG_VIEW)) {
            return new RecordedView(ext, table);
        }
        return track == null ? NEVER : new Heuristic(track, table);
    }

    /** v1: the first view turn more than 100 ms after the open, or the first swing start. */
    public static final class Heuristic implements Source {
        private final SelfTrack track;
        private final TickTable table;

        public Heuristic(SelfTrack track, TickTable table) {
            this.track = track;
            this.table = table;
        }

        @Override
        public boolean closedBy(long openTick, long openUs, long k) {
            if (k < openTick) {
                return false;
            }
            if (track.swingStartsAt(table, k)) {
                return true;
            }
            // Any sample more than the grace after the open and at or before T[k] makes the latest one such a sample.
            long head = table.timeOf(k);
            int si = track.segmentIndexAt(head);
            if (si < 0) {
                return false;
            }
            SelfTrack.Segment s = track.segment(si);
            int c = s.cameraIndexAt(head);
            return c >= 0 && s.cameraTimeUs(c) - openUs > CAMERA_GRACE_US;
        }
    }

    /** v2: the latest VIEW record since the open's tick head leaves "container". */
    public static final class RecordedView implements Source {
        private final ExtRecords ext;
        private final TickTable table;
        private final ExtCodec.View view = new ExtCodec.View();
        // A forward-only scan cache for straight play; any other question starts the scan again.
        private long cacheOpenTick = Long.MIN_VALUE;
        private long cacheK = Long.MIN_VALUE;
        private int pos;
        private int lastKind = -1;

        public RecordedView(ExtRecords ext, TickTable table) {
            this.ext = ext;
            this.table = table;
        }

        @Override
        public boolean closedBy(long openTick, long openUs, long k) {
            if (k < openTick) {
                return false;
            }
            long from = table.timeOf(openTick);
            long to = table.timeOf(k);
            if (openTick != cacheOpenTick || k < cacheK) {
                cacheOpenTick = openTick;
                pos = ext.firstTimedAfter(from - 1);
                lastKind = -1;
            }
            cacheK = k;
            while (pos < ext.timedCount()) {
                int i = ext.timed(pos);
                if (ext.timeUs(i) > to) {
                    break;
                }
                pos++;
                if (ext.tag(i) != RewindFormat.TAG_VIEW) {
                    continue;
                }
                try {
                    lastKind = view.read(ext.buffer(), ext.offset(i), ext.length(i)).screenKind;
                } catch (RewindFormat.FormatException e) {
                    // a malformed VIEW record says nothing
                }
            }
            return lastKind >= 0 && !container(lastKind);
        }

        private static boolean container(int kind) {
            return kind == RewindFormat.SCREEN_CHEST || kind == RewindFormat.SCREEN_OTHER_CONTAINER;
        }
    }
}
