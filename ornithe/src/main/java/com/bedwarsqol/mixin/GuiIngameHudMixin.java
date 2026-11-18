package com.bedwarsqol.mixin;

import com.bedwarsqol.hud.TabHudGate;
import com.bedwarsqol.ornithe.ModEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two HUD layers Forge's {@code GuiIngameForge} provided, in vanilla
 * {@code GuiIngame.renderGameOverlay}:
 * <ul>
 *   <li>Text ({@link ModEvents#HUD_TEXT}): at the method's only read of
 *       {@code GameSettings.showDebugInfo}. It comes right after the demo-overlay branch, so it runs
 *       every frame, at the same place Forge's {@code renderHUDText} (and its
 *       {@code RenderGameOverlayEvent.Text}) draws: before the record/title text, the scoreboard,
 *       chat and the tab list.</li>
 *   <li>Post ({@link ModEvents#HUD_POST}): at the method's single return, after everything else
 *       (Forge's {@code RenderGameOverlayEvent.Post} with type ALL).</li>
 * </ul>
 * Both are {@code require = 1}: a missed target fails at boot instead of silently dropping the HUD.
 * While the tab list shows this frame ({@link TabHudGate}), the Text layer is not posted, so no
 * Cobblify HUD element draws under or over it; the Post layer still runs and its listeners check
 * {@link TabHudGate#hidesHud()} themselves.
 */
@Mixin(GuiIngame.class)
public abstract class GuiIngameHudMixin {

    @Inject(method = "renderGameOverlay",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/settings/GameSettings;showDebugInfo:Z",
                    opcode = org.objectweb.asm.Opcodes.GETFIELD),
            require = 1)
    private void bedwarsqol$hudText(float partialTicks, CallbackInfo ci) {
        // Forge's renderHUDText sets this blend function before posting its Text event.
        // Kept unconditional so vanilla's GL state is the same whether or not the HUD draws.
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        TabHudGate.beginFrame(Minecraft.getMinecraft());
        if (!TabHudGate.hidesHud()) ModEvents.post(ModEvents.HUD_TEXT, partialTicks);
    }

    @Inject(method = "renderGameOverlay", at = @At("TAIL"), require = 1)
    private void bedwarsqol$hudPost(float partialTicks, CallbackInfo ci) {
        ModEvents.post(ModEvents.HUD_POST, partialTicks);
        TabHudGate.endFrame();
    }
}
