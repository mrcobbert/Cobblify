package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.CameraOptions;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom: every return of {@code getFOVModifier} is divided while the Zoom key is held (world pass only, the
 * {@code useFOVSetting} call). No Hurt Cam: {@code hurtCameraEffect} is skipped when it is on.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererCameraMixin {

    @Inject(method = "getFOVModifier", at = @At("RETURN"), cancellable = true, require = 1)
    private void bedwarsqol$zoom(float partialTicks, boolean useFOVSetting, CallbackInfoReturnable<Float> cir) {
        float fov = cir.getReturnValueF();
        float zoomed = CameraOptions.fov(fov, useFOVSetting);
        if (zoomed != fov) cir.setReturnValue(zoomed);
    }

    @Inject(method = "hurtCameraEffect", at = @At("HEAD"), cancellable = true, require = 1)
    private void bedwarsqol$noHurtCam(float partialTicks, CallbackInfo ci) {
        if (CameraOptions.cancelHurtCam()) ci.cancel();
    }
}
