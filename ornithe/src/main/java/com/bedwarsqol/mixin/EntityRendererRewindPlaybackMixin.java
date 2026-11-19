package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.watch.CameraController;
import com.bedwarsqol.rewind.watch.CameraStatePart;
import com.bedwarsqol.rewind.watch.FovPart;
import com.bedwarsqol.rewind.watch.RewindPlayback;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rewind playback at the start of each world render (PLAN section 3): behind the opaque loading card the world is not
 * drawn during a catch-up, except its last 40 ticks so the final view's chunk meshes build; otherwise the camera takes
 * this frame's pose. In the free camera {@code hurtCameraEffect} is skipped, so it never tilts (also not vanilla's
 * death branch at recorded health 0); your view keeps vanilla's. The free camera also keeps spectator terrain culling
 * (the spectator flag {@code renderWorldPass} hands {@code RenderGlobal.setupTerrain}), although the camera is a
 * spectator only in your view of a recorded spectator (Phase 7, amendment 1). In your view of a recording with FOV
 * records the world FOV ({@code getFOVModifier} with {@code useFOVSetting}) is the recorded one (Phase 12,
 * {@link FovPart}). Outside a session vanilla runs. (R1's camera sample is {@code EntityRendererRewindMixin}.)
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

    @ModifyArg(method = "renderWorldPass",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;setupTerrain(Lnet/minecraft/entity/Entity;DLnet/minecraft/client/renderer/culling/ICamera;IZ)V"),
            index = 4, require = 1)
    private boolean bedwarsqol$rewindFreeCameraTerrain(boolean playerSpectator) {
        return CameraStatePart.terrainSpectator(RewindPlayback.active(), CameraController.freeCamera(), playerSpectator);
    }

    @Inject(method = "getFOVModifier", at = @At("RETURN"), cancellable = true, require = 1)
    private void bedwarsqol$rewindRecordedFov(float partialTicks, boolean useFOVSetting,
                                             CallbackInfoReturnable<Float> cir) {
        float fov = cir.getReturnValueF();
        float recorded = FovPart.worldFov(fov, useFOVSetting);
        if (recorded != fov) cir.setReturnValue(recorded);
    }
}
