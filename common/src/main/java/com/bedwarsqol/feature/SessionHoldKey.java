package com.bedwarsqol.feature;

/**
 * Rules for the Session Stats "Hold Key to Show" option, kept free of Minecraft and LWJGL types so
 * both platforms share them: when the HUD draws, what a key or mouse press does while the settings
 * Key row is waiting for one, and how a binding is named.
 *
 * <p>Bindings use Minecraft's own KeyBinding convention: a keyboard key is its LWJGL key code, mouse
 * button {@code b} is {@code b - 100}, and {@code 0} is unbound.
 */
public final class SessionHoldKey {

    /** LWJGL 2 key codes the capture rules need (org.lwjgl.input.Keyboard). */
    public static final int KEY_ESCAPE = 1;
    public static final int KEY_BACK = 14;
    public static final int KEY_DELETE = 211;

    /** {@link #bindingForKey} result: leave the binding as it was and stop waiting. */
    public static final int CANCEL = Integer.MIN_VALUE;
    /** {@link #bindingForKey} result: not a key press (a typed character without a key), keep waiting. */
    public static final int KEEP_WAITING = Integer.MAX_VALUE;

    private static final int MOUSE_OFFSET = 100;
    private static final int MAX_MOUSE_BUTTONS = 16;
    private static final int MAX_KEY = 255;

    private SessionHoldKey() {
    }

    /**
     * Whether the Session Stats HUD draws now. Off when Session Stats is off; always in the HUD editor
     * ({@code example}) so the box can be placed; otherwise only on Hypixel. In hold mode it shows only
     * while the bound key is held and no screen is open; unbound means hidden.
     */
    public static boolean hudVisible(boolean enabled, boolean example, boolean onHypixel, boolean holdMode,
                                     int keyCode, boolean screenOpen, boolean keyDown) {
        if (!enabled) return false;
        if (example) return true;
        if (!onHypixel) return false;
        if (!holdMode) return true;
        return keyCode != 0 && !screenOpen && keyDown;
    }

    /**
     * What a key press does while the Key row waits: Escape cancels, Backspace or Delete clear the
     * binding, a press without a key code keeps waiting, any other key becomes the binding.
     */
    public static int bindingForKey(int keyCode) {
        if (keyCode == KEY_ESCAPE) return CANCEL;
        if (keyCode == KEY_BACK || keyCode == KEY_DELETE) return 0;
        if (keyCode == 0) return KEEP_WAITING;
        return keyCode;
    }

    /** The binding for mouse button {@code button} (any button, left and right click included). */
    public static int bindingForMouse(int button) {
        return button - MOUSE_OFFSET;
    }

    public static boolean isMouse(int code) {
        return code < 0;
    }

    /** The mouse button index of a mouse binding. */
    public static int mouseButton(int code) {
        return code + MOUSE_OFFSET;
    }

    /** Whether a stored binding is one this option can read: unbound, a keyboard key, or a mouse button. */
    public static boolean isValidBinding(int code) {
        if (code == 0) return true;
        if (isMouse(code)) {
            int b = mouseButton(code);
            return b >= 0 && b < MAX_MOUSE_BUTTONS;
        }
        return code <= MAX_KEY;
    }

    /**
     * What the Key row shows for a binding. {@code keyboardName} is the platform's name for a keyboard
     * key (LWJGL's {@code Keyboard.getKeyName}); it is ignored for mouse buttons and unbound.
     */
    public static String displayName(int code, String keyboardName) {
        if (code == 0) return "None";
        if (isMouse(code)) {
            int b = mouseButton(code);
            switch (b) {
                case 0: return "Left Click";
                case 1: return "Right Click";
                case 2: return "Middle Click";
                default: return "Mouse " + (b + 1);
            }
        }
        return keyboardName != null && !keyboardName.isEmpty() ? keyboardName : "Key " + code;
    }
}
