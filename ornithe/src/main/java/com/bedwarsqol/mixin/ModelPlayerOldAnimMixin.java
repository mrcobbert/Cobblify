package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.OldAnimations;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.ModelPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 1.7 Animations, Smooth Sneak: {@code render}'s own sneak {@code translate(0, 0.2, 0)} before the jacket, sleeves
 * and trousers (ordinal 1, after the biped body drew) is skipped with the body's, so clothing stays on the body.
 */
@Mixin(ModelPlayer.class)
public abstract class ModelPlayerOldAnimMixin {

    @WrapOperation(method = "render(Lnet/minecraft/entity/Entity;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;translate(FFF)V",
                    ordinal = 1), require = 1)
    private void bedwarsqol$oldSneakClothing(float x, float y, float z, Operation<Void> original) {
        if (OldAnimations.vanillaSneakTranslate()) original.call(x, y, z);
    }
}
