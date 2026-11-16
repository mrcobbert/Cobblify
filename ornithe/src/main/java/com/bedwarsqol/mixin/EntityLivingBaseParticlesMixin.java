package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.ParticleOptions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hide Own Potion: the potion swirl spawn in {@code updatePotionEffects} (its only particle call) is skipped
 * for the local player while in first person. Everything before it, random rolls included, runs as vanilla;
 * other entities' swirls and your own in third person spawn as vanilla. Module or row off: always spawned.
 */
@Mixin(EntityLivingBase.class)
public abstract class EntityLivingBaseParticlesMixin {

    @WrapOperation(method = "updatePotionEffects()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;spawnParticle(Lnet/minecraft/util/EnumParticleTypes;DDDDDD[I)V"),
            require = 1)
    private void bedwarsqol$hideOwnPotion(World world, EnumParticleTypes type, double x, double y, double z,
                                          double dx, double dy, double dz, int[] params, Operation<Void> original) {
        if (ParticleOptions.hideOwnPotion((EntityLivingBase) (Object) this)) return;
        original.call(world, type, x, y, z, dx, dy, dz, params);
    }
}
