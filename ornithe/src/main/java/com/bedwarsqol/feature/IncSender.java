package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * Sends {@code /pc INC} to party chat when the "Send /pc INC" key is pressed. Cooldown is based on
 * successful delivery acknowledgements from {@link OutgoingChat} — a cancelled/unsent queue entry
 * does not burn the cooldown.
 *
 * <p>Polled once per client tick (Ornithe has no key-input event); the binding's press count
 * carries every press since the last poll, so the result matches Forge's per-key-event check.
 */
public class IncSender {

    private final KeyBinding keyBinding;

    public IncSender(KeyBinding keyBinding) {
        this.keyBinding = keyBinding;
    }

    public void onKeyInput() {
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
