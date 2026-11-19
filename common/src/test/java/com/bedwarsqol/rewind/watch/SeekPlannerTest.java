package com.bedwarsqol.rewind.watch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Seek plans: forward in place, backward from tick 0, clamping to the timeline, and feeding along a plan. */
public class SeekPlannerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long S = 1_000_000L;

    @Test
    public void forwardCatchesUpInPlace() {
        SeekPlanner.Plan p = SeekPlanner.plan(100, 250);
        assertFalse(p.restart);
        assertEquals(250, p.targetTick);
        assertEquals("tickOf(target) - ticks run", 150, p.catchUpTicks);
        SeekPlanner.Plan same = SeekPlanner.plan(100, 100);
        assertFalse(same.restart);
        assertEquals(0, same.catchUpTicks);
    }

    @Test
    public void backwardRestartsFromTickZero() {
        SeekPlanner.Plan p = SeekPlanner.plan(300, 120);
        assertTrue(p.restart);
        assertEquals(120, p.targetTick);
        assertEquals("tickOf(target) after a restart", 120, p.catchUpTicks);
        assertTrue(SeekPlanner.plan(1, 0).restart);
    }

    @Test
    public void openingRestartsAndCatchesUpToTheGameStart() {
        Timeline tl = Timeline.build(0, 60 * S, 30 * S + 20_000L, 0, null, null);
        SeekPlanner.Plan p = SeekPlanner.opening(tl);
        assertTrue(p.restart);
        assertEquals(tl.startTick(), p.targetTick);
        assertEquals(601, p.targetTick); // ceil(30.02 s / 50 ms)
    }

    @Test
    public void targetsAreClampedToTheTimeline() {
        Timeline tl = Timeline.build(0, 60 * S, 10 * S, 0, null, null);
        assertEquals(200, tl.startTick());
        assertEquals(1201, tl.endTick()); // tick 1200 (60 s) fed too (code review 2, I3)
        assertEquals(200, SeekPlanner.clamp(tl, 0));
        assertEquals(1201, SeekPlanner.clamp(tl, 5000));
        assertEquals(700, SeekPlanner.clamp(tl, 700));
        assertEquals(200, SeekPlanner.tickForTime(tl, 3 * S));
        assertEquals(401, SeekPlanner.tickForTime(tl, 20 * S + 10));
        assertEquals("Left 5 s", 600, SeekPlanner.relative(tl, 700, -5 * S));
        assertEquals("Shift+Right 30 s", 1201, SeekPlanner.relative(tl, 700, 30 * S));
        assertEquals("Shift+Left 30 s near the start", 200, SeekPlanner.relative(tl, 300, -30 * S));
    }

    /** Feeds ticks as the session does and returns the record indices fed. */
    private static void runTo(TickFeeder feeder, ReplayTicks ticks, long target, List<Long> fed, PacketSink sink) {
        while (ticks.ticksRun() < target) {
            long k = ticks.beginTick();
            feeder.feedForTick(k, sink);
            ticks.finishTick();
        }
    }

    private static PacketSink recorder(final TickFeeder feeder, final List<Long> fed) {
        return new PacketSink() {
            @Override
            public boolean apply(int packetId, byte[] buf, int off, int len) {
                if (feeder.recordIndex() != TickFeeder.HEADER) {
                    fed.add(feeder.recordIndex());
                }
                return true;
            }

            @Override
            public void boundary(int packetId, byte[] buf, int off, int len) {
            }

            @Override
            public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
                return true;
            }

            @Override
            public void sample(long tick, long timeUs) {
            }
        };
    }

    private LoadedRecording recording() throws Exception {
        long o = 7 * S;
        TickFeederTest.Rec rec = new TickFeederTest.Rec()
                .segment(1, TickFeederTest.standardHeader0(), TickFeederTest.standardHeader1())
                .at(o, RecordingFixture.joinGame(0));
        for (int i = 1; i <= 400; i++) {
            rec.at(o + i * 37_000L, TickFeederTest.p(0x02 + i % 3, i & 0x7F));
        }
        return rec.load(tmp.newFolder(), o + S, o + 400 * 37_000L);
    }

    @Test
    public void aForwardSeekNeverFeedsARecordTwice() throws Exception {
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        TickFeeder feeder = new TickFeeder(r);
        ReplayTicks ticks = new ReplayTicks(tl.endTick());
        List<Long> fed = new ArrayList<Long>();
        PacketSink sink = recorder(feeder, fed);
        runTo(feeder, ticks, 60, fed, sink);
        SeekPlanner.Plan p = SeekPlanner.plan(ticks.ticksRun(), 210);
        assertFalse(p.restart);
        ticks.seek(p);
        long ran = 0;
        while (ticks.catchingUp()) {
            long k = ticks.beginTick();
            feeder.feedForTick(k, sink);
            ticks.finishTick();
            ran++;
        }
        assertEquals(p.catchUpTicks, ran);
        assertEquals(210, ticks.ticksRun());
        for (int i = 0; i < fed.size(); i++) {
            assertEquals("each record once, in order", (long) i, (long) fed.get(i));
        }
        // Exactly the records at or before tick 209's time.
        long want = 0;
        NetCursor c = r.newCursor();
        while (!c.atEnd() && c.timeUs() <= tl.timeOfTick(209)) {
            want++;
            c.advance();
        }
        assertEquals(want, fed.size());
    }

    @Test
    public void aBackwardSeekRestartsAtTickZeroAndCatchesUp() throws Exception {
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        TickFeeder feeder = new TickFeeder(r);
        ReplayTicks ticks = new ReplayTicks(tl.endTick());
        List<Long> straight = new ArrayList<Long>();
        runTo(feeder, ticks, 150, straight, recorder(feeder, straight));
        List<Long> straightTo100 = new ArrayList<Long>();
        {
            TickFeeder f2 = new TickFeeder(r);
            ReplayTicks t2 = new ReplayTicks(tl.endTick());
            runTo(f2, t2, 100, straightTo100, recorder(f2, straightTo100));
        }

        SeekPlanner.Plan p = SeekPlanner.plan(ticks.ticksRun(), 100);
        assertTrue(p.restart);
        assertEquals(100, p.catchUpTicks);
        List<Long> after = new ArrayList<Long>();
        PacketSink sink = recorder(feeder, after);
        feeder.restart();
        ticks.seek(p);
        assertEquals(0, ticks.ticksRun());
        assertEquals(0, feeder.nextTick());
        long ran = 0;
        while (ticks.catchingUp()) {
            long k = ticks.beginTick();
            feeder.feedForTick(k, sink);
            ticks.finishTick();
            ran++;
        }
        assertEquals(100, ran);
        assertEquals("the restart feeds from the first record, as straight play does", straightTo100, after);
        assertTrue(after.size() > 0 && after.get(0) == 0L);
        List<Long> sorted = new ArrayList<Long>(after);
        Collections.sort(sorted);
        assertEquals(sorted, after);
    }
}
