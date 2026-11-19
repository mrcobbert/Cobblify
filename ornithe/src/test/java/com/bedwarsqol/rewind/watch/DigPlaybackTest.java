package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Your own digging (PLAN Phase 11, "C. Playback of v2 data"; TASK C1). Live, while you hold the button on a block,
 * {@code PlayerControllerMP} calls {@code theWorld.sendBlockBreakProgress(yourId, block, stage)} every damaging tick
 * (stage = {@code (int)(damage x 10) - 1}; -1 when it stops or breaks, which removes the entry) and
 * {@code sendClickBlockToController} adds {@code effectRenderer.addBlockHitEffects(block, face)}; the break adds
 * {@code addBlockDestroyEffects} (through {@code playAuxSFX(2001)}) before the block is removed. The recorder stores the
 * dig state (DIG) at the end of each tick it changed in, and the predicted break (BLOCK, cause own break).
 *
 * <p>Playback: {@link DigState} (common) carries the recorded state from tick to tick; {@link DigPart} makes the same
 * client calls in each world tick (the crack on the render global, the hit particles; never a packet), and
 * {@link BlockPart} the break particles and the block change, at the recorded ticks. The feed is the real
 * {@link TickFeeder}, so a seek reaches the state straight play reaches.
 */
public class DigPlaybackTest {

