package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.gui.SettingsGui;
import com.bedwarsqol.config.KeyCodes;
import net.minecraft.client.Minecraft;
import net.weavemc.api.event.KeyboardEvent;
import net.weavemc.api.event.MouseEvent;
import net.weavemc.api.event.SubscribeEvent;

/**
 * Opens the BedwarsQOL settings GUI on the configured key (default Right Shift). Driven by Weave's
 * {@link KeyboardEvent} / {@link MouseEvent} (press edge, no screen open) since there is no vanilla {@code KeyBinding}
 * registration under Weave — the key is rebindable via {@link com.bedwarsqol.config.ClientSettings#settingsKeyCode}.
 */
public class SettingsKeyHandler {

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
        if (!KeyCodes.matches(BedwarsQol.config.settingsKeyCode, pressed)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen != null) return;
        mc.displayGuiScreen(new SettingsGui());
    }
}
