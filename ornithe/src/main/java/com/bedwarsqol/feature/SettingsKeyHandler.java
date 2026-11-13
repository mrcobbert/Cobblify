package com.bedwarsqol.feature;

import com.bedwarsqol.gui.SettingsGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

public class SettingsKeyHandler {

    private final KeyBinding keyBinding;

    public SettingsKeyHandler(KeyBinding keyBinding) {
        this.keyBinding = keyBinding;
    }

    public void onClientTick() {
        if (keyBinding == null || !keyBinding.isPressed()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen != null) return;
        mc.displayGuiScreen(new SettingsGui());
    }
}
