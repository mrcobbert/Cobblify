package com.bedwarsqol.rewind.watch;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockWall;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;

/**
 * Derived footsteps (PLAN Phase 8, B7): each world tick, {@link StepSounds} runs vanilla's step rule over the recorded
 * movement, and a step makes the puppet play its block's step sound the way vanilla's {@code Entity.playStepSound}
 * does (javap @0-87): {@code Block.stepSound}, volume x 0.15, its frequency; a snow layer above: the snow sound; a
 * liquid: nothing. The puppet's {@code playSound} reaches {@link PuppetSounds}, which plays it (muted during a
 * catch-up or seek), so {@link #register()} registers that part too.
 *
 * <p>Off for a recording with stored SOUND records (its real footsteps play from the file; never both). The rule runs
 * on every tick, also during a catch-up, so straight play and a seek reach the same step distance; a new world
 * (S01/S07) and a fresh connection start it again, like the new player entity live.
 *
 * <p>Ornithe only. Client thread; does nothing when no recording plays and never sends.
 */
public final class StepSoundPart implements PlaybackPart {

    private static final StepSoundPart INSTANCE = new StepSoundPart();

    private final StepSounds steps = new StepSounds();
    /** The world {@link #blocks} reads, set only during {@link #tick}. */
    private World world;
    private final StepSounds.Blocks blocks = (x, y, z) -> kind(world.getBlockState(new BlockPos(x, y, z)).getBlock());

    private StepSoundPart() {
    }

    /** Registers this part and {@link PuppetSounds} (which plays the step) with playback, once each. Client thread. */
    public static void register() {
        PuppetSounds.register();
        RewindPlayback.register(INSTANCE);
    }

    /** The registered instance. */
    static StepSoundPart instance() {
        return INSTANCE;
    }

    @Override
    public void restart() {
        LoadedRecording r = RewindPlayback.session().recording();
        steps.forRecording(r == null ? null : r.ext());
    }

    @Override
    public void boundary() {
        steps.reset();
    }

    @Override
    public void tick(long tick, long timeUs) {
        if (!steps.enabled()) {
            return;
        }
        PlaybackSession s = RewindPlayback.session();
        LoadedRecording r = s.recording();
        Timeline t = s.timeline();
        PlaybackSink sink = RewindPlayback.currentSink();
        World w = Minecraft.getMinecraft().theWorld;
        if (r == null || t == null || sink == null || w == null) {
            return;
        }
        boolean noClip = sink.recordedGameType() == WorldSettings.GameType.SPECTATOR;
        boolean step;
        world = w;
        try {
            step = steps.tick(r.selfTrack(), t.ticks(), tick, noClip, blocks);
        } finally {
            world = null;
        }
        RewindSelfPuppet puppet = sink.puppet();
        if (step && puppet != null && puppet.worldObj == w) {
            playStep(w, new BlockPos(steps.blockX(), steps.blockY(), steps.blockZ()), puppet::playSound);
        }
    }

    @Override
    public void exit() {
        steps.reset();
    }

    /** How the step rule reads a block: air, ladder, fence/wall/gate, anything else. */
    static int kind(Block b) {
        if (b == null || b.getMaterial() == Material.air) {
            return StepSounds.AIR;
        }
        if (b == Blocks.ladder) {
            return StepSounds.LADDER;
        }
        if (b instanceof BlockFence || b instanceof BlockWall || b instanceof BlockFenceGate) {
            return StepSounds.FENCE;
        }
        return StepSounds.SOLID;
    }

    /** Where a step sound goes (the puppet's {@code playSound}). */
    interface Out {
        void playSound(String name, float volume, float pitch);
    }

    /** Vanilla's {@code Entity.playStepSound(pos, block)}: the step sound of the block at {@code pos}. */
    static void playStep(World w, BlockPos pos, Out out) {
        Block block = w.getBlockState(pos).getBlock();
        Block.SoundType type = block.stepSound;
        if (w.getBlockState(pos.up()).getBlock() == Blocks.snow_layer) {
            type = Blocks.snow_layer.stepSound;
            out.playSound(type.getStepSound(), type.getVolume() * 0.15F, type.getFrequency());
        } else if (!block.getMaterial().isLiquid()) {
            out.playSound(type.getStepSound(), type.getVolume() * 0.15F, type.getFrequency());
        }
    }
}
