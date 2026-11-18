package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.watch.RewindGalleryScreen;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The pause menu's "Watch Last Game" row (G16, G35, G48; Rewind R2): on Hypixel, with a kept game, in a lobby or once
 * the current game has a result, a full-width row goes above Disconnect and opens the newest kept game; the decision
 * and the layout are {@link RewindGalleryScreen#pauseMenuInit}'s. Otherwise the menu is untouched. Ornithe only.
 *
 * <p>VERIFIED against the mapped 1.8.9 jar (javap): {@code public void initGui()} {@code ()V} clears
 * {@code buttonList} and adds ids 1, 4, 0, 7, 5, 6 (Disconnect at {@code h/4+104}); {@code protected void
 * actionPerformed(GuiButton)} {@code (Lnet/minecraft/client/gui/GuiButton;)V} switches on the button id.
 */
@Mixin(GuiIngameMenu.class)
public abstract class GuiIngameMenuRewindMixin extends GuiScreen {

    @Inject(method = "initGui()V", at = @At("RETURN"), require = 1)
    private void bedwarsqol$watchLastGameRow(CallbackInfo ci) {
        RewindGalleryScreen.pauseMenuInit(this.buttonList, this.width, this.height);
    }

    @Inject(method = "actionPerformed(Lnet/minecraft/client/gui/GuiButton;)V", at = @At("HEAD"), cancellable = true,
            require = 1)
    private void bedwarsqol$watchLastGame(GuiButton button, CallbackInfo ci) {
        if (RewindGalleryScreen.pauseMenuAction(button)) ci.cancel();
    }
}
