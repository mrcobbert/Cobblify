package com.bedwarsqol.hud;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The sidebar scoreboard as a HUD item for the Ornithe build: where vanilla would draw it, where the item
 * box puts it, and the transform between the two, applied around the {@code renderScoreboard} call
 * ({@code GuiIngameOverlayScaleMixin}) rather than inside the method. Argentum's Font Batching records the
 * scoreboard's text and background and draws them after {@code renderScoreboard} returns, with whatever GL
 * matrix is current then, so a transform pushed and popped inside the method was lost.
 * {@code GuiIngameMixin} still applies it inside the method when this wrapper is not active.
 */
public final class OverlayScale {

    private static int scoreboardWraps;

    private OverlayScale() {
    }

    /** Scoreboard scale: the scoreboard item's scale (1.0 = vanilla's full size). */
    public static float scoreboardScale() {
        return BedwarsQol.config == null ? OverlayItems.DEFAULT_SCALE : BedwarsQol.config.scoreboardHudScale;
    }

    /** Whether the scoreboard item is shown (Edit HUD's Hide); hidden = vanilla's sidebar is not drawn. */
    public static boolean scoreboardShown() {
        return BedwarsQol.config == null || BedwarsQol.config.scoreboardHudEnabled;
    }

    /** The objective vanilla's sidebar shows: the player's team-colour slot first, then the sidebar slot. */
    public static ScoreObjective sidebarObjective(Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) return null;
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return null;
        ScoreObjective objective = null;
        ScorePlayerTeam team = board.getPlayersTeam(mc.thePlayer.getName());
        if (team != null && team.getChatFormat() != null) {
            int colour = team.getChatFormat().getColorIndex();
            if (colour >= 0) objective = board.getObjectiveInDisplaySlot(3 + colour);
        }
        return objective != null ? objective : board.getObjectiveInDisplaySlot(1);
    }

    /**
     * Where vanilla draws the sidebar for {@code objective} at scale 1, with the score column trimmed
     * ({@link ScoreboardLayout#vanillaRect}); null when it draws nothing. Builds the shown lines exactly as
     * {@code renderScoreboard} does and measures them with the same font.
     */
    public static float[] scoreboardRect(ScoreObjective objective, int sw, int sh) {
        if (objective == null) return null;
        FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;
        Scoreboard board = objective.getScoreboard();
        if (fr == null || board == null) return null;
        Collection<Score> sorted = board.getSortedScores(objective);
        List<Score> filtered = new ArrayList<Score>();
        for (Score score : sorted) {
            String name = score.getPlayerName();
            if (name != null && !name.startsWith("#")) filtered.add(score);
        }
        int n = ScoreboardLayout.shownCount(sorted.size(), filtered.size());
        List<Score> shown = filtered.subList(filtered.size() - n, filtered.size());
        int[] widths = new int[shown.size()];
        for (int i = 0; i < widths.length; i++) {
            Score score = shown.get(i);
            ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
            String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName()) + ": "
                    + EnumChatFormatting.RED + score.getScorePoints();
            widths[i] = fr.getStringWidth(ScoreboardLayout.withoutScore(line));
        }
        return ScoreboardLayout.vanillaRect(sw, sh, fr.getStringWidth(objective.getDisplayName()), widths);
    }

    /** The scoreboard item's box for vanilla rect {@code rect}: its stored spot, or vanilla's scaled about its anchor. */
    public static float[] scoreboardBox(ClientSettings cfg, float[] rect, float sw, float sh) {
        float s = cfg == null ? OverlayItems.DEFAULT_SCALE : cfg.scoreboardHudScale;
        float[] auto = ScoreboardLayout.autoBox(rect, sw, sh, s);
        if (cfg == null) return auto;
        // the stored spot to a fraction of a GUI pixel (Edit HUD moves it by one screen pixel)
        HudModuleState st = HudModules.get(cfg, BedwarsHudRenderer.SCOREBOARD_HUD);
        return OverlayBox.placed(rect, s, st.exactX(), st.exactY(), st.anchor, sw, sh, auto);
    }

    /** Maps the vanilla sidebar onto the item's box on the current matrix. */
    public static void applyScoreboardTransform(ScoreObjective objective, ScaledResolution sr) {
        int sw = sr.getScaledWidth(), sh = sr.getScaledHeight();
        float[] rect = scoreboardRect(objective, sw, sh);
        if (rect == null) return;
        float[] box = scoreboardBox(BedwarsQol.config, rect, sw, sh);
        applyTransform(rect, box, scoreboardScale());
    }

    /** {@code translate(box.x, box.y); scale(s); translate(-rect.x, -rect.y)}, skipped when it is the identity. */
    static void applyTransform(float[] rect, float[] box, float s) {
        if (s == 1f && box[0] == rect[0] && box[1] == rect[1]) return;
        GlStateManager.translate(box[0], box[1], 0f);
        GlStateManager.scale(s, s, 1f);
        GlStateManager.translate(-rect[0], -rect[1], 0f);
    }

    public static void beginScoreboard(ScoreObjective objective, ScaledResolution sr) {
        GlStateManager.pushMatrix();
        scoreboardWraps++;
        applyScoreboardTransform(objective, sr);
    }

    public static void endScoreboard() {
        scoreboardWraps--;
        GlStateManager.popMatrix();
    }

    /** True while the call-site wrapper has applied the transform; the in-method fallback then does nothing. */
    public static boolean scoreboardWrapped() {
        return scoreboardWraps > 0;
    }
}
