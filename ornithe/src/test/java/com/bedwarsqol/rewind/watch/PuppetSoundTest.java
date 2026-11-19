package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.IChunkProvider;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The recorded player's own sounds (PLAN Phase 8, B2, B7). The puppet is an other-player entity, whose
 * {@code playSound} is silent on a client ({@code Entity.playSound} ends in an empty {@code RenderGlobal} call), so the
 * hurt sound S19 status 2 makes and the eat ticks of its own item use would be lost; live, both come through
 * {@code EntityPlayerSP.playSound} = {@code worldObj.playSound(posX, posY, posZ, name, volume, pitch, false)}.
 * {@link PuppetSounds} answers the puppet's {@code playSound} question:
 * <ul>
 *   <li>v1 (no SOUND records): every puppet sound plays, once.</li>
 *   <li>A recording with SOUND records (v2): the sounds your own tick made are in the file (Phase 11 plays them), so
 *       the puppet plays only while a recorded packet is being fed (hurt, death: server-caused), never both.</li>
 *   <li>Never during a catch-up or seek.</li>
 * </ul>
 * The feed is the real {@link TickFeeder}; a sink stands in for vanilla's S19 handler, which calls the puppet's
 * {@code playSound} from inside the feed, and the eat tick runs after the feed, where the world tick runs.
 */
public class PuppetSoundTest {

    private static final long MS = 1_000L;
    private static final long EO = 40 * MS;
    private static final String HURT = "game.player.hurt";
    private static final String EAT = "random.eat";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    private final List<String> heard = new ArrayList<String>();

    private final class Source implements PuppetSounds.Source {
        boolean muted;
        LoadedRecording recording;
        TickFeeder feeder;

        @Override
        public boolean muted() {
            return muted;
        }

        @Override
        public LoadedRecording recording() {
            return recording;
        }

        @Override
        public TickFeeder feeder() {
            return feeder;
        }
    }

    private final Source source = new Source();
    private final PuppetSounds part = new PuppetSounds(source,
            (puppet, name, volume, pitch) -> heard.add(name + " " + volume + " " + pitch));
    private final List<Boolean> answers = new ArrayList<Boolean>();

