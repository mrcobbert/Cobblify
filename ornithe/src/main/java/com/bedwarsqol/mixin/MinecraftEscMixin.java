package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Rebinds the hardcoded Esc -> pause-menu open. In 1.8.9 the in-world Esc handler is a literal
 * {@code if (Keyboard.getEventKey() == 1) this.displayInGameMenu();} inside {@link Minecraft#runTick()};
 * Esc is NOT a {@link net.minecraft.client.settings.KeyBinding}, so the vanilla Controls menu can't
 * touch it. This redirect swallows that call when {@code suppressEscMenu} is on, so an accidental Esc
 * tap in combat no longer opens the menu (and can't fumble onward into Options/Language).
 *
 * <p>Screen-closing is intentionally left alone: {@code GuiScreen.keyTyped} still maps Esc to close, so
 * Esc keeps backing you out of menus. The pause menu can be reopened deliberately via the rebindable
 * "Open Game Menu" KeyBinding (see {@link com.bedwarsqol.feature.PauseKeyHandler}).
 *
 * VERIFIED against 1.8.9 MCP-named bytecode: public void runTick() invokes public void displayInGameMenu().
 */
@Mixin(Minecraft.class)
public class MinecraftEscMixin {

    @Redirect(
            method = "runTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;displayInGameMenu()V"))
    private void bedwarsqol$rebindEsc(Minecraft mc) {
        if (BedwarsQol.config != null && BedwarsQol.config.suppressEscMenu) return; // swallow accidental Esc-open
        mc.displayInGameMenu();
    }
}
