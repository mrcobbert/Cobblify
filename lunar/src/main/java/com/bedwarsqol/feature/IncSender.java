package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.config.KeyCodes;
import net.minecraft.client.Minecraft;
import net.weavemc.api.event.KeyboardEvent;
import net.weavemc.api.event.MouseEvent;
import net.weavemc.api.event.SubscribeEvent;

/**
 * Sends {@code /pc INC} when the key is pressed. Cooldown follows successful delivery acks from
 * {@link OutgoingChat} — cancelled/unsent queue entries do not burn the cooldown.
 */
public class IncSender {

    @SubscribeEvent
    public void onKey(KeyboardEvent event) {
        if (event.getKeyState()) onPress(event.getKeyCode());
    }

    /** A mouse-button bind ({@code -100 + button}) set from the Controls menu. */
    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.getButtonState()) onPress(KeyCodes.fromMouseButton(event.getButton()));
    }

    private void onPress(int pressed) {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.pcIncKey) return;
        if (!KeyCodes.matches(cfg.pcIncKeyCode, pressed)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.currentScreen != null) return;
        long now = System.currentTimeMillis();
        if (!OutgoingChat.get().canSubmitInc(now)) return;
        OutgoingChat.get().submitInc("/pc INC");
    }
}
