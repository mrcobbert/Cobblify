package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
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
}
