package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.export.ExportPlan;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.bedwarsqol.rewind.watch.PlaybackSessionExportTest.ORIGIN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The real-time floor of an export (Rewind Export PLAN Revision 4, as amended by 4.1 r3-B1), on the production
 * {@link PlaybackSession} with a fake wall clock: the ticks and timed records of step i are not applied before
 * {@code applied(i-1) + (t(i) - t(i-1))} of wall time, {@code applied(i-1)} being when step i-1 was really applied; the
 * pre-roll and the first step after the seek follow the same rule; a frame is captured after its own step. So a
 * packet applied at wall w is followed by the frame for recording time T + d no earlier than w + d, stall or not; no
 * step is skipped; a slow machine is never made to wait more than one step interval; pause and speed do nothing.
 */
public class ExportPacingTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final long MS_NS = 1_000_000L;

    PlaybackSessionExportTest fx;

    /** Every step: the recording time it applied, the wall time it was applied, the frame captured then (or -1). */
    static final class Step {
        final long p;
        final long wall;
        final long frame;
        final boolean preroll;

        Step(long p, long wall, long frame, boolean preroll) {
            this.p = p;
            this.wall = wall;
            this.frame = frame;
            this.preroll = preroll;
        }

        @Override
        public String toString() {
            return (p - ORIGIN) + "@" + wall / 1000 + (frame >= 0 ? " f" + frame : preroll ? " pre" : "");
        }
    }

    final List<Step> steps = new ArrayList<Step>();
    final List<long[]> captures = new ArrayList<long[]>(); // {frame, wall, P}
    long landedWall = -1;
    int passes;

    @Before
    public void client() throws IOException {
        fx = new PlaybackSessionExportTest();
        fx.tmp = tmp;
        fx.client();
        fx.recording(PlaybackSessionExportTest.every50(3_000_000L), 3_000_000L, null);
        fx.open();
        fx.watchTo(2_500_000L); // the export rebuilds backward: a real seek before the pre-roll
    }

    /** Wall time before the next pass, given what the session last did. */
    interface Schedule {
        long next(long lastCaptured);
    }

    /** Runs {@code plan} with {@code s} until the last frame was captured; records steps and captures. */
    void run(ExportPlan plan, Schedule s, Runnable atFrame10) {
        assertTrue(fx.session.beginExport(plan));
        long lastP = fx.session.renderTimeUs();
        long lastCaptured = -1;
        for (int guard = 0; guard < 1_000_000; guard++) {
            fx.frame(s.next(lastCaptured));
            passes++;
            if (landedWall < 0 && fx.session.state() == PlaybackSession.State.WATCHING) {
                landedWall = fx.ops.nanos;
            }
            long p = fx.session.renderTimeUs();
            long f = fx.session.exportFrame();
            if (p != lastP) {
                steps.add(new Step(p, fx.ops.nanos, f, fx.session.exportPreroll()));
                lastP = p;
            }
            if (f >= 0) {
                assertEquals("frame " + f + " is captured right after its own step", plan.timeOfFrame(f), p);
                captures.add(new long[]{f, fx.ops.nanos, p});
                if (f == 10 && atFrame10 != null) {
                    atFrame10.run();
                }
                fx.session.advanceExportFrame();
                lastCaptured = f;
                if (f == plan.frames() - 1) {
                    return;
                }
            }
        }
        throw new AssertionError("the export never ended");
    }

    /**
     * No step after step i is applied, or captured, sooner than its recording-time distance from step i (the steps
     * after the seek landed; the landing itself sets playback time to the tick it reached, which is not a step).
     */
    void assertFloor() {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).wall <= landedWall) {
                continue;
            }
            for (int j = i + 1; j < steps.size(); j++) {
                long need = (steps.get(j).p - steps.get(i).p) * 1000L;
                long got = steps.get(j).wall - steps.get(i).wall;
                assertTrue("step " + steps.get(j) + " only " + got / 1000 + " us after " + steps.get(i) + ", needs "
                        + need / 1000, got >= need);
            }
        }
    }

    void assertNoStepSkipped(ExportPlan plan) {
        assertEquals(plan.frames(), captures.size());
        for (int i = 0; i < captures.size(); i++) {
            assertEquals(i, captures.get(i)[0]);
            assertEquals(plan.timeOfFrame(i), captures.get(i)[2]);
        }
    }

    /** The round-3 schedule (r3-B1): 5 ms passes, a stall that applies recording 1 s at wall 2 s. */
    @Test
    public void aStalledStepGivesTheFramesAfterItTheirOwnTime() {
        ExportPlan plan = new ExportPlan(ORIGIN + 500_000L, ORIGIN + 1_500_000L, 60);
        final long stallFrame = 30; // 500 ms + 30 / 60 s = recording 1 s
        final long[] w0 = {-1};
        final boolean[] stalled = {false};
        run(plan, last -> {
            if (w0[0] < 0 && last == 0) {
                w0[0] = captures.get(0)[1];
            }
            if (!stalled[0] && last == stallFrame - 1) {
                stalled[0] = true;
                return w0[0] + 2_000 * MS_NS - fx.ops.nanos; // the stall: recording 1 s is applied at wall 2 s
            }
            return 5 * MS_NS;
        }, null);
        assertNoStepSkipped(plan);
        long wStall = captures.get((int) stallFrame)[1];
        assertEquals("recording 1 s applied at wall 2 s", w0[0] + 2_000 * MS_NS, wStall);
        long t50 = captures.get((int) stallFrame + 3)[1]; // 1 s + 50 ms
        assertEquals(ORIGIN + 1_050_000L, captures.get((int) stallFrame + 3)[2]);
        assertTrue("the T + 50 ms frame at wall >= 2.050 s, was " + (t50 - w0[0]) / 1000 + " us",
                t50 - w0[0] >= 2_050 * MS_NS);
        assertFloor();
    }

    /**
     * Code review 1 B1: the interval is counted from when the previous step's packets were really fed (its ticks,
     * after Minecraft ran them), not from the wall time sampled at {@code frameStart()}. Frame 6's pass stalls 100 ms
     * between {@code frameStart()} and {@code tickHead()}; at 1 ms passes frame 7 must still wait a whole step after
     * frame 6's tick was fed.
     */
    @Test
    public void theIntervalCountsFromWhenTheTicksWereFedNotFromFrameStart() {
        final ExportPlan plan = new ExportPlan(ORIGIN + 1_000_000L, ORIGIN + 2_000_000L, 60);
        final boolean[] armed = {false};
        final long[] fed = {-1};
        run(plan, last -> {
            if (armed[0] && fed[0] < 0 && fx.stallBeforeTicksNs == 0) {
                fed[0] = fx.ops.nanos; // the pass that just ended fed frame 6's tick after the stall
            }
            if (!armed[0] && last == 5) {
                armed[0] = true;
                fx.stallBeforeTicksNs = 100 * MS_NS;
            }
            return MS_NS;
        }, null);
        assertNoStepSkipped(plan);
        assertTrue("frame 6's tick was fed after the stall", fed[0] > 0);
        assertEquals("the stall fed frame 6's step", fed[0], captures.get(6)[1]);
        long need = (plan.timeOfFrame(7) - plan.timeOfFrame(6)) * 1000L;
        long got = captures.get(7)[1] - fed[0];
        assertTrue("frame 7 only " + got / 1000 + " us after frame 6's tick was fed, needs " + need / 1000,
                got >= need);
        assertFloor();
    }

    @Test
    public void thePreRollAndTheFirstStepAfterTheSeekArePacedToo() {
        ExportPlan plan = new ExportPlan(ORIGIN + 1_000_000L, ORIGIN + 1_200_000L, 60);
        run(plan, last -> 2 * MS_NS, null);
        assertTrue("the seek landed", landedWall > 0);
        Step first = steps.get(0);
        for (Step s : steps) {
            if (s.wall > landedWall) {
                first = s;
                break;
            }
        }
        assertTrue(first.preroll);
        assertTrue("the first step after the seek waits one step: " + (first.wall - landedWall) / 1000 + " us",
                first.wall - landedWall >= 16_666_000L);
        int pre = 0;
        for (Step s : steps) {
            if (s.preroll) {
                pre++;
            }
        }
        assertTrue("a second of pre-roll steps: " + pre, pre >= 59);
        assertFloor();
        assertTrue("the export took at least the pre-roll plus the range: " + (captures.get(captures.size() - 1)[1]
                - landedWall) / MS_NS + " ms", captures.get(captures.size() - 1)[1] - landedWall >= 1_183 * MS_NS);
    }

    /** A machine slower than real time is never held back: every pass applies a step. */
    @Test
    public void aSlowMachineIsNeverMadeToWait() {
        ExportPlan plan = new ExportPlan(ORIGIN + 500_000L, ORIGIN + 1_000_000L, 60);
        run(plan, last -> 30 * MS_NS, null);
        assertNoStepSkipped(plan);
        for (int i = 1; i < steps.size(); i++) {
            if (steps.get(i - 1).wall < landedWall) {
                continue;
            }
            assertEquals("no pass without a step at 30 ms passes: " + steps.get(i - 1) + " -> " + steps.get(i),
                    30 * MS_NS, steps.get(i).wall - steps.get(i - 1).wall);
        }
    }

    /** At 10 ms passes, a step waits at most until the first pass past its floor: under one step interval. */
    @Test
    public void aStepWaitsAtMostOneStepInterval() {
        ExportPlan plan = new ExportPlan(ORIGIN + 500_000L, ORIGIN + 1_000_000L, 60);
        run(plan, last -> 10 * MS_NS, null);
        assertNoStepSkipped(plan);
        for (int i = 1; i < steps.size(); i++) {
            if (steps.get(i - 1).wall <= landedWall) {
                continue;
            }
            long floor = steps.get(i - 1).wall + (steps.get(i).p - steps.get(i - 1).p) * 1000L;
            long late = steps.get(i).wall - floor;
            assertTrue("step " + steps.get(i) + " " + late / 1000 + " us past its floor",
                    late >= 0 && late < 10 * MS_NS);
        }
        assertFloor();
    }

    /** Pause and speed pressed mid-export change neither the steps nor when they are applied. */
    @Test
    public void pauseAndSpeedDoNothing() throws IOException {
        ExportPlan plan = new ExportPlan(ORIGIN + 500_000L, ORIGIN + 900_000L, 60);
        run(plan, last -> 7 * MS_NS, null);
        List<String> plain = new ArrayList<String>();
        long base = steps.get(0).wall;
        for (Step s : steps) {
            plain.add((s.p - ORIGIN) + "@" + (s.wall - base));
        }
        steps.clear();
        captures.clear();
        landedWall = -1;
        client();
        run(plan, last -> 7 * MS_NS, () -> {
            fx.session.setPaused(true);
            fx.session.faster();
            fx.session.faster();
        });
        List<String> pressed = new ArrayList<String>();
        base = steps.get(0).wall;
        for (Step s : steps) {
            pressed.add((s.p - ORIGIN) + "@" + (s.wall - base));
        }
        assertEquals(plain, pressed);
    }
}
