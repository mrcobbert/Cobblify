package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.OldAnimations;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 1.7 Animations, Smooth Sneak: the Y {@code doRender} hands on for the local player. Vanilla lowers other sneaking
 * players by 0.125 and never the local one; with the 1.7 model the local one sinks with its eased eye drop instead,
 * as 1.7 drew it. Other players and the inventory preview keep vanilla's Y.
 */
@Mixin(RenderPlayer.class)
public abstract class RenderPlayerOldAnimMixin {

    @ModifyArg(method = "doRender(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/RendererLivingEntity;doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V"),
            index = 2, require = 1)
    private double bedwarsqol$localSneakY(EntityLivingBase entity, double x, double y, double z, float yaw,
                                          float partialTicks) {
        return OldAnimations.playerModelY(entity, y, partialTicks);
    }
}
