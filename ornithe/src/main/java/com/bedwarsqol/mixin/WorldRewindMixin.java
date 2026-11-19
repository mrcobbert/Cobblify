package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.ActionCapture;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rewind's BLOCK record (format v2, PLAN section A, A5): a client-world block change made inside a local scope (the
 * player's own place or break), with the state before (HEAD) and after (RETURN). The integrated server's worlds pay
 * one final-field read ({@code isRemote}); nothing is changed or cancelled.
 */
@Mixin(World.class)
public abstract class WorldRewindMixin {

    @Shadow @Final public boolean isRemote;

    @Inject(method = "setBlockState(Lnet/minecraft/util/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
            at = @At("HEAD"), require = 1)
    private void bedwarsqol$rewindBlockHead(BlockPos pos, IBlockState state, int flags,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (isRemote) ActionCapture.onBlockChangeHead((World) (Object) this, pos);
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
            at = @At("RETURN"), require = 1)
    private void bedwarsqol$rewindBlockReturn(BlockPos pos, IBlockState state, int flags,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (isRemote) ActionCapture.onBlockChangeReturn((World) (Object) this, pos, cir.getReturnValueZ());
    }
}
