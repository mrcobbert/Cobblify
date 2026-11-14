package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.feature.SessionStatsWatch;
import com.bedwarsqol.feature.TeamUpgradesWatch;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.OverlayScale;
import com.bedwarsqol.hud.ScoreboardLayout;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.ScoreObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The vanilla sidebar scoreboard as a HUD item: drawn at the item's box (moved and scaled), with the red
 * score numbers hidden and their column trimmed, and the Background look (Lunar's see-through fill and no
 * text shadow, or no fill and a shadow). The placement is normally applied around the call
 * ({@link GuiIngameOverlayScaleMixin}, for Argentum's Font Batching); the HEAD/RETURN pair here only
 * applies it when that wrapper is not active, e.g. another caller.
 *
 * <p>The look changes vanilla's constants and arguments rather than skipping its {@code drawRect} calls:
 * Argentum redirects those calls, and {@code drawRect} ends with {@code disableBlend}, which keeps the
 * {@code 0x20FFFFFF} text opaque. Targets are from the mapped 1.8.9 {@code renderScoreboard} bytecode
 * ({@link ScoreboardLayout} cites the offsets); each is {@code require = 1}.
 *
 * <p>Also observes titles for the Session Stats tally: Hypixel announces the Bedwars outcome as a title
 * (gold VICTORY! / red GAME OVER!), which is a more reliable win/loss signal than the chat block.
 * public void displayTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) — the
 * inject forwards the title text and changes nothing about the vanilla display. A second inject
 * forwards the title and the subtitle to Team Upgrades (trap triggered, game end).
 */
@Mixin(GuiIngame.class)
public class GuiIngameMixin {

    @Inject(method = "renderScoreboard", at = @At("HEAD"), cancellable = true)
    private void bedwarsqol$scaleScoreboardStart(ScoreObjective objective, ScaledResolution sr, CallbackInfo ci) {
        if (OverlayScale.scoreboardWrapped()) return;
        if (!OverlayScale.scoreboardShown()) {
            ci.cancel();
            return;
        }
        GlStateManager.pushMatrix();
        OverlayScale.applyScoreboardTransform(objective, sr);
    }

    @Inject(method = "renderScoreboard", at = @At("RETURN"))
    private void bedwarsqol$scaleScoreboardEnd(ScoreObjective objective, ScaledResolution sr, CallbackInfo ci) {
        if (OverlayScale.scoreboardWrapped()) return;
        GlStateManager.popMatrix();
    }

    private static boolean bedwarsqol$scoreboardBackground() {
        return BedwarsHudRenderer.overlayBackground(BedwarsQol.config, BedwarsHudRenderer.SCOREBOARD_HUD);
    }

    /** The two {@code 0x50000000} fills: each line, and the 1 px gap under the title. */
    @ModifyConstant(method = "renderScoreboard", constant = @Constant(intValue = 1342177280), require = 1)
    private int bedwarsqol$scoreboardLineFill(int vanilla) {
        return ScoreboardLayout.lineFill(bedwarsqol$scoreboardBackground());
    }

    /** The title row's {@code 0x60000000} fill. */
    @ModifyConstant(method = "renderScoreboard", constant = @Constant(intValue = 1610612736), require = 1)
    private int bedwarsqol$scoreboardTitleFill(int vanilla) {
        return ScoreboardLayout.titleFill(bedwarsqol$scoreboardBackground());
    }

    /** The width loop measures each line without its score, so the score column is trimmed. */
    @ModifyArg(method = "renderScoreboard", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/FontRenderer;getStringWidth(Ljava/lang/String;)I", ordinal = 1),
            require = 1)
    private String bedwarsqol$scoreboardWidthWithoutScore(String line) {
        return ScoreboardLayout.withoutScore(line);
    }

    /** The red score is not drawn (its return value is discarded by vanilla). */
    @WrapOperation(method = "renderScoreboard", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/FontRenderer;drawString(Ljava/lang/String;III)I", ordinal = 1),
            require = 1)
    private int bedwarsqol$scoreboardHideScore(FontRenderer fr, String text, int x, int y, int color,
                                               Operation<Integer> original) {
        return 0;
    }

    /** Each line's name. */
    @WrapOperation(method = "renderScoreboard", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/FontRenderer;drawString(Ljava/lang/String;III)I", ordinal = 0),
            require = 1)
    private int bedwarsqol$scoreboardName(FontRenderer fr, String text, int x, int y, int color,
                                          Operation<Integer> original) {
        return bedwarsqol$scoreboardText(fr, text, x, y, color, original);
    }

    /** The title. */
    @WrapOperation(method = "renderScoreboard", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/FontRenderer;drawString(Ljava/lang/String;III)I", ordinal = 2),
            require = 1)
    private int bedwarsqol$scoreboardTitle(FontRenderer fr, String text, int x, int y, int color,
                                           Operation<Integer> original) {
        return bedwarsqol$scoreboardText(fr, text, x, y, color, original);
    }

    private static int bedwarsqol$scoreboardText(FontRenderer fr, String text, int x, int y, int color,
                                                Operation<Integer> original) {
        if (!ScoreboardLayout.textShadow(bedwarsqol$scoreboardBackground())) return original.call(fr, text, x, y, color);
        return fr.drawString(text, (float) x, (float) y, color, true);
    }

    @Inject(method = "displayTitle", at = @At("HEAD"))
    private void bedwarsqol$observeTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut, CallbackInfo ci) {
        if (title != null) SessionStatsWatch.onTitle(title);
    }

    // Team Upgrades: trap-triggered titles and subtitles, and the game-end title (changes nothing shown).
    @Inject(method = "displayTitle", at = @At("HEAD"))
    private void bedwarsqol$observeTeamUpgradeTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut, CallbackInfo ci) {
        if (title != null) TeamUpgradesWatch.onTitle(title);
        if (subtitle != null) TeamUpgradesWatch.onTitle(subtitle);
    }
}
