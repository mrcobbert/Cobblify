package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.OldAnimations;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 1.7 Animations, Smooth Sneak: the eye height {@code orientCamera} reads (its only {@code getEyeHeight} call)
 * becomes the eased one. Camera only: the entity's real eye height, the crosshair ray and the hitbox are unchanged.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererSneakCameraMixin {

    @WrapOperation(method = "orientCamera(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getEyeHeight()F"), require = 1)
    private float bedwarsqol$smoothSneakCamera(Entity viewer, Operation<Float> original,
                                               @Local(argsOnly = true) float partialTicks) {
        return OldAnimations.cameraEyeHeight(viewer, original.call(viewer), partialTicks);
    }
}
