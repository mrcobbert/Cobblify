package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.watch.RewindPlayback;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind playback at the start of each world render (PLAN section 3): behind the opaque loading card the world is not
 * drawn during a catch-up, except its last 40 ticks so the final view's chunk meshes build; otherwise the camera takes
 * this frame's pose. Outside a session vanilla runs. (R1's camera sample is {@code EntityRendererRewindMixin}.)
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererRewindPlaybackMixin {

    @Inject(method = "renderWorld", at = @At("HEAD"), cancellable = true, require = 1)
    private void bedwarsqol$rewindPlaybackFrame(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        if (RewindPlayback.onRenderWorld(partialTicks)) ci.cancel();
    }
}
