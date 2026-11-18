package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The feed half of the seek equivalence contract (PLAN "Seek equivalence contract", check 1).
 *
 * <p><b>Trace:</b> straight play with frames of 0-10 random ticks, random pauses and speed changes, a forward seek and
 * a backward seek, all to the same tick K, record (tick, records fed, puppet sample time) since the last restart; the
 * traces are equal element by element.
 *
 * <p><b>Pure client model:</b> a small stand-in for the vanilla client driven by the same feed and ticks: an
 * interpolating living entity (3-tick targets, as {@code EntityOtherPlayerMP}), a world clock that ticks, chat lines
 * aged by a GUI counter that grows per tick, a title timer, chunks whose unload removes the entities listed in them
 * (membership changes only in ticks), spawns refused in unloaded chunks, and a GUI reset at every restart. Its state
 * is equal for all three ways of reaching K, including the reviewer's S14 case and the chat lines at 5 s and 15 s
 * with a backward seek from 20 s to 10 s.
 */
public class TickFeedEquivalenceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long S = 1_000_000L;
    /** Origin, deliberately not a round number. */
    private static final long O = 11 * S + 12_345L;
    private static final long GAME_START = O + 3 * S;
    private static final long END = O + 30 * S;

    private static final int PLAYER = 10;
    private static final int MOB_MOVES_OUT = 11;
    private static final int MOB_REFUSED = 12;
    private static final int MOB_REFUSED_LATER = 13;

    // ---------------- model packets (every id passes the policy, except the camera teleport) ----------------

    private static byte[] pk(int id, int... ints) {
        byte[] b = new byte[1 + 4 * ints.length];
        b[0] = (byte) id;
        for (int i = 0; i < ints.length; i++) {
            RewindFormat.putInt(b, 1 + 4 * i, ints[i]);
        }
        return b;
    }

    private static byte[] chat(int line) {
        return pk(0x02, line);
    }

    private static byte[] time(int worldTime) {
        return pk(0x03, worldTime);
    }

    private static byte[] spawnPlayer(int id, int blocksX) {
        return pk(0x0C, id, blocksX * 32);
    }

    private static byte[] spawnMob(int id, int blocksX) {
        return pk(0x0F, id, blocksX * 32);
    }

    private static byte[] move(int id, int dxBlocks) {
        return pk(0x15, id, dxBlocks * 32);
    }

    private static byte[] chunk(int cx, boolean load) {
        return pk(0x21, cx, load ? 1 : 0);
    }

    private static byte[] title(int ticks) {
        return pk(0x45, ticks);
    }

    /** S08 acts on the client player: the policy drops it, so the model's camera never moves. */
    private static byte[] teleportCamera(int x) {
        return pk(0x08, x);
    }

    private static byte[] filler(int i) {
        return pk(0x04, i);
    }

    private static final class Timed {
        final long t;
        final byte[] p;

        Timed(long t, byte[] p) {
            this.t = t;
            this.p = p;
        }
    }

    private LoadedRecording recording() throws Exception {
        List<Timed> game = new ArrayList<Timed>();
        game.add(new Timed(O + 30_000L, chunk(0, true)));
        game.add(new Timed(O + 30_000L, chunk(1, true)));
        game.add(new Timed(O + S, spawnPlayer(PLAYER, 0)));
        game.add(new Timed(O + S, spawnMob(MOB_MOVES_OUT, 17)));
        game.add(new Timed(O + S + 20_000L, spawnMob(MOB_REFUSED, 5 * 16 + 1)));
        game.add(new Timed(O + 2 * S, time(6000)));
        game.add(new Timed(O + 5 * S, chat(1)));
        game.add(new Timed(O + 9 * S, title(90)));
        game.add(new Timed(O + 12 * S + 10_000L, time(13000)));
        game.add(new Timed(O + 15 * S, chat(2)));
        game.add(new Timed(O + 17 * S + 500_000L, teleportCamera(99)));
        game.add(new Timed(O + 20 * S, move(PLAYER, 3))); // the reviewer's S14 case
        game.add(new Timed(O + 21 * S, move(MOB_MOVES_OUT, -7))); // x 17 -> 10: from chunk 1 to chunk 0
        game.add(new Timed(O + 21 * S + 100_000L, chunk(1, false))); // two ticks later
        game.add(new Timed(O + 21 * S + 110_000L, spawnMob(MOB_REFUSED_LATER, 17)));
        game.add(new Timed(O + 24 * S + 950_000L, chat(3)));
        game.add(new Timed(O + 25 * S, chat(4)));
        game.add(new Timed(O + 25 * S + 1, chat(5)));
        game.add(new Timed(O + 28 * S, chat(6)));
        for (int i = 0; i < 100; i++) {
            game.add(new Timed(O + 100_000L + i * 300_000L, filler(i)));
        }
        // Stable sort by time keeps the listed order of records that share a time.
        Collections.sort(game, new Comparator<Timed>() {
            @Override
            public int compare(Timed a, Timed b) {
                return a.t < b.t ? -1 : a.t > b.t ? 1 : 0;
            }
        });
        TickFeederTest.Rec rec = new TickFeederTest.Rec()
                .segment(1, TickFeederTest.standardHeader0(), TickFeederTest.standardHeader1())
                .at(O, RecordingFixture.joinGame(0))
                // A second segment, as in the real files: a same-dimension S07 a few ms in. Its header is never fed.
                .segment(2, new byte[][]{RecordingFixture.joinGame(-1)}, TickFeederTest.standardHeader1())
                .at(O + 20_000L, RecordingFixture.respawn(0));
        for (Timed t : game) {
            rec.at(t.t, t.p);
        }
        return rec.load(tmp.newFolder(), GAME_START, END);
    }

    // ---------------- the model ----------------

    static final class Model {
        static final class Ent {
            final int id;
            final boolean player;
            double x;
            double serverX;
            double target;
            int steps;
            int listed;

            Ent(int id, boolean player, double x) {
                this.id = id;
                this.player = player;
                this.x = x;
                this.serverX = x;
                this.target = x;
                this.listed = chunkOf(x);
            }
        }

        boolean guiResetAtRestart = true;
        boolean world;
        int dimension;
        long worldTime;
        long counter;
        final TreeMap<Integer, Ent> ents = new TreeMap<Integer, Ent>();
        final TreeSet<Integer> chunks = new TreeSet<Integer>();
        final List<int[]> chat = new ArrayList<int[]>();
        int titleTimer;
        int cameraX;

        static int chunkOf(double x) {
            return (int) Math.floor(x / 16.0);
        }

        /** A restart: the world is torn down; GUI state outside the world is reset (round 2 B2). */
        void restart() {
            if (guiResetAtRestart) {
                chat.clear();
                titleTimer = 0;
                counter = 0;
            }
            world = false;
            ents.clear();
            chunks.clear();
            worldTime = 0;
        }

        private void newWorld(int dim) {
            world = true;
            dimension = dim;
            ents.clear();
            chunks.clear();
            worldTime = 0;
        }

        void apply(int id, byte[] b, int off, int len) {
            switch (id) {
                case 0x01:
                    newWorld(b[off + 6]);
                    break;
                case 0x07: {
                    int dim = RewindFormat.getInt(b, off + 1);
                    if (!world || dim != dimension) {
                        newWorld(dim);
                    }
                    break;
                }
                case 0x02:
                    chat.add(new int[]{RewindFormat.getInt(b, off + 1), (int) counter});
                    break;
                case 0x03:
                    worldTime = RewindFormat.getInt(b, off + 1);
                    break;
                case 0x0C:
                case 0x0F: {
                    int eid = RewindFormat.getInt(b, off + 1);
                    double x = RewindFormat.getInt(b, off + 5) / 32.0;
                    boolean player = id == 0x0C;
                    if (player || chunks.contains(chunkOf(x))) {
                        ents.put(eid, new Ent(eid, player, x));
                    }
                    break;
                }
                case 0x15: {
                    Ent e = ents.get(RewindFormat.getInt(b, off + 1));
                    if (e != null) {
                        e.serverX += RewindFormat.getInt(b, off + 5) / 32.0;
                        e.target = e.serverX;
                        e.steps = 3;
                    }
                    break;
                }
                case 0x21: {
                    int cx = RewindFormat.getInt(b, off + 1);
                    if (RewindFormat.getInt(b, off + 5) != 0) {
                        chunks.add(cx);
                    } else {
                        chunks.remove(cx);
                        for (Iterator<Ent> it = ents.values().iterator(); it.hasNext(); ) {
                            if (it.next().listed == cx) {
                                it.remove();
                            }
                        }
                    }
                    break;
                }
                case 0x45:
                    titleTimer = RewindFormat.getInt(b, off + 1);
                    break;
                case 0x08:
                    cameraX = RewindFormat.getInt(b, off + 1);
                    break;
                default:
                    break;
            }
        }

        /** One world tick: GUI counter and title always; world clock, interpolation and chunk lists with a world. */
        void tick() {
            counter++;
            if (titleTimer > 0) {
                titleTimer--;
            }
            if (!world) {
                return;
            }
            worldTime++;
            for (Ent e : ents.values()) {
                if (e.steps > 0) {
                    e.x += (e.target - e.x) / e.steps;
                    e.steps--;
                }
                int c = chunkOf(e.x);
                if (c != e.listed && chunks.contains(c)) {
                    e.listed = c;
                }
            }
        }

        List<int[]> visibleChat() {
            List<int[]> out = new ArrayList<int[]>();
            for (int[] l : chat) {
                long age = counter - l[1];
                if (age < 200) {
                    out.add(new int[]{l[0], (int) age});
                }
            }
            return out;
        }

        String snapshot() {
            StringBuilder sb = new StringBuilder();
            sb.append("world=").append(world).append(" dim=").append(dimension).append(" time=").append(worldTime)
                    .append(" counter=").append(counter).append(" title=").append(titleTimer)
                    .append(" camera=").append(cameraX).append(" chunks=").append(chunks).append(" ents=");
            for (Map.Entry<Integer, Ent> en : ents.entrySet()) {
                Ent e = en.getValue();
                sb.append('[').append(e.id).append(e.player ? "p" : "m").append(" x=").append(e.x)
                        .append(" sx=").append(e.serverX).append(" in=").append(e.listed).append(" steps=")
                        .append(e.steps).append(']');
            }
            sb.append(" chat=");
            for (int[] l : visibleChat()) {
                sb.append('[').append(l[0]).append(" age ").append(l[1]).append(']');
            }
            return sb.toString();
        }
    }

    // ---------------- the harness: drives feeder, ticks and model as the session will ----------------

    static final class Harness implements PacketSink {
        final Timeline tl;
        final TickFeeder feeder;
        final ReplayTicks ticks;
        final Model model = new Model();
        final Random rnd;
        /** Since the last restart. */
        final List<String> trace = new ArrayList<String>();
        long tick = -1;
        int restarts;

        Harness(LoadedRecording r, long seed) {
            tl = r.timeline();
            feeder = new TickFeeder(r);
            ticks = new ReplayTicks(tl.endTick());
            rnd = new Random(seed);
        }

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            trace.add("R t" + tick + " #" + feeder.recordIndex() + " id" + packetId + " at" + feeder.recordTimeUs());
            model.apply(packetId, buf, off, len);
            return true;
        }

        @Override
        public void boundary(int packetId, byte[] buf, int off, int len) {
            trace.add("B t" + tick + " id" + packetId);
        }

        @Override
        public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
            trace.add("H t" + tick + " id" + packetId);
            return true;
        }

        @Override
        public void sample(long t, long timeUs) {
            trace.add("S t" + t + " at" + timeUs);
        }

        /** Rebuild: GUI reset, world gone, headers before tick 0. */
        void restart() {
            restarts++;
            feeder.restart();
            model.restart();
            trace.clear();
            tick = -1;
            feeder.feedHeaders(this);
        }

        void runTick() {
            long k = ticks.beginTick();
            tick = k;
            assertEquals(TickFeeder.State.RUNNING, feeder.feedForTick(k, this));
            model.tick();
            ticks.finishTick();
        }

        void seek(SeekPlanner.Plan p) {
            if (p.restart) {
                restart();
            }
            ticks.seek(p);
            while (ticks.catchingUp()) {
                int budget = 1 + rnd.nextInt(40); // frames of a bounded catch-up
                for (int i = 0; i < budget && ticks.catchingUp(); i++) {
                    runTick();
                }
            }
        }

        Harness open() {
            seek(SeekPlanner.opening(tl));
            return this;
        }

        Harness seekTo(long target) {
            seek(SeekPlanner.plan(ticks.ticksRun(), target));
            return this;
        }

        /** Straight play to tick k with frames of 0-10 ticks, random pauses and speed changes; never past k. */
        Harness playTo(long k) {
            while (ticks.ticksRun() < k) {
                int r = rnd.nextInt(10);
                if (r == 0) {
                    ticks.setPaused(!ticks.paused());
                } else if (r == 1) {
                    if (rnd.nextBoolean()) {
                        ticks.faster();
                    } else {
                        ticks.slower();
                    }
                }
                int n = (int) Math.min(ticks.frameTicks(rnd.nextInt(11)), k - ticks.ticksRun());
                for (int i = 0; i < n; i++) {
                    runTick();
                }
                if (ticks.paused() && rnd.nextInt(3) == 0) {
                    ticks.setPaused(false);
                }
            }
            assertEquals(k, ticks.ticksRun());
            return this;
        }
    }

    private long tick(Timeline tl, long us) {
        return tl.tickOf(us);
    }

    // ---------------- tests ----------------

    @Test
    public void straightPlayForwardSeekAndBackwardSeekGiveEqualTracesAndModelState() throws Exception {
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        long start = tl.startTick();
        long end = tl.endTick();
        long[] targets = {tick(tl, O + 10 * S), tick(tl, O + 21 * S + 50_000L), tick(tl, O + 25 * S), end};
        int compared = 0;
        for (long k : targets) {
            for (long seed = 0; seed < 15; seed++) {
                Harness straight = new Harness(r, seed).open().playTo(k);
                assertEquals(1, straight.restarts);

                Random pick = new Random(seed * 31 + k);
                long j = start + (long) (pick.nextDouble() * (k - start));
                Harness forward = new Harness(r, seed + 1000).open().playTo(j);
                if (pick.nextBoolean()) {
                    forward.ticks.setPaused(true);
                }
                forward.seekTo(k);
                assertEquals("forward seeks catch up in place", 1, forward.restarts);
                assertEquals(k, forward.ticks.ticksRun());
                assertEquals("forward trace, K=" + k + " seed " + seed, straight.trace, forward.trace);
                assertEquals("forward state, K=" + k + " seed " + seed, straight.model.snapshot(),
                        forward.model.snapshot());
                compared++;

                if (k < end) {
                    long j2 = k + 1 + (long) (pick.nextDouble() * (end - k - 1));
                    Harness backward = new Harness(r, seed + 2000).open().playTo(j2);
                    backward.seekTo(k);
                    assertEquals("backward seeks restart", 2, backward.restarts);
                    assertEquals(k, backward.ticks.ticksRun());
                    assertEquals("backward trace, K=" + k + " seed " + seed, straight.trace, backward.trace);
                    assertEquals("backward state, K=" + k + " seed " + seed, straight.model.snapshot(),
                            backward.model.snapshot());
                    compared++;
                }
            }
        }
        assertEquals(15 * 4 + 15 * 3, compared);
    }

    @Test
    public void theReviewersS14CaseAPausedForwardSeekRunsTheInterpolationTicks() throws Exception {
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        Harness straight = new Harness(r, 7).open().playTo(tick(tl, O + 25 * S));
        straight.ticks.setPaused(true);

        Harness seeked = new Harness(r, 8).open().playTo(tick(tl, O + 15 * S));
        seeked.ticks.setPaused(true);
        assertEquals(0.0, seeked.model.ents.get(PLAYER).x, 0.0);
        seeked.seekTo(tick(tl, O + 25 * S));
        assertTrue("still paused after the seek", seeked.ticks.paused());
        assertEquals(0, seeked.ticks.frameTicks(10));

        assertEquals("x = 3 as in straight play", 3.0, seeked.model.ents.get(PLAYER).x, 0.0);
        assertEquals(3.0, straight.model.ents.get(PLAYER).x, 0.0);
        assertEquals(straight.model.snapshot(), seeked.model.snapshot());
        assertEquals(straight.trace, seeked.trace);
    }

    @Test
    public void chatLinesAtFiveAndFifteenSecondsSeekedBackFromTwentyToTen() throws Exception {
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        long ten = tick(tl, O + 10 * S);
        Harness straight = new Harness(r, 3).open().playTo(ten);
        Harness back = new Harness(r, 4).open().playTo(tick(tl, O + 20 * S));
        assertEquals("both lines kept at 20 s", 2, back.model.chat.size());
        assertEquals("the 5 s line has faded (200 ticks)", 1, back.model.visibleChat().size());
        back.seekTo(ten);

        assertEquals("one 5 s line", 1, back.model.chat.size());
        List<int[]> lines = back.model.visibleChat();
        assertEquals(1, lines.size());
        assertEquals(1, lines.get(0)[0]);
        assertEquals("aged 5 s of ticks", 100, lines.get(0)[1]);
        List<int[]> want = straight.model.visibleChat();
        assertEquals(1, want.size());
        assertEquals("age equal to straight play", want.get(0)[1], lines.get(0)[1]);
        assertEquals(straight.model.snapshot(), back.model.snapshot());
    }

    @Test
    public void withoutTheGuiResetTheBackwardSeekWouldDiffer() throws Exception {
        // Shows the model can tell: the same backward seek with no GUI reset keeps old lines and the old counter.
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        long ten = tick(tl, O + 10 * S);
        Harness straight = new Harness(r, 3).open().playTo(ten);
        Harness back = new Harness(r, 4);
        back.model.guiResetAtRestart = false;
        back.open().playTo(tick(tl, O + 20 * S)).seekTo(ten);
        assertNotEquals(straight.model.snapshot(), back.model.snapshot());
        assertEquals(straight.trace, back.trace);
    }

    @Test
    public void anEntityThatLeftAChunkBeforeItsUnloadSurvivesEverySeek() throws Exception {
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        long k = tick(tl, O + 22 * S);
        Harness straight = new Harness(r, 5).open().playTo(k);
        Harness forward = new Harness(r, 6).open().playTo(tick(tl, O + 19 * S)).seekTo(k);
        Harness backward = new Harness(r, 9).open().playTo(tick(tl, O + 29 * S)).seekTo(k);
        for (Harness h : new Harness[]{straight, forward, backward}) {
            Model.Ent mob = h.model.ents.get(MOB_MOVES_OUT);
            assertTrue("listed in chunk 0 before chunk 1 unloaded", mob != null && mob.listed == 0);
            assertEquals(10.0, mob.x, 1e-9);
            assertNull("spawn in an unloaded chunk refused", h.model.ents.get(MOB_REFUSED));
            assertNull(h.model.ents.get(MOB_REFUSED_LATER));
            assertFalse(h.model.chunks.contains(1));
            assertEquals("the dropped camera packet never applied", 0, h.model.cameraX);
        }
        assertEquals(straight.model.snapshot(), forward.model.snapshot());
        assertEquals(straight.model.snapshot(), backward.model.snapshot());
    }

    @Test
    public void feedingWithoutTicksTheRevisionOneCatchUpWouldDiverge() throws Exception {
        // The rejected design: feed the records up to the target without running world ticks. The model catches it.
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        long k = tick(tl, O + 25 * S);
        Harness straight = new Harness(r, 1).open().playTo(k);
        Harness noTicks = new Harness(r, 2).open().playTo(tick(tl, O + 15 * S));
        while (noTicks.ticks.ticksRun() < k) {
            long t = noTicks.ticks.beginTick();
            noTicks.tick = t;
            noTicks.feeder.feedForTick(t, noTicks);
            noTicks.ticks.finishTick(); // no model.tick(): what Rev 1 did
        }
        assertEquals(straight.trace, noTicks.trace);
        assertEquals("the S14 move never interpolated", 0.0, noTicks.model.ents.get(PLAYER).x, 0.0);
        assertNull("the unload removed a mob still listed in the old chunk", noTicks.model.ents.get(MOB_MOVES_OUT));
        assertNotEquals(straight.model.snapshot(), noTicks.model.snapshot());
    }

    @Test
    public void tracesAreTakenSinceTheLastRestartAndStartWithTheHeaders() throws Exception {
        LoadedRecording r = recording();
        Harness h = new Harness(r, 11).open();
        assertTrue(h.trace.get(0).startsWith("R t-1 #-1 id1 "));
        assertEquals("B t-1 id1", h.trace.get(1));
        assertTrue(h.trace.get(2).startsWith("R t-1 #-1 id56 ")); // S38 (S09 dropped)
        assertTrue(h.trace.get(3).startsWith("R t-1 #-1 id71 ")); // S47
        assertTrue(h.trace.get(4).startsWith("R t0 #0 id1 "));
        int samples = 0;
        for (String s : h.trace) {
            if (s.startsWith("S ")) {
                samples++;
            }
        }
        assertEquals("one sample per tick of the opening", r.timeline().startTick(), samples);
    }
}
