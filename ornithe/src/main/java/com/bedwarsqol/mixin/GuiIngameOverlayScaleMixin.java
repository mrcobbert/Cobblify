package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.OverlayScale;
import com.bedwarsqol.hud.TabHudGate;
import com.bedwarsqol.hud.TabOverlayScale;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The scoreboard and tab list items' placement from the two call sites in {@code renderGameOverlay}, so
 * the transform is current when Argentum's Font Batching draws what it recorded (after the callee
 * returns). Argentum wraps the scoreboard call with its own WrapOperation; this mixin's higher priority
 * makes ours the outer wrapper. It wraps the whole {@code renderPlayerlist} method, which a call-site
 * wrapper always encloses. Without Argentum the result is the same transform, one level further out.
 *
 * <p>The chat item is placed the same way around the {@code drawChat} call: Argentum flushes its chat
 * batches inside {@code drawChat} (before the scrollbar and at its {@code popMatrix}), so a transform
 * applied out here is current at both flushes.
 */
@Mixin(value = GuiIngame.class, priority = 1500)
public abstract class GuiIngameOverlayScaleMixin {

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;renderScoreboard(Lnet/minecraft/scoreboard/ScoreObjective;Lnet/minecraft/client/gui/ScaledResolution;)V"))
    private void bedwarsqol$scaleScoreboard(GuiIngame self, ScoreObjective objective, ScaledResolution sr,
                                            Operation<Void> original) {
        if (!OverlayScale.scoreboardShown()) return; // hidden in Edit HUD: vanilla's sidebar is not drawn
        OverlayScale.beginScoreboard(objective, sr);
        try {
            original.call(self, objective, sr);
        } finally {
            OverlayScale.endScoreboard();
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiPlayerTabOverlay;renderPlayerlist(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;)V"))
    private void bedwarsqol$scaleTab(GuiPlayerTabOverlay tab, int width, Scoreboard scoreboard, ScoreObjective objective,
                                     Operation<Void> original) {
        TabHudGate.vanillaDrewTab();
        TabOverlayScale scale = (TabOverlayScale) tab;
        scale.bedwarsqol$beginWrappedTab(width, scoreboard, objective);
        try {
            original.call(tab, width, scoreboard, objective);
            scale.bedwarsqol$drawPendingStats();
        } finally {
            scale.bedwarsqol$endWrappedTab();
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiNewChat;drawChat(I)V"), require = 1)
    private void bedwarsqol$placeChat(GuiNewChat chat, int updateCounter, Operation<Void> original) {
        float[] t = BedwarsHudRenderer.chatTransform(Minecraft.getMinecraft(), BedwarsQol.config);
        GlStateManager.pushMatrix();
        try {
            if (t != null) {
                GlStateManager.translate(t[0], t[1], 0f);
                GlStateManager.scale(t[2], t[2], 1f);
            }
            original.call(chat, updateCounter);
        } finally {
            GlStateManager.popMatrix();
        }
    }
}
