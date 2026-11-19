package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.BlockPos;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Predicted blocks (PLAN Phase 11, "C. Playback of v2 data", "Event order"; TASK C2). A BLOCK record (a change the
 * live client predicted for its own place or break) is applied in the local phase of the tick it was stamped in, so
 * the block appears or disappears at the recorded tick, before the server's confirmation; a server S23 handled after
 * the prediction overwrites it, whatever its arrival stamp, as in the live game.
 *
 * <p>Harness: the real {@link TickFeeder} over a v2 recording (handled counts in the tick heads), vanilla's own
 * {@link NetHandlerPlayClient} on the socketless {@link PlaybackConnection} processing the recorded S23 (written by
 * vanilla's {@code S23PacketBlockChange} writer, read by {@link VanillaPacketDecoder}), and {@link BlockPart} on the
 * same client world. The world is a {@link WorldClient} with a block map instead of chunks (no constructor run), so
 * vanilla's {@code handleBlockChange} reaches its {@code invalidateRegionAndSetBlock}.
 */
public class BlockPredictionTest {

    static final long MS = 1_000L;
    /** The origin: the S01, ordinal 1. */
    static final long EO = 40 * MS;
    static final BlockPos P = new BlockPos(10, 64, -3);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        PrintStream out = System.out;
        Bootstrap.register();
        System.setOut(out);
    }

    // ------------------------------------------------------------------ a vanilla client with a map world

    /** A client world with a block map instead of chunks; built without its constructor. */
    static final class MapWorld extends WorldClient {
        private Map<Long, IBlockState> blocks;
        private List<String> writes;

        MapWorld() {
            super(null, null, 0, null, null); // never run: instances come from allocateInstance
        }

        static MapWorld create() throws Exception {
            return (MapWorld) RecordedScreensTest.allocate(MapWorld.class);
        }

        Map<Long, IBlockState> blocks() {
            if (blocks == null) {
                blocks = new HashMap<Long, IBlockState>();
            }
            return blocks;
        }

        List<String> writes() {
            if (writes == null) {
                writes = new ArrayList<String>();
            }
            return writes;
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            IBlockState s = blocks().get(pos.toLong());
            return s == null ? Blocks.air.getDefaultState() : s;
        }

        /** What vanilla's predictions (and {@link BlockPart}) call. */
        @Override
        public boolean setBlockState(BlockPos pos, IBlockState state, int flags) {
            writes().add("set " + name(state) + " flags " + flags);
            blocks().put(pos.toLong(), state);
            return true;
        }

        /** What vanilla's {@code handleBlockChange} (S23) calls. */
        @Override
        public boolean invalidateRegionAndSetBlock(BlockPos pos, IBlockState state) {
            writes().add("server " + name(state));
            blocks().put(pos.toLong(), state);
            return true;
        }

        String at(BlockPos pos) {
            return name(getBlockState(pos));
        }

        /** The map, by position, as names (for equality across seeks). */
        String snapshot() {
            TreeMap<Long, String> m = new TreeMap<Long, String>();
            for (Map.Entry<Long, IBlockState> e : blocks().entrySet()) {
                m.put(e.getKey(), name(e.getValue()));
            }
            return m.toString();
        }
    }

    static String name(IBlockState s) {
        return Block.blockRegistry.getNameForObject(s.getBlock()).getResourcePath();
    }

    /** Vanilla's client on a socketless playback connection, a camera as the client player, a map world. */
    static final class Client {
        private static final GameProfile PROFILE = new GameProfile(new UUID(5L, 6L), "FakeViewer");
        final RecordedScreensTest.Mc mc;
        final PlaybackConnection connection;
        final NetHandlerPlayClient handler;
        final RewindCamera camera;
        MapWorld world;
        private final Field theMinecraft;
        private final Object previousMc;

        Client() throws Exception {
            mc = (RecordedScreensTest.Mc) RecordedScreensTest.allocate(RecordedScreensTest.Mc.class);
            mc.displayWidth = 854;
            mc.displayHeight = 480;
            mc.gameSettings = (GameSettings) RecordedScreensTest.allocate(GameSettings.class);
            mc.gameSettings.guiScale = 2;
            set(Minecraft.class, mc, "mcThread", Thread.currentThread());
            theMinecraft = Minecraft.class.getDeclaredField("theMinecraft");
            theMinecraft.setAccessible(true);
            previousMc = theMinecraft.get(null);
            theMinecraft.set(null, mc);
            connection = PlaybackConnection.open(mc, PROFILE);
            handler = connection.handler();
            mc.playerController = new PlayerControllerMP(mc, handler);
            camera = (RewindCamera) RecordedScreensTest.allocate(RewindCamera.class);
            camera.inventory = new InventoryPlayer(camera);
            camera.inventoryContainer = new RecordedScreensTest.PlayerContainer(camera.inventory);
            camera.openContainer = camera.inventoryContainer;
            set(EntityPlayerSP.class, camera, "sendQueue", handler);
            mc.thePlayer = camera;
            newWorld();
        }

        /** A fresh world (a restart: the connection's world is rebuilt). */
        MapWorld newWorld() throws Exception {
            world = MapWorld.create();
            set(NetHandlerPlayClient.class, handler, "clientWorldController", world);
            mc.theWorld = world;
            return world;
        }

        void close() throws Exception {
            theMinecraft.set(null, previousMc);
            connection.close();
        }

        static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        }
    }

    /**
     * What {@code PlaybackSink} does with the feed, for the packets in {@link #vanilla}: decode with vanilla's reader
     * and process on vanilla's handler ({@code apply}, and {@code window} for a holder that takes windows); every
     * other packet is taken and logged. Local records and ticks go to the parts, in order.
     */
    static final class VanillaSink implements PacketSink {
        final NetHandlerPlayClient handler;
        final Set<Integer> vanilla;
        final List<PlaybackPart> parts;
        final List<String> log = new ArrayList<String>();
        long tick = -1;

        VanillaSink(NetHandlerPlayClient handler, Set<Integer> vanilla, PlaybackPart... parts) {
            this.handler = handler;
            this.vanilla = vanilla;
            this.parts = Arrays.asList(parts);
        }

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            if (!vanilla.contains(packetId)) {
                return true;
            }
            Packet<INetHandlerPlayClient> p;
            try {
                p = VanillaPacketDecoder.decode(buf, off, len);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            p.processPacket(handler);
            log.add("net 0x" + Integer.toHexString(packetId) + " @" + tick);
            return true;
        }

        @Override
        public boolean window(int packetId, byte[] buf, int off, int len) {
            return apply(packetId, buf, off, len);
        }

        @Override
        public void boundary(int packetId, byte[] buf, int off, int len) {
        }

        @Override
        public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
            return true;
        }

        @Override
        public void local(int tag, long timeUs, byte[] buf, int off, int len) {
            log.add("local " + RewindFormat.extName(tag) + " @" + tick);
            for (PlaybackPart p : parts) {
                p.local(tag, timeUs, buf, off, len);
            }
        }

        @Override
        public void sample(long t, long timeUs) {
            for (PlaybackPart p : parts) {
                p.tick(t, timeUs);
            }
        }

        void feed(TickFeeder feeder, long k) {
            tick = k;
            feeder.feedForTick(k, this);
        }
    }

    // ------------------------------------------------------------------ fixture

    private Client client;
    private final List<String> effects = new ArrayList<String>();
    private BlockPart part;

    @Before
    public void setUp() throws Exception {
        client = new Client();
        part = new BlockPart(new BlockPart.Source() {
            @Override
            public net.minecraft.world.World world() {
                return client.world;
            }

            @Override
            public void destroyEffects(BlockPos pos, IBlockState old) {
                effects.add("destroy " + name(old) + " at " + pos.toLong());
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        client.close();
    }

    static int id(IBlockState s) {
        return Block.BLOCK_STATE_IDS.get(s);
    }

    /** The S23 vanilla's server writer makes for {@code pos} in a world where it is {@code state}. */
    static byte[] s23(BlockPos pos, IBlockState state) throws Exception {
        MapWorld w = MapWorld.create();
        w.blocks().put(pos.toLong(), state);
        PacketBuffer b = new PacketBuffer(Unpooled.buffer());
        b.writeVarIntToBuffer(0x23);
        new S23PacketBlockChange(w, pos).writePacketData(b);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        return out;
    }

    static byte[] blockBody(BlockPos pos, IBlockState old, IBlockState now, int cause) {
        ExtCodec c = new ExtCodec();
        int n = c.block(pos.toLong(), id(old), id(now), cause);
        return ExtRecords.body(c.buffer(), n);
    }

    private static final Set<Integer> S23_ONLY = new HashSet<Integer>(Collections.singletonList(0x23));

    /**
     * The review cases (TickFeederTest's shape, with vanilla's S23 and the client world): S01 at 40 (ordinal 1),
     * fillers at 50-90 (ordinals 2-6), an S47 at 99.2 (no ordinal), the S23(P, {@code server}) at {@code s23Us}
     * (ordinal 7), an S47 after it; heads at 100 and 150 with handled counts {@code h100} and 7 - h100; the BLOCK
     * record(s) after.
     */
    private LoadedRecording recording(long s23Us, int h100, IBlockState server, ExtRecords.Builder blocks)
            throws Exception {
        TickFeederTest.Rec rec = new TickFeederTest.Rec()
                .segment(1, TickFeederTest.standardHeader0(), TickFeederTest.standardHeader1())
                .at(EO, RecordingFixture.joinGame(0));
        for (int i = 0; i < 5; i++) {
            rec.at((50 + 10 * i) * MS, TickFeederTest.p(0x03, i));
        }
        rec.at(99_200L, TickFeederTest.p(0x47, 1));
        rec.at(s23Us, s23(P, server));
        rec.at(s23Us + 200, TickFeederTest.p(0x47, 2));
        ExtRecords.Builder b = TickFeederTest.heads(new long[]{100, 150}, new int[]{h100, 7 - h100}, null);
        ExtRecords more = blocks.build();
        for (int i = 0; i < more.size(); i++) {
            b.add(more.segmentIndex(i), more.timeUs(i), more.tag(i), more.buffer(), more.offset(i), more.length(i));
        }
        return rec.load(tmp.newFolder(), EO, 400 * MS).withExt(b.build());
    }

    private static ExtRecords.Builder placeWool() {
        return new ExtRecords.Builder().add(0, 101 * MS, RewindFormat.TAG_BLOCK,
                blockBody(P, Blocks.air.getDefaultState(), Blocks.wool.getDefaultState(), RewindFormat.BLOCK_CAUSE_OTHER));
    }

    /** Plays ticks 0..{@code to} straight; returns the block at P after each tick in {@code watch}. */
    private Map<Long, String> play(LoadedRecording r, long to, long... watch) {
        TickFeeder feeder = new TickFeeder(r);
        VanillaSink sink = new VanillaSink(client.handler, S23_ONLY, part);
        feeder.feedHeaders(sink);
        Map<Long, String> seen = new TreeMap<Long, String>();
        Set<Long> w = new HashSet<Long>();
        for (long k : watch) {
            w.add(k);
        }
        for (long k = 0; k <= to; k++) {
            sink.feed(feeder, k);
            if (w.contains(k)) {
                seen.put(k, client.world.at(P));
            }
        }
        return seen;
    }

    // ------------------------------------------------------------------ the review's same-interval cases

    /**
     * Round 2: the S23 (the server rejects the placement: air) is queued at 99.5, before the head at 100, but its
     * handler ran after that head (H[100] excludes it); the placement is predicted at 101. Live: wool appears in tick
     * 100, the server's air wins in the tick at 150. Playback the same, through vanilla's handler.
     */
    @Test
    public void roundTwoTheRejectedPlacementShowsAtItsTickThenEndsAsAir() throws Exception {
        LoadedRecording r = recording(99_500L, 6, Blocks.air.getDefaultState(), placeWool());
        TickTable t = r.timeline().ticks();
        long k100 = t.tickOf(100 * MS);
        long k150 = t.tickOf(150 * MS);
        Map<Long, String> seen = play(r, k150, k100 - 1, k100, k150);
        assertEquals("nothing before the placement's tick", "air", seen.get(k100 - 1));
        assertEquals("the predicted block shows at its recorded tick, before the server's answer", "wool",
                seen.get(k100));
        assertEquals("the server's correction handled after the prediction wins", "air", seen.get(k150));
        assertEquals("prediction first, then vanilla's S23 handler",
                Arrays.asList("set wool flags 3", "server air"), client.world.writes());
    }

    /** Round 1: the S23 is stamped at 110, after the head; same result. */
    @Test
    public void roundOneTheS23StampedAfterTheHeadAlsoEndsAsAir() throws Exception {
        LoadedRecording r = recording(110_000L, 6, Blocks.air.getDefaultState(), placeWool());
        TickTable t = r.timeline().ticks();
        long k100 = t.tickOf(100 * MS);
        long k150 = t.tickOf(150 * MS);
        Map<Long, String> seen = play(r, k150, k100, k150);
        assertEquals("wool", seen.get(k100));
        assertEquals("air", seen.get(k150));
    }

    /** The S23 handled before the head comes first (whatever its stamp); the prediction after it stands. */
    @Test
    public void anS23HandledBeforeThePredictionIsOverwrittenByIt() throws Exception {
        LoadedRecording r = recording(99_500L, 7, Blocks.air.getDefaultState(), placeWool());
        TickTable t = r.timeline().ticks();
        long k100 = t.tickOf(100 * MS);
        long k150 = t.tickOf(150 * MS);
        Map<Long, String> seen = play(r, k150, k100, k150);
        assertEquals("wool", seen.get(k100));
        assertEquals("wool", seen.get(k150));
        assertEquals(Arrays.asList("server air", "set wool flags 3"), client.world.writes());
    }

    /** A confirmed placement: the server's S23 says wool too; the block never flickers. */
    @Test
    public void aConfirmedPlacementStaysFromItsTickOn() throws Exception {
        LoadedRecording r = recording(110_000L, 6, Blocks.wool.getDefaultState(), placeWool());
        TickTable t = r.timeline().ticks();
        long k100 = t.tickOf(100 * MS);
        long k150 = t.tickOf(150 * MS);
        Map<Long, String> seen = play(r, k150, k100 - 1, k100, k150);
        assertEquals("air", seen.get(k100 - 1));
        assertEquals("wool", seen.get(k100));
        assertEquals("wool", seen.get(k150));
    }

    // ------------------------------------------------------------------ breaks

    /**
     * Your own break: the block is gone at the recorded tick with vanilla's break particles of the old block
     * ({@code addBlockDestroyEffects}, what {@code playAuxSFX(2001)} adds), drawn before the block is removed (vanilla's
     * order: the effect reads the block's actual state). The break sound is a SOUND record of its own (SoundPart), so
     * no sound is played here.
     */
    @Test
    public void anOwnBreakRemovesTheBlockAtItsTickWithBreakParticles() throws Exception {
        client.world.blocks().put(P.toLong(), Blocks.stone.getDefaultState());
        ExtRecords.Builder b = new ExtRecords.Builder().add(0, 101 * MS, RewindFormat.TAG_BLOCK,
                blockBody(P, Blocks.stone.getDefaultState(), Blocks.air.getDefaultState(),
                        RewindFormat.BLOCK_CAUSE_OWN_BREAK));
        LoadedRecording r = recording(110_000L, 6, Blocks.air.getDefaultState(), b);
        TickTable t = r.timeline().ticks();
        long k100 = t.tickOf(100 * MS);
        Map<Long, String> seen = play(r, k100, k100 - 1, k100);
        assertEquals("stone", seen.get(k100 - 1));
        assertEquals("air", seen.get(k100));
        assertEquals(Collections.singletonList("destroy stone at " + P.toLong()), effects);
    }

    /** A change that is not your break (a placement, a door) and a break of air make no particles. */
    @Test
    public void onlyAnOwnBreakOfABlockMakesBreakParticles() throws Exception {
        byte[] place = blockBody(P, Blocks.air.getDefaultState(), Blocks.wool.getDefaultState(),
                RewindFormat.BLOCK_CAUSE_OTHER);
        part.local(RewindFormat.TAG_BLOCK, 0, place, 0, place.length);
        byte[] airBreak = blockBody(P.up(), Blocks.air.getDefaultState(), Blocks.air.getDefaultState(),
                RewindFormat.BLOCK_CAUSE_OWN_BREAK);
        part.local(RewindFormat.TAG_BLOCK, 0, airBreak, 0, airBreak.length);
        assertEquals("wool", client.world.at(P));
        assertEquals(Collections.<String>emptyList(), effects);
    }

    // ------------------------------------------------------------------ seek equivalence

    /**
     * After a seek the world equals straight play: a backward seek rebuilds the world from the start and the feed hands
     * the same BLOCK records to the same ticks; a forward seek catches up through them.
     */
    @Test
    public void afterASeekTheWorldEqualsStraightPlay() throws Exception {
        ExtRecords.Builder b = placeWool().add(0, 120 * MS, RewindFormat.TAG_BLOCK,
                blockBody(P.east(), Blocks.air.getDefaultState(), Blocks.stone.getDefaultState(),
                        RewindFormat.BLOCK_CAUSE_OTHER))
                .add(0, 160 * MS, RewindFormat.TAG_BLOCK,
                        blockBody(P.east(), Blocks.stone.getDefaultState(), Blocks.air.getDefaultState(),
                                RewindFormat.BLOCK_CAUSE_OWN_BREAK));
        LoadedRecording r = recording(99_500L, 6, Blocks.air.getDefaultState(), b);
        TickTable t = r.timeline().ticks();
        long k100 = t.tickOf(100 * MS);
        long k150 = t.tickOf(150 * MS);
        long end = t.tickOf(300 * MS);
        for (long target : new long[]{k100, k150, k150 + 1, end}) {
            client.newWorld();
            play(r, target);
            String straight = client.world.snapshot();

            // backward: straight to the end, then a fresh world and a catch-up from tick 0 to the target
            client.newWorld();
            play(r, end);
            client.newWorld();
            TickFeeder feeder = new TickFeeder(r);
            VanillaSink sink = new VanillaSink(client.handler, S23_ONLY, part);
            feeder.restart();
            feeder.feedHeaders(sink);
            for (long k = 0; k <= target; k++) {
                sink.feed(feeder, k);
            }
            assertEquals("backward seek to tick " + target, straight, client.world.snapshot());
        }
        client.newWorld();
        play(r, k100);
        assertTrue(client.world.snapshot().contains("wool"));
    }
}
