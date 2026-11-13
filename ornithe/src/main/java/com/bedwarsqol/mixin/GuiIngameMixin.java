package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.SessionStatsWatch;
import com.bedwarsqol.hud.OverlayScale;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.ScoreObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Resizes the vanilla sidebar scoreboard via the Scoreboard Size setting (Small/Medium/Large).
 * The whole render is uniformly scaled around the screen's right edge and vertical centre, so the
 * sidebar stays pinned where Minecraft draws it and no lines/info are dropped when it shrinks —
 * Large (1.0) leaves the vanilla render untouched. Purely visual; the scoreboard data is untouched.
 *
 * VERIFIED against 1.8.9 MCP-named bytecode: protected void renderScoreboard(ScoreObjective, ScaledResolution).
 *
 * Also observes titles for the Session Stats tally: Hypixel announces the Bedwars outcome as a title
 * (gold VICTORY! / red GAME OVER!), which is a more reliable win/loss signal than the chat block.
 * public void displayTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) — the
 * inject forwards the title text and changes nothing about the vanilla display.
 */
@Mixin(GuiIngame.class)
public class GuiIngameMixin {

    // The scale is normally applied around the call (GuiIngameOverlayScaleMixin, for Argentum's Font
    // Batching); these two only scale when that wrapper is not active, e.g. another caller.
    @Inject(method = "renderScoreboard", at = @At("HEAD"))
    private void bedwarsqol$scaleScoreboardStart(ScoreObjective objective, ScaledResolution sr, CallbackInfo ci) {
        if (OverlayScale.scoreboardWrapped()) return;
        GlStateManager.pushMatrix();
        OverlayScale.applyScoreboardTransform(sr);
    }

    @Inject(method = "renderScoreboard", at = @At("RETURN"))
    private void bedwarsqol$scaleScoreboardEnd(ScoreObjective objective, ScaledResolution sr, CallbackInfo ci) {
        if (OverlayScale.scoreboardWrapped()) return;
        GlStateManager.popMatrix();
    }

    @Inject(method = "displayTitle", at = @At("HEAD"))
    private void bedwarsqol$observeTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut, CallbackInfo ci) {
        if (title != null) SessionStatsWatch.onTitle(title);
    }
}
