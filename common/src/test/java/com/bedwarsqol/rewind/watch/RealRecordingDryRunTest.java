package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Loads every real recording in the folder named by {@code COBBLIFY_REWIND_SAMPLES} (skipped when it is unset) and
 * checks the loader's invariants on real data. Recordings are player data: the output is one counts-only line per
 * file, numbered by position, never the file id, a name, chat or a map.
 *
 * <p>Phase 2 adds the engine dry run: every record through the packet policy and the tick-locked feed, seek plans to
 * every marker and to 25/50/75/100 %, and the trace of straight play to the last tick against a seek to it (and a
 * backward seek to the middle against straight play's prefix).
 */
public class RealRecordingDryRunTest {

    static File[] samples() {
        String dir = System.getenv("COBBLIFY_REWIND_SAMPLES");
        Assume.assumeTrue("COBBLIFY_REWIND_SAMPLES unset", dir != null && !dir.trim().isEmpty());
        File[] files = new File(dir.trim()).listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.endsWith(".cbr");
            }
        });
        Assume.assumeTrue("no .cbr files", files != null && files.length > 0);
        Arrays.sort(files);
        return files;
    }

    static File jsonFor(File cbr) {
        String name = cbr.getName();
        return new File(cbr.getParentFile(), name.substring(0, name.length() - 4) + ".json");
    }

    @Test
    public void everyRealRecordingLoadsAndKeepsTheLoaderInvariants() {
        File[] files = samples();
        for (int n = 0; n < files.length; n++) {
            String label = "file #" + (n + 1);
            long t0 = System.nanoTime();
            LoadedRecording r = LoadedRecording.load(files[n], jsonFor(files[n]));
            long loadMs = (System.nanoTime() - t0) / 1_000_000L;
            assertNull(label + " problem", r.problem());
            assertNull(label + " quick check", LoadedRecording.quickCheck(files[n], jsonFor(files[n])));

            for (LoadedRecording.Segment s : r.segments()) {
                if (s.netRecordCount() > 0) {
                    int id = s.firstPacketId();
                    assertTrue(label + " segment starts with S01/S07",
                            id == RewindFormat.PACKET_JOIN_GAME || id == RewindFormat.PACKET_RESPAWN);
                }
            }

            NetCursor c = r.newCursor();
            long records = 0;
            long backInSegment = 0;
            long backAcrossSegments = 0;
            long last = Long.MIN_VALUE;
            int seg = Integer.MIN_VALUE;
            while (!c.atEnd()) {
                if (c.segment() == seg) {
                    if (c.timeUs() < last) {
                        backInSegment++;
                    }
                } else {
                    if (c.timeUs() < last) {
                        backAcrossSegments++;
                    }
                    int id = c.packetId();
                    assertTrue(label + " boundary first", c.firstOfSegment()
                            && (id == RewindFormat.PACKET_JOIN_GAME || id == RewindFormat.PACKET_RESPAWN));
                    seg = c.segment();
                }
                last = c.timeUs();
                records++;
                c.advance();
            }
            assertEquals(label + " NET times go back inside a segment", 0, backInSegment);
            assertEquals(label + " cursor count", r.netRecordCount(), records);

            SelfTrack track = r.selfTrack();
            for (int i = 0; i < track.segmentCount(); i++) {
                SelfTrack.Segment s = track.segment(i);
                if (s.tickCount() > 0) {
                    assertEquals(label + " first TICK is full", RewindFormat.TICK_ALL, s.tickMask(0));
                }
            }
            assertTrue(label + " has TICKs", track.tickCount() > 0);

            Timeline t = r.timeline();
            assertNotNull(label + " timeline", t);
            assertTrue(label + " start inside the data", t.startInsideData());
            assertTrue(label + " end after start", t.endUs() >= t.gameStartUs());
            for (Timeline.Marker m : t.markers()) {
                assertTrue(label + " marker inside the game", m.timeUs >= t.gameStartUs() && m.timeUs <= t.endUs());
            }
            assertEquals(label + " every recorded marker lies inside the game", r.trackMarkerCount(),
                    t.markers().size());

            System.out.println("rewind dry run " + label + ": segments=" + r.segments().size()
                    + " skipped=" + r.skippedSegments() + " torn=" + r.tornTail()
                    + " netBlocks=" + r.netBlockCount() + " netRecords=" + records
                    + " storedNetKB=" + r.retainedNetBytes() / 1024 + " rawNetKB=" + r.rawNetBytes() / 1024
                    + " backAcrossSegments=" + backAcrossSegments
                    + " ticks=" + track.tickCount() + " camera=" + track.cameraCount()
                    + " equip=" + track.equipCount() + " markers=" + t.markers().size()
                    + " startTick=" + t.startTick() + " endTick=" + t.endTick()
                    + " lastNetTick=" + t.tickOf(t.lastNetUs()) + " loadMs=" + loadMs);
            r.close();
        }
    }

    /** Hashes what the feeder hands over, per tick: records (index, id, length, time), hooks and the sample time. */
    private static final class TraceSink implements PacketSink {
        final TickFeeder feeder;
        final long[] hash;
        final long[] fedThrough;
        long tick = -1;
        long h = 17;
        long records;
        long boundaries;
        long hooks;

        TraceSink(TickFeeder feeder, long ticks) {
            this.feeder = feeder;
            this.hash = new long[(int) ticks + 1];
            this.fedThrough = new long[(int) ticks + 1];
        }

        private void mix(long v) {
            h = h * 1_000_003L ^ v;
        }

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            mix(feeder.recordIndex());
            mix(packetId);
            mix(len);
            mix(feeder.recordTimeUs());
            if (feeder.recordIndex() != TickFeeder.HEADER) {
                records++;
            }
            return true;
        }

        @Override
        public void boundary(int packetId, byte[] buf, int off, int len) {
            boundaries++;
            mix(-packetId);
        }

        @Override
        public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
            hooks++;
            mix(-1000 - packetId);
            return true;
        }

        @Override
        public void sample(long t, long timeUs) {
            mix(t);
            mix(timeUs);
            hash[(int) t] = h;
            fedThrough[(int) t] = records;
        }

        void run(ReplayTicks ticks) {
            long k = ticks.beginTick();
            tick = k;
            assertEquals(TickFeeder.State.RUNNING, feeder.feedForTick(k, this));
            ticks.finishTick();
        }

        void catchUp(ReplayTicks ticks) {
            while (ticks.catchingUp()) {
                run(ticks);
            }
        }
    }

    @Test
    public void everyRealRecordingFeedsTheSameTraceStraightAndSeeked() {
        File[] files = samples();
        for (int n = 0; n < files.length; n++) {
            String label = "file #" + (n + 1);
            LoadedRecording r = LoadedRecording.load(files[n], jsonFor(files[n]));
            assertNull(label + " problem", r.problem());
            Timeline t = r.timeline();
            long start = t.startTick();
            long end = t.endTick();

            // Straight play from the opening to the end: frames of 0-10 ticks, random pauses and speed changes.
            long t0 = System.nanoTime();
            TickFeeder straightFeeder = new TickFeeder(r);
            TraceSink straight = new TraceSink(straightFeeder, end);
            ReplayTicks ticks = new ReplayTicks(end);
            straightFeeder.feedHeaders(straight);
            ticks.seek(SeekPlanner.opening(t));
            straight.catchUp(ticks);
            assertEquals(label + " opening lands on the game start", start, ticks.ticksRun());
            Random rnd = new Random(n);
            while (!ticks.atEnd()) {
                if (rnd.nextInt(20) == 0) {
                    ticks.setPaused(!ticks.paused());
                } else if (rnd.nextInt(20) == 0) {
                    if (rnd.nextBoolean()) {
                        ticks.faster();
                    } else {
                        ticks.slower();
                    }
                }
                int frame = ticks.frameTicks(rnd.nextInt(11));
                for (int i = 0; i < frame; i++) {
                    straight.run(ticks);
                }
                if (ticks.paused() && !ticks.atEnd() && rnd.nextInt(4) == 0) {
                    ticks.setPaused(false);
                }
            }
            long feedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertEquals(label + " no failures", 0, straightFeeder.failures());
            assertEquals(end, ticks.ticksRun());
            long reached = straightFeeder.recordsPassed();
            if (t.tickOf(t.lastNetUs()) < end) {
                assertEquals(label + " every record reached by the end tick", r.netRecordCount(), reached);
            }

            // A seek straight to the last tick (one catch-up from a fresh start).
            TickFeeder seekFeeder = new TickFeeder(r);
            TraceSink seeked = new TraceSink(seekFeeder, end);
            ReplayTicks seekTicks = new ReplayTicks(end);
            seekFeeder.feedHeaders(seeked);
            seekTicks.seek(SeekPlanner.plan(0, end));
            seeked.catchUp(seekTicks);
            assertArrayEquals(label + " trace: straight play vs a seek to the last tick",
                    Arrays.copyOf(straight.hash, (int) end), Arrays.copyOf(seeked.hash, (int) end));

            // A backward seek from the end to the middle: restart, then the same prefix as straight play.
            long mid = start + (end - start) / 2;
            SeekPlanner.Plan back = SeekPlanner.plan(seekTicks.ticksRun(), mid);
            assertTrue(back.restart);
            TraceSink again = new TraceSink(seekFeeder, end);
            seekFeeder.restart();
            seekFeeder.feedHeaders(again);
            seekTicks.seek(back);
            again.catchUp(seekTicks);
            assertEquals(mid, seekTicks.ticksRun());
            assertArrayEquals(label + " trace: backward seek vs straight play's prefix",
                    Arrays.copyOf(straight.hash, (int) mid), Arrays.copyOf(again.hash, (int) mid));
            assertEquals(label + " the same failures are not counted twice", 0, seekFeeder.failures());

            // Seek plans from the opening (forward, in place) and from the end (backward, restart).
            MarkerNav nav = new MarkerNav(t);
            long markerMaxTicks = 0;
            long markerMaxRecords = 0;
            for (int i = 0; i < nav.count(); i++) {
                long target = SeekPlanner.clamp(t, nav.landingTick(i));
                SeekPlanner.Plan p = SeekPlanner.plan(start, target);
                assertTrue(label + " marker landing at or after the start", !p.restart);
                markerMaxTicks = Math.max(markerMaxTicks, p.catchUpTicks);
                markerMaxRecords = Math.max(markerMaxRecords, recordsBetween(straight, start, target));
            }
            StringBuilder plans = new StringBuilder();
            for (int pct = 25; pct <= 100; pct += 25) {
                long target = start + (end - start) * pct / 100;
                SeekPlanner.Plan fwd = SeekPlanner.plan(start, target);
                SeekPlanner.Plan bwd = SeekPlanner.plan(end, target);
                assertEquals(target - start, fwd.catchUpTicks);
                assertEquals(pct == 100 ? 0 : target, bwd.catchUpTicks);
                plans.append(" seek").append(pct).append("=").append(fwd.catchUpTicks).append("t/")
                        .append(recordsBetween(straight, start, target)).append("r back=")
                        .append(bwd.catchUpTicks).append("t/").append(recordsBetween(straight, 0, target))
                        .append('r');
            }

            System.out.println("rewind engine dry run " + label + ": startTick=" + start + " endTick=" + end
                    + " reached=" + reached + "/" + r.netRecordCount() + " appliedNet=" + straight.records
                    + " applied=" + straightFeeder.applied() + " dropped=" + straightFeeder.dropped()
                    + " boundaries=" + straight.boundaries + " hooks=" + straight.hooks
                    + " state=" + straightFeeder.state() + " markers=" + nav.count()
                    + " markerSeekMaxTicks=" + markerMaxTicks + " markerSeekMaxRecords=" + markerMaxRecords
                    + plans + " straightFeedMs=" + feedMs);
            r.close();
        }
    }

    /** Records fed in ticks {@code [from, to)} of straight play. */
    private static long recordsBetween(TraceSink s, long from, long to) {
        long a = from <= 0 ? 0 : s.fedThrough[(int) from - 1];
        long b = to <= 0 ? 0 : s.fedThrough[(int) to - 1];
        return b - a;
    }
}
