package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Self state binding (PLAN "Self state binding", round 2 B2; TASK C9): the TICK, EQUIP (and later USE, INV, DIG)
 * samples are written at the end of the tick that produced them, so a sample stamped in {@code [T[k], T[k+1])} is
 * tick k's end state, and the world tick k uses exactly that, for v1 and v2 alike. Run through the production
 * {@link PlaybackSession} and {@link TickFeeder} with a sink that does what the Ornithe sink does with
 * {@link PuppetState} and {@link HandState}.
 */
public class SelfStateBindingTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long MS = 1_000L;
    /** The S01, the origin. */
    private static final long O = 40 * MS;
    private static final String ID = "20261118-120000-bind";

    /** Per sample: tick, selected slot, puppet burning, hotbar slot 1 changed, swing started, puppet x. */
    static final class Row {
        final long tick;
        final int slot;
        final boolean burning;
        final boolean equip1;
        final boolean swing;
        final double x;

        Row(long tick, int slot, boolean burning, boolean equip1, boolean swing, double x) {
            this.tick = tick;
            this.slot = slot;
            this.burning = burning;
            this.equip1 = equip1;
            this.swing = swing;
            this.x = x;
        }
    }

    final class Sink implements PacketSink {
        final PuppetState puppet = new PuppetState();
        final HandState hand = new HandState();
        final List<Row> rows = new ArrayList<Row>();

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            if (packetId == 0x01) {
                ops.world = true;
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
            SelfTrack track = session.recording().selfTrack();
            TickTable table = session.timeline().ticks();
            assertEquals("the sample's time is the tick's", table.timeOf(tick), timeUs);
            assertTrue(puppet.sample(track, table, tick));
            assertTrue(hand.sample(track, table, tick, HandState.SWING_END));
            rows.add(new Row(tick, hand.selectedSlot(), puppet.burning(), hand.hotbarChanged(1),
                    hand.swingStarted(), puppet.x()));
        }
    }

    final class Ops implements ClientOps {
        boolean world;
        long nanos = 5_000_000_000L;
        int elapsed;
        boolean paused;
        File cbr;
        File json;
        ExtRecords ext;
        final Sink sink = new Sink();

        @Override
        public RecordingMeta readMeta(String id) {
            return null;
        }

        @Override
        public boolean connected() {
            return false;
        }

        @Override
        public void disconnect() {
        }

        @Override
        public void settleRecorder() {
        }

        @Override
        public void showLoading() {
        }

        @Override
        public void showViewer() {
        }

        @Override
        public void startLoad(String id, PlaybackSession.Load load) {
            LoadedRecording r = LoadedRecording.load(cbr, json);
            load.succeed(ext == null ? r : r.withExt(ext));
        }

        @Override
        public void resetGui() {
        }

        @Override
        public void newConnection() {
            world = false;
        }

        @Override
        public PacketSink sink() {
            return sink;
        }

        @Override
        public boolean worldPresent() {
            return world;
        }

        @Override
        public boolean gamePaused() {
            return paused;
        }

        @Override
        public int elapsedTicks() {
            return elapsed;
        }

        @Override
        public void setElapsedTicks(int ticks) {
            elapsed = ticks;
        }

        @Override
        public void setPartialTicks(float partial) {
        }

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void teardown() {
        }

        @Override
        public void clearTitlesAndBossBar() {
        }

        @Override
        public void openTitle() {
        }

        @Override
        public void openRejoin() {
        }

        @Override
        public void openGallery() {
        }

        @Override
        public void log(String line) {
        }

        @Override
        public float timerSpeed() {
            return 1f;
        }

        @Override
        public void setTimerSpeed(float speed) {
        }

        @Override
        public int thirdPersonView() {
            return 0;
        }

        @Override
        public void setThirdPersonView(int view) {
        }

        @Override
        public boolean hideGui() {
            return false;
        }

        @Override
        public void setHideGui(boolean hide) {
        }

        @Override
        public boolean showDebugInfo() {
            return false;
        }

        @Override
        public void setShowDebugInfo(boolean show) {
        }

        @Override
        public boolean smoothCamera() {
            return false;
        }

        @Override
        public void setSmoothCamera(boolean smooth) {
        }

        @Override
        public int difficulty() {
            return 2;
        }

        @Override
        public void setDifficulty(int id) {
        }

        @Override
        public void setGamePaused(boolean p) {
            paused = p;
        }

        @Override
        public Object copyChat() {
            return null;
        }

        @Override
        public void restoreChat(Object saved) {
        }

        @Override
        public int updateCounter() {
            return 0;
        }

        @Override
        public void setUpdateCounter(int counter) {
        }
    }

    private Ops ops;
    private PlaybackSession session;

    /** One frame as {@code runGameLoop} runs it, {@code frameNs} after the last. */
    private void frame(long frameNs) {
        ops.nanos += frameNs;
        session.frameStart();
        for (int j = 0; j < ops.elapsed; j++) {
            session.tickHead();
            session.tickEnd();
        }
        ops.paused = false;
        session.afterPauseWrite();
    }

    /**
     * The recording: S01 at 40 ms; TICKs (the tails) at {@code tails} ms with x = index, held slot = index, the
     * second burning and the third with a swing counter of 1 (the swing started in the tick before); an EQUIP of the
     * held stack at 101.2 ms; v2 adds TICK_HEADs at {@code heads}.
     */
    private void open(long[] tails, long[] heads) throws IOException {
        RecordingFixture f = new RecordingFixture();
        f.header(1, RewindFormat.SOURCE_CLIENT_SNAPSHOT, O, RecordingFixture.joinGame(0));
        long[] times = new long[60];
        byte[][] packets = new byte[60][];
        times[0] = O;
        packets[0] = RecordingFixture.joinGame(0);
        for (int i = 1; i < times.length; i++) {
            times[i] = O + i * 10 * MS;
            packets[i] = new byte[]{0x03, (byte) i};
        }
        f.net(1, O, times, packets);
        SelfTrackEncoder enc = new SelfTrackEncoder();
        List<RecordingFixture.Track> track = new ArrayList<RecordingFixture.Track>();
        for (int i = 0; i < tails.length; i++) {
            int flags = i == 1 ? RewindFormat.FLAG_BURNING : 0;
            int swing = i == 2 ? 1 : 0;
            int n = enc.tick(i, 64, 0, 0f, 0f, 0f, flags, i, swing, 0);
            track.add(new RecordingFixture.Track(tails[i] * MS, RewindFormat.TAG_TICK, Arrays.copyOf(enc.buffer(), n)));
            if (i == 1) {
                track.add(new RecordingFixture.Track(101_200L, RewindFormat.TAG_EQUIP,
                        new byte[]{RewindFormat.EQUIP_HELD, 0, 7, 1, 0, 0, 0}));
            }
        }
        f.track(1, tails[0] * MS, track.toArray(new RecordingFixture.Track[0]));
        RecordingMeta meta = RecordingFixture.meta(ID, O, O + 600 * MS);
        f.end(meta, O + 600 * MS);
        File dir = tmp.newFolder();
        ops = new Ops();
        ops.cbr = f.write(dir, ID);
        ops.json = RecordingFixture.writeMeta(dir, ID, meta);
        if (heads != null) {
            ExtCodec c = new ExtCodec();
            ExtRecords.Builder b = new ExtRecords.Builder();
            for (long h : heads) {
                int n = c.tickHead(0);
                b.addEncoded(0, h * MS, RewindFormat.TAG_TICK_HEAD, c.buffer(), n);
            }
            ops.ext = b.build();
        }
        session = new PlaybackSession(ops, new PlaybackGate(0));
        session.open(ID);
        for (int i = 0; i < 50 && session.state() != PlaybackSession.State.WATCHING; i++) {
            frame(0);
        }
        assertEquals(PlaybackSession.State.WATCHING, session.state());
    }

    /** Plays until tick {@code k} has run. */
    private void playThrough(long k) {
        for (int i = 0; i < 400 && session.ticks().ticksRun() <= k; i++) {
            frame(5_000_000L);
        }
    }

    private Row row(long tickTimeMs) {
        long k = session.timeline().ticks().tickOf(tickTimeMs * MS);
        for (Row r : ops.sink.rows) {
            if (r.tick == k) {
                return r;
            }
        }
        throw new AssertionError("tick at " + tickTimeMs + " ms not sampled");
    }

    private void theReviewersCase(String what) {
        playThrough(session.timeline().ticks().tickOf(150 * MS));
        int[] slots = {row(50).slot, row(100).slot, row(150).slot};
        assertArrayEquals(what + ": held slots 0, 1, 2 for the ticks at 50, 100, 150 (the old lookup gave 0, 0, 1)",
                new int[]{0, 1, 2}, slots);
        assertFalse(what, row(50).burning);
        assertTrue(what + ": the burning flag set in tick 1 reaches tick 1's puppet state", row(100).burning);
        assertTrue(what + ": the EQUIP stamped 101.2 is in tick 1's hand state", row(100).equip1);
        assertFalse(what, row(50).equip1);
        assertFalse(what, row(150).equip1);
        assertTrue(what + ": the swing counter 1 at tick 2 means the swing started in tick 1 (one tick of lookahead)",
                row(100).swing);
        assertFalse(what, row(50).swing);
        assertFalse(what, row(150).swing);
        assertEquals(what + ": the puppet moves to tick 1's end position during tick 1", 1.0, row(100).x, 0);
    }

    @Test
    public void v2HeadsGiveEachTickItsOwnEndState() throws IOException {
        open(new long[]{51, 101, 151}, new long[]{50, 100, 150});
        assertEquals(TickTable.Kind.V2, session.timeline().ticks().kind());
        theReviewersCase("v2");
    }

    @Test
    public void theSameDataAsV1RebuildsTheTicksAndBindsTheSame() throws IOException {
        open(new long[]{51, 101, 151}, null);
        assertEquals(TickTable.Kind.V1, session.timeline().ticks().kind());
        theReviewersCase("v1");
    }

    @Test
    public void thePoseLerpsTickJMinusOneToTickJByTheRecordedInterval() throws IOException {
        open(new long[]{51, 101, 151}, new long[]{50, 100, 150});
        SelfTrack track = session.recording().selfTrack();
        TickTable t = session.timeline().ticks();
        SelfTrack.Pose p = new SelfTrack.Pose();
        long j = t.tickOf(100 * MS);
        assertTrue(track.pose(t, j, 0.5f, 125 * MS, p));
        assertEquals("j = 1, partial 0.5: the midpoint of ticks 0 and 1", 0.5, p.x, 1e-9);
        assertTrue(track.pose(t, j, 0f, 100 * MS, p));
        assertEquals(0.0, p.x, 1e-9);
        assertTrue(track.pose(t, j, 1f, 150 * MS, p));
        assertEquals(1.0, p.x, 1e-9);
        assertEquals(64 + SelfTrack.EYE_HEIGHT, p.eyeY, 1e-9);
    }

    @Test
    public void withHeadsAt100And170ThePoseAtPlaybackTime135IsTheMidpoint() throws IOException {
        open(new long[]{51, 101, 171}, new long[]{100, 170});
        TickTable t = session.timeline().ticks();
        // to P = 135: the tick at 100 has run, the one at 170 has not
        for (int i = 0; i < 400 && session.renderTimeUs() < 135 * MS; i++) {
            frame(Math.max(1L, Math.min(5_000_000L, (135 * MS - session.renderTimeUs()) * 1000)));
        }
        assertEquals(135 * MS, session.renderTimeUs());
        assertEquals(t.tickOf(100 * MS), session.renderTick());
        assertEquals("interval 70 ms, not 50", 0.5f, session.renderPartial(), 1e-6f);
        SelfTrack.Pose p = new SelfTrack.Pose();
        assertTrue(session.yourPose(p));
        assertEquals(0.5, p.x, 1e-9);
    }
}
