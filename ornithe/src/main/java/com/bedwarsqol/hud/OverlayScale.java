package com.bedwarsqol.hud;

import com.bedwarsqol.BedwarsQol;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Scoreboard Size for the Ornithe build, applied around the {@code renderScoreboard} call
 * ({@code GuiIngameOverlayScaleMixin}) rather than inside the method. Argentum's Font Batching
 * records the scoreboard's text and background and draws them after {@code renderScoreboard}
 * returns, with whatever GL matrix is current then, so a scale pushed and popped inside the method
 * was lost. {@code GuiIngameMixin} still scales inside the method when this wrapper is not active.
 */
public final class OverlayScale {

    private static int scoreboardWraps;

    private OverlayScale() {
    }

    /** Scoreboard scale: the scoreboard item's scale (1.0 = vanilla's full size). */
    public static float scoreboardScale() {
        return BedwarsQol.config == null ? OverlayItems.DEFAULT_SCALE : BedwarsQol.config.scoreboardHudScale;
    }

    /** Scales the current matrix around the screen's right edge / vertical centre, so the sidebar stays put. */
    public static void applyScoreboardTransform(ScaledResolution sr) {
        float s = scoreboardScale();
        if (s == 1.0f) return;
        float ax = sr.getScaledWidth();
        float ay = sr.getScaledHeight() / 2f;
        GlStateManager.translate(ax, ay, 0f);
        GlStateManager.scale(s, s, 1f);
        GlStateManager.translate(-ax, -ay, 0f);
    }

    public static void beginScoreboard(ScaledResolution sr) {
        GlStateManager.pushMatrix();
        applyScoreboardTransform(sr);
        scoreboardWraps++;
    }

    public static void endScoreboard() {
        scoreboardWraps--;
        GlStateManager.popMatrix();
    }

    /** True while the call-site wrapper has applied the scale; the in-method fallback then does nothing. */
    public static boolean scoreboardWrapped() {
        return scoreboardWraps > 0;
    }
}
