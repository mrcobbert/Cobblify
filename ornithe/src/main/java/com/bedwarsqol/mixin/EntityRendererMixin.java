package com.bedwarsqol.mixin;

import com.bedwarsqol.ornithe.BlockHighlightEvent;
import com.bedwarsqol.ornithe.ModEvents;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Posts {@link ModEvents#BLOCK_HIGHLIGHT} in place of each block-outline draw in
 * {@code renderWorldPass} (both of them, as Forge's {@code DrawBlockHighlightEvent} hook does); the
 * vanilla outline is drawn only when nobody canceled. A wrap, not a redirect, so another mod's wrap
 * of the same call still composes.
 *
 * <p>Also posts {@link ModEvents#WORLD_LAST} once per pass, right before the "hand" profiler swap (the method's only
 * {@code endStartSection("hand")}), where Forge posts {@code RenderWorldLastEvent}: after the world, before the held
 * item. {@code EntityRendererMotionBlurMixin} hooks the instruction after that swap, so it runs after WORLD_LAST
 * listeners have drawn.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @WrapOperation(method = "renderWorldPass",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;drawSelectionBox(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/util/MovingObjectPosition;IF)V"),
            require = 1)
    private void bedwarsqol$blockHighlight(RenderGlobal renderGlobal, EntityPlayer player, MovingObjectPosition target,
                                           int execute, float partialTicks, Operation<Void> original) {
        if (ModEvents.post(ModEvents.BLOCK_HIGHLIGHT, new BlockHighlightEvent(player, target, partialTicks))) return;
        original.call(renderGlobal, player, target, execute, partialTicks);
    }

    @Inject(method = "renderWorldPass",
            at = @At(value = "INVOKE_STRING",
                    target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V",
                    args = "ldc=hand"),
            require = 1)
    private void bedwarsqol$worldLast(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        ModEvents.post(ModEvents.WORLD_LAST, partialTicks);
    }
}
