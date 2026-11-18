package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.watch.RewindPlayback;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind catch-ups mute particles except in their last 60 ticks (PLAN section 2): every particle reaches the renderer
 * through {@code addEffect}, after it was constructed, so its creation (and any randomness it draws) is unchanged.
 * Outside a catch-up vanilla runs.
 */
@Mixin(EffectRenderer.class)
public abstract class EffectRendererRewindMixin {

    @Inject(method = "addEffect(Lnet/minecraft/client/particle/EntityFX;)V", at = @At("HEAD"), cancellable = true,
            require = 1)
    private void bedwarsqol$rewindMuteParticle(EntityFX effect, CallbackInfo ci) {
        if (RewindPlayback.particlesMuted()) ci.cancel();
    }
}
