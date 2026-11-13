package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.config.SettingsManager;
import com.bedwarsqol.mixin.KeyBindingAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * Toggle Sprint (Ornithe build), run at the end of every client tick: the "Toggle Sprint" key flips
 * {@link ClientSettings#toggleSprintOn}, and while it is on the sprint key binding is held down, the
 * same state as the player holding it. Vanilla releases every key when a screen opens; this presses
 * it again once the screen closes, and releases it when the toggle goes off.
 */
public final class ToggleSprint {

    private final KeyBinding key;
    private boolean holding;

    public ToggleSprint(KeyBinding key) {
        this.key = key;
    }

    public void onClientTick() {
        ClientSettings c = BedwarsQol.config;
        Minecraft mc = Minecraft.getMinecraft();
        if (c == null || key == null || mc == null || mc.gameSettings == null) return;
        boolean screenOpen = mc.currentScreen != null;
        boolean pressed = key.isPressed();
        boolean next = SprintRules.toggled(c.toggleSprintOn, c.toggleSprintEnabled, pressed, screenOpen);
        if (next != c.toggleSprintOn) {
            c.toggleSprintOn = next;
            SettingsManager.save(c);
        }
        KeyBinding sprint = mc.gameSettings.keyBindSprint;
        if (SprintRules.holdSprint(next, mc.thePlayer != null, screenOpen)) {
            ((KeyBindingAccessor) sprint).bedwarsqol$setPressed(true);
            holding = true;
        } else if (holding) {
            holding = false;
            if (!screenOpen) ((KeyBindingAccessor) sprint).bedwarsqol$setPressed(false);
        }
    }
}
