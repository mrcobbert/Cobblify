package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Blocks you placed or broke, as the live client predicted them (PLAN Phase 11, "C. Playback of v2 data", "Event
 * order"; TASK C1, C2).
 *
 * <p>A BLOCK record (position, old and new state id as S23 carries them in {@code Block.BLOCK_STATE_IDS}, cause) is
 * applied in the local phase of the tick it was stamped in: {@code world.setBlockState(pos, new, 3)}, what vanilla's
 * prediction ({@code ItemBlock.onItemUse}, {@code setBlockToAir}) and S23's handler do. So the block appears or
 * disappears at the recorded tick, before the server's confirmation; the feed hands NET records over in the order the
 * live client handled them, so an S23 handled after the prediction overwrites it, whatever its arrival stamp. Your own
 * break (cause {@link RewindFormat#BLOCK_CAUSE_OWN_BREAK}) of a block that is not air first adds vanilla's break
 * particles of the old block ({@code EffectRenderer.addBlockDestroyEffects}, what {@code playAuxSFX(2001)} adds, before
 * the block is removed); its sound is a SOUND record of its own ({@link SoundPart}), so nothing here plays one.
 *
 * <p>The world follows the feed, so a seek's catch-up reaches the world straight play reaches. A record with a state
 * id this build does not know is skipped. Register once with {@link #register()} (Phase 16). Client thread; never
 * sends.
 */
public final class BlockPart implements PlaybackPart {

    /** The client world and the break particles (a test supplies its own). */
    interface Source {
        /** The playback world, or null. */
        World world();

        /** {@code EffectRenderer.addBlockDestroyEffects(pos, old)}. */
        void destroyEffects(BlockPos pos, IBlockState old);
    }

    private static final Source CLIENT = new Source() {
        @Override
        public World world() {
            Minecraft mc = Minecraft.getMinecraft();
            return mc == null ? null : mc.theWorld;
        }

        @Override
        public void destroyEffects(BlockPos pos, IBlockState old) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.effectRenderer != null) {
                mc.effectRenderer.addBlockDestroyEffects(pos, old);
            }
        }
    };

    private static final BlockPart INSTANCE = new BlockPart(CLIENT);

    private final Source source;
    private final ExtCodec.Block record = new ExtCodec.Block();

    BlockPart(Source source) {
        this.source = source;
    }

    /** Registers the part with playback (once; repeated calls do nothing). Client thread. */
    public static void register() {
        RewindPlayback.register(INSTANCE);
    }

    /** The registered instance. */
    static BlockPart instance() {
        return INSTANCE;
    }

    @Override
    public void local(int tag, long timeUs, byte[] buf, int off, int len) {
        if (tag != RewindFormat.TAG_BLOCK) {
            return;
        }
        record.read(buf, off, len);
        World world = source.world();
        IBlockState now = Block.BLOCK_STATE_IDS.getByValue(record.newState);
        if (world == null || now == null) {
            return;
        }
        BlockPos pos = BlockPos.fromLong(record.pos);
        if (record.cause == RewindFormat.BLOCK_CAUSE_OWN_BREAK) {
            IBlockState old = Block.BLOCK_STATE_IDS.getByValue(record.oldState);
            if (old != null && old.getBlock().getMaterial() != Material.air) {
                source.destroyEffects(pos, old);
            }
        }
        world.setBlockState(pos, now, 3);
    }
}
