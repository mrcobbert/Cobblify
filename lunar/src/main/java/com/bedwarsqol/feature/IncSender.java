package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.weavemc.api.event.KeyboardEvent;
import net.weavemc.api.event.SubscribeEvent;
import org.lwjgl.input.Keyboard;

/**
 * Sends {@code /pc INC} when the key is pressed. Cooldown follows successful delivery acks from
 * {@link OutgoingChat} — cancelled/unsent queue entries do not burn the cooldown.
 */
public class IncSender {

    @SubscribeEvent
    public void onKey(KeyboardEvent event) {
        if (!event.getKeyState()) return;
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.pcIncKey) return;
        int key = cfg.pcIncKeyCode;
        if (key == Keyboard.KEY_NONE || event.getKeyCode() != key) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.currentScreen != null) return;
        long now = System.currentTimeMillis();
        if (!OutgoingChat.get().canSubmitInc(now)) return;
        OutgoingChat.get().submitInc("/pc INC");
    }
}
