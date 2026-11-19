package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.ActionCapture;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rewind's BLOCK cause (format v2, PLAN section A): a block change made while {@code onPlayerDestroyBlock} is on the
 * stack is the player's own break. Enter at HEAD, exit at every return; nothing is read or changed.
 */
@Mixin(PlayerControllerMP.class)
public abstract class PlayerControllerMPRewindRecordMixin {

    @Inject(method = "onPlayerDestroyBlock", at = @At("HEAD"), require = 1)
    private void bedwarsqol$rewindDestroyEnter(BlockPos pos, EnumFacing side, CallbackInfoReturnable<Boolean> cir) {
        ActionCapture.enterDestroy();
    }

    @Inject(method = "onPlayerDestroyBlock", at = @At("RETURN"), require = 1)
    private void bedwarsqol$rewindDestroyExit(BlockPos pos, EnumFacing side, CallbackInfoReturnable<Boolean> cir) {
        ActionCapture.exitDestroy();
    }
}
