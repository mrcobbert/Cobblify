package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

/**
 * Your own digging in playback (PLAN Phase 11, "C. Playback of v2 data"; TASK C1): the crack growing on the block you
 * mined and the hit particles, at the recorded ticks.
 *
 * <p>Each DIG record (tick-scoped) goes into {@link DigState} in its tick's local phase; at each world tick the part
 * makes the client calls vanilla made in that tick: {@code RenderGlobal.sendBlockBreakProgress(breaker, block, stage)}
 * (what {@code PlayerControllerMP} reaches through {@code World.sendBlockBreakProgress}; a client call, never a packet)
 * and {@code EffectRenderer.addBlockHitEffects(block, face)} (what {@code sendClickBlockToController} adds). The
 * breaker id is the recorded player's entity id from the last S01 (the server sends no S25 for your own digging).
 * Break particles and the block change come from the BLOCK record ({@link BlockPart}); the dig and break sounds are
 * SOUND records ({@link SoundPart}).
 *
 * <p>The crack is state, so it follows every tick, also in a catch-up (the particles' mute is the session's, in
 * {@code EffectRendererRewindMixin}); a restart, a new world (the recorder forgets the dig state at each one) and the
 * exit remove a crack still shown, under the id it was shown with, so nothing is left in the render global for live
 * play. A recording without DIG records makes no call.
 *
 * <p>Register once with {@link #register()} (Phase 16 calls it from {@code BedwarsQol}). Client thread; never sends.
 */
public final class DigPart implements PlaybackPart {

    /** The client calls (a test supplies its own). */
    interface Out {
        /** {@code RenderGlobal.sendBlockBreakProgress}: stage 0-9 shows, anything else removes the breaker's entry. */
        void crack(int breakerId, BlockPos pos, int stage);

        /** {@code EffectRenderer.addBlockHitEffects}. */
        void hit(BlockPos pos, EnumFacing face);
    }

    /** What the part reads from the session. */
    interface Source {
        /** The breaker id cracks are shown with: the recorded player's entity id. */
        int breakerId();
    }

    private static final Source SESSION = () -> {
        PlaybackSink s = RewindPlayback.currentSink();
        int id = s == null ? Integer.MIN_VALUE : s.recordedEntityId();
        return id == Integer.MIN_VALUE ? RewindCamera.RESERVED_ID : id;
    };

    private static final Out CLIENT = new Out() {
        @Override
        public void crack(int breakerId, BlockPos pos, int stage) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.renderGlobal != null) {
                mc.renderGlobal.sendBlockBreakProgress(breakerId, pos, stage);
            }
        }

        @Override
        public void hit(BlockPos pos, EnumFacing face) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.effectRenderer != null && mc.theWorld != null) {
                mc.effectRenderer.addBlockHitEffects(pos, face);
            }
        }
    };

    private static final DigPart INSTANCE = new DigPart(SESSION, CLIENT);

    private final Source source;
    private final Out out;
    private final DigState dig = new DigState();
    private final ExtCodec.Dig record = new ExtCodec.Dig();
    /** The id the shown crack was sent with. */
    private int shownId;

    DigPart(Source source, Out out) {
        this.source = source;
        this.out = out;
    }

    /** Registers the part with playback (once; repeated calls do nothing). Client thread. */
    public static void register() {
        RewindPlayback.register(INSTANCE);
    }

    /** The registered instance. */
    static DigPart instance() {
        return INSTANCE;
    }

    /** The dig state (tests). */
    DigState state() {
        return dig;
    }

    @Override
    public void restart() {
        forget();
    }

    /** A new world: the recorder starts each one from "not hitting". */
    @Override
    public void boundary() {
        forget();
    }

    @Override
    public void exit() {
        forget();
    }

    private void forget() {
        if (dig.takeShown()) {
            out.crack(shownId, BlockPos.fromLong(dig.shownPos()), -1);
        }
        dig.reset();
    }

    @Override
    public void local(int tag, long timeUs, byte[] buf, int off, int len) {
        if (tag != RewindFormat.TAG_DIG) {
            return;
        }
        record.read(buf, off, len);
        dig.record(record.flags, record.pos, record.face, record.stage);
    }

    @Override
    public void tick(long tick, long timeUs) {
        boolean wasShown = dig.shown();
        dig.tick();
        if (dig.crackCall()) {
            // a clear goes to the id the crack was shown with; a new crack takes the current id
            int id = !dig.hitting() && wasShown ? shownId : source.breakerId();
            out.crack(id, BlockPos.fromLong(dig.crackPos()), dig.crackStage());
            if (dig.shown()) {
                shownId = id;
            }
        }
        if (dig.hit()) {
            out.hit(BlockPos.fromLong(dig.hitPos()), EnumFacing.getFront(dig.hitFace()));
        }
    }
}
