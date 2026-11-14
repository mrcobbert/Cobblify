package cobdev.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Fakes mouse state the way a real device would leave it, then lets the screen's own
 * {@code handleMouseInput} read it. {@code org.lwjgl.input.Mouse} here is whatever the game loads:
 * Pylon's SDL3-backed facade in this setup (its state lives in {@code eventX}/{@code eventY}
 * doubles, {@code eventButtonState}, {@code eventWheelDelta} in notches, and the pointer in
 * {@code MouseSdl.lastX/lastY}), or plain LWJGL 2 without Pylon. The bridge runs inside Knot, so it
 * sees the same copy the game uses.
 *
 * <p>Never calls {@code Mouse.setCursorPosition}: on Pylon that warps the user's real pointer.
 * These are private Pylon internals (checked against Pylon 0.2.8); a rename fails loudly here.
 *
 * <p>Keyboard state works the same way: {@code Keyboard.keyDownBuffer} is what
 * {@code Keyboard.isKeyDown} reads (modifier keys, {@code GameSettings.isKeyDown}).
 */
final class Input {

    private static final Class<?> MOUSE = org.lwjgl.input.Mouse.class;
    private static final boolean PYLON = has(MOUSE, "eventButtonState");
    private static final Class<?> KEYBOARD = org.lwjgl.input.Keyboard.class;
    /** Pylon's Keyboard copies {@code KeyboardSdl.keyDownBuffer} (byte[], LWJGL codes) into its own buffer in poll(). */
    private static final boolean PYLON_KEYBOARD = has(KEYBOARD, "SDL");

    private Input() {
    }

    static String backend() {
        return PYLON ? "pylon" : "lwjgl2";
    }

    /** One mouse event in display pixels, LWJGL origin (bottom-left). {@code notches} > 0 = wheel down. */
    static void event(int x, int y, int button, boolean pressed, int notches) throws Exception {
        if (PYLON) {
            set(MOUSE, null, "eventX", (double) x);
            set(MOUSE, null, "eventY", (double) y);
            set(MOUSE, null, "eventButton", button);
            set(MOUSE, null, "eventButtonState", pressed);
            set(MOUSE, null, "eventWheelRemainder", 0.0);
            set(MOUSE, null, "eventWheelDelta", (double) -notches);
        } else {
            set(MOUSE, null, "event_x", x);
            set(MOUSE, null, "event_y", y);
            set(MOUSE, null, "eventButton", button);
            set(MOUSE, null, "eventState", pressed);
            set(MOUSE, null, "event_dwheel", -notches * 120);
        }
        pointer(x, y);
    }

    static void clearWheel() throws Exception {
        if (PYLON) {
            set(MOUSE, null, "eventWheelDelta", 0.0);
            set(MOUSE, null, "eventWheelRemainder", 0.0);
        } else {
            set(MOUSE, null, "event_dwheel", 0);
        }
    }

    /** Where {@code Mouse.getX/getY} report the pointer until the real mouse moves. */
    static void pointer(int x, int y) throws Exception {
        if (PYLON) {
            Class<?> sdl = Class.forName("pl.tomgirl.pylon.window.MouseSdl", true, MOUSE.getClassLoader());
            Method instance = sdl.getMethod("instance");
            Object mouseSdl = instance.invoke(null);
            // MouseSdl.pollMouse copies lastX/lastY into the poll state every frame; set both so the
            // current frame sees it too.
            set(sdl, mouseSdl, "lastX", (double) x);
            set(sdl, mouseSdl, "lastY", (double) y);
            Object poll = field(MOUSE, "pollState").get(null);
            set(poll.getClass(), poll, "x", (double) x);
            set(poll.getClass(), poll, "y", (double) y);
        } else {
            set(MOUSE, null, "x", x);
            set(MOUSE, null, "y", y);
        }
    }

    // ------------------------------------------------------------------------------ keyboard

    /** {@code Keyboard.isKeyDown(code)} as the game reads it right now. */
    static boolean keyDown(int code) throws Exception {
        return keyBuffer().get(code) != 0;
    }

    /**
     * Sets the keys {@code Keyboard.isKeyDown} reports until the game's next {@code poll()} refills the
     * buffer at the end of the frame. Used around one synchronous injected event ({@code --mods});
     * returns the previous states for {@link #restoreKeys}.
     */
    static boolean[] pressKeys(int[] codes) throws Exception {
        ByteBuffer b = keyBuffer();
        boolean[] was = new boolean[codes.length];
        for (int i = 0; i < codes.length; i++) {
            was[i] = b.get(codes[i]) != 0;
            b.put(codes[i], (byte) 1);
        }
        return was;
    }

    static void restoreKeys(int[] codes, boolean[] was) throws Exception {
        ByteBuffer b = keyBuffer();
        for (int i = codes.length - 1; i >= 0; i--) b.put(codes[i], (byte) (was[i] ? 1 : 0));
    }

    /**
     * A raw hold's keyboard state, as a real key would leave it: the game's buffer for this frame and, on
     * Pylon, the SDL-side state that {@code poll()} copies in every frame (kept until a real key event for
     * that key changes it). No key event is queued and no binding is touched.
     */
    static void rawKey(int code, boolean down) throws Exception {
        keyBuffer().put(code, (byte) (down ? 1 : 0));
        if (PYLON_KEYBOARD) {
            Class<?> sdl = Class.forName("pl.tomgirl.pylon.window.KeyboardSdl", true, KEYBOARD.getClassLoader());
            Object keyboardSdl = sdl.getMethod("instance").invoke(null);
            byte[] state = (byte[]) field(sdl, "keyDownBuffer").get(keyboardSdl);
            if (code < state.length) state[code] = (byte) (down ? 1 : 0);
        }
    }

    /** {@code Keyboard.keyDownBuffer}: the same name and type in Pylon and LWJGL 2. */
    private static ByteBuffer keyBuffer() throws Exception {
        return (ByteBuffer) field(KEYBOARD, "keyDownBuffer").get(null);
    }

    private static boolean has(Class<?> c, String name) {
        try {
            c.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new NoSuchFieldException(c.getName() + "." + name + " is gone (" + backend()
                    + " internals changed; update tools/devloop/bridge Input.java)");
        }
    }

    private static void set(Class<?> c, Object target, String name, Object value) throws Exception {
        field(c, name).set(target, value);
    }
}
