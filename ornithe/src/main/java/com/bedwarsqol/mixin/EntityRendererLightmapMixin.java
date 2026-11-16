package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.Fullbright;
import com.bedwarsqol.feature.FullbrightRules;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fullbright: in {@code updateLightmap}, after vanilla fills {@code lightmapColors} and just before
 * {@code lightmapTexture.updateDynamicTexture()} uploads it, each texel is raised toward white by the fade
 * level. Vanilla rebuilds the lightmap once a tick; while the level moves, the HEAD hook asks for a
 * rebuild every frame so the fade is smooth. Module off and level 0: neither hook changes anything.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererLightmapMixin {

    @Shadow private boolean lightmapUpdateNeeded;
    @Shadow @Final private int[] lightmapColors;

    @Inject(method = "updateLightmap", at = @At("HEAD"), require = 1)
    private void bedwarsqol$fullbrightFade(float partialTicks, CallbackInfo ci) {
        if (Fullbright.advance()) lightmapUpdateNeeded = true;
    }

    @Inject(method = "updateLightmap",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;updateDynamicTexture()V"),
            require = 1)
    private void bedwarsqol$fullbrightLift(float partialTicks, CallbackInfo ci) {
        float level = Fullbright.level();
        if (level <= 0f) return;
        int[] colors = lightmapColors;
        for (int i = 0; i < colors.length; i++) colors[i] = FullbrightRules.lift(colors[i], level);
    }
}
