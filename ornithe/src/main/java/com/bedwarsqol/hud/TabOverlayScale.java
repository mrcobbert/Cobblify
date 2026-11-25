package com.bedwarsqol.hud;

import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;

/**
 * Implemented by {@code GuiPlayerTabOverlayMixin}: Tab List Size and the tab stats, applied from the
 * {@code renderPlayerlist} call site ({@code GuiIngameOverlayScaleMixin}). Argentum wraps the whole
 * method and draws the batched list after it returns, so the size scale must be current outside the
 * method, and the stats (positioned with their own translate) are drawn after the list is flushed.
 */
public interface TabOverlayScale {

    /** Pushes the matrix and applies the Size scale (and the fit-to-screen clamp). */
    void bedwarsqol$beginWrappedTab(int width, Scoreboard scoreboard, ScoreObjective objective);

    /** Draws the stats recorded during the list, inside the scale. */
    void bedwarsqol$drawPendingStats();

    /** Pops the matrix and drops any recorded stats. */
    void bedwarsqol$endWrappedTab();
}
