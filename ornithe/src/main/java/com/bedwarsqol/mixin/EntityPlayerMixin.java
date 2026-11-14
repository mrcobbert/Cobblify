package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.ClutchPractice;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clutch Practice's player tick: end of {@code onUpdate} (Forge's {@code PlayerTickEvent} END; the
 * hook keeps only the server side).
 */
@Mixin(EntityPlayer.class)
public abstract class EntityPlayerMixin {

    @Inject(method = "onUpdate", at = @At("RETURN"), require = 1)
    private void bedwarsqol$clutchPlayerTick(CallbackInfo ci) {
        ClutchPractice clutch = ClutchPractice.get();
        if (clutch != null) clutch.onPlayerTick((EntityPlayer) (Object) this);
    }
}
