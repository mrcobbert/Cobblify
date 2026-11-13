package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Configurable first-person held-item position — a 1.8.9 take on the "HandPosition" mod. When the
 * master {@code handPositionEnabled} is on, the held item is translated by the user's X/Y/Z offset
 * (eye space: +X right, +Y up, +Z toward the camera) before vanilla's own placement, shifting the
 * whole item, then scaled by the user's size factor at the end (resizing it in place). Purely
 * visual; sends no packets.
 *
 * Applied at the HEAD of {@link ItemRenderer#transformFirstPersonItem(float, float)} and skipped
 * while actively using an item — the eat/drink/bow/block branches call that method a second time,
 * which would otherwise double-apply the offset.
 *
 * VERIFIED against 1.8.9 MCP-named bytecode: private void transformFirstPersonItem(float, float).
 */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {

    @Inject(method = "transformFirstPersonItem", at = @At("HEAD"))
    private void bedwarsqol$handPosition(float equipProgress, float swingProgress, CallbackInfo ci) {
        if (BedwarsQol.config == null || !BedwarsQol.config.handPositionEnabled) return;
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null && player.isUsingItem()) return;
        GlStateManager.translate(BedwarsQol.config.handPosX, BedwarsQol.config.handPosY, BedwarsQol.config.handPosZ);
    }

    @Inject(method = "transformFirstPersonItem", at = @At("TAIL"))
    private void bedwarsqol$handScale(float equipProgress, float swingProgress, CallbackInfo ci) {
        if (BedwarsQol.config == null || !BedwarsQol.config.handPositionEnabled) return;
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player != null && player.isUsingItem()) return;
        float s = BedwarsQol.config.handScale;
        if (s != 1.0f) GlStateManager.scale(s, s, s);
    }
}
