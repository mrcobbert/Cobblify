package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.Arrays;

/**
 * Makes the "Open Settings", "Open Game Menu" and "Send /pc INC" keys rebindable from Minecraft's own
 * Controls menu. Weave has no {@code ClientRegistry.registerKeyBinding}, so we append vanilla {@link KeyBinding}s
 * to {@code GameSettings.keyBindings} ourselves, under the existing <i>Miscellaneous</i> category (a
 * custom category would NPE GuiControls' sort, which looks categories up in a fixed order map).
 *
 * <p>Registration runs once on the first client tick (when {@code gameSettings} exists). Because that is
 * after vanilla has already read {@code options.txt}, our config — not options.txt — is the source of
 * truth across restarts: each tick we copy any Controls rebind back into the config (and save), and the
 * actual key actions are fired from the config value by {@link SettingsKeyHandler}/{@link PauseKeyHandler}/
 * {@link IncSender}.
 */
public final class KeybindRegistry {

    public static KeyBinding settingsKey;
    public static KeyBinding pauseKey;
    public static KeyBinding incKey;
    public static KeyBinding playersKey;
    private static boolean registered;

    @SubscribeEvent
    public void onTick(TickEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) return;

        if (!registered) {
            int sCode = BedwarsQol.config != null ? BedwarsQol.config.settingsKeyCode : Keyboard.KEY_RSHIFT;
            int pCode = BedwarsQol.config != null ? BedwarsQol.config.pauseKeyCode : Keyboard.KEY_NONE;
            int iCode = BedwarsQol.config != null ? BedwarsQol.config.pcIncKeyCode : Keyboard.KEY_NONE;
            int plCode = BedwarsQol.config != null ? BedwarsQol.config.playersKeyCode : Keyboard.KEY_NONE;
            settingsKey = new KeyBinding("Open Cobblify Settings", sCode, "key.categories.misc");
            pauseKey = new KeyBinding("Cobblify: Open Game Menu", pCode, "key.categories.misc");
            incKey = new KeyBinding("Cobblify: Send /pc INC", iCode, "key.categories.misc");
            playersKey = new KeyBinding("Cobblify: Open Players", plCode, "key.categories.misc");
            KeyBinding[] cur = mc.gameSettings.keyBindings;
            KeyBinding[] next = Arrays.copyOf(cur, cur.length + 4);
            next[cur.length] = settingsKey;
            next[cur.length + 1] = pauseKey;
            next[cur.length + 2] = incKey;
            next[cur.length + 3] = playersKey;
            mc.gameSettings.keyBindings = next;
            registered = true;
        }

        if (BedwarsQol.config == null) return;
        boolean dirty = false;
        if (settingsKey != null && settingsKey.getKeyCode() != BedwarsQol.config.settingsKeyCode) {
            BedwarsQol.config.settingsKeyCode = settingsKey.getKeyCode();
            dirty = true;
        }
        if (pauseKey != null && pauseKey.getKeyCode() != BedwarsQol.config.pauseKeyCode) {
            BedwarsQol.config.pauseKeyCode = pauseKey.getKeyCode();
            dirty = true;
        }
        if (incKey != null && incKey.getKeyCode() != BedwarsQol.config.pcIncKeyCode) {
            BedwarsQol.config.pcIncKeyCode = incKey.getKeyCode();
            dirty = true;
        }
        if (playersKey != null && playersKey.getKeyCode() != BedwarsQol.config.playersKeyCode) {
            BedwarsQol.config.playersKeyCode = playersKey.getKeyCode();
            dirty = true;
        }
        if (dirty) BedwarsQol.config.save();
    }
}
