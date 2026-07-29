package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.feature.ChatPlayerHeads;
import net.minecraft.client.gui.FontRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The whole Chat Heads render bridge, kept at the {@link FontRenderer} level so it works on both Forge
 * and Lunar. Two roles:
 * <ul>
 *   <li><b>Hide the marker.</b> Every allocated head-sentinel codepoint (see
 *       {@link ChatPlayerHeads#isSentinel}) is invisible and zero-width — it is only a position/skin
 *       marker. The visible slot is real spaces ({@link ChatPlayerHeads#slotGap}), measured natively, so
 *       the name is held clear even where this char-width hook doesn't apply (Lunar's chat layout).</li>
 *   <li><b>Draw the head.</b> After each string is drawn ({@code drawString} TAIL), paint a head over any
 *       sentinel in it. This is the shared low-level path: Lunar renders chat through its own HUD renderer
 *       (so vanilla {@code GuiNewChat.drawChat} never runs there), but it still draws the line text through
 *       vanilla {@code FontRenderer} — proven by its rendered § colors — so a single hook here covers both.</li>
 * </ul>
 * All of it is gated on the toggle, so a server-sent literal PUA glyph renders exactly as vanilla when the
 * feature is off (and the feature never leaves a sentinel behind while off — see
 * {@link ChatPlayerHeads#onToggle}).
 */
@Mixin(FontRenderer.class)
public class FontRendererMixin {

    private static boolean bedwarsqol$active() {
        return BedwarsQol.config != null && BedwarsQol.config.chatPlayerHeads;
    }

    @Inject(method = "getCharWidth", at = @At("HEAD"), cancellable = true)
    private void bedwarsqol$sentinelWidth(char c, CallbackInfoReturnable<Integer> cir) {
        if (bedwarsqol$active() && ChatPlayerHeads.isSentinel(c)) {
            cir.setReturnValue(0); // zero-width marker; the slotGap() spaces reserve the room
        }
    }

    @Inject(method = "renderChar", at = @At("HEAD"), cancellable = true)
    private void bedwarsqol$sentinelRender(char c, boolean italic, CallbackInfoReturnable<Float> cir) {
        if (bedwarsqol$active() && ChatPlayerHeads.isSentinel(c)) {
            cir.setReturnValue(0f); // draw nothing, advance nothing
        }
    }

    /**
     * Paint a head over any sentinel in the string just drawn. {@code drawString(String,float,float,int,
     * boolean)} is the core both {@code drawStringWithShadow} and the int overload funnel through, so this
     * fires once per visible string (after its shadow+main passes) in whatever matrix the caller set up —
     * the chat line's, for both vanilla and Lunar chat rendering.
     */
    @Inject(method = "drawString(Ljava/lang/String;FFIZ)I", at = @At("TAIL"))
    private void bedwarsqol$drawHeads(String text, float x, float y, int color, boolean dropShadow,
                                      CallbackInfoReturnable<Integer> cir) {
        if (!bedwarsqol$active()) return;
        ChatPlayerHeads.drawRowHeads((FontRenderer) (Object) this, text, (int) x, (int) y, color);
    }
}
