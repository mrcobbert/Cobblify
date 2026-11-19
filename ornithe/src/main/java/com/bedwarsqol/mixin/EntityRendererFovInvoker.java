package com.bedwarsqol.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Rewind's recorder reads the final world field of view (PLAN section A "FOV"): vanilla's private
 * {@code getFOVModifier(partialTicks, true)}, the value the world pass uses, with every mod's change to it (Zoom,
 * Dynamic FOV, potions, bow, water). {@code com.bedwarsqol.rewind.ViewCapture} calls it at most once per 16 ms while a
 * game is recorded; the call reads state and changes nothing.
 */
@Mixin(EntityRenderer.class)
public interface EntityRendererFovInvoker {

    @Invoker("getFOVModifier")
    float bedwarsqol$getFOVModifier(float partialTicks, boolean useFOVSetting);
}
