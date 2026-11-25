package com.bedwarsqol.mixin;

import com.bedwarsqol.ornithe.ModEvents;
import com.bedwarsqol.ornithe.NametagRenderEvent;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Posts {@link ModEvents#NAMETAG_POST} at the end of {@code RendererLivingEntity.renderName}, where
 * Forge posts {@code RenderLivingEvent.Specials.Post} (also when vanilla drew no name).
 */
@Mixin(RendererLivingEntity.class)
public abstract class RendererLivingEntityMixin {

    @Inject(method = "renderName(Lnet/minecraft/entity/EntityLivingBase;DDD)V", at = @At("RETURN"), require = 1)
    private void bedwarsqol$nametagPost(EntityLivingBase entity, double x, double y, double z, CallbackInfo ci) {
        ModEvents.NAMETAG_POST.post(new NametagRenderEvent(entity, x, y, z));
    }
}
