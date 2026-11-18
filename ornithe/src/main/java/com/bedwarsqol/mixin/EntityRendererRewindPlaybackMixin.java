package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.watch.CameraController;
import com.bedwarsqol.rewind.watch.RewindPlayback;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind playback at the start of each world render (PLAN section 3): behind the opaque loading card the world is not
 * drawn during a catch-up, except its last 40 ticks so the final view's chunk meshes build; otherwise the camera takes
 * this frame's pose. In the free camera {@code hurtCameraEffect} is skipped, so it never tilts (also not vanilla's
 * death branch at recorded health 0); your view keeps vanilla's. Outside a session vanilla runs. (R1's camera sample is
 * {@code EntityRendererRewindMixin}.)
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererRewindPlaybackMixin {

    @Inject(method = "renderWorld", at = @At("HEAD"), cancellable = true, require = 1)
    private void bedwarsqol$rewindPlaybackFrame(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        if (RewindPlayback.onRenderWorld(partialTicks)) ci.cancel();
    }

    @Inject(method = "hurtCameraEffect", at = @At("HEAD"), cancellable = true, require = 1)
    private void bedwarsqol$rewindFreeCameraNoTilt(float partialTicks, CallbackInfo ci) {
        if (CameraController.cancelsHurtTilt(RewindPlayback.active(), CameraController.freeCamera())) ci.cancel();
    }
}
