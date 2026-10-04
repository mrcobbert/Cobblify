package com.bedwarsqol.gui.sheet;

/**
 * Tells a fresh key press from a key still held down: its auto-repeat, or a key that was already down when the
 * screen opened. Vanilla's {@code GuiScreen.handleKeyboardInput} passes a screen key-downs only, so releases are
 * polled; LWJGL's own {@code isRepeatEvent} is not relied on, because Lunar's input layer may not have it. Only
 * keys that were pressed, or that {@link #open} names, are ever polled. Pure: the screen supplies
 * {@code Keyboard.isKeyDown}.
 */
public final class KeyHold {

    /** {@code Keyboard.isKeyDown}. */
    public interface Keys {
        boolean isDown(int code);
    }

    private final boolean[] held = new boolean[256];

    /** When the screen opens: those of {@code codes} already down count as held until they are released. */
    public void open(Keys keys, int... codes) {
        for (int code : codes) {
            if (code > 0 && code < held.length && keys.isDown(code)) held[code] = true;
        }
    }

    /**
     * Once a tick, after the screen has taken every queued key event ({@code updateScreen}, which runs right after
     * {@code handleInput}): a held key seen up is released. LWJGL updates the key states and queues the events in
     * the same poll, so polling any earlier, from a frame, can see a release while that key's last repeats are
     * still queued, and the next of them would then read as a fresh press.
     */
    public void tick(Keys keys) {
        for (int code = 1; code < held.length; code++) {
            if (held[code] && !keys.isDown(code)) held[code] = false;
        }
    }

    /** A key-down. True for a fresh press; false while the key has stayed down since an earlier press. */
    public boolean press(int code) {
        if (code <= 0 || code >= held.length) return true;
        boolean fresh = !held[code];
        held[code] = true;
        return fresh;
    }
}
