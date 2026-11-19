package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.ActionCapture;
import net.minecraft.client.entity.EntityPlayerSP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind's local scope (format v2, PLAN section A): the player's own tick ({@code EntityPlayerSP.onUpdate}: footsteps,
 * fall, swim, eat and drink sounds) is a local scope from HEAD to every return. Nothing is read or changed.
 */
@Mixin(EntityPlayerSP.class)
public abstract class EntityPlayerSPRewindMixin {

    @Inject(method = "onUpdate", at = @At("HEAD"), require = 1)
    private void bedwarsqol$rewindTickEnter(CallbackInfo ci) {
        ActionCapture.enterLocal();
    }

    @Inject(method = "onUpdate", at = @At("RETURN"), require = 1)
    private void bedwarsqol$rewindTickExit(CallbackInfo ci) {
        ActionCapture.exitLocal();
    }
}
