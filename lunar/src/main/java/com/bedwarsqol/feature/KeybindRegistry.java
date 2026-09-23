package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.config.KeyCodes;
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
 * {@link IncSender}/{@link PlayersKeyHandler}, from Weave's keyboard and mouse events.
 *
 * <p>A mouse bind is stored as {@code -100 + button}, like vanilla. {@link KeyCodes#reconcile} and the
 * config's sanitize agree on what can be stored, so a rebind costs one save; a code the config cannot
 * hold is pushed back onto the KeyBinding instead of being saved again every tick.
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

        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null) return;
        int s = synced(settingsKey, cfg.settingsKeyCode);
        int p = synced(pauseKey, cfg.pauseKeyCode);
        int i = synced(incKey, cfg.pcIncKeyCode);
        int pl = synced(playersKey, cfg.playersKeyCode);
        if (s != cfg.settingsKeyCode || p != cfg.pauseKeyCode
                || i != cfg.pcIncKeyCode || pl != cfg.playersKeyCode) {
            cfg.settingsKeyCode = s;
            cfg.pauseKeyCode = p;
            cfg.pcIncKeyCode = i;
            cfg.playersKeyCode = pl;
            cfg.save();
        }
        // The KeyBinding shows what the config holds after the save's sanitize, so the next tick
        // compares equal instead of saving again.
        boolean rebound = pushBack(settingsKey, cfg.settingsKeyCode)
                | pushBack(pauseKey, cfg.pauseKeyCode)
                | pushBack(incKey, cfg.pcIncKeyCode)
                | pushBack(playersKey, cfg.playersKeyCode);
        if (rebound) KeyBinding.resetKeyBindingArrayAndHash();
    }

    private static int synced(KeyBinding binding, int configCode) {
        return binding == null ? configCode : KeyCodes.reconcile(binding.getKeyCode(), configCode);
    }

    private static boolean pushBack(KeyBinding binding, int configCode) {
        if (binding == null || binding.getKeyCode() == configCode) return false;
        binding.setKeyCode(configCode);
        return true;
    }
}
