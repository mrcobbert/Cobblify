package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.watch.RewindGalleryScreen;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The title screen's Rewind entry (G17, G50; Rewind R2): while the Rewind module is on, the Minecraft Realms button
 * (id 14) reads "Rewind" and opens Rewind's gallery instead of Realms; with it off the button is vanilla. Also lends
 * the gallery the title's panorama ({@link RewindGalleryScreen.TitleBackdrop}). Ornithe only.
 *
 * <p>VERIFIED against the mapped 1.8.9 jar (javap): {@code public void initGui()} {@code ()V} adds the Realms button
 * as {@code new GuiButton(14, ...)} in {@code addSingleplayerMultiplayerButtons}; {@code protected void
 * actionPerformed(GuiButton)} {@code (Lnet/minecraft/client/gui/GuiButton;)V} calls {@code switchToRealms()} for id
 * 14; {@code private void renderSkybox(int, int, float)} {@code (IIF)V} draws the panorama.
 */
@Mixin(GuiMainMenu.class)
public abstract class GuiMainMenuRewindMixin extends GuiScreen implements RewindGalleryScreen.TitleBackdrop {

    @Shadow
    private void renderSkybox(int mouseX, int mouseY, float partialTicks) {
        throw new AssertionError();
    }

    @Inject(method = "initGui()V", at = @At("RETURN"), require = 1)
    private void bedwarsqol$rewindSlot(CallbackInfo ci) {
        RewindGalleryScreen.titleScreenInit(this.buttonList);
    }

    @Inject(method = "actionPerformed(Lnet/minecraft/client/gui/GuiButton;)V", at = @At("HEAD"), cancellable = true,
            require = 1)
    private void bedwarsqol$openGallery(GuiButton button, CallbackInfo ci) {
        if (RewindGalleryScreen.titleScreenAction(this, button)) ci.cancel();
    }

    @Override
    public void bedwarsqol$drawPanorama(int mouseX, int mouseY, float partialTicks) {
        renderSkybox(mouseX, mouseY, partialTicks);
    }
}
