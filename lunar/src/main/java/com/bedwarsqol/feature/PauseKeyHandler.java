package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.KeyCodes;
import net.minecraft.client.Minecraft;
import net.weavemc.api.event.KeyboardEvent;
import net.weavemc.api.event.MouseEvent;
import net.weavemc.api.event.SubscribeEvent;

/**
 * Opens the vanilla pause menu on a rebindable key, so players who turn on "Disable Esc Menu"
 * ({@link com.bedwarsqol.mixin.MinecraftEscMixin}) can still reach the menu on demand. Driven by Weave's
 * {@link KeyboardEvent} and {@link MouseEvent}; the key is set via {@link com.bedwarsqol.config.ClientSettings#pauseKeyCode}
 * (default unbound). Acts on the key-down edge while in-world with no screen open.
 */
public class PauseKeyHandler {

    @SubscribeEvent
    public void onKey(KeyboardEvent event) {
        if (event.getKeyState()) onPress(event.getKeyCode()); // key-down edge only
    }

    /** A mouse-button bind ({@code -100 + button}) set from the Controls menu. */
    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.getButtonState()) onPress(KeyCodes.fromMouseButton(event.getButton()));
    }

    private void onPress(int pressed) {
        if (BedwarsQol.config == null) return;
        if (!KeyCodes.matches(BedwarsQol.config.pauseKeyCode, pressed)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen != null || mc.thePlayer == null) return;
        mc.displayInGameMenu();
    }
}
