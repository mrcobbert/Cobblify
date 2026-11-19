package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.watch.RiddenView;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind's other players' views (Phase 14, TASK D3): no hotbar. The controller is CREATIVE there, so vanilla draws the
 * hand but no hearts, hunger or XP bar; the hotbar ({@code renderTooltip}, which {@code renderGameOverlay} calls for
 * every mode but spectator) is skipped while a recording plays in another player's view
 * ({@link RiddenView#hidesHotbar()}), and with it the held-item name that fades in over it when the ridden player
 * switches items ({@code renderSelectedItem}, also drawn for every mode but spectator; review code-2 O6). With nothing
 * playing each returns at once and the live hotbar and item name are unchanged.
 */
@Mixin(GuiIngame.class)
public abstract class GuiIngameRewindMixin {

    @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/ScaledResolution;F)V", at = @At("HEAD"),
            cancellable = true, require = 1)
    private void bedwarsqol$noHotbarInAnotherPlayersView(ScaledResolution resolution, float partialTicks,
                                                         CallbackInfo ci) {
        if (RiddenView.hidesHotbar()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSelectedItem(Lnet/minecraft/client/gui/ScaledResolution;)V", at = @At("HEAD"),
            cancellable = true, require = 1)
    private void bedwarsqol$noItemNameInAnotherPlayersView(ScaledResolution resolution, CallbackInfo ci) {
        if (RiddenView.hidesHotbar()) {
            ci.cancel();
        }
    }
}
