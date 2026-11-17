package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.OldAnimations;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.layers.LayerCustomHead;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 1.7 Animations, Smooth Sneak: the worn head item's sneak {@code translate(0, 0.2, 0)} (the layer's first
 * translate) is skipped with the body's, so a skull or pumpkin stays on the head.
 */
@Mixin(LayerCustomHead.class)
public abstract class LayerCustomHeadOldAnimMixin {

    @WrapOperation(method = "doRenderLayer(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;translate(FFF)V",
                    ordinal = 0), require = 1)
    private void bedwarsqol$oldSneakHead(float x, float y, float z, Operation<Void> original) {
        if (OldAnimations.vanillaSneakTranslate()) original.call(x, y, z);
    }
}
