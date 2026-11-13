package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.ClutchPractice;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clutch Practice's player hooks:
 * <ul>
 *   <li>end of {@code onUpdate} (Forge's {@code PlayerTickEvent} END; the hook keeps only the
 *       server side);</li>
 *   <li>the damage argument of {@code damageEntity} (Forge's {@code LivingHurtEvent} amount), capped
 *       so practice never kills. As after Forge's hook, a hit capped to nothing stops there.</li>
 * </ul>
 */
@Mixin(EntityPlayer.class)
public abstract class EntityPlayerMixin {

    @Inject(method = "onUpdate", at = @At("RETURN"), require = 1)
    private void bedwarsqol$clutchPlayerTick(CallbackInfo ci) {
        ClutchPractice clutch = ClutchPractice.get();
        if (clutch != null) clutch.onPlayerTick((EntityPlayer) (Object) this);
    }

    @ModifyVariable(method = "damageEntity", at = @At("HEAD"), argsOnly = true, require = 1)
    private float bedwarsqol$clutchHurtCap(float amount) {
        ClutchPractice clutch = ClutchPractice.get();
        return clutch == null ? amount : clutch.onHurt((EntityPlayer) (Object) this, amount);
    }

    @Inject(method = "damageEntity", at = @At("HEAD"), cancellable = true, require = 1)
    private void bedwarsqol$clutchHurtNothing(DamageSource source, float amount, CallbackInfo ci) {
        ClutchPractice clutch = ClutchPractice.get();
        EntityPlayer self = (EntityPlayer) (Object) this;
        if (clutch == null || !ClutchPractice.appliesTo(self)) return;
        if (clutch.onHurt(self, amount) <= 0f) ci.cancel();
    }
}
