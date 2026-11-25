package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.CameraOptions;
import net.minecraft.client.entity.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dynamic FOV: with it off, {@code getFovModifier} (sprint, Speed, Slowness, bow draw) returns 1, so
 * {@code EntityRenderer} eases the view back to the FOV setting and keeps it there.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerFovMixin {

    @Inject(method = "getFovModifier", at = @At("RETURN"), cancellable = true, require = 1)
    private void bedwarsqol$dynamicFov(CallbackInfoReturnable<Float> cir) {
        float vanilla = cir.getReturnValueF();
        float fov = CameraOptions.fovModifier(vanilla);
        if (fov != vanilla) cir.setReturnValue(fov);
    }
}
