package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.ClutchPractice;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Clutch Practice's explosion filter, at Forge's {@code ExplosionEvent.Detonate} position: the
 * entity query in {@code doExplosionA}, which runs after the affected blocks are collected and
 * before entities are damaged and pushed. For Clutch Practice's own blast the hook empties the
 * entity list and filters the blocks; every other explosion passes through unchanged.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    @WrapOperation(method = "doExplosionA",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getEntitiesWithinAABBExcludingEntity(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/AxisAlignedBB;)Ljava/util/List;"),
            require = 1)
    private List<Entity> bedwarsqol$clutchDetonate(World world, Entity exploder, AxisAlignedBB box,
                                                   Operation<List<Entity>> original) {
        List<Entity> entities = original.call(world, exploder, box);
        ClutchPractice.onDetonate((Explosion) (Object) this, world, entities);
        return entities;
    }
}
