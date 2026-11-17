package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.OldAnimRules;
import com.bedwarsqol.feature.OldAnimations;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.ModelBiped;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 1.7 Animations. Smooth Sneak: {@code render}'s sneak {@code translate(0, 0.2, 0)} (the adult branch, ordinal 2)
 * is skipped, as 1.7 had none (armour models are bipeds too, so they follow). Block Hit: the blocking arm's yaw
 * constant in {@code setRotationAngles} becomes 1.7's 0.
 */
@Mixin(ModelBiped.class)
public abstract class ModelBipedOldAnimMixin {

    @WrapOperation(method = "render(Lnet/minecraft/entity/Entity;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;translate(FFF)V",
                    ordinal = 2), require = 1)
    private void bedwarsqol$oldSneakModel(float x, float y, float z, Operation<Void> original) {
        if (OldAnimations.vanillaSneakTranslate()) original.call(x, y, z);
    }

    @ModifyConstant(method = "setRotationAngles(FFFFFFLnet/minecraft/entity/Entity;)V",
            constant = @Constant(floatValue = -0.5235988F), require = 1)
    private float bedwarsqol$blockingArmYaw(float yaw) {
        return OldAnimRules.blockingArmYaw(yaw, OldAnimations.blockHit());
    }
}
