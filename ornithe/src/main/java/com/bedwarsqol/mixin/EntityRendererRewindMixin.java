package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.Rewind;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind's CAMERA sample: the player's rendered (interpolated) yaw and pitch at the start of each world render.
 * {@link Rewind#onRenderWorld} rate-limits it to once per 4 ms, writes only a change, never throws and does nothing
 * unless a game segment is being recorded.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererRewindMixin {

    @Inject(method = "renderWorld", at = @At("HEAD"), require = 1)
    private void bedwarsqol$rewindCamera(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        Rewind.onRenderWorld(partialTicks);
    }
}
