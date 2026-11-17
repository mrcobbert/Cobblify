package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.OldAnimRules;
import com.bedwarsqol.feature.OldAnimations;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.7 Animations, Red Armor: {@code shouldCombineTextures} answers true, so vanilla's own hurt tint (set up per
 * layer from this answer) also colours armour. Nothing else changes.
 */
@Mixin(LayerArmorBase.class)
public abstract class LayerArmorBaseOldAnimMixin {

    @Inject(method = "shouldCombineTextures()Z", at = @At("RETURN"), cancellable = true, require = 1)
    private void bedwarsqol$redArmor(CallbackInfoReturnable<Boolean> cir) {
        boolean vanilla = cir.getReturnValueZ();
        boolean tint = OldAnimRules.armorTint(vanilla, OldAnimations.redArmor());
        if (tint != vanilla) cir.setReturnValue(tint);
    }
}
