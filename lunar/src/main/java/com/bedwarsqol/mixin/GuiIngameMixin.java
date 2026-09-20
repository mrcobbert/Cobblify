package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.feature.SessionStatsWatch;
import com.bedwarsqol.gui.render.GuiBlur;
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
 * inject forwards the title text and changes nothing about the vanilla display. require = 0 so a
 * Weave mapping miss degrades to the chat-only outcome signal instead of failing the mixin.
 */
@Mixin(GuiIngame.class)
public class GuiIngameMixin {

    /** Scoreboard scale from the Size setting (Large = 1.0, the vanilla full size). */
    private static float bedwarsqol$scoreboardScale() {
        int size = BedwarsQol.config == null ? 2 : BedwarsQol.config.scoreboardSize;
        switch (size) {
            case 0: return 0.7f;
            case 1: return 0.85f;
            default: return 1.0f;
        }
    }

    @Inject(method = "renderScoreboard", at = @At("HEAD"))
    private void bedwarsqol$scaleScoreboardStart(ScoreObjective objective, ScaledResolution sr, CallbackInfo ci) {
        GlStateManager.pushMatrix();
        float s = bedwarsqol$scoreboardScale();
        if (s != 1.0f) {
            // Anchor at the right edge / vertical centre so the sidebar stays put as it scales.
            float ax = sr.getScaledWidth();
            float ay = sr.getScaledHeight() / 2f;
            GlStateManager.translate(ax, ay, 0f);
            GlStateManager.scale(s, s, 1f);
            GlStateManager.translate(-ax, -ay, 0f);
        }
    }

    @Inject(method = "renderScoreboard", at = @At("RETURN"))
    private void bedwarsqol$scaleScoreboardEnd(ScoreObjective objective, ScaledResolution sr, CallbackInfo ci) {
        GlStateManager.popMatrix();
    }

    @Inject(method = "displayTitle", at = @At("HEAD"), require = 0)
    private void bedwarsqol$observeTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut, CallbackInfo ci) {
        if (title != null) SessionStatsWatch.onTitle(title);
    }

    /** While the settings GUI's world-blur is up, skip the whole vanilla HUD so the blurred backdrop
     *  stays clean (no smeared chat/scoreboard/hotbar showing through the translucent scrim). */
    @Inject(method = "renderGameOverlay", at = @At("HEAD"), cancellable = true)
    private void bedwarsqol$hideHudUnderBlur(float partialTicks, CallbackInfo ci) {
        if (GuiBlur.isActive()) ci.cancel();
    }
}