    private static final long MS = 1_000L;
    private static final long EO = 40 * MS;
    private static final BlockPos P = new BlockPos(10, 64, -3);
    private static final BlockPos Q = new BlockPos(11, 64, -3);
    private static final int ID = 77;
    private static final int UP = EnumFacing.UP.getIndex();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        PrintStream out = System.out;
        Bootstrap.register();
        System.setOut(out);
    }

    // ------------------------------------------------------------------ DigState (pure)

    private static final int HIT = RewindFormat.DIG_HITTING;

    @Test
    public void whileHittingEachTickRefreshesTheCrackAndAddsHitParticles() {
        DigState d = new DigState();
        d.record(HIT, P.toLong(), UP, 3);
        for (int i = 0; i < 3; i++) { // a tick without a record carries the state (vanilla sends every tick)
            d.tick();
            assertTrue(d.crackCall());
            assertEquals(P.toLong(), d.crackPos());
            assertEquals(3, d.crackStage());
            assertTrue(d.hit());
            assertEquals(P.toLong(), d.hitPos());
            assertEquals(UP, d.hitFace());
            assertTrue(d.shown());
        }
    }

    @Test
    public void stageMinusOneWhileHittingIsSentAsVanillaDoesAndShowsNothing() {
        DigState d = new DigState();
        d.record(HIT, P.toLong(), UP, -1);
        d.tick();
        assertTrue("vanilla calls sendBlockBreakProgress with -1 while damage < 0.1", d.crackCall());
        assertEquals(-1, d.crackStage());
        assertFalse(d.shown());
        assertTrue("hit particles from the first tick", d.hit());
    }

    @Test
    public void aStopOrBreakClearsTheCrackOnceThenNothing() {
        DigState d = new DigState();
        d.record(HIT, P.toLong(), UP, 6);
        d.tick();
        d.record(0, P.toLong(), UP, -1);
        d.tick();
        assertTrue(d.crackCall());
        assertEquals(P.toLong(), d.crackPos());
        assertEquals(-1, d.crackStage());
        assertFalse(d.hit());
        assertFalse(d.shown());
        d.tick();
        assertFalse("nothing to clear any more", d.crackCall());
        assertFalse(d.hit());
    }

    @Test
    public void notHittingFromTheStartCallsNothing() {
        DigState d = new DigState();
        d.tick();
        assertFalse(d.crackCall());
        assertFalse(d.hit());
        d.record(0, P.toLong(), 2, -1);
        d.tick();
        assertFalse(d.crackCall());
        assertFalse(d.hit());
    }

    @Test
    public void anotherBlockMovesTheCrack() {
        DigState d = new DigState();
        d.record(HIT, P.toLong(), UP, 4);
        d.tick();
        d.record(HIT, Q.toLong(), 3, 0);
        d.tick();
        assertEquals(Q.toLong(), d.crackPos());
        assertEquals(0, d.crackStage());
        assertEquals(Q.toLong(), d.hitPos());
        assertEquals(3, d.hitFace());
        assertEquals(Q.toLong(), d.shownPos());
    }

    @Test
    public void aFaceOutsideTheSixMakesNoHitParticles() {
        DigState d = new DigState();
        d.record(HIT, P.toLong(), 9, 2);
        d.tick();
        assertTrue(d.crackCall());
        assertFalse(d.hit());
    }

    @Test
    public void takeShownHandsOverAShownCrackOnceAndResetForgetsTheState() {
        DigState d = new DigState();
        assertFalse(d.takeShown());
        d.record(HIT, P.toLong(), UP, 5);
        d.tick();
        assertTrue(d.takeShown());
        assertEquals(P.toLong(), d.shownPos());
        assertFalse(d.takeShown());
        d.reset();
        assertFalse(d.hitting());
        d.tick();
        assertFalse(d.crackCall());
    }

    // ------------------------------------------------------------------ DigPart + BlockPart through the feed

    private final List<String> calls = new ArrayList<String>();
    private long tick = -1;
    private int breakerId = ID;

    private DigPart digPart() {
        return new DigPart(() -> breakerId, new DigPart.Out() {
            @Override
            public void crack(int id, BlockPos pos, int stage) {
                calls.add("crack " + id + " " + pos.toLong() + " " + stage + " @" + tick);
            }

            @Override
            public void hit(BlockPos pos, EnumFacing face) {
                calls.add("hit " + pos.toLong() + " " + face.getIndex() + " @" + tick);
            }
        });
    }

    private BlockPart blockPart(BlockPredictionTest.MapWorld world) {
        return new BlockPart(new BlockPart.Source() {
            @Override
            public net.minecraft.world.World world() {
                return world;
            }

            @Override
            public void destroyEffects(BlockPos pos, IBlockState old) {
                calls.add("destroy " + BlockPredictionTest.name(old) + " " + pos.toLong() + " @" + tick);
            }
        });
    }

    private static byte[] dig(int flags, BlockPos pos, int face, int stage) {
        ExtCodec c = new ExtCodec();
        int n = c.dig(flags, pos.toLong(), face, stage);
        return ExtRecords.body(c.buffer(), n);
    }

    /** S01 at 40; heads every 50 ms from 100 to 600; DIG and BLOCK records at their end-of-tick stamps. */
    private LoadedRecording recording(ExtRecords.Builder more) throws Exception {
        TickFeederTest.Rec rec = new TickFeederTest.Rec()
                .segment(1, TickFeederTest.standardHeader0(), TickFeederTest.standardHeader1())
                .at(EO, RecordingFixture.joinGame(0))
                .at(700 * MS, TickFeederTest.p(0x03, 1));
        long[] heads = new long[11];
        int[] inc = new int[11];
        for (int i = 0; i < heads.length; i++) {
            heads[i] = 100 + 50 * i;
        }
        inc[0] = 1;
        ExtRecords.Builder b = TickFeederTest.heads(heads, inc, null);
        if (more != null) {
            ExtRecords m = more.build();
            for (int i = 0; i < m.size(); i++) {
                b.add(m.segmentIndex(i), m.timeUs(i), m.tag(i), m.buffer(), m.offset(i), m.length(i));
            }
        }
        return rec.load(tmp.newFolder(), EO, 800 * MS).withExt(b.build());
    }

    /** One block dug: start at 130, stages 2, 5, (carried), 9, broken at 380 (BLOCK own break at 379). */
    private static ExtRecords.Builder oneDig() {
        byte[] brk = BlockPredictionTest.blockBody(P, Blocks.stone.getDefaultState(), Blocks.air.getDefaultState(),
                RewindFormat.BLOCK_CAUSE_OWN_BREAK);
        return new ExtRecords.Builder()
                .add(0, 130 * MS, RewindFormat.TAG_DIG, dig(HIT, P, UP, -1))
                .add(0, 180 * MS, RewindFormat.TAG_DIG, dig(HIT, P, UP, 2))
                .add(0, 230 * MS, RewindFormat.TAG_DIG, dig(HIT, P, UP, 5))
                .add(0, 330 * MS, RewindFormat.TAG_DIG, dig(HIT, P, UP, 9))
                .add(0, 379 * MS, RewindFormat.TAG_BLOCK, brk)
                .add(0, 380 * MS, RewindFormat.TAG_DIG, dig(0, P, UP, -1));
    }

    private void play(LoadedRecording r, long to, PlaybackPart... parts) {
        TickFeeder feeder = new TickFeeder(r);
        BlockPredictionTest.VanillaSink sink = new BlockPredictionTest.VanillaSink(null,
                Collections.<Integer>emptySet(), parts);
        feeder.feedHeaders(sink);
        for (long k = 0; k <= to; k++) {
            tick = k;
            sink.feed(feeder, k);
        }
    }

    @Test
    public void theCrackGrowsWithHitParticlesAndTheBreakAddsBreakParticlesAtTheRecordedTicks() throws Exception {
        LoadedRecording r = recording(oneDig());
        TickTable t = r.timeline().ticks();
        BlockPredictionTest.MapWorld world = BlockPredictionTest.MapWorld.create();
        world.blocks().put(P.toLong(), Blocks.stone.getDefaultState());
        play(r, t.tickOf(500 * MS), blockPart(world), digPart());
        long p = P.toLong();
        long k = t.tickOf(100 * MS);
        assertEquals(Arrays.asList(
                "crack " + ID + " " + p + " -1 @" + k, "hit " + p + " " + UP + " @" + k,
                "crack " + ID + " " + p + " 2 @" + (k + 1), "hit " + p + " " + UP + " @" + (k + 1),
                "crack " + ID + " " + p + " 5 @" + (k + 2), "hit " + p + " " + UP + " @" + (k + 2),
                "crack " + ID + " " + p + " 5 @" + (k + 3), "hit " + p + " " + UP + " @" + (k + 3),
                "crack " + ID + " " + p + " 9 @" + (k + 4), "hit " + p + " " + UP + " @" + (k + 4),
                "destroy stone " + p + " @" + (k + 5),
                "crack " + ID + " " + p + " -1 @" + (k + 5)), calls);
        assertEquals("air", world.at(P));
        assertEquals("one tick per head", k + 5, t.tickOf(350 * MS));
    }

    @Test
    public void aV1RecordingOrOneWithoutDigRecordsMakesNoCall() throws Exception {
        play(recording(null), 12, digPart());
        assertEquals(Collections.<String>emptyList(), calls);
    }

    @Test
    public void otherRecordsAreIgnored() {
        DigPart part = digPart();
        ExtCodec c = new ExtCodec();
        int n = c.use(72000);
        byte[] use = ExtRecords.body(c.buffer(), n);
        part.local(RewindFormat.TAG_USE, 0, use, 0, use.length);
        part.tick(0, 0);
        assertEquals(Collections.<String>emptyList(), calls);
    }

    /**
     * A restart (opening, backward seek) clears a crack it showed, under the id it showed it with (the new connection
     * has no recorded id yet); so do a boundary (the recorder forgets the dig state at each new world) and the exit.
     */
    @Test
    public void restartBoundaryAndExitClearAShownCrackOnce() {
        for (int which = 0; which < 3; which++) {
            calls.clear();
            breakerId = ID;
            DigPart part = digPart();
            byte[] b = dig(HIT, P, UP, 4);
            part.local(RewindFormat.TAG_DIG, 0, b, 0, b.length);
            part.tick(0, 0);
            calls.clear();
            breakerId = RewindCamera.RESERVED_ID; // a new sink before its S01
            if (which == 0) {
                part.restart();
            } else if (which == 1) {
                part.boundary();
            } else {
                part.exit();
            }
            assertEquals(Collections.singletonList("crack " + ID + " " + P.toLong() + " -1 @" + tick), calls);
            calls.clear();
            part.tick(1, 0);
            part.restart();
            part.exit();
            assertEquals("the state is forgotten and nothing is shown", Collections.<String>emptyList(), calls);
        }
    }

    /**
     * Seek equivalence: a backward seek (restart, catch-up from tick 0) and a forward one (catch-up in place) reach
     * the dig state straight play reaches, and the restart cleared the crack shown before it.
     */
    @Test
    public void afterASeekTheDigStateEqualsStraightPlay() throws Exception {
        LoadedRecording r = recording(oneDig());
        TickTable t = r.timeline().ticks();
        long end = t.tickOf(600 * MS);
        for (long target : new long[]{t.tickOf(150 * MS), t.tickOf(250 * MS), t.tickOf(300 * MS),
                t.tickOf(350 * MS), end}) {
            BlockPredictionTest.MapWorld w1 = BlockPredictionTest.MapWorld.create();
            w1.blocks().put(P.toLong(), Blocks.stone.getDefaultState());
            DigPart straight = digPart();
            play(r, target, blockPart(w1), straight);

            BlockPredictionTest.MapWorld w2 = BlockPredictionTest.MapWorld.create();
            w2.blocks().put(P.toLong(), Blocks.stone.getDefaultState());
            DigPart seeked = digPart();
            play(r, t.tickOf(300 * MS), blockPart(w2), seeked); // mid-dig: stage 9 shown (DIG at 330 is tick 300's)
            calls.clear();
            seeked.restart();
            assertEquals("the restart clears the shown crack",
                    Collections.singletonList("crack " + ID + " " + P.toLong() + " -1 @" + tick), calls);
            BlockPredictionTest.MapWorld w3 = BlockPredictionTest.MapWorld.create();
            w3.blocks().put(P.toLong(), Blocks.stone.getDefaultState());
            play(r, target, blockPart(w3), seeked);
            assertEquals("tick " + target, describe(straight.state()), describe(seeked.state()));
            assertEquals(w1.snapshot(), w3.snapshot());
        }
    }

    private static String describe(DigState d) {
        return d.hitting() + " " + d.pos() + " " + d.face() + " " + d.stage() + " shown=" + d.shown() + " "
                + (d.shown() ? d.shownPos() : 0);
    }

    @Test
    public void registerAddsThePartOnce() {
        DigPart.register();
        DigPart.register();
        assertEquals(1, Collections.frequency(RewindPlayback.parts(), DigPart.instance()));
    }
}
