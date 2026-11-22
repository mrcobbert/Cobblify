package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.hud.RecordedHudSource;
import com.bedwarsqol.hud.TextModules;
import com.bedwarsqol.rewind.ActionCapture;
import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.HudCapture;
import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RewindFormat;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S2DPacketOpenWindow;
import net.minecraft.network.play.server.S2EPacketCloseWindow;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S30PacketWindowItems;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.IntHashMap;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.storage.WorldInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Rewind parity Phase 16, end to end: a format v2 recording written through the real {@code RewindWriter} with every
 * extension record kind, played by the production {@link PlaybackSession} with every playback part registered the way
 * {@code BedwarsQol} registers them ({@link BedwarsQol#wireRewindPlayback}). The parts reach the session through
 * {@link RewindPlayback} as in the game: the test's session is put in its place for the test.
 *
 * <p>Checked: straight play to tick K, a forward seek to K and a backward seek to K leave equal state in every group
 * (blocks, the open container and the held screen, the camera's inventory and hand, the view state and FOV, the
 * recorded HUD's values, the dig state and crack, the camera's bob and flags); the recorded values themselves are
 * the ones written (the parts really ran); each stored sound plays once in straight play and none in a catch-up;
 * nothing is written to the playback channel; the production gate is muted and the recorder's capture unbound for the
 * whole session; no part threw.
 *
 * <p>What stands in for the game: a client with no window ({@link RecordedScreensTest.Mc}) whose world is a block map
 * ({@link BlockPredictionTest.MapWorld}); vanilla's own {@link NetHandlerPlayClient} on the socketless
 * {@link PlaybackConnection} processes the recorded block and window packets; the cracks, particles and sounds land
 * in recording stand-ins for {@code RenderGlobal}, {@code EffectRenderer} and {@code SoundHandler}. The sink is a
 * stand-in for {@code PlaybackSink} (vanilla's S01 handler needs a running client): it makes a fresh world and camera
 * on S01, routes window packets as {@code PlaybackSink.window} does, puts {@link HandState} on the camera each tick as
 * {@code PlaybackSink.sample} does and calls the parts through the same {@code RewindPlayback.parts*} entry points;
 * the recorded player's puppet is not built (it needs an entity world). Fake profiles and synthetic values only.
 */
public class RewindParityPlaybackTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final BlockPos A = new BlockPos(3, 64, 5);
    static final BlockPos B = new BlockPos(4, 64, 6);
    static final int WINDOW = 3;
    static final int TICKS = 100;
    static final long TICK_US = 50_000L;
    static final int K = 72;
    private static final GameProfile VIEWER = new GameProfile(new UUID(7L, 8L), "FakeViewer");

    @BeforeClass
    public static void bootstrap() {
        PrintStream out = System.out;
        Bootstrap.register();
        System.setOut(out);
    }

    // ------------------------------------------------------------------ the recording

    static IBlockState stone() {
        return Blocks.stone.getDefaultState();
    }

    static IBlockState wool() {
        return Blocks.wool.getDefaultState();
    }

    static IBlockState air() {
        return Blocks.air.getDefaultState();
    }

    static int stateId(IBlockState s) {
        return Block.BLOCK_STATE_IDS.get(s);
    }

    static byte[] slot(Item item, int count) {
        return RecordingFixture.itemSlot(Item.getIdFromItem(item), count, 0);
    }

    static byte[] vanilla(int id, Packet<?> p) throws IOException {
        PacketBuffer b = new PacketBuffer(Unpooled.buffer());
        b.writeVarIntToBuffer(id);
        p.writePacketData(b);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        return out;
    }

    /** The 90 stacks of the double chest's S30: 54 diamonds, then the player's inventory as recorded then. */
    static List<ItemStack> chestContents() {
        List<ItemStack> all = new ArrayList<ItemStack>();
        for (int i = 0; i < 54; i++) {
            all.add(new ItemStack(Items.diamond, 1 + i % 64));
        }
        ItemStack[] main = new ItemStack[36];
        main[0] = new ItemStack(Items.iron_sword);
        main[1] = new ItemStack(Blocks.wool, 63);
        for (int i = 9; i < 36; i++) {
            all.add(main[i]);
        }
        for (int i = 0; i < 9; i++) {
            all.add(main[i]);
        }
        return all;
    }

    /**
     * One segment of {@link #TICKS} ticks 50 ms apart. Each tick: its TICK_HEAD (handled count = the stored packets
     * that arrived in the previous interval), its local records 1 ms later, the self track's TICK at its end, then
     * the packets that arrive mid-interval (handled at the next head). Digging A (ticks 12-18) breaks it (19, server
     * confirms in 20); wool placed at B (25, confirmed 26); F5 (10), F1 (30-34), Tab (45-46), the inventory screen
     * (50-54), a double chest the server opens at 60 with 54 slots filled in the same drain (closed by the player at
     * 80, by the server at 81), a slot the server sets at 65 and one the player's click set at 66, a hotbar slot the
     * server sets through the chest's window at 71 (amendment 2), chat typing (90-94); FOV samples while sprinting
     * (0-20) and a zoom (40-41), then steady; FPS shown 120 then 118; one mouse tap.
     */
    File writeRecording(File dir) throws Exception {
        RecordingFixture.WriterRun w = new RecordingFixture.WriterRun(dir, 10_000_000L);
        w.connect(16_384);
        w.advance(1_000);
        w.boundary(0, RecordingFixture.joinGame(0), 0);
        w.open();
        ExtCodec c = new ExtCodec();
        c.beginSettings();
        c.settingFloat(RewindFormat.SETTING_FOV, 90f);
        c.settingString(RewindFormat.SETTING_GUI_ACCENT, "pale-blue");
        ext(w, c, RewindFormat.TAG_SETTINGS, c.endSettings());
        ext(w, c, RewindFormat.TAG_IDENTITY, c.identity(11L, 12L, "FakeRecorded", "", ""));
        int pending = 1; // the S01: ordinal 1, handled before the first head
        long base = w.nowUs + 10_000L;
        byte[] swing = new byte[0];
        for (int i = 0; i < TICKS; i++) {
            w.nowUs = base + i * TICK_US;
            ext(w, c, RewindFormat.TAG_TICK_HEAD, c.tickHead(pending));
            pending = 0;
            w.advance(1_000);
            if (i == 0) {
                inv(w, c, 0, slot(Items.iron_sword, 1));
                inv(w, c, 1, slot(Item.getItemFromBlock(Blocks.wool), 64));
                ext(w, c, RewindFormat.TAG_VIEW, c.view(0, 0, RewindFormat.SCREEN_NONE));
                ext(w, c, RewindFormat.TAG_INPUT, c.input(0));
                hud(w, c, 120);
            }
            if (i <= 20) {
                ext(w, c, RewindFormat.TAG_FOV, c.fov(70f + i * 0.5f));
            }
            if (i == 40) {
                ext(w, c, RewindFormat.TAG_FOV, c.fov(30f));
                ext(w, c, RewindFormat.TAG_USE, c.use(72_000));
                hud(w, c, 118);
            }
            if (i == 41) {
                ext(w, c, RewindFormat.TAG_FOV, c.fov(80.25f));
            }
            if (i == 10) ext(w, c, RewindFormat.TAG_VIEW, c.view(1, 0, RewindFormat.SCREEN_NONE));
            if (i == 11) ext(w, c, RewindFormat.TAG_VIEW, c.view(0, 0, RewindFormat.SCREEN_NONE));
            if (i >= 12 && i <= 18) {
                ext(w, c, RewindFormat.TAG_OUT, c.out(0x0A, swing, 0, 0));
                ext(w, c, RewindFormat.TAG_DIG, c.dig(RewindFormat.DIG_HITTING, A.toLong(), 1, i - 12));
            }
            if (i == 12) {
                ext(w, c, RewindFormat.TAG_TAP, c.tap(RewindFormat.TAP_LMB));
                ext(w, c, RewindFormat.TAG_INPUT, c.input(RewindFormat.INPUT_LMB));
            }
            if (i == 19) {
                ext(w, c, RewindFormat.TAG_DIG, c.dig(0, A.toLong(), 1, 0));
                ext(w, c, RewindFormat.TAG_BLOCK, c.block(A.toLong(), stateId(stone()), stateId(air()),
                        RewindFormat.BLOCK_CAUSE_OWN_BREAK));
                ext(w, c, RewindFormat.TAG_SOUND, c.sound("minecraft:dig.stone", 3.5f, 64.5f, 5.5f, 1f, 0.8f));
                ext(w, c, RewindFormat.TAG_INPUT, c.input(0));
            }
            if (i == 25) {
                byte[] place = {0x00, 0x01, 0x02, 0x03};
                ext(w, c, RewindFormat.TAG_OUT, c.out(0x08, place, 0, place.length));
                ext(w, c, RewindFormat.TAG_BLOCK, c.block(B.toLong(), stateId(air()), stateId(wool()),
                        RewindFormat.BLOCK_CAUSE_OTHER));
                ext(w, c, RewindFormat.TAG_SOUND, c.sound("minecraft:dig.cloth", 4.5f, 64.5f, 6.5f, 1f, 0.8f));
                inv(w, c, 1, slot(Item.getItemFromBlock(Blocks.wool), 63));
            }
            if (i == 30) ext(w, c, RewindFormat.TAG_VIEW, c.view(0, RewindFormat.VIEW_HIDE_GUI, RewindFormat.SCREEN_NONE));
            if (i == 35) ext(w, c, RewindFormat.TAG_VIEW, c.view(0, 0, RewindFormat.SCREEN_NONE));
            if (i == 45) ext(w, c, RewindFormat.TAG_VIEW, c.view(0, RewindFormat.VIEW_TAB, RewindFormat.SCREEN_NONE));
            if (i == 47) ext(w, c, RewindFormat.TAG_VIEW, c.view(0, 0, RewindFormat.SCREEN_NONE));
            if (i == 50) {
                ext(w, c, RewindFormat.TAG_VIEW, c.view(0, 0, RewindFormat.SCREEN_INVENTORY));
                ext(w, c, RewindFormat.TAG_MOUSE, c.mouse(-30, 10));
            }
            if (i == 55) ext(w, c, RewindFormat.TAG_VIEW, c.view(0, 0, RewindFormat.SCREEN_NONE));
            if (i == 60) ext(w, c, RewindFormat.TAG_VIEW, c.view(0, 0, RewindFormat.SCREEN_CHEST));
            if (i >= 60 && i < 80 && i % 4 == 0) ext(w, c, RewindFormat.TAG_MOUSE, c.mouse(i - 70, 5));
            if (i == 66) {
                byte[] pearl = slot(Items.ender_pearl, 2);
                ext(w, c, RewindFormat.TAG_CONT, c.cont(WINDOW, 11, pearl, 0, pearl.length));
                byte[] click = {0x03, 0x00, 0x0B, 0x00};
                ext(w, c, RewindFormat.TAG_OUT, c.out(0x0E, click, 0, click.length));
            }
            if (i == 71) inv(w, c, 0, slot(Items.golden_apple, 3));
            if (i == 80) ext(w, c, RewindFormat.TAG_VIEW, c.view(0, 0, RewindFormat.SCREEN_NONE));
            if (i == 90) ext(w, c, RewindFormat.TAG_VIEW, c.view(0, 0, RewindFormat.SCREEN_CHAT));
            if (i == 91) ext(w, c, RewindFormat.TAG_CHAT_INPUT, c.chatInput("/fake hi", 8));
            if (i == 95) ext(w, c, RewindFormat.TAG_VIEW, c.view(0, 0, RewindFormat.SCREEN_NONE));
            w.advance(1_000);
            int n = w.enc.tick(0.1 * i, 64, 0, 0f, 0f, 0f, RewindFormat.FLAG_ON_GROUND, 1, -1, 0);
            if (n > 0) {
                w.track(RewindFormat.TAG_TICK, Arrays.copyOf(w.enc.buffer(), n));
            }
            w.advance(10_000);
            if (i == 0) {
                w.net(BlockPredictionTest.s23(A, stone()));
                pending++;
            }
            if (i == 19) {
                w.net(BlockPredictionTest.s23(A, air()));
                pending++;
            }
            if (i == 25) {
                w.net(BlockPredictionTest.s23(B, wool()));
                pending++;
            }
            if (i == 59) {
                w.net(vanilla(0x2D, new S2DPacketOpenWindow(WINDOW, "minecraft:container",
                        new ChatComponentText("Large Chest"), 54)));
                w.net(vanilla(0x30, new S30PacketWindowItems(WINDOW, chestContents())));
                pending += 2;
            }
            if (i == 64) {
                w.net(vanilla(0x2F, new S2FPacketSetSlot(WINDOW, 10, new ItemStack(Items.gold_ingot, 5))));
                pending++;
            }
            if (i == 70) {
                w.net(vanilla(0x2F, new S2FPacketSetSlot(WINDOW, 81, new ItemStack(Items.golden_apple, 3))));
                pending++;
            }
            if (i == 80) {
                w.net(vanilla(0x2E, new S2EPacketCloseWindow(WINDOW)));
                pending++;
            }
            w.step();
        }
        return w.close();
    }

    private static void ext(RecordingFixture.WriterRun w, ExtCodec c, int tag, int n) {
        w.track(tag, Arrays.copyOf(c.buffer(), n));
    }

    private static void inv(RecordingFixture.WriterRun w, ExtCodec c, int slot, byte[] item) {
        ext(w, c, RewindFormat.TAG_INV, c.inv(slot, item, 0, item.length));
    }

    private static void hud(RecordingFixture.WriterRun w, ExtCodec c, int fps) {
        ext(w, c, RewindFormat.TAG_HUD, c.beginHud(RewindFormat.HUD_FPS, RewindFormat.HUD_FLAG_ON_SCREEN)
                .putVarInt(fps).end());
    }

    // ------------------------------------------------------------------ stand-ins for the client's outputs

    /** {@code RenderGlobal.sendBlockBreakProgress}: the crack each breaker shows. */
    static final class Cracks extends RenderGlobal {
        Map<Integer, String> shown;

        Cracks() {
            super(null); // never run: allocated
        }

        @Override
        public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {
            if (shown == null) shown = new TreeMap<Integer, String>();
            if (progress >= 0 && progress < 10) {
                shown.put(breakerId, pos.toLong() + ":" + progress);
            } else {
                shown.remove(breakerId);
            }
        }

        String state() {
            return shown == null ? "{}" : shown.toString();
        }
    }

    /** {@code EffectRenderer}: counts the particles parts ask for. */
    static final class Particles extends EffectRenderer {
        int hits;
        int destroys;

        Particles() {
            super(null, null); // never run: allocated
        }

        @Override
        public void addBlockHitEffects(BlockPos pos, EnumFacing side) {
            hits++;
        }

        @Override
        public void addBlockDestroyEffects(BlockPos pos, IBlockState state) {
            destroys++;
        }
    }

    /** {@code SoundHandler.playSound}: what was played, with the playback time it played at. */
    static final class Sounds extends SoundHandler {
        List<String> played;
        PlaybackSession session;

        Sounds() {
            super(null, null); // never run: allocated
        }

        @Override
        public void playSound(ISound sound) {
            if (played == null) played = new ArrayList<String>();
            played.add(sound.getSoundLocation() + "@" + sound.getXPosF() + "," + sound.getYPosF() + ","
                    + sound.getZPosF() + " at " + session.renderTimeUs() + " state " + session.state());
        }
    }

    // ------------------------------------------------------------------ the harness

    /** {@code PlaybackSink}'s routing on the test client (see the class comment for what it leaves out). */
    final class Sink implements PacketSink {
        private final HandState hand = new HandState();
        private final ViewGameType viewType = new ViewGameType();
        private RewindCamera handCamera;

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            if (packetId == RewindFormat.PACKET_JOIN_GAME) {
                newWorld();
                return true;
            }
            if (packetId != 0x23 && packetId != 0x2D && packetId != 0x2E && packetId != 0x2F && packetId != 0x30) {
                return true; // the header's synthetic snapshot packets: taken, as a failed decode would be counted
            }
            Packet<INetHandlerPlayClient> p;
            try {
                p = VanillaPacketDecoder.decode(buf, off, len);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            p.processPacket(connection.handler());
            TickFeeder f = session.feeder();
            hand.inventoryPacket(buf, off, len, f == null ? 0 : f.recordTimeUs());
            return true;
        }

        @Override
        public boolean window(int packetId, byte[] buf, int off, int len) {
            return !RewindCamera.screenHolder().takesWindows() || apply(packetId, buf, off, len);
        }

        @Override
        public void boundary(int packetId, byte[] buf, int off, int len) {
            if (mc.thePlayer != null) {
                mc.thePlayer.setEntityId(RewindCamera.RESERVED_ID);
            }
            viewType.onPacket(buf, off, len);
            mc.playerController.setGameType(WorldSettings.GameType.getByID(
                    viewType.forView(RewindPlayback.view().view())));
            CameraController.restorePose();
            RewindPlayback.partsBoundary();
        }

        @Override
        public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
            return viewType.onPacket(buf, off, len);
        }

        @Override
        public void local(int tag, long timeUs, byte[] buf, int off, int len) {
            RewindPlayback.partsLocal(tag, timeUs, buf, off, len);
        }

        @Override
        public void timed(int tag, long timeUs, byte[] buf, int off, int len) {
            RewindPlayback.partsTimed(tag, timeUs, buf, off, len);
        }

        @Override
        public void sample(long tick, long timeUs) {
            LoadedRecording r = session.recording();
            TickTable table = session.timeline().ticks();
            if (hand.sample(r.selfTrack(), table, tick, HandState.SWING_END) && mc.thePlayer instanceof RewindCamera) {
                RewindCamera cam = (RewindCamera) mc.thePlayer;
                boolean full = cam != handCamera;
                handCamera = cam;
                cam.applyHand(hand, full, CameraController.yourView(), null);
            }
            RewindPlayback.partsTick(tick, timeUs);
        }

        void reset() {
            handCamera = null;
        }
    }

    /** The session's client: a fake clock, timer and settings; the test's world, camera and sink. */
    final class Ops implements ClientOps {
        File cbr;
        long nanos = 1_000_000_000L;
        int elapsed;
        float partial;
        boolean paused;
        float timerSpeed = 1f;
        int thirdPerson;
        boolean hideGui;
        boolean showDebug;
        boolean smoothCamera;
        int difficulty = 2;
        int counter;

        @Override public RecordingMeta readMeta(String id) { return null; }
        @Override public boolean connected() { return false; }
        @Override public void disconnect() { }
        @Override public void settleRecorder() { }
        @Override public void showLoading() { }
        @Override public void showViewer() { }

        @Override
        public void startLoad(String id, PlaybackSession.Load load) {
            File json = new File(cbr.getParentFile(), cbr.getName().replace(".cbr", ".json"));
            LoadedRecording r = LoadedRecording.load(cbr, json.isFile() ? json : null);
            if (r.problem() != null) {
                load.fail(PlaybackSession.Failure.UNPLAYABLE, String.valueOf(r.problem()));
            } else {
                load.succeed(r);
            }
        }

        @Override public void resetGui() { counter = 0; }

        @Override
        public void newConnection() {
            mc.theWorld = null;
            mc.thePlayer = null;
            sink.reset();
            RewindPlayback.partsRestart();
        }

        @Override public PacketSink sink() { return sink; }
        @Override public boolean worldPresent() { return mc.theWorld != null; }
        @Override public boolean gamePaused() { return paused; }
        @Override public int elapsedTicks() { return elapsed; }
        @Override public void setElapsedTicks(int ticks) { elapsed = ticks; }
        @Override public void setPartialTicks(float p) { partial = p; }
        @Override public long nanoTime() { return nanos; }

        @Override
        public void teardown() {
            mc.theWorld = null;
            mc.thePlayer = null;
            RewindPlayback.partsExit();
        }

        @Override public void clearTitlesAndBossBar() { }
        @Override public void openTitle() { }
        @Override public void openRejoin() { }
        @Override public void openGallery() { }
        @Override public void log(String line) { }
        @Override public float timerSpeed() { return timerSpeed; }
        @Override public void setTimerSpeed(float speed) { timerSpeed = speed; }
        @Override public int thirdPersonView() { return thirdPerson; }
        @Override public void setThirdPersonView(int view) { thirdPerson = view; }
        @Override public boolean hideGui() { return hideGui; }
        @Override public void setHideGui(boolean hide) { hideGui = hide; }
        @Override public boolean showDebugInfo() { return showDebug; }
        @Override public void setShowDebugInfo(boolean show) { showDebug = show; }
        @Override public boolean smoothCamera() { return smoothCamera; }
        @Override public void setSmoothCamera(boolean smooth) { smoothCamera = smooth; }
        @Override public int difficulty() { return difficulty; }
        @Override public void setDifficulty(int id) { difficulty = id; }
        @Override public void setGamePaused(boolean p) { paused = p; }
        @Override public Object copyChat() { return "chat"; }
        @Override public void restoreChat(Object saved) { }
        @Override public int updateCounter() { return counter; }
        @Override public void setUpdateCounter(int c) { counter = c; }
    }

    RecordedScreensTest.Mc mc;
    PlaybackConnection connection;
    final Ops ops = new Ops();
    final Sink sink = new Sink();
    PlaybackSession session;
    final PlaybackGate gate = PlaybackGate.global();
    Cracks cracks;
    Particles particles;
    Sounds sounds;
    BlockPredictionTest.MapWorld world;
    int frames;
    final List<String> violations = new ArrayList<String>();

    private Field theMinecraft;
    private Object previousMc;
    private Object previousSession;
    private List<PlaybackPart> previousParts;
    private RecordedScreenHolder previousHolder;
    private ClientSettings savedConfig;

    @Before
    public void client() throws Exception {
        if (gate.isMuted()) gate.exit();
        mc = (RecordedScreensTest.Mc) RecordedScreensTest.allocate(RecordedScreensTest.Mc.class);
        mc.displayWidth = 854;
        mc.displayHeight = 480;
        mc.gameSettings = (GameSettings) RecordedScreensTest.allocate(GameSettings.class);
        mc.gameSettings.guiScale = 2;
        mc.entityRenderer = (net.minecraft.client.renderer.EntityRenderer) RecordedScreensTest.allocate(
                net.minecraft.client.renderer.EntityRenderer.class); // setRenderViewEntity's shader check
        BlockPredictionTest.Client.set(Minecraft.class, mc, "mcThread", Thread.currentThread());
        cracks = (Cracks) RecordedScreensTest.allocate(Cracks.class);
        particles = (Particles) RecordedScreensTest.allocate(Particles.class);
        sounds = (Sounds) RecordedScreensTest.allocate(Sounds.class);
        set(mc, RenderGlobal.class, cracks);
        set(mc, EffectRenderer.class, particles);
        set(mc, SoundHandler.class, sounds);
        theMinecraft = Minecraft.class.getDeclaredField("theMinecraft");
        theMinecraft.setAccessible(true);
        previousMc = theMinecraft.get(null);
        theMinecraft.set(null, mc);
        connection = PlaybackConnection.open(mc, VIEWER);
        mc.playerController = new PlayerControllerMP(mc, connection.handler());

        session = new PlaybackSession(ops, gate);
        sounds.session = session;
        Field s = RewindPlayback.class.getDeclaredField("session");
        s.setAccessible(true);
        previousSession = s.get(instance());
        s.set(instance(), session);

        previousParts = new ArrayList<PlaybackPart>(RewindWiringTest.registry());
        previousHolder = RewindCamera.screenHolder();
        RewindWiringTest.registry().clear();
        failedParts().clear();
        BedwarsQol.wireRewindPlayback();
        savedConfig = BedwarsQol.config;
        BedwarsQol.config = null;
    }

    @After
    public void restore() throws Exception {
        try {
            if (session.state() != PlaybackSession.State.IDLE) {
                session.requestExit(PlaybackSession.Exit.TITLE);
                frame(0);
            }
        } finally {
            Field s = RewindPlayback.class.getDeclaredField("session");
            s.setAccessible(true);
            s.set(instance(), previousSession);
            RewindWiringTest.registry().clear();
            RewindWiringTest.registry().addAll(previousParts);
            RewindCamera.setScreenHolder(previousHolder);
            failedParts().clear();
            theMinecraft.set(null, previousMc);
            BedwarsQol.config = savedConfig;
            RewindPlayback.view().reset();
            CameraController.selectYourView();
            connection.close();
            if (gate.isMuted()) gate.exit();
        }
    }

    private static Object instance() throws Exception {
        Field f = RewindPlayback.class.getDeclaredField("INSTANCE");
        f.setAccessible(true);
        return f.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<PlaybackPart, Boolean> failedParts() throws Exception {
        Field f = RewindPlayback.class.getDeclaredField("FAILED");
        f.setAccessible(true);
        return (Map<PlaybackPart, Boolean>) f.get(null);
    }

    private static void set(Object target, Class<?> type, Object value) throws Exception {
        for (Field f : Minecraft.class.getDeclaredFields()) {
            if (f.getType() == type) {
                f.setAccessible(true);
                f.set(target, value);
                return;
            }
        }
        throw new AssertionError("Minecraft has no " + type.getSimpleName() + " field");
    }

    /** S01: a fresh world (a block map) and a fresh camera on it, as vanilla's join handler makes them. */
    void newWorld() {
        try {
            world = BlockPredictionTest.MapWorld.create();
            BlockPredictionTest.Client.set(World.class, world, "provider", new WorldProviderSurface());
            BlockPredictionTest.Client.set(World.class, world, "worldInfo",
                    RecordedScreensTest.allocate(WorldInfo.class));
            BlockPredictionTest.Client.set(World.class, world, "worldBorder", new WorldBorder());
            BlockPredictionTest.Client.set(NetHandlerPlayClient.class, connection.handler(), "clientWorldController",
                    world);
            mc.theWorld = world;
            RewindCamera cam = new RewindCamera(mc, world, connection.handler(), null);
            mc.thePlayer = cam;
            mc.setRenderViewEntity(cam);
            cracks.shown = null; // vanilla's RenderGlobal forgets its cracks with the old world
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ the game loop

    /** Advances the fake clock so exactly {@code n} more ticks are due (as PlaybackSessionTest's loop does). */
    private void clockFor(int n) {
        if (session.state() != PlaybackSession.State.WATCHING || session.clock() == null
                || session.ticks().paused()) {
            return;
        }
        TickTable t = session.timeline().ticks();
        long want = t.timeOf(session.ticks().ticksRun() + n) - 1 - session.clock().playbackUs();
        if (want > 0) {
            ops.nanos += (long) Math.ceil(want * 1000.0 / session.ticks().speed());
        }
    }

    /** One frame of {@code Minecraft.runGameLoop}: the frame hook, the ticks, the pause write, then renderWorld. */
    void frame(int ticks) {
        clockFor(ticks);
        ops.elapsed = ticks;
        session.frameStart();
        checkIsolation("frame start");
        for (int j = 0; j < ops.elapsed; j++) {
            session.tickHead();
            checkIsolation("tick head");
            if (mc.theWorld != null && !ops.paused && mc.thePlayer != null) {
                mc.thePlayer.onUpdate(); // the camera's own tick in the world tick
            }
            session.tickEnd();
        }
        ops.paused = false;
        session.afterPauseWrite();
        RewindPlayback.onRenderWorld(ops.partial); // the production renderWorld HEAD hook: pose, parts' frame
        frames++;
    }

    private void checkIsolation(String where) {
        PlaybackSession.State s = session.state();
        if (s == PlaybackSession.State.CATCHING_UP || s == PlaybackSession.State.WATCHING
                || s == PlaybackSession.State.REBUILDING) {
            if (!gate.isMuted()) violations.add(where + ": gate not muted in " + s);
            if (ActionCapture.bound()) violations.add(where + ": the recorder's action capture is bound");
            if (HudCapture.shared().active()) violations.add(where + ": the recorder's HUD capture is on");
        }
    }

    void until(PlaybackSession.State state) {
        for (int i = 0; i < 5000 && session.state() != state; i++) {
            frame(1);
        }
        assertEquals(state, session.state());
    }

    void open(File cbr) {
        ops.cbr = cbr;
        session.open("fake-recording");
        until(PlaybackSession.State.WATCHING);
    }

    /** A paused seek to {@code tick}, landed, then one drawn frame. */
    void seek(long tick) {
        session.seekTo(tick);
        frame(0);
        for (int i = 0; i < 5000 && (session.state() != PlaybackSession.State.WATCHING
                || session.ticks().catchingUp()); i++) {
            frame(1);
        }
        assertEquals(PlaybackSession.State.WATCHING, session.state());
        frame(0);
    }

    // ------------------------------------------------------------------ the snapshot

    static String describe(ItemStack s) {
        return s == null ? "-" : Item.getIdFromItem(s.getItem()) + "x" + s.stackSize + ":" + s.getMetadata();
    }

    /** Every equivalence group, as text. */
    Map<String, String> snapshot() throws Exception {
        Map<String, String> g = new LinkedHashMap<String, String>();
        RewindCamera cam = (RewindCamera) mc.thePlayer;
        assertNotNull("a camera", cam);
        g.put("tick", session.ticks().ticksRun() + " playhead " + session.playheadTick());
        g.put("blocks", world.snapshot());

        Container open = cam.openContainer;
        StringBuilder cont = new StringBuilder(open == cam.inventoryContainer ? "inventory container"
                : open.getClass().getSimpleName() + " window " + open.windowId + " slots " + open.inventorySlots.size());
        for (Slot sl : open.inventorySlots) {
            cont.append(';').append(describe(sl.getStack()));
        }
        RecordedScreens screens = (RecordedScreens) RewindCamera.screenHolder();
        cont.append(" | held ").append(screens.held() == null ? "none" : screens.held().getClass().getSimpleName());
        g.put("container", cont.toString());

        StringBuilder inv = new StringBuilder();
        for (ItemStack st : cam.inventory.mainInventory) inv.append(describe(st)).append(';');
        for (ItemStack st : cam.inventory.armorInventory) inv.append(describe(st)).append(';');
        inv.append("cursor ").append(describe(cam.inventory.getItemStack())).append(" selected ")
                .append(cam.inventory.currentItem).append(" use ").append(describe(cam.getItemInUse())).append(',')
                .append(cam.getItemInUseCount()).append(" swing ").append(cam.isSwingInProgress).append(',')
                .append(cam.swingProgressInt).append(',').append(cam.swingProgress);
        g.put("inventory", inv.toString());

        ViewState v = session.view();
        g.put("view", "view " + v.view() + " perspective " + v.perspective() + " hideGui " + v.hideGui() + " f3 "
                + v.showDebug() + " tab " + v.tabList() + " screen " + v.screenKind() + " noHud "
                + v.hidesEveryHud() + " recordedHud " + v.recordedHud() + " | written " + ops.thirdPerson + ","
                + ops.hideGui + "," + ops.showDebug + " | fov " + fovPart().recorded() + " camera "
                + cam.getFovModifier());

        RecordedHudSource src = hudSource();
        LoadedRecording r = session.recording();
        long t = session.renderTimeUs();
        int seg = ViewTrack.segmentFor(r.extTrack(), RewindFormat.TAG_SETTINGS, ViewPart.segmentNow(session), t);
        src.at(r.extTrack(), seg, t);
        StringBuilder hud = new StringBuilder("settings " + (src.settings() != null) + " fps");
        for (TextModules.Seg s : src.textRuns(mc, RewindFormat.hudModuleName(RewindFormat.HUD_FPS))) {
            hud.append(' ').append(s.text);
        }
        hud.append(" keys ").append(Arrays.toString(src.keyStates(mc))).append(" lmb ")
                .append(src.keyFade(5).level(false, src.now()));
        g.put("hud", hud.toString());

        DigState d = DigPart.instance().state();
        g.put("dig", "hitting " + d.hitting() + " pos " + d.pos() + " stage " + d.stage() + " shown " + d.shown()
                + " crack " + cracks.state());

        g.put("camera", "bob " + cam.cameraYaw + "," + cam.prevCameraYaw + "," + cam.cameraPitch + ","
                + cam.distanceWalkedModified + " invisible " + cam.isInvisible() + " burning " + cam.isBurning()
                + " hurt " + cam.hurtTime + " yaw " + cam.rotationYaw + "," + cam.rotationPitch);
        return g;
    }

    /**
     * Your view's eye position, interpolated by playback time (not a tick state): straight play pauses at
     * {@code T[K] - 1 us} (the clock is set just short of the next tick), a landed seek at {@code T[K]}, so the two
     * may differ by what the recorded player moves in that microsecond.
     */
    double[] pose() {
        RewindCamera cam = (RewindCamera) mc.thePlayer;
        return new double[]{cam.posX, cam.posY, cam.posZ, session.renderTimeUs()};
    }

    static void assertSamePose(String what, double[] a, double[] b) {
        double us = Math.abs(a[3] - b[3]);
        assertTrue(what + ": playback times " + a[3] + " and " + b[3], us <= 1);
        double tol = 1e-7 + us * 0.1 / TICK_US; // the fixture walks 0.1 block per tick; the partial is a float
        for (int i = 0; i < 3; i++) {
            assertEquals(what + " axis " + i, a[i], b[i], tol);
        }
    }

    static FovPart fovPart() throws Exception {
        Field f = FovPart.class.getDeclaredField("INSTANCE");
        f.setAccessible(true);
        return (FovPart) f.get(null);
    }

    static RecordedHudSource hudSource() throws Exception {
        Field f = HudPlayback.class.getDeclaredField("INSTANCE");
        f.setAccessible(true);
        return ((HudPlayback) f.get(null)).source();
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void aV2RecordingPlaysTheSameStraightAndAfterForwardAndBackwardSeeks() throws Exception {
        File cbr = writeRecording(tmp.newFolder("rewind"));
        open(cbr);
        LoadedRecording r = session.recording();
        assertEquals(2, r.formatVersion());
        for (int tag : RewindFormat.EXT_TAGS) {
            assertTrue("the fixture holds " + RewindFormat.extName(tag), r.has(tag));
        }
        assertEquals(TickTable.Kind.V2, session.timeline().ticks().kind());

        // straight play, one tick per frame, to K
        while (session.ticks().ticksRun() < K) {
            frame(1);
        }
        session.setPaused(true);
        frame(0);
        long landed = session.playheadTick();
        Map<String, String> straight = snapshot();
        double[] straightPose = pose();
        List<String> straightSounds = sounds.played == null ? new ArrayList<String>()
                : new ArrayList<String>(sounds.played);

        // what the parts made of the records (not only equal: the right values)
        assertEquals("A broken by you", "air", world.at(A));
        assertEquals("B placed by you", "wool", world.at(B));
        RewindCamera cam = (RewindCamera) mc.thePlayer;
        assertEquals("the double chest is open", WINDOW, cam.openContainer.windowId);
        assertEquals(90, cam.openContainer.inventorySlots.size());
        for (int i = 0; i < 54; i++) {
            if (i == 10 || i == 11) continue;
            assertEquals("chest slot " + i, 1 + i % 64, cam.openContainer.getSlot(i).getStack().stackSize);
        }
        assertEquals("the server's S2F", "gold_ingot", Item.itemRegistry.getNameForObject(
                cam.openContainer.getSlot(10).getStack().getItem()).getResourcePath());
        assertEquals("your click's CONT", "ender_pearl", Item.itemRegistry.getNameForObject(
                cam.openContainer.getSlot(11).getStack().getItem()).getResourcePath());
        assertEquals("amendment 2: the hotbar slot set through the chest's window", "golden_apple",
                Item.itemRegistry.getNameForObject(cam.inventory.mainInventory[0].getItem()).getResourcePath());
        assertEquals("wool after the placement (INV)", 63, cam.inventory.mainInventory[1].stackSize);
        assertTrue("the held screen is the chest", ((RecordedScreens) RewindCamera.screenHolder()).held()
                instanceof net.minecraft.client.gui.inventory.GuiChest);
        assertEquals(RewindFormat.SCREEN_CHEST, session.view().screenKind());
        assertEquals("the recorded FOV after the zoom", 80.25f, fovPart().recorded(), 0f);
        assertEquals("v2: no derived FOV on the camera", 1f, cam.getFovModifier(), 0f);
        assertTrue(straight.get("hud"), straight.get("hud").contains("fps 118"));
        assertTrue(straight.get("hud"), straight.get("hud").startsWith("settings true"));
        assertFalse("the dig ended", DigPart.instance().state().hitting());
        assertEquals("the crack was cleared", "{}", cracks.state());
        assertTrue("hit particles while digging", particles.hits >= 6);
        assertEquals("break particles once (BLOCK, own break of stone)", 1, particles.destroys);

        // each stored sound once, at or after its own stamp, while watching
        assertEquals(straightSounds.toString(), 2, straightSounds.size());
        assertTrue(straightSounds.get(0), straightSounds.get(0).startsWith("minecraft:dig.stone@3.5,64.5,5.5"));
        assertTrue(straightSounds.get(1), straightSounds.get(1).startsWith("minecraft:dig.cloth@4.5,64.5,6.5"));
        for (String s : straightSounds) {
            assertTrue(s, s.endsWith("state WATCHING"));
        }

        // a forward seek to K: back to K - 40 first (a restart), then forward (a catch-up)
        seek(landed - 40);
        int soundsBefore = sounds.played.size();
        seek(landed);
        Map<String, String> forward = snapshot();
        assertSamePose("forward seek", straightPose, pose());
        assertEquals("no sound in a forward catch-up", soundsBefore, sounds.played.size());

        // a backward seek to K: past it first, then back (a restart from the segment's start)
        seek(TICKS - 5);
        soundsBefore = sounds.played.size();
        seek(landed);
        Map<String, String> backward = snapshot();
        assertSamePose("backward seek", straightPose, pose());
        assertEquals("no sound in a rebuild", soundsBefore, sounds.played.size());

        for (String group : straight.keySet()) {
            assertEquals("forward seek, group " + group, straight.get(group), forward.get(group));
            assertEquals("backward seek, group " + group, straight.get(group), backward.get(group));
        }

        assertEquals("nothing reached the playback channel", 0, connection.discarded());
        assertTrue(connection.channel().outboundMessages().isEmpty());
        assertTrue(violations.toString(), violations.isEmpty());
        assertTrue("no part threw: " + failedParts().keySet(), failedParts().isEmpty());
        assertTrue("straight play drew a frame per tick", frames > K);

        session.requestExit(PlaybackSession.Exit.TITLE);
        frame(0);
        assertEquals(PlaybackSession.State.IDLE, session.state());
        assertFalse("the gate is open again after the session", gate.isMuted());
        assertEquals("the crack is not left for live play", "{}", cracks.state());
    }

    /** Recorded F1 through the production session: hideGUI written, the overlay skipped, the recorded HUD not drawn. */
    @Test
    public void recordedF1HidesTheHudThroughTheSessionAndTheCleanViewOverridesIt() throws Exception {
        open(writeRecording(tmp.newFolder("rewind")));
        session.setPaused(true);
        seek(32);
        assertTrue("the session wrote the recorded F1", ops.hideGui);
        assertTrue("the whole overlay is skipped", HudPlayback.hidesEveryHud());
        assertEquals("first person", 0, ops.thirdPerson);

        RewindPlayback.view().cyclePerspective(); // F5 while watching
        RewindPlayback.viewStateChanged();
        frame(0);
        assertFalse("F5 overrides the recorded F1 until key 1", ops.hideGui);
        assertEquals(1, ops.thirdPerson);
        assertFalse(HudPlayback.hidesEveryHud());

        RewindPlayback.view().toggleClean(); // the Clean View Key
        RewindPlayback.viewStateChanged();
        frame(0);
        assertTrue(ops.hideGui);
        assertTrue(HudPlayback.hidesEveryHud());

        RewindPlayback.view().selectYou(); // key 1
        RewindPlayback.viewStateChanged();
        seek(40);
        assertFalse("after the recorded F1 ended", ops.hideGui);
        assertFalse(HudPlayback.hidesEveryHud());
        assertTrue("no part threw: " + failedParts().keySet(), failedParts().isEmpty());
        assertEquals(0, connection.discarded());
    }

    /** {@code EntityRenderer.setupOverlayRendering}: counts the 2D GUI projections set up. */
    static final class Projection extends net.minecraft.client.renderer.EntityRenderer {
        int overlays;

        Projection() {
            super(null, null); // never run: allocated
        }

        @Override
        public void setupOverlayRendering() {
            overlays++;
        }
    }

    /**
     * Review code-2 B1: with a world loaded, vanilla sets the GUI projection only inside {@code renderGameOverlay}
     * (offset 30) before the current screen draws. Whenever the HUD layer skips that method (the clean view, recorded
     * F1 with no recorded screen), the same projection is set up, so the viewer bar, the Esc menu and the loading card
     * draw in screen space; when the overlay runs, the skip path sets nothing.
     */
    @Test
    public void aSkippedOverlayStillSetsUpTheGuiProjectionForTheScreens() throws Exception {
        open(writeRecording(tmp.newFolder("rewind")));
        Projection p = (Projection) RecordedScreensTest.allocate(Projection.class);
        mc.entityRenderer = p;
        session.setPaused(true);
        seek(20);
        assertFalse("the overlay runs (and sets the projection itself)", HudPlayback.skipsOverlay(mc));
        assertEquals(0, p.overlays);

        RewindPlayback.view().toggleClean(); // the Clean View Key
        RewindPlayback.viewStateChanged();
        frame(0);
        assertTrue("the clean view skips the overlay", HudPlayback.skipsOverlay(mc));
        assertEquals("and sets up the GUI projection instead", 1, p.overlays);
        RewindPlayback.view().toggleClean();
        RewindPlayback.viewStateChanged();
        frame(0);
        assertFalse(HudPlayback.skipsOverlay(mc));
        assertEquals(1, p.overlays);

        RewindPlayback.view().selectYou(); // key 1: follow the recording again
        RewindPlayback.viewStateChanged();
        seek(32); // the recorded F1 (ticks 30-34), no recorded screen
        assertTrue(ops.hideGui);
        assertTrue("recorded F1 skips the overlay", HudPlayback.skipsOverlay(mc));
        assertEquals("and sets up the GUI projection instead", 2, p.overlays);

        session.requestExit(PlaybackSession.Exit.TITLE);
        frame(0);
        assertFalse("nothing plays: vanilla's overlay, nothing added", HudPlayback.skipsOverlay(mc));
        assertEquals(2, p.overlays);
    }

    // ------------------------------------------------------------------ D4: the ridden player leaves

    static final int OTHER_ID = 4242;
    private static final GameProfile OTHER = new GameProfile(new UUID(21L, 22L), "FakeOther");

    /**
     * Another player in the playback world, as vanilla's S0C and S38 handlers leave them: in the world's entity map
     * and player list with a tab entry (the S0C spawn's chunk bookkeeping is left out: the test world has no chunks).
     * The world's collections the constructor would make are made here, so vanilla's own removal can run on it.
     */
    private EntityOtherPlayerMP spawnOther() throws Exception {
        BlockPredictionTest.Client.set(WorldClient.class, world, "mc", mc);
        BlockPredictionTest.Client.set(WorldClient.class, world, "entityList", new HashSet<Entity>());
        BlockPredictionTest.Client.set(WorldClient.class, world, "entitySpawnQueue", new HashSet<Entity>());
        BlockPredictionTest.Client.set(World.class, world, "entitiesById", new IntHashMap<Entity>());
        BlockPredictionTest.Client.set(World.class, world, "playerEntities", new ArrayList<EntityPlayer>());
        BlockPredictionTest.Client.set(World.class, world, "worldAccesses", new ArrayList<IWorldAccess>());
        EntityOtherPlayerMP p = new EntityOtherPlayerMP(world, OTHER);
        p.setEntityId(OTHER_ID);
        Field byId = World.class.getDeclaredField("entitiesById");
        byId.setAccessible(true);
        @SuppressWarnings("unchecked")
        IntHashMap<Entity> ids = (IntHashMap<Entity>) byId.get(world);
        ids.addKey(OTHER_ID, p);
        world.playerEntities.add(p);
        Field list = WorldClient.class.getDeclaredField("entityList");
        list.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<Entity> entities = (java.util.Set<Entity>) list.get(world);
        entities.add(p);
        Field info = NetHandlerPlayClient.class.getDeclaredField("playerInfoMap");
        info.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, NetworkPlayerInfo> tab = (Map<UUID, NetworkPlayerInfo>) info.get(connection.handler());
        tab.put(OTHER.getId(), new NetworkPlayerInfo(OTHER));
        return p;
    }

    private static void assertEye(String what, RewindCamera cam, double x, double eyeY, double z, float yaw,
                                  float pitch) {
        assertEquals(what + " x", x, cam.posX, 1e-9);
        assertEquals(what + " eye y", eyeY, cam.posY + cam.getEyeHeight(), 1e-9);
        assertEquals(what + " z", z, cam.posZ, 1e-9);
        assertEquals(what + " yaw", yaw, cam.rotationYaw, 1e-4f);
        assertEquals(what + " pitch", pitch, cam.rotationPitch, 1e-4f);
    }

    /**
     * TASK D4 (outcome review I1): when the ridden player leaves the world the view becomes the free camera where the
     * ridden view was. The client path, not the model alone: key 3 ({@link RewindPlayback#cyclePlayer}) rides another
     * player of the playback world, vanilla's S13 removal ({@code WorldClient.removeEntityFromWorld}) takes them out,
     * and the next drawn frame ({@code RiddenView.follow} -> {@code toFree} -> {@link
     * CameraController#selectFreeCameraAt}) is the free camera at their last eye and head turn, viewed from the
     * camera; playback keeps running and the free camera stays there.
     */
    @Test
    public void whenTheRiddenPlayerLeavesTheViewBecomesTheFreeCameraWhereItWas() throws Exception {
        open(writeRecording(tmp.newFolder("rewind")));
        frame(1);
        EntityOtherPlayerMP other = spawnOther();
        other.setPositionAndRotation(20.5, 70, -8.25, 135f, -20f);
        RewindCamera cam = (RewindCamera) mc.thePlayer;

        assertTrue("key 3 rides the other player", RewindPlayback.cyclePlayer(1));
        assertEquals(OTHER_ID, RewindPlayback.view().riddenEntityId());
        frame(1);
        assertSame("vanilla views from them", other, mc.getRenderViewEntity());
        assertEye("the camera at their eye", cam, 20.5, 70.0 + other.getEyeHeight(), -8.25, 135f, -20f);

        other.setPositionAndRotation(21.0, 70.5, -9.0, 140f, -25f); // where they are last seen
        frame(1);
        assertEye("following them", cam, 21.0, 70.5 + (double) other.getEyeHeight(), -9.0, 140f, -25f);
        double lastEyeY = 70.5 + (double) other.getEyeHeight();

        assertSame("vanilla's S13 removal", other, world.removeEntityFromWorld(OTHER_ID));
        assertNull("they left the world", world.getEntityByID(OTHER_ID));
        long ticks = session.ticks().ticksRun();
        frame(1);
        assertTrue("the free camera", RewindPlayback.view().freeCamera());
        assertEquals(CameraController.FREE_CAMERA, CameraController.cameraIndex());
        assertEquals("nobody is ridden any more", -1, RewindPlayback.view().riddenEntityId());
        assertSame("viewed from the camera again", cam, mc.getRenderViewEntity());
        assertSame("the same camera", cam, mc.thePlayer);
        assertEye("where the ridden view was", cam, 21.0, lastEyeY, -9.0, 140f, -25f);

        for (int i = 0; i < 5; i++) {
            frame(1);
        }
        assertEquals("playback keeps running", PlaybackSession.State.WATCHING, session.state());
        assertFalse(session.ticks().paused());
        assertEquals("a tick per frame", ticks + 6, session.ticks().ticksRun());
        assertTrue("the free camera still", RewindPlayback.view().freeCamera());
        assertEye("the free camera stays there", cam, 21.0, lastEyeY, -9.0, 140f, -25f);

        assertTrue("no part threw: " + failedParts().keySet(), failedParts().isEmpty());
        assertEquals("nothing reached the playback channel", 0, connection.discarded());
        assertTrue(connection.channel().outboundMessages().isEmpty());
    }
}
