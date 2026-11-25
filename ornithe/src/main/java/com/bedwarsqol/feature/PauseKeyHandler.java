package com.bedwarsqol.feature;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * Opens the vanilla pause menu on a rebindable key, so players who turn on "Disable Esc Menu"
 * ({@link com.bedwarsqol.mixin.MinecraftEscMixin}) can still reach the menu on demand. The
 * KeyBinding lives in Minecraft's native Controls menu (registered in {@code BedwarsQol#onInit}, default
 * unbound). Mirrors {@link SettingsKeyHandler}: acts on the key-down edge while no screen is open.
 */
public class PauseKeyHandler {

    private final KeyBinding keyBinding;

    public PauseKeyHandler(KeyBinding keyBinding) {
        this.keyBinding = keyBinding;
    }

    public void onClientTick() {
        if (keyBinding == null || !keyBinding.isPressed()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen != null) return;
        mc.displayInGameMenu();
    }
}
