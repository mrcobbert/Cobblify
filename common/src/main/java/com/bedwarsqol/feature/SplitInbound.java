package com.bedwarsqol.feature;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * The Split module's "Teammate Returning" arrival latch. A teammate who has been away from the base (beyond
 * {@link #AWAY_RANGE} of the forge, or not seen at all) for {@link #AWAY_MS} is armed; when they come back inside
 * {@link #ARRIVE_RANGE} while closing on the forge, one cue fires and they are disarmed. The cue ends when they reach
 * the forge, turn away, go unseen, or after {@link #CUE_MS}. Teammates who stay in the base never arm, so never cue.
 *
 * <p>The platform feeds one scan at a time: {@link #observe} for each teammate entity it sees, then {@link #endScan}.
 * Distances are 3-D blocks to the locked forge; times are passed in, so this holds no Minecraft state. Names are
 * compared case-insensitively.
 */
public final class SplitInbound {

    /** Beyond this from the forge = away. */
    public static final double AWAY_RANGE = 36.0;
    /** Continuously away this long = armed. */
    public static final long AWAY_MS = 8_000L;
    /** An armed teammate inside this (inclusive) and closing fires the cue. */
    public static final double ARRIVE_RANGE = 18.0;
    /** "Closing" = at least this much nearer than a sample at least {@link #CLOSING_MS} old. */
    public static final double CLOSING_BLOCKS = 0.5;
    public static final long CLOSING_MS = 400L;
    /** The cue ends at this distance (inclusive): they are at the forge. */
    public static final double REACHED_RANGE = 3.0;
    /** The cue ends when they are this much farther than their nearest point since it fired. */
    public static final double TURN_AWAY_BLOCKS = 2.0;
    /** The cue ends this long after it fired. */
    public static final long CUE_MS = 6_000L;
    /** The cue shows only while the local player is this near the forge (inclusive). */
    public static final double LOCAL_RANGE = 8.0;

    private static final long NONE = Long.MIN_VALUE;

    private static final class Mate {
        /** Observed since the previous endScan. */
        boolean seen;
        /** Missed at least one scan since the last observation: the next observation has no older sample. */
        boolean gap;
        long awaySince = NONE;
        boolean armed;
        boolean live;
        long cueSince;
        double nearest;
        /** Recent (time, distance) samples, oldest first; cleared while unseen. */
        final ArrayDeque<double[]> samples = new ArrayDeque<double[]>();
    }

    private final Map<String, Mate> mates = new HashMap<String, Mate>();

    /** Forgets every teammate. */
    public void reset() {
        mates.clear();
    }

    /**
     * A teammate died and will respawn at the base: arm them now, so their reappearance near the forge is an
     * arrival even when they were never away for {@link #AWAY_MS}.
     */
    public void arm(String name) {
        if (name == null) return;
        String key = name.toLowerCase(Locale.ROOT);
        Mate m = mates.get(key);
        if (m == null) {
            m = new Mate();
            mates.put(key, m);
        }
        m.armed = true;
    }

    /**
     * One teammate seen this scan, at this distance (3-D) from the forge. Returns whether their cue fired now.
     * A teammate's first-ever observation never arms them by itself.
     */
    public boolean observe(String name, double distToForge, long nowMs) {
        if (name == null) return false;
        String key = name.toLowerCase(Locale.ROOT);
        Mate m = mates.get(key);
        if (m == null) {
            m = new Mate();
            mates.put(key, m);
        }
        m.seen = true;
        boolean reappeared = m.gap;
        m.gap = false;

        if (m.live) {
            if (nowMs - m.cueSince >= CUE_MS) {
                m.live = false;
            } else {
                m.nearest = Math.min(m.nearest, distToForge);
                if (distToForge <= REACHED_RANGE || distToForge - m.nearest >= TURN_AWAY_BLOCKS) m.live = false;
            }
        }

        if (distToForge > AWAY_RANGE) {
            if (m.awaySince == NONE) m.awaySince = nowMs;
            if (nowMs - m.awaySince >= AWAY_MS) m.armed = true;
        } else {
            m.awaySince = NONE;
        }

        boolean fired = false;
        if (m.armed && distToForge <= ARRIVE_RANGE && (reappeared || closing(m, distToForge, nowMs))) {
            m.armed = false;
            m.live = true;
            m.cueSince = nowMs;
            m.nearest = distToForge;
            fired = true;
        }
        record(m, distToForge, nowMs);
        return fired;
    }

    /**
     * End of a scan: every known teammate not observed since the previous endScan counts as away (dead, or out of
     * tracking range), and any cue of theirs ends.
     */
    public void endScan(long nowMs) {
        for (Mate m : mates.values()) {
            if (m.seen) {
                m.seen = false;
                continue;
            }
            m.gap = true;
            m.live = false;
            m.samples.clear();
            if (m.awaySince == NONE) m.awaySince = nowMs;
            if (nowMs - m.awaySince >= AWAY_MS) m.armed = true;
        }
    }

    /** Whether any teammate's cue is live and the local player is within {@link #LOCAL_RANGE} of the forge. */
    public boolean cue(double localDistToForge, long nowMs) {
        if (localDistToForge > LOCAL_RANGE) return false;
        for (Mate m : mates.values()) {
            if (m.live && nowMs - m.cueSince < CUE_MS) return true;
        }
        return false;
    }

    /** Whether {@code dist} is at least {@link #CLOSING_BLOCKS} nearer than the newest sample at least {@link #CLOSING_MS} old. */
    private static boolean closing(Mate m, double dist, long nowMs) {
        double[] ref = null;
        for (double[] s : m.samples) {
            if (nowMs - (long) s[0] >= CLOSING_MS) ref = s;
            else break;
        }
        return ref != null && ref[1] - dist >= CLOSING_BLOCKS;
    }

    /** Appends a sample and drops those older than the newest one that could still serve as a closing reference. */
    private static void record(Mate m, double dist, long nowMs) {
        m.samples.addLast(new double[]{nowMs, dist});
        // keep the newest sample at least CLOSING_MS old, and everything after it
        int keepFrom = 0, i = 0;
        for (double[] s : m.samples) {
            if (nowMs - (long) s[0] >= CLOSING_MS) keepFrom = i;
            i++;
        }
        Iterator<double[]> it = m.samples.iterator();
        for (int j = 0; j < keepFrom && it.hasNext(); j++) {
            it.next();
            it.remove();
        }
    }
}
