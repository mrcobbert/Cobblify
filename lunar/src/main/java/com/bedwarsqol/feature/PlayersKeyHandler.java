package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.gui.SettingsGui;
import net.minecraft.client.Minecraft;
import net.weavemc.api.event.KeyboardEvent;
import net.weavemc.api.event.SubscribeEvent;
import org.lwjgl.input.Keyboard;

/**
 * Opens the settings GUI straight to the Players tab on the configured key (default unbound). Driven by
 * Weave's {@link KeyboardEvent} (key-down edge) since there is no vanilla {@code KeyBinding} action under
 * Weave — the key is rebindable via {@link com.bedwarsqol.config.ClientSettings#playersKeyCode}.
 */
public class PlayersKeyHandler {

    @SubscribeEvent
    public void onKey(KeyboardEvent event) {
        if (!event.getKeyState()) return; // key-down edge only
        if (BedwarsQol.config == null) return;
        int key = BedwarsQol.config.playersKeyCode;
        if (key == Keyboard.KEY_NONE || event.getKeyCode() != key) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen != null) return;
        mc.displayGuiScreen(SettingsGui.players());
    }
}
