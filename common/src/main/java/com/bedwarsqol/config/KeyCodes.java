package com.bedwarsqol.config;

/**
 * Key codes as vanilla 1.8.9 stores them: LWJGL keyboard codes ({@code >= 0}, {@code 0} = unbound)
 * and mouse buttons as {@code -100 + button} (what the Controls menu writes for a mouse bind).
 *
 * <p>Lunar has no {@code ClientRegistry}, so its KeybindRegistry keeps a vanilla {@code KeyBinding}
 * and the config in step each tick. {@link #reconcile} and {@link #sanitize} share one notion of a
 * storable code, so a value copied into the config always survives the save's sanitize: one save,
 * then a fixed point, never a save per tick.
 */
public final class KeyCodes {

    private KeyCodes() {}

    /** Vanilla's mouse-button offset: button {@code b} is stored as {@code b - 100}. */
    public static final int MOUSE_OFFSET = -100;

    /** Whether the config can hold {@code code}: any keyboard code or a mouse button. */
    public static boolean isStorable(int code) {
        return code >= MOUSE_OFFSET;
    }

    /** {@code code} when storable, else {@code fallback}. */
    public static int sanitize(int code, int fallback) {
        return isStorable(code) ? code : fallback;
    }

    /**
     * The code both the KeyBinding and the config should hold after a tick: a storable KeyBinding
     * code (a Controls rebind) wins; anything else is reset to the config value.
     */
    public static int reconcile(int bindingCode, int configCode) {
        return isStorable(bindingCode) ? bindingCode : configCode;
    }

    /** The stored code for a mouse button index from LWJGL/Weave; {@code -1} (no button) maps below the range. */
    public static int fromMouseButton(int button) {
        return button + MOUSE_OFFSET;
    }

    /** Whether a press of {@code pressedCode} fires a key bound to {@code boundCode}. Unbound never fires. */
    public static boolean matches(int boundCode, int pressedCode) {
        return boundCode != 0 && isStorable(boundCode) && boundCode == pressedCode;
    }
}
