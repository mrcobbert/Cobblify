package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.ParticleOptions;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hide Block Break: the block-destroy and block-hit particle bursts are not spawned. Both methods only create
 * digging particles (the break sound is played elsewhere), so cancelling them at HEAD removes nothing else.
 * Module or row off: vanilla runs.
 */
@Mixin(EffectRenderer.class)
public abstract class EffectRendererParticlesMixin {

    @Inject(method = "addBlockDestroyEffects(Lnet/minecraft/util/BlockPos;Lnet/minecraft/block/state/IBlockState;)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void bedwarsqol$hideBlockDestroy(BlockPos pos, IBlockState state, CallbackInfo ci) {
        if (ParticleOptions.hideBlockBreak()) ci.cancel();
    }

    @Inject(method = "addBlockHitEffects(Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void bedwarsqol$hideBlockHit(BlockPos pos, EnumFacing side, CallbackInfo ci) {
        if (ParticleOptions.hideBlockBreak()) ci.cancel();
    }
}
