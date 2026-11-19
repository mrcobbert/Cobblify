package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RewindFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The time frame of a loaded recording, all on the recording's own clock (µs, the clock NET and TRACK share).
 *
 * <ul>
 *   <li><b>Origin:</b> the first NET record of the start segment. Tick k of playback stands for
 *       {@code origin + k x 50 ms}; every restart uses the same origin, so there is one tick grid.</li>
 *   <li><b>Game start:</b> the first GAME_START record's time minus its {@code backUs}; without one, the
 *       {@code .json gameStartUs}; without either, the origin.</li>
 *   <li><b>End:</b> the {@code .json endUs} when it lies after the game start; otherwise the last NET record.
 *       Playing to the end feeds every record up to it ({@link #endTick()}).</li>
 *   <li><b>Markers:</b> the TRACK MARKER records; without any, the {@code .json} markers. Only those inside
 *       {@code [game start, end]} are kept, in time order.</li>
 * </ul>
 *
 * Immutable.
 */
public final class Timeline {

    /** Length of one client tick. */
    public static final long TICK_US = 50_000L;
    /** "No GAME_START record" for {@link #build}. */
    public static final long NONE = Long.MIN_VALUE;

    /** One marker on the timeline: its time and its kind ({@code RewindFormat.MARKER_*}). */
    public static final class Marker {
        public final long timeUs;
        public final int kind;

        public Marker(long timeUs, int kind) {
            this.timeUs = timeUs;
            this.kind = kind;
        }
    }

    private static final Comparator<Marker> BY_TIME = new Comparator<Marker>() {
        @Override
        public int compare(Marker a, Marker b) {
            return a.timeUs < b.timeUs ? -1 : a.timeUs > b.timeUs ? 1 : 0;
        }
    };

    private final long originUs;
    private final long lastNetUs;
    private final long gameStartUs;
    private final long endUs;
    private final List<Marker> markers;

    private Timeline(long originUs, long lastNetUs, long gameStartUs, long endUs, List<Marker> markers) {
        this.originUs = originUs;
        this.lastNetUs = lastNetUs;
        this.gameStartUs = gameStartUs;
        this.endUs = endUs;
        this.markers = markers;
    }

    /**
     * @param originUs          first NET record of the start segment
     * @param lastNetUs         last NET record that playback can reach
     * @param gameStartRecordUs time of the first GAME_START record, or {@link #NONE}
     * @param backUs            that record's {@code backUs} (a negative value counts as 0)
     * @param trackMarkers      the TRACK MARKER records (any order; may be null)
     * @param meta              the recording's metadata, or null
     */
    public static Timeline build(long originUs, long lastNetUs, long gameStartRecordUs, long backUs,
                                 List<Marker> trackMarkers, RecordingMeta meta) {
        long start;
        if (gameStartRecordUs != NONE) {
            start = gameStartRecordUs - Math.max(0L, backUs);
        } else if (meta != null && meta.gameStartUs != 0) {
            start = meta.gameStartUs;
        } else {
            start = originUs;
        }
        long end = meta != null && meta.endUs > start ? meta.endUs : Math.max(start, lastNetUs);

        List<Marker> source = new ArrayList<Marker>();
        if (trackMarkers != null && !trackMarkers.isEmpty()) {
            source.addAll(trackMarkers);
        } else if (meta != null && meta.markers != null) {
            for (RecordingMeta.Marker m : meta.markers) {
                int kind = m == null ? -1 : kindOf(m.kind);
                if (kind > 0) {
                    source.add(new Marker(m.tUs, kind));
                }
            }
        }
        List<Marker> kept = new ArrayList<Marker>();
        for (Marker m : source) {
            if (m.timeUs >= start && m.timeUs <= end) {
                kept.add(m);
            }
        }
        Collections.sort(kept, BY_TIME);
        return new Timeline(originUs, lastNetUs, start, end, Collections.unmodifiableList(kept));
    }

    /** The {@code RewindFormat.MARKER_*} code of a {@code .json} marker kind, or -1. */
    static int kindOf(String name) {
        if (name == null) {
            return -1;
        }
        for (int k = RewindFormat.MARKER_KEY; k <= RewindFormat.MARKER_BED_LOST; k++) {
            if (name.equals(RewindFormat.markerName(k))) {
                return k;
            }
        }
        return -1;
    }

    public long originUs() {
        return originUs;
    }

    /** The last NET record playback can reach. */
    public long lastNetUs() {
        return lastNetUs;
    }

    public long gameStartUs() {
        return gameStartUs;
    }

    public long endUs() {
        return endUs;
    }

    /** End minus game start: the gallery's length. */
    public long lengthUs() {
        return endUs - gameStartUs;
    }

    /** Markers inside {@code [game start, end]}, in time order. */
    public List<Marker> markers() {
        return markers;
    }

    /** True when the game start lies between the origin and the last NET record (both included). */
    public boolean startInsideData() {
        return gameStartUs >= originUs && gameStartUs <= lastNetUs;
    }

    /** The first tick whose time is at or after {@code tUs}: {@code ceil((t - origin) / 50 ms)}. */
    public long tickOf(long tUs) {
        return -Math.floorDiv(originUs - tUs, TICK_US);
    }

    /** The time tick {@code k} stands for: {@code origin + k x 50 ms}. */
    public long timeOfTick(long k) {
        return originUs + k * TICK_US;
    }

    public long startTick() {
        return tickOf(gameStartUs);
    }

    /**
     * The ticks run when playback reaches the end: one past {@link #tickOf tickOf(end)}, so the last tick fed (whose
     * time is at or after the end) has handed over every record up to the end, also those in the final tick window
     * (code review 2, I3). The picture then shows that tick's time, at most one tick past the end.
     */
    public long endTick() {
        return tickOf(endUs) + 1;
    }
}
