package cobdev.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
 */
final class Input {

    private static final Class<?> MOUSE = org.lwjgl.input.Mouse.class;
    private static final boolean PYLON = has(MOUSE, "eventButtonState");

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
