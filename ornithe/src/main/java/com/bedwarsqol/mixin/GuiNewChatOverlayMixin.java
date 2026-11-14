package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.ChatLayout;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.GuiNewChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The chat item's look and line count inside vanilla {@code drawChat} (mapped 1.8.9 bytecode,
 * {@link ChatLayout}):
 * <ul>
 *   <li>Lines never fade: each line's age ({@code updateCounter - getUpdatedCounter()}, the only
 *       {@code getUpdatedCounter} call, at 168) reads 0, so it keeps full alpha; the Chat Opacity
 *       setting still applies.</li>
 *   <li>A closed chat shows at most 10 rows ({@code getLineCount()} at 18, its only call there); an open
 *       chat keeps vanilla's count, and {@code getChatComponent}, which only answers while open, is
 *       untouched.</li>
 *   <li>Background Off skips the line fill ({@code drawRect} ordinal 0 at 295; the scrollbar's ordinals 1
 *       and 2 always draw). Vanilla re-enables blending for the text right after.</li>
 * </ul>
 * Priority 1500, above Argentum's default, so this fill wrapper is outside Argentum's on the same call:
 * a skipped fill never reaches Argentum's batch. Each injector is {@code require = 1}.
 */
@Mixin(value = GuiNewChat.class, priority = 1500)
public abstract class GuiNewChatOverlayMixin {

    @Shadow public abstract boolean getChatOpen();

    @ModifyExpressionValue(method = "drawChat", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/ChatLine;getUpdatedCounter()I"), require = 1)
    private int bedwarsqol$neverFade(int lineCounter, @Local(argsOnly = true) int updateCounter) {
        return updateCounter;
    }

    @ModifyExpressionValue(method = "drawChat", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiNewChat;getLineCount()I"), require = 1)
    private int bedwarsqol$closedLineCap(int lineCount) {
        return getChatOpen() ? lineCount : ChatLayout.closedLineCount(lineCount);
    }

    @WrapOperation(method = "drawChat", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiNewChat;drawRect(IIIII)V", ordinal = 0), require = 1)
    private void bedwarsqol$lineFill(int left, int top, int right, int bottom, int color, Operation<Void> original) {
        if (ChatLayout.lineFill(BedwarsHudRenderer.overlayBackground(BedwarsQol.config, BedwarsHudRenderer.CHAT_HUD))) {
            original.call(left, top, right, bottom, color);
        }
    }
}
