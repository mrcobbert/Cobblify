package com.bedwarsqol.rewind;

import com.bedwarsqol.rewind.watch.PlaybackGate;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.IChunkProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A5: dig state (block, face, crack stage, hitting) is stored when it changes, and each block change the client made
 * inside a local scope (its own place or break) is stored with position, old and new state; a change outside every
 * scope (a server packet's) or on a world that is not the client's is not. Cause 1 inside
 * {@code onPlayerDestroyBlock}.
 */
public class DigCaptureTest {

    /** A world with a block map instead of chunks; {@code remote} = a client world. */
    static final class MapWorld extends World {
        final Map<Long, IBlockState> blocks = new HashMap<Long, IBlockState>();

        MapWorld(boolean remote) {
            super(null, null, WorldProvider.getProviderForDimension(0), new Profiler(), remote);
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

        /** What vanilla's {@code setBlockState(pos, state, 3)} does around the recorder's HEAD and RETURN hooks. */
        boolean change(BlockPos pos, IBlockState state) {
            ActionCapture.onBlockChangeHead(this, pos);
            boolean changed = getBlockState(pos) != state;
            if (changed) {
                blocks.put(pos.toLong(), state);
            }
            ActionCapture.onBlockChangeReturn(this, pos, changed);
            return changed;
        }
    }

    private RecordingCaptureSink sink;

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    @Before
    public void setUp() {
        sink = new RecordingCaptureSink();
        ActionCapture.bind(sink, null, null);
        ActionCapture.startSegment(1);
    }

    @After
    public void tearDown() {
        ActionCapture.unbind();
        if (PlaybackGate.global().isMuted()) {
            PlaybackGate.global().exit();
        }
    }

    // ------------------------------------------------------------------ DIG

    @Test
    public void aDigSequenceWritesOnlyOnChange() {
        BlockPos block = new BlockPos(3, 65, -7);
        ActionCapture.dig(false, new BlockPos(-1, -1, -1), 2, 0f); // nothing dug yet
        assertEquals(0, sink.appends);
        ActionCapture.dig(true, block, 1, 0f); // clickBlock: hitting, damage 0 -> stage -1
        ActionCapture.dig(true, block, 1, 0.04f); // still stage -1
        float damage = 0f;
        for (int stage = 0; stage <= 9; stage++) {
            damage = (stage + 1) / 10f + 0.01f;
            ActionCapture.dig(true, block, 1, damage);
            ActionCapture.dig(true, block, 1, damage); // the same tick state again: nothing
        }
        ActionCapture.dig(true, block, 4, damage); // looked at another face
        ActionCapture.dig(false, block, 4, 0f); // resetBlockRemoving
        ActionCapture.dig(false, block, 0, 0f); // looking around while not digging: nothing
        ActionCapture.dig(false, block, 5, 0f);

        List<RecordingCaptureSink.Rec> digs = sink.ofTag(RewindFormat.TAG_DIG);
        assertEquals("start + 10 stages + face + stop", 13, digs.size());
        assertEquals(13, sink.recs.size());
        ExtCodec.Dig d = new ExtCodec.Dig();
        read(d, digs.get(0));
        assertTrue(d.hitting());
        assertEquals(block.toLong(), d.pos);
        assertEquals(1, d.face);
        assertEquals(-1, d.stage);
        for (int stage = 0; stage <= 9; stage++) {
            read(d, digs.get(1 + stage));
            assertTrue(d.hitting());
            assertEquals(stage, d.stage);
            assertEquals(1, d.face);
        }
        read(d, digs.get(11));
        assertEquals(4, d.face);
        assertEquals(9, d.stage);
        read(d, digs.get(12));
        assertTrue(!d.hitting());
        assertEquals(-1, d.stage);
        assertEquals(block.toLong(), d.pos);
    }

    @Test
    public void aNewSegmentForgetsTheLastDigState() {
        BlockPos block = new BlockPos(0, 64, 0);
        ActionCapture.dig(true, block, 1, 0.35f);
        ActionCapture.startSegment(2);
        ActionCapture.dig(true, block, 1, 0.35f);
        assertEquals(2, sink.ofTag(RewindFormat.TAG_DIG).size());
    }

    @Test
    public void digIsNotWrittenWhileNotRecordingOrMuted() {
        BlockPos block = new BlockPos(0, 64, 0);
        sink.recording = false;
        ActionCapture.dig(true, block, 1, 0.35f);
        sink.recording = true;
        PlaybackGate.global().enter();
        ActionCapture.onTickEnd(null, null);
        PlaybackGate.global().exit();
        assertEquals(0, sink.appends);
    }

    private static void read(ExtCodec.Dig d, RecordingCaptureSink.Rec r) {
        d.read(r.body, 0, r.body.length);
    }

    // ------------------------------------------------------------------ BLOCK

    private static int id(IBlockState s) {
        return Block.BLOCK_STATE_IDS.get(s);
    }

    @Test
    public void aBlockChangeInsideALocalScopeIsStoredWithOldAndNewState() {
        MapWorld world = new MapWorld(true);
        BlockPos at = new BlockPos(10, 70, 10);
        IBlockState wool = Blocks.wool.getStateFromMeta(14);
        ActionCapture.enterLocal(); // rightClickMouse
        world.change(at, wool);
        ActionCapture.exitLocal();

        List<RecordingCaptureSink.Rec> blocks = sink.ofTag(RewindFormat.TAG_BLOCK);
        assertEquals(1, blocks.size());
        ExtCodec.Block b = new ExtCodec.Block();
        b.read(blocks.get(0).body, 0, blocks.get(0).body.length);
        assertEquals(at.toLong(), b.pos);
        assertEquals(id(Blocks.air.getDefaultState()), b.oldState);
        assertEquals(id(wool), b.newState);
        assertEquals(RewindFormat.BLOCK_CAUSE_OTHER, b.cause);
    }

    @Test
    public void aBreakInsideTheDestroyScopeHasCauseOwnBreak() {
        MapWorld world = new MapWorld(true);
        BlockPos at = new BlockPos(1, 2, 3);
        world.blocks.put(at.toLong(), Blocks.end_stone.getDefaultState());
        ActionCapture.enterLocal(); // sendClickBlockToController
        ActionCapture.enterDestroy(); // onPlayerDestroyBlock
        world.change(at, Blocks.air.getDefaultState());
        ActionCapture.exitDestroy();
        world.change(at.up(), Blocks.stone.getDefaultState()); // still local, out of the destroy scope
        ActionCapture.exitLocal();

        List<RecordingCaptureSink.Rec> blocks = sink.ofTag(RewindFormat.TAG_BLOCK);
        assertEquals(2, blocks.size());
        ExtCodec.Block b = new ExtCodec.Block();
        b.read(blocks.get(0).body, 0, blocks.get(0).body.length);
        assertEquals(id(Blocks.end_stone.getDefaultState()), b.oldState);
        assertEquals(id(Blocks.air.getDefaultState()), b.newState);
        assertEquals(RewindFormat.BLOCK_CAUSE_OWN_BREAK, b.cause);
        b.read(blocks.get(1).body, 0, blocks.get(1).body.length);
        assertEquals(RewindFormat.BLOCK_CAUSE_OTHER, b.cause);
        assertEquals(at.up().toLong(), b.pos);
    }

    @Test
    public void aChangeOutsideEveryScopeOrOnAServerWorldOrUnchangedIsNotStored() {
        MapWorld client = new MapWorld(true);
        MapWorld server = new MapWorld(false);
        BlockPos at = new BlockPos(4, 5, 6);
        client.change(at, Blocks.stone.getDefaultState()); // a server packet's change: no scope
        ActionCapture.enterLocal();
        server.change(at, Blocks.stone.getDefaultState()); // the integrated server's world
        client.change(at, Blocks.stone.getDefaultState()); // no change (already stone)
        ActionCapture.exitLocal();
        ActionCapture.exitLocal(); // unbalanced exit clamps
        client.change(at.up(), Blocks.stone.getDefaultState());
        assertEquals(0, sink.appends);
    }

    @Test
    public void nestedChangesInsideAScopeKeepTheirOwnOldState() {
        final MapWorld world = new MapWorld(true);
        final BlockPos outer = new BlockPos(0, 10, 0);
        final BlockPos inner = new BlockPos(0, 11, 0);
        world.blocks.put(inner.toLong(), Blocks.sand.getDefaultState());
        ActionCapture.enterLocal();
        ActionCapture.onBlockChangeHead(world, outer);
        world.blocks.put(outer.toLong(), Blocks.stone.getDefaultState());
        world.change(inner, Blocks.air.getDefaultState()); // a neighbour update inside the outer call
        ActionCapture.onBlockChangeReturn(world, outer, true);
        ActionCapture.exitLocal();
        List<RecordingCaptureSink.Rec> blocks = sink.ofTag(RewindFormat.TAG_BLOCK);
        assertEquals(2, blocks.size());
        ExtCodec.Block b = new ExtCodec.Block();
        b.read(blocks.get(0).body, 0, blocks.get(0).body.length);
        assertEquals(inner.toLong(), b.pos);
        assertEquals(id(Blocks.sand.getDefaultState()), b.oldState);
        b.read(blocks.get(1).body, 0, blocks.get(1).body.length);
        assertEquals(outer.toLong(), b.pos);
        assertEquals(id(Blocks.air.getDefaultState()), b.oldState);
        assertEquals(id(Blocks.stone.getDefaultState()), b.newState);
    }

    @Test
    public void theTickHeadClosesScopesLeftOpenByAThrow() {
        MapWorld world = new MapWorld(true);
        ActionCapture.enterLocal(); // the method threw: its RETURN hook never ran
        ActionCapture.onRunTickHead();
        sink.recs.clear();
        world.change(new BlockPos(0, 1, 0), Blocks.stone.getDefaultState());
        assertEquals(0, sink.ofTag(RewindFormat.TAG_BLOCK).size());
    }
}
