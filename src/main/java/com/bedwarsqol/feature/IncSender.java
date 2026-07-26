package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;

/**
 * Sends {@code /pc INC} to party chat when the "Send /pc INC" key is pressed. Cooldown is based on
 * successful delivery acknowledgements from {@link OutgoingChat} — a cancelled/unsent queue entry
 * does not burn the cooldown.
 */
public class IncSender {

    private final KeyBinding keyBinding;

    public IncSender(KeyBinding keyBinding) {
        this.keyBinding = keyBinding;
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (keyBinding == null || !keyBinding.isPressed()) return;
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.pcIncKey) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        if (mc.currentScreen != null) return;
        long now = System.currentTimeMillis();
        if (!OutgoingChat.get().canSubmitInc(now)) return;
        OutgoingChat.get().submitInc("/pc INC");
    }
}