    /** Vanilla's S19 handler on the puppet: status 2 plays the hurt sound through {@code playSound}. */
    private final class HandlerSink extends TickFeederTest.LogSink {
        HandlerSink(TickFeeder feeder) {
            super(feeder);
        }

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            boolean ok = super.apply(packetId, buf, off, len);
            if (packetId == 0x19) {
                answers.add(part.puppetSound(null, HURT, 1f, 1.1f));
            }
            return ok;
        }
    }

    /** S01 at 40 ms, the S19 (status 2) at 120 ms, a filler at 300 ms. */
    private LoadedRecording recording(ExtRecords ext) throws Exception {
        TickFeederTest.Rec rec = new TickFeederTest.Rec()
                .segment(1, TickFeederTest.standardHeader0(), TickFeederTest.standardHeader1())
                .at(EO, RecordingFixture.joinGame(0))
                .at(120 * MS, TickFeederTest.p(0x19, 0, 0, 0, 7, 2))
                .at(300 * MS, TickFeederTest.p(0x03, 1));
        LoadedRecording r = rec.load(tmp.newFolder(), EO, 400 * MS);
        return ext == null ? r : r.withExt(ext);
    }

    private static ExtRecords withStoredSound() {
        ExtCodec c = new ExtCodec();
        int n = c.sound("minecraft:random.eat", 0f, 64f, 0f, 0.5f, 1f);
        return new ExtRecords.Builder().addEncoded(0, 200 * MS, RewindFormat.TAG_SOUND, c.buffer(), n).build();
    }

    /** Feeds every tick to 400 ms; after tick {@code eatTick}'s feed its world tick plays one eat sound. */
    private void play(LoadedRecording r, long eatTick) {
        TickFeeder feeder = new TickFeeder(r);
        source.recording = r;
        source.feeder = feeder;
        HandlerSink sink = new HandlerSink(feeder);
        TickTable t = r.timeline().ticks();
        feeder.feedHeaders(sink);
        for (long k = 0; k <= t.tickOf(400 * MS); k++) {
            sink.feed(k);
            if (k == eatTick) {
                answers.add(part.puppetSound(null, EAT, 0.5f, 0.9f)); // EntityPlayer.updateItemUse, world tick k
            }
        }
    }

    @Test
    public void onAV1RecordingTheHurtAndEatSoundsAreHeardOnceEach() throws Exception {
        play(recording(null), 5);
        assertEquals(Arrays.asList(HURT + " 1.0 1.1", EAT + " 0.5 0.9"), heard);
        assertEquals("the part takes the question, so vanilla's (silent) default never runs as a second copy",
                Arrays.asList(true, true), answers);
    }

    @Test
    public void withStoredSoundsOnlyTheFedSoundPlays() throws Exception {
        play(recording(withStoredSound()), 5);
        assertEquals("the eat tick is in the file (Phase 11 plays it): never both",
                Collections.singletonList(HURT + " 1.0 1.1"), heard);
        assertEquals("the puppet's own copy is swallowed, not passed to vanilla", Arrays.asList(true, true), answers);
    }

    @Test
    public void aV2RecordingWithoutSoundRecordsPlaysLikeV1() throws Exception {
        ExtCodec c = new ExtCodec();
        int n = c.view(1, 0, RewindFormat.SCREEN_NONE);
        ExtRecords viewOnly = new ExtRecords.Builder().addEncoded(0, 90 * MS, RewindFormat.TAG_VIEW, c.buffer(), n)
                .build();
        play(recording(viewOnly), 5);
        assertEquals(Arrays.asList(HURT + " 1.0 1.1", EAT + " 0.5 0.9"), heard);
    }

    @Test
    public void nothingPlaysDuringACatchUpOrSeek() throws Exception {
        source.muted = true;
        play(recording(null), 5);
        play(recording(withStoredSound()), 5);
        assertEquals(Collections.<String>emptyList(), heard);
        for (Boolean a : answers) {
            assertTrue("muted sounds are swallowed too", a);
        }
    }

    @Test
    public void outsideAnyFeedTheRuleSeesNoPacket() throws Exception {
        LoadedRecording r = recording(withStoredSound());
        source.recording = r;
        source.feeder = new TickFeeder(r);
        assertTrue(part.puppetSound(null, EAT, 0.5f, 1f));
        source.feeder = null; // between sessions
        assertTrue(part.puppetSound(null, EAT, 0.5f, 1f));
        assertEquals(Collections.<String>emptyList(), heard);
    }

    @Test
    public void registerAddsThePartOnce() {
        PuppetSounds.register();
        PuppetSounds.register();
        int n = 0;
        for (PlaybackPart p : RewindPlayback.parts()) {
            if (p instanceof PuppetSounds) {
                n++;
            }
        }
        assertEquals(1, n);
        assertTrue(RewindPlayback.parts().contains(PuppetSounds.instance()));
    }

    // ---------------- derived footsteps reach the puppet's playSound (StepSoundPart, B7)

    /** A world with a block map instead of chunks. */
    static final class MapWorld extends World {
        final Map<Long, IBlockState> blocks = new HashMap<Long, IBlockState>();

        MapWorld() {
            super(null, null, WorldProvider.getProviderForDimension(0), new Profiler(), true);
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected int getRenderDistanceChunks() {
            return 2;
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            IBlockState s = blocks.get(pos.toLong());
            return s == null ? Blocks.air.getDefaultState() : s;
        }

        MapWorld put(int x, int y, int z, net.minecraft.block.Block b) {
            blocks.put(new BlockPos(x, y, z).toLong(), b.getDefaultState());
            return this;
        }
    }

    @Test
    public void blocksReadAsTheStepRuleExpects() {
        assertEquals(StepSounds.AIR, StepSoundPart.kind(Blocks.air));
        assertEquals(StepSounds.LADDER, StepSoundPart.kind(Blocks.ladder));
        assertEquals(StepSounds.FENCE, StepSoundPart.kind(Blocks.oak_fence));
        assertEquals(StepSounds.FENCE, StepSoundPart.kind(Blocks.nether_brick_fence));
        assertEquals(StepSounds.FENCE, StepSoundPart.kind(Blocks.cobblestone_wall));
        assertEquals(StepSounds.FENCE, StepSoundPart.kind(Blocks.oak_fence_gate));
        assertEquals(StepSounds.SOLID, StepSoundPart.kind(Blocks.wool));
        assertEquals(StepSounds.SOLID, StepSoundPart.kind(Blocks.water));
        assertEquals("tall grass is not air (Material.vine)", StepSounds.SOLID, StepSoundPart.kind(Blocks.tallgrass));
    }

    @Test
    public void aStepPlaysTheBlocksStepSoundLikeVanillasPlayStepSound() {
        List<String> out = new ArrayList<String>();
        StepSoundPart.Out to = (name, volume, pitch) -> out.add(name + " " + volume + " " + pitch);
        MapWorld w = new MapWorld().put(0, 63, 0, Blocks.stone).put(1, 63, 0, Blocks.wool)
                .put(2, 63, 0, Blocks.stone).put(2, 64, 0, Blocks.snow_layer).put(3, 63, 0, Blocks.water);
        StepSoundPart.playStep(w, new BlockPos(0, 63, 0), to);
        StepSoundPart.playStep(w, new BlockPos(1, 63, 0), to);
        StepSoundPart.playStep(w, new BlockPos(2, 63, 0), to);
        StepSoundPart.playStep(w, new BlockPos(3, 63, 0), to);
        assertEquals(Arrays.asList(
                Blocks.stone.stepSound.getStepSound() + " " + 0.15f + " 1.0",
                Blocks.wool.stepSound.getStepSound() + " " + 0.15f + " 1.0",
                Blocks.snow_layer.stepSound.getStepSound() + " " + 0.15f + " 1.0"), out);
        assertEquals("step.stone", Blocks.stone.stepSound.getStepSound());
        assertEquals("step.cloth", Blocks.wool.stepSound.getStepSound());
        assertEquals("step.snow", Blocks.snow_layer.stepSound.getStepSound());
    }

    @Test
    public void stepSoundPartRegistersItselfAndThePuppetSoundsOnce() {
        StepSoundPart.register();
        StepSoundPart.register();
        List<PlaybackPart> parts = RewindPlayback.parts();
        assertEquals(1, Collections.frequency(parts, StepSoundPart.instance()));
        assertEquals(1, Collections.frequency(parts, PuppetSounds.instance()));
        assertTrue("PuppetSounds answers first", parts.indexOf(PuppetSounds.instance())
                < parts.indexOf(StepSoundPart.instance()));
    }
}
