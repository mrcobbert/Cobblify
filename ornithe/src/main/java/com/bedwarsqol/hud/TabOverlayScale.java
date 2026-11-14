package com.bedwarsqol.hud;

import com.bedwarsqol.config.ClientSettings;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;

/**
 * Implemented by {@code GuiPlayerTabOverlayMixin}: the tab list item's placement and the tab stats,
 * applied from the {@code renderPlayerlist} call site ({@code GuiIngameOverlayScaleMixin}). Argentum
 * wraps the whole method and draws the batched list after it returns, so the transform must be current
 * outside the method, and the stats (positioned with their own translate) are drawn after the list is
 * flushed.
 */
public interface TabOverlayScale {

    /** Pushes the matrix and maps the list onto the tab list item's box (with the fit-to-screen clamp). */
    void bedwarsqol$beginWrappedTab(int width, Scoreboard scoreboard, ScoreObjective objective);

    /** Draws the stats recorded during the list, inside the transform. */
    void bedwarsqol$drawPendingStats();

    /** Pops the matrix and drops any recorded stats. */
    void bedwarsqol$endWrappedTab();

    /**
     * Where vanilla draws the live player list at scale 1, with Cobblify's widened columns:
     * {@code {x, y, w, h, naturalGridWidth}}, or null without a connection. Header and footer count unless
     * Hide Tab Header/Footer is on. Works whether or not vanilla is drawing the list this frame.
     */
    float[] bedwarsqol$measure(int width, Scoreboard scoreboard, ScoreObjective objective);

    /**
     * The tab list item's box for a {@link #bedwarsqol$measure} result: {@code {x, y, w, h, scale}}, where
     * scale is the drawn scale (the item's, shrunk to fit a grid wider than the screen).
     */
    static float[] placement(ClientSettings cfg, float[] measure, float sw, float sh) {
        float s = cfg == null ? OverlayItems.DEFAULT_SCALE : cfg.tabListHudScale;
        float es = TabListLayout.effectiveScale(s, measure[4], sw);
        float[] rect = {measure[0], measure[1], measure[2], measure[3]};
        float[] auto = TabListLayout.autoBox(rect, sw, es);
        float[] box = cfg == null ? auto
                : OverlayBox.placed(rect, es, cfg.tabListHudX, cfg.tabListHudY, cfg.tabListHudAnchor, sw, sh, auto);
        return new float[]{box[0], box[1], box[2], box[3], es};
    }
}
