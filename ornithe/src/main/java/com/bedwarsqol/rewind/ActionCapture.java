package com.bedwarsqol.rewind;

import com.bedwarsqol.mixin.PlayerControllerMPRewindAccessor;
import com.bedwarsqol.rewind.watch.PlaybackGate;
import io.netty.buffer.Unpooled;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Rewind's capture of the client's own actions (PLAN section A, Phase 2): the format v2 records TICK_HEAD, OUT, DIG,
 * BLOCK and SOUND, written through a {@link Sink} over the TRACK producer, plus the client half of the handled order
 * (the pops of the {@link HandledRing}).
 *
 * <p><b>Entry points</b> (each called by one mixin, or by the recorder's tick):
 * <ul>
 *   <li>{@link #onRunTickHead} ({@code Minecraft.runTick} HEAD): closes every local scope, takes the handled increase
 *       from the ring and writes one TICK_HEAD while the segment is recorded.</li>
 *   <li>{@link #onSend} ({@code NetHandlerPlayClient.addToSendQueue} HEAD): on the client thread, one OUT for each
 *       C02, C07, C08, C09, C0A, C0B, C0D, C0E and C16, as {@code varint id + writePacketData} into one reused
 *       buffer. It reads the packet and returns; nothing can be cancelled or changed.</li>
 *   <li>{@link #enterLocal}/{@link #exitLocal} ({@code clickMouse}, {@code rightClickMouse},
 *       {@code sendClickBlockToController}, {@code EntityPlayerSP.onUpdate}), {@link #enterDestroy}/{@link #exitDestroy}
 *       ({@code PlayerControllerMP.onPlayerDestroyBlock}): the {@link LocalScope}.</li>
 *   <li>{@link #onBlockChangeHead}/{@link #onBlockChangeReturn} ({@code World.setBlockState(pos, state, flags)} HEAD
 *       and RETURN, client worlds only): one BLOCK for a change made inside a local scope, with the state ids before
 *       and after ({@code Block.BLOCK_STATE_IDS}, the id S23 carries) and the cause.</li>
 *   <li>{@link #onSound} ({@code SoundHandler.playSound} HEAD): one SOUND for a positioned, non-repeating,
 *       distance-attenuated sound played inside a local scope. Names are cached as UTF-8 per {@link ResourceLocation}.</li>
 *   <li>{@link #onTickEnd} (the recorder's end-of-tick sample): one DIG when the dig state changed.</li>
 *   <li>{@link #onPacketRun} ({@code PacketThreadUtil$1.run} HEAD): pops the ring for a play packet about to run.</li>
 * </ul>
 *
 * <p><b>Inert until wired.</b> Before {@link #bind} (and after {@link #unbind}) every entry point returns at its first
 * check. While a recording plays ({@link PlaybackGate#muted()}) they return too. Records are written only while
 * {@link Sink#recording()} says the current TRACK segment is recorded; the ring is popped whenever one is bound.
 *
 * <p><b>Failures</b> (A14): every entry point catches Throwable. A throw ends action capture until the next
 * {@link #startSegment} and is reported once through {@link Sink#failed}; a sink that has no room
 * ({@link Sink#append} false) ends it the same way without a report (the sink knows). The hooked vanilla call is never
 * touched: no hook cancels, changes, delays or sends anything.
 *
 * <p><b>Cost</b> (A12): client thread only (a call from another thread returns), no I/O, no logging, no locks. Steady
 * state allocates nothing: the only allocations are vanilla's NBT writer for an OUT C08/C0E carrying a tagged item,
 * the first sight of a sound name, and the reused buffers' growth.
 */
public final class ActionCapture {

    /** Where the records go: the TRACK producer of the recorder (Phase 6). Client thread. */
    public interface Sink {
        /** True while the current TRACK segment is recorded. */
        boolean recording();

        /**
         * Appends one extension record stamped now: {@code tag}, then {@code buf[0, len)} ({@code varint len | body}).
         * False when there was no room; the sink then ends recording for the game itself.
         */
        boolean append(int tag, byte[] buf, int len);

        /** A capture hook threw; called once per failure. The game's recording ends (the prefix stays readable). */
        void failed(Throwable cause);
    }

    /** Nested {@code setBlockState} calls tracked with their own old state. */
    private static final int MAX_BLOCK_DEPTH = 16;
    private static final int NOT_CAPTURED = Integer.MIN_VALUE;
    /** Sound names cached at most; a name past the cap is encoded per play. */
    private static final int MAX_SOUND_NAMES = 4096;

    private static final LocalScope SCOPE = new LocalScope();
    private static final ExtCodec CODEC = new ExtCodec(512);
    private static final HashMap<ResourceLocation, byte[]> SOUND_NAMES = new HashMap<ResourceLocation, byte[]>();
    private static final int[] BLOCK_OLD = new int[MAX_BLOCK_DEPTH];

    private static Sink sink;
    private static HandledRing ring;
    private static PacketStats.Feed feed;
    private static Thread clientThread;
    private static int segment;
    private static boolean failed;
    private static PacketBuffer outBuffer;
    private static int blockDepth;

    // last DIG written
    private static boolean digHitting;
    private static long digPos;
    private static int digFace;
    private static int digStage;

    private ActionCapture() {
    }

    // ================================================================== wiring (Phase 6)

    /**
     * Turns capture on for a connection; call on the client thread, which becomes the only thread the hooks act on.
     * {@code ring} is the connection's {@link CaptureSession#handledRing()} (null: every TICK_HEAD says the order is
     * unknown); {@code feed} gets the OUT timing (may be null). Resets all capture state.
     */
    public static void bind(Sink s, HandledRing r, PacketStats.Feed f) {
        clientThread = Thread.currentThread();
        ring = r;
        feed = f;
        segment = 0;
        failed = false;
        SCOPE.reset();
        blockDepth = 0;
        resetDig();
        sink = s;
    }

    /** Turns capture off: every entry point is inert again. */
    public static void unbind() {
        sink = null;
        ring = null;
        feed = null;
        clientThread = null;
        failed = false;
        SCOPE.reset();
        blockDepth = 0;
    }

    /** True between {@link #bind} and {@link #unbind}. */
    public static boolean bound() {
        return sink != null;
    }

    /**
     * TRACK segment {@code segment} opened ({@code WORLD_LOAD}, the same number the capture handler gives its frames).
     * Forgets the last DIG and re-arms capture after a failure.
     */
    public static void startSegment(int newSegment) {
        segment = newSegment;
        failed = false;
        resetDig();
    }

    /** The local scope (tests read it). */
    public static LocalScope scope() {
        return SCOPE;
    }

    private static void resetDig() {
        digHitting = false;
        digPos = Long.MIN_VALUE;
        digFace = 0;
        digStage = -1;
    }

    private static boolean onClientThread() {
        return Thread.currentThread() == clientThread;
    }

    private static void fail(Throwable t) {
        if (failed) {
            return;
        }
        failed = true;
        Sink s = sink;
        if (s != null) {
            try {
                s.failed(t);
            } catch (Throwable ignored) {
                // the hooked call goes on whatever the sink does
            }
        }
    }

    private static void append(Sink s, int tag, int n) {
        if (!s.append(tag, CODEC.buffer(), n)) {
            failed = true;
        }
    }

    // ================================================================== tick head

    /** {@code Minecraft.runTick} HEAD. */
    public static void onRunTickHead() {
        Sink s = sink;
        if (s == null) {
            return;
        }
        SCOPE.reset();
        blockDepth = 0;
        if (!onClientThread() || PlaybackGate.muted()) {
            return;
        }
        try {
            HandledRing r = ring;
            int handled = 0;
            int flags = RewindFormat.TICK_HEAD_ORDER_UNKNOWN;
            if (r != null) {
                r.takeHead(segment);
                handled = r.headIncrease();
                flags = r.headFlags();
            }
            if (failed || !s.recording()) {
                return;
            }
            if (flags != 0 && feed != null) {
                feed.orderUnknownTick();
            }
            append(s, RewindFormat.TAG_TICK_HEAD, CODEC.tickHead(handled, flags));
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ================================================================== handled order

    /** {@code PacketThreadUtil$1.run} HEAD: {@code packet} is about to run on {@code handler}. */
    public static void onPacketRun(Packet<?> packet, INetHandler handler) {
        if (ring == null || !(handler instanceof NetHandlerPlayClient) || !onClientThread() || PlaybackGate.muted()) {
            return;
        }
        try {
            Integer id = EnumConnectionState.PLAY.getPacketId(EnumPacketDirection.CLIENTBOUND, packet);
            if (id != null) {
                onPacketRun(id.intValue());
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** The play packet with clientbound id {@code packetId} is about to run. */
    static void onPacketRun(int packetId) {
        HandledRing r = ring;
        if (r == null || !onClientThread() || PlaybackGate.muted()) {
            return;
        }
        try {
            r.onHandled(packetId);
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ================================================================== OUT

    /** {@code NetHandlerPlayClient.addToSendQueue} HEAD. Reads the packet; never changes it. */
    public static void onSend(Packet<?> packet) {
        Sink s = sink;
        if (s == null || failed || packet == null || !onClientThread() || PlaybackGate.muted()) {
            return;
        }
        try {
            if (!s.recording()) {
                return;
            }
            Integer boxed = EnumConnectionState.PLAY.getPacketId(EnumPacketDirection.SERVERBOUND, packet);
            if (boxed == null || !RewindFormat.isStoredOutbound(boxed.intValue())) {
                return;
            }
            long t0 = System.nanoTime();
            PacketBuffer b = outBuffer;
            if (b == null) {
                b = outBuffer = new PacketBuffer(Unpooled.buffer(256));
            }
            b.clear();
            packet.writePacketData(b);
            int n = CODEC.out(boxed.intValue(), b.array(), b.arrayOffset() + b.readerIndex(), b.readableBytes());
            append(s, RewindFormat.TAG_OUT, n);
            PacketStats.Feed f = feed;
            if (f != null) {
                f.out.add(System.nanoTime() - t0);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ================================================================== local scope

    public static void enterLocal() {
        if (sink != null) {
            SCOPE.enter();
        }
    }

    public static void exitLocal() {
        if (sink != null) {
            SCOPE.exit();
        }
    }

    public static void enterDestroy() {
        if (sink != null) {
            SCOPE.enterDestroy();
        }
    }

    public static void exitDestroy() {
        if (sink != null) {
            SCOPE.exitDestroy();
        }
    }

    // ================================================================== BLOCK

    /** {@code World.setBlockState(pos, state, flags)} HEAD; the mixin calls it for client worlds only. */
    public static void onBlockChangeHead(World world, BlockPos pos) {
        Sink s = sink;
        if (s == null || world == null || !world.isRemote) {
            return;
        }
        int d = blockDepth++;
        if (d >= MAX_BLOCK_DEPTH) {
            return;
        }
        BLOCK_OLD[d] = NOT_CAPTURED;
        if (!SCOPE.local() || failed || pos == null || !onClientThread() || PlaybackGate.muted()) {
            return;
        }
        try {
            if (s.recording()) {
                BLOCK_OLD[d] = Block.BLOCK_STATE_IDS.get(world.getBlockState(pos));
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** {@code World.setBlockState(pos, state, flags)} RETURN; {@code changed} is its return value. */
    public static void onBlockChangeReturn(World world, BlockPos pos, boolean changed) {
        Sink s = sink;
        if (s == null || world == null || !world.isRemote || blockDepth == 0) {
            return;
        }
        int d = --blockDepth;
        if (d >= MAX_BLOCK_DEPTH) {
            return;
        }
        int old = BLOCK_OLD[d];
        if (old == NOT_CAPTURED || !changed || failed || !onClientThread()) {
            return;
        }
        try {
            IBlockState now = world.getBlockState(pos);
            append(s, RewindFormat.TAG_BLOCK,
                    CODEC.block(pos.toLong(), old, Block.BLOCK_STATE_IDS.get(now), SCOPE.blockCause()));
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ================================================================== SOUND

    /** {@code SoundHandler.playSound(ISound)} HEAD. */
    public static void onSound(ISound sound) {
        Sink s = sink;
        if (s == null || failed || !SCOPE.local() || sound == null || !onClientThread() || PlaybackGate.muted()) {
            return;
        }
        try {
            if (sound.getAttenuationType() != ISound.AttenuationType.LINEAR || sound.canRepeat()) {
                return;
            }
            ResourceLocation loc = sound.getSoundLocation();
            if (loc == null || !s.recording()) {
                return;
            }
            byte[] name = SOUND_NAMES.get(loc);
            if (name == null) {
                name = loc.toString().getBytes(StandardCharsets.UTF_8);
                if (SOUND_NAMES.size() < MAX_SOUND_NAMES) {
                    SOUND_NAMES.put(loc, name);
                }
            }
            append(s, RewindFormat.TAG_SOUND, CODEC.sound(name, 0, name.length, sound.getXPosF(), sound.getYPosF(),
                    sound.getZPosF(), sound.getVolume(), sound.getPitch()));
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ================================================================== DIG

    /**
     * End of a client tick: the controller's dig state ({@code isHittingBlock}, {@code currentBlock},
     * {@code curBlockDamageMP}) and the face under the cursor, written when it changed.
     */
    public static void onTickEnd(PlayerControllerMP controller, MovingObjectPosition mouseOver) {
        if (sink == null || failed || controller == null || !onClientThread() || PlaybackGate.muted()) {
            return;
        }
        try {
            PlayerControllerMPRewindAccessor a = (PlayerControllerMPRewindAccessor) controller;
            int face = -1;
            if (mouseOver != null && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && mouseOver.sideHit != null) {
                face = mouseOver.sideHit.getIndex();
            }
            dig(a.bedwarsqol$isHittingBlock(), a.bedwarsqol$currentBlock(), face, a.bedwarsqol$curBlockDamageMP());
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * One dig sample: stage is vanilla's {@code (int)(damage * 10) - 1} while hitting, -1 otherwise; the face is the
     * one under the cursor while hitting ({@code face < 0}: unchanged); block and face are not compared while not
     * hitting (a stop keeps the last block and face).
     */
    static void dig(boolean hitting, BlockPos pos, int face, float damage) {
        Sink s = sink;
        if (s == null || failed || !onClientThread() || PlaybackGate.muted()) {
            return;
        }
        try {
            if (!s.recording()) {
                return;
            }
            long p = hitting && pos != null ? pos.toLong() : digPos;
            int stage = hitting ? (int) (damage * 10.0F) - 1 : -1;
            int f = hitting && face >= 0 ? face : digFace;
            if (hitting == digHitting && p == digPos && f == digFace && stage == digStage) {
                return;
            }
            digHitting = hitting;
            digPos = p;
            digFace = f;
            digStage = stage;
            append(s, RewindFormat.TAG_DIG, CODEC.dig(hitting ? RewindFormat.DIG_HITTING : 0, p, f, stage));
        } catch (Throwable t) {
            fail(t);
        }
    }
}
