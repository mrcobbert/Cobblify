package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.watch.RewindCamera;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Rewind's free camera has no body (review code-2 O5): an XP orb drifts to the closest player
 * ({@code EntityXPOrb.onUpdate} asks {@code World.getClosestPlayerToEntity} once per tick, javap @246), and since
 * amendment 1 the camera is no spectator, so vanilla's search could pick it. {@link RewindCamera#orbTarget} keeps
 * vanilla's pick unless it is the camera in the free camera. Live play and the integrated server never have a camera:
 * vanilla's pick returns after one type check.
 */
@Mixin(EntityXPOrb.class)
public abstract class EntityXPOrbRewindMixin {

    @WrapOperation(method = "onUpdate()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getClosestPlayerToEntity(Lnet/minecraft/entity/Entity;D)Lnet/minecraft/entity/player/EntityPlayer;"),
            require = 1)
    private EntityPlayer bedwarsqol$noOrbsToTheFreeCamera(World world, Entity orb, double range,
                                                          Operation<EntityPlayer> original) {
        return RewindCamera.orbTarget(world, orb, range, original.call(world, orb, range));
    }
}
