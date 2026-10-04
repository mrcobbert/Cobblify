package cobdev;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.instrument.ClassDefinition;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntUnaryOperator;

/**
 * Everything the control API does to the running game. Minecraft is reached by reflection on the
 * dev environment's MCP names, and only after the game has loaded {@code Minecraft} itself, so this
 * class never forces a game class to load before Forge's transformers are in place.
 *
 * <p>Input is injected the way a real device delivers it: LWJGL's event fields are set and the
 * screen's own {@code handleMouseInput} runs, so overrides such as the settings screen's wheel
 * handling see exactly what a mouse would give them.
 */
final class Game {

    private static final String MOD_PREFIX = "com.bedwarsqol.";
    private static final String MIXIN_PREFIX = "com.bedwarsqol.mixin.";
    private static final Class<?>[] NONE = new Class<?>[0];
    private static final long CURSOR_HOLD_MS = 20_000;
    private static final long STARTED_AT = System.currentTimeMillis();

    private static volatile Object mc;
    private static volatile Class<?> mcClass;

    /** Virtual cursor in display pixels (LWJGL origin, bottom-left), held until {@link #cursorUntil}. */
    private static volatile int[] cursor;
    private static volatile long cursorUntil;
    private static final AtomicBoolean cursorQueued = new AtomicBoolean();

    private Game() {
    }

    // ---------------------------------------------------------------- readiness and threading

    private static Object mc() throws Exception {
        Object m = mc;
        if (m != null) return m;
        Class<?> found = null;
        for (Class<?> c : DevAgent.inst.getAllLoadedClasses()) {
            if ("net.minecraft.client.Minecraft".equals(c.getName()) && c.getClassLoader() != null) {
                found = c;
                break;
            }
        }
        if (found == null) throw new DevAgent.Refused("the game is still starting");
        Object instance = callStatic(found, "getMinecraft");
        if (instance == null || (get(instance, "currentScreen") == null && get(instance, "theWorld") == null)) {
            throw new DevAgent.Refused("the game is still starting");
        }
        mcClass = found;
        mc = instance;
        return instance;
    }

    /** Runs {@code work} on the game thread between frames and waits for it. */
    private static <T> T onMain(Callable<T> work, long timeoutMs) throws Exception {
        Object m = mc();
        FutureTask<T> task = new FutureTask<>(work);
        call(m, "addScheduledTask", new Class<?>[]{Runnable.class}, task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(false);
            throw new DevAgent.Refused("the game thread did not answer within " + timeoutMs
                    + " ms (frozen, or minimized and throttled by macOS?)");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            if (c instanceof Error) throw (Error) c;
            throw e;
        }
    }

    private static <T> T onMain(Callable<T> work) throws Exception {
        return onMain(work, 10_000);
    }

    // ---------------------------------------------------------------- endpoints

    static Map<String, Object> status() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("pid", ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        out.put("uptimeMs", System.currentTimeMillis() - STARTED_AT);
        out.put("classesDir", System.getProperty("cobdev.classes"));
        try {
            mc();
        } catch (DevAgent.Refused e) {
            out.put("ready", false);
            out.put("reason", e.getMessage());
            return out;
        }
        out.put("ready", true);
        try {
            out.putAll(onMain(() -> {
                Map<String, Object> s = new LinkedHashMap<>();
                Object m = mc;
                Object screen = get(m, "currentScreen");
                int[] sr = scaled();
                s.put("screen", screen == null ? null : screen.getClass().getName());
                s.put("display", get(m, "displayWidth") + "x" + get(m, "displayHeight"));
                s.put("gui", sr[0] + "x" + sr[1]);
                s.put("scaleFactor", sr[2]);
                s.put("guiScaleSetting", get(get(m, "gameSettings"), "guiScale"));
                s.put("inWorld", get(m, "theWorld") != null);
                s.put("onServer", onServer(m));
                s.put("cursorHeld", cursor != null && System.currentTimeMillis() < cursorUntil);
                return s;
            }, 3_000));
            out.put("responsive", true);
        } catch (DevAgent.Refused e) {
            out.put("responsive", false);
            out.put("reason", e.getMessage());
        }
        return out;
    }

    static Map<String, Object> swap(List<String> names, boolean reinit) throws Exception {
        mc();
        File dir = new File(System.getProperty("cobdev.classes", ""));
        Map<String, byte[]> bytes = new HashMap<>();
        List<String> mixins = new ArrayList<>();
        for (String raw : names) {
            String n = raw.trim();
            if (n.isEmpty()) continue;
            if (!n.matches("[A-Za-z0-9_$.]+")) throw new DevAgent.Refused("bad class name: " + n);
            if (n.startsWith(MIXIN_PREFIX)) {
                mixins.add(n);
                continue;
            }
            bytes.put(n, Files.readAllBytes(new File(dir, n.replace('.', '/') + ".class").toPath()));
        }
        if (!mixins.isEmpty()) {
            return restartNeeded("mixins are applied at class load and cannot be swapped", mixins);
        }
        List<ClassDefinition> defs = new ArrayList<>();
        List<String> swapped = new ArrayList<>();
        for (Class<?> c : DevAgent.inst.getAllLoadedClasses()) {
            byte[] b = bytes.get(c.getName());
            if (b != null && c.getClassLoader() != null) {
                defs.add(new ClassDefinition(c, b));
                swapped.add(c.getName());
            }
        }
        List<String> notLoaded = new ArrayList<>(bytes.keySet());
        notLoaded.removeAll(swapped);
        try {
            onMain(() -> {
                DevAgent.inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
                if (reinit && get(mc, "currentScreen") != null) reinitScreen();
                return null;
            });
        } catch (UnsupportedOperationException | LinkageError e) {
            return restartNeeded(e.toString(), swapped);
        }
        Map<String, Object> out = ok();
        out.put("swapped", swapped);
        out.put("notLoadedYet", notLoaded);
        return out;
    }

    static Map<String, Object> reinit() throws Exception {
        return onMain(() -> {
            if (get(mc, "currentScreen") == null) throw new DevAgent.Refused("no screen is open");
            reinitScreen();
            return ok();
        });
    }

    static Map<String, Object> open(String className, Integer arg) throws Exception {
        if (!className.startsWith(MOD_PREFIX)) throw new DevAgent.Refused("only Cobblify screens can be opened");
        return onMain(() -> {
            requireLocal();
            Class<?> guiScreen = gameClass("net.minecraft.client.gui.GuiScreen");
            Class<?> c = gameClass(className);
            if (!guiScreen.isAssignableFrom(c)) throw new DevAgent.Refused(className + " is not a GuiScreen");
            Constructor<?> ctor = arg == null ? c.getDeclaredConstructor() : c.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            Object screen = arg == null ? ctor.newInstance() : ctor.newInstance(arg);
            call(mc, "displayGuiScreen", new Class<?>[]{guiScreen}, screen);
            return ok();
        });
    }

    static Map<String, Object> close() throws Exception {
        return onMain(() -> {
            requireLocal();
            call(mc, "displayGuiScreen", new Class<?>[]{gameClass("net.minecraft.client.gui.GuiScreen")}, new Object[]{null});
            return ok();
        });
    }

    static Map<String, Object> click(int x, int y, int button) throws Exception {
        int[] p = onMain(() -> {
            requireLocal();
            int[] d = toDisplay(x, y);
            holdCursor(d);
            inject(modScreen(), d[0], d[1], button, true, 0);
            return d;
        });
        // Release on the next frame, as a real click would; it goes to whatever screen the press left open.
        return onMain(() -> {
            Object screen = get(mc, "currentScreen");
            if (screen != null && screen.getClass().getName().startsWith(MOD_PREFIX)) {
                inject(screen, p[0], p[1], button, false, 0);
            }
            return ok();
        });
    }

    static Map<String, Object> mouseButton(int x, int y, int button, boolean down) throws Exception {
        return onMain(() -> {
            requireLocal();
            int[] d = toDisplay(x, y);
            holdCursor(d);
            inject(modScreen(), d[0], d[1], button, down, 0);
            return ok();
        });
    }

    static Map<String, Object> drag(int x, int y) throws Exception {
        return onMain(() -> {
            requireLocal();
            int[] d = toDisplay(x, y);
            holdCursor(d);
            // Button -1 with a button still held in the screen is vanilla's drag event (mouseClickMove).
            inject(modScreen(), d[0], d[1], -1, false, 0);
            return ok();
        });
    }

    /** {@code notches} > 0 scrolls down (wheel toward you), like the CLI documents. */
    static Map<String, Object> scroll(int x, int y, int notches) throws Exception {
        return onMain(() -> {
            requireLocal();
            int[] d = toDisplay(x, y);
            holdCursor(d);
            inject(modScreen(), d[0], d[1], -1, false, -notches * 120);
            return ok();
        });
    }

    static Map<String, Object> hover(int x, int y) throws Exception {
        return onMain(() -> {
            holdCursor(toDisplay(x, y));
            return ok();
        });
    }

    static Map<String, Object> hoverOff() {
        cursor = null;
        return ok();
    }

    static Map<String, Object> type(String text) throws Exception {
        return onMain(() -> {
            requireLocal();
            Object screen = modScreen();
            for (char ch : text.toCharArray()) {
                call(screen, "keyTyped", new Class<?>[]{char.class, int.class}, ch, 0);
            }
            return ok();
        });
    }

    static Map<String, Object> key(int code, char ch) throws Exception {
        return onMain(() -> {
            requireLocal();
            call(modScreen(), "keyTyped", new Class<?>[]{char.class, int.class}, ch, code);
            return ok();
        });
    }

    /** Resize the game window to {@code w} x {@code h} display px, the way vanilla's fullscreen toggle does. */
    static Map<String, Object> size(int w, int h) throws Exception {
        if (w < 320 || h < 240) throw new DevAgent.Refused("the window must be at least 320x240");
        return onMain(() -> {
            // macOS clamps a window that would run off the screen, so start from the top-left corner
            org.lwjgl.opengl.Display.setLocation(0, 0);
            org.lwjgl.opengl.Display.setDisplayMode(new org.lwjgl.opengl.DisplayMode(w, h));
            call(mc, "resize", new Class<?>[]{int.class, int.class}, w, h);
            Map<String, Object> out = ok();
            out.put("window", org.lwjgl.opengl.Display.getWidth() + "x" + org.lwjgl.opengl.Display.getHeight()
                    + " at " + org.lwjgl.opengl.Display.getX() + "," + org.lwjgl.opengl.Display.getY());
            out.put("desktop", String.valueOf(org.lwjgl.opengl.Display.getDesktopDisplayMode()));
            out.put("pixelScale", org.lwjgl.opengl.Display.getPixelScaleFactor());
            return out;
        });
    }

    static Map<String, Object> scale(int n) throws Exception {
        if (n < 0 || n > 4) throw new DevAgent.Refused("GUI scale is 0 (auto) to 4");
        return onMain(() -> {
            set(get(mc, "gameSettings"), "guiScale", n);
            if (get(mc, "currentScreen") != null) reinitScreen();
            return ok();
        });
    }

    /**
     * Note: scheduled tasks run while the game holds its task-queue lock, and launchIntegratedServer
     * waits for the server thread. A server-thread handler (e.g. a WorldEvent.Load subscriber) that
     * called Minecraft.addScheduledTask would block on that lock and hang the game. None does today.
     */
    static Map<String, Object> world() throws Exception {
        return onMain(() -> {
            requireLocal();
            if (get(mc, "theWorld") != null) return ok();
            Object saves = call(mc, "getSaveLoader", NONE);
            boolean exists = (Boolean) call(saves, "canLoadWorld", new Class<?>[]{String.class}, "cobdev");
            Class<?> settingsClass = gameClass("net.minecraft.world.WorldSettings");
            Object settings = null;
            if (!exists) {
                Class<?> gameType = gameClass("net.minecraft.world.WorldSettings$GameType");
                Class<?> worldType = gameClass("net.minecraft.world.WorldType");
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object creative = Enum.valueOf((Class) gameType, "CREATIVE");
                settings = settingsClass.getConstructor(long.class, gameType, boolean.class, boolean.class, worldType)
                        .newInstance(0L, creative, false, false, getStatic(worldType, "FLAT"));
                call(settings, "enableCommands", NONE);
            }
            call(mc, "launchIntegratedServer", new Class<?>[]{String.class, String.class, settingsClass},
                    "cobdev", "Cobblify Dev", settings);
            return ok();
        }, 90_000);
    }

    static Map<String, Object> refresh() throws Exception {
        return onMain(() -> {
            call(mc, "refreshResources", NONE);
            return ok();
        }, 60_000);
    }

    static Map<String, Object> restart() throws Exception {
        File run = new File(System.getProperty("cobdev.run", "."));
        Files.write(new File(run, "restart-requested").toPath(), new byte[0]);
        return onMain(() -> {
            call(mc, "shutdown", NONE);
            return ok();
        });
    }

    static Map<String, Object> log(int lines) throws Exception {
        File f = new File(System.getProperty("cobdev.run", "."), "logs/latest.log");
        List<String> all = Files.readAllLines(f.toPath(), StandardCharsets.ISO_8859_1);
        Map<String, Object> out = ok();
        out.put("lines", all.subList(Math.max(0, all.size() - Math.max(1, lines)), all.size()));
        return out;
    }

    /** The last fully rendered frame, as PNG, after waiting {@code waitMs} for animations to settle. */
    static byte[] shot(int waitMs) throws Exception {
        onMain(() -> null);
        if (waitMs > 0) Thread.sleep(Math.min(waitMs, 10_000));
        Object[] frame = onMain(() -> {
            Object fb = call(mc, "getFramebuffer", NONE);
            int w = (Integer) get(fb, "framebufferWidth");
            int h = (Integer) get(fb, "framebufferHeight");
            if (!(Boolean) callStatic(gameClass("net.minecraft.client.renderer.OpenGlHelper"), "isFramebufferEnabled")) {
                throw new DevAgent.Refused("framebuffers are off in video settings; screenshots need them");
            }
            // At this point in the loop the main framebuffer still holds the whole previous frame.
            IntBuffer buf = BufferUtils.createIntBuffer(w * h);
            call(fb, "bindFramebuffer", new Class<?>[]{boolean.class}, false);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, w, h, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
            call(fb, "unbindFramebuffer", NONE);
            int[] px = new int[w * h];
            buf.get(px);
            return new Object[]{w, h, px};
        });
        int w = (Integer) frame[0];
        int h = (Integer) frame[1];
        int[] px = (int[]) frame[2];
        // RGB only: the window shows the framebuffer with alpha writes masked off.
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < h; row++) img.setRGB(0, h - 1 - row, w, 1, px, row * w, w);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- input plumbing

    static void startCursorThread() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Object m = mc;
                    if (m != null && cursor != null && System.currentTimeMillis() < cursorUntil
                            && cursorQueued.compareAndSet(false, true)) {
                        Runnable apply = () -> {
                            try {
                                int[] c = cursor;
                                if (c != null) setMousePos(c[0], c[1]);
                            } catch (Exception ignored) {
                                // the next frame tries again
                            } finally {
                                cursorQueued.set(false);
                            }
                        };
                        call(m, "addScheduledTask", new Class<?>[]{Runnable.class}, apply);
                    }
                    Thread.sleep(4);
                } catch (Throwable e) {
                    cursorQueued.set(false);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "cobdev-cursor");
        t.setDaemon(true);
        t.start();
    }

    private static void holdCursor(int[] display) throws Exception {
        cursor = display;
        cursorUntil = System.currentTimeMillis() + CURSOR_HOLD_MS;
        setMousePos(display[0], display[1]);
    }

    /** Overrides LWJGL's polled position for this frame; vanilla reads it for drawScreen's mouse. */
    private static void setMousePos(int x, int y) throws Exception {
        Class<?> mouse = Class.forName("org.lwjgl.input.Mouse");
        setStatic(mouse, "x", x);
        setStatic(mouse, "y", y);
    }

    private static void inject(Object screen, int x, int y, int button, boolean pressed, int wheel) throws Exception {
        Class<?> mouse = Class.forName("org.lwjgl.input.Mouse");
        setStatic(mouse, "event_x", x);
        setStatic(mouse, "event_y", y);
        setStatic(mouse, "eventButton", button);
        setStatic(mouse, "eventState", pressed);
        setStatic(mouse, "event_dwheel", wheel);
        setMousePos(x, y);
        try {
            call(screen, "handleMouseInput", NONE);
        } finally {
            setStatic(mouse, "event_dwheel", 0);
        }
    }

    /** GUI coordinates to the display pixel vanilla maps back onto them (middle of the run). */
    private static int[] toDisplay(int gx, int gy) throws Exception {
        int dw = (Integer) get(mc, "displayWidth");
        int dh = (Integer) get(mc, "displayHeight");
        int[] sr = scaled();
        int sw = sr[0];
        int sh = sr[1];
        int x = middle(v -> v * sw / dw, gx, dw);
        int y = middle(v -> sh - v * sh / dh - 1, gy, dh);
        if (x < 0 || y < 0) throw new DevAgent.Refused("(" + gx + "," + gy + ") is outside the " + sw + "x" + sh + " GUI");
        return new int[]{x, y};
    }

    private static int middle(IntUnaryOperator f, int target, int range) {
        int lo = -1;
        int hi = -1;
        for (int v = 0; v < range; v++) {
            if (f.applyAsInt(v) == target) {
                if (lo < 0) lo = v;
                hi = v;
            }
        }
        return lo < 0 ? -1 : (lo + hi) / 2;
    }

    private static int[] scaled() throws Exception {
        Object sr = gameClass("net.minecraft.client.gui.ScaledResolution").getConstructor(mcClass).newInstance(mc);
        return new int[]{(Integer) call(sr, "getScaledWidth", NONE), (Integer) call(sr, "getScaledHeight", NONE),
                (Integer) call(sr, "getScaleFactor", NONE)};
    }

    private static void reinitScreen() throws Exception {
        int[] sr = scaled();
        call(get(mc, "currentScreen"), "setWorldAndResolution", new Class<?>[]{mcClass, int.class, int.class},
                mc, sr[0], sr[1]);
    }

    private static Object modScreen() throws Exception {
        Object screen = get(mc, "currentScreen");
        if (screen == null || !screen.getClass().getName().startsWith(MOD_PREFIX)) {
            throw new DevAgent.Refused("input only goes to Cobblify screens; the current screen is "
                    + (screen == null ? "none" : screen.getClass().getName()));
        }
        return screen;
    }

    private static boolean onServer(Object m) throws Exception {
        return get(m, "theWorld") != null && !(Boolean) call(m, "isSingleplayer", NONE);
    }

    private static void requireLocal() throws Exception {
        if (onServer(mc)) {
            throw new DevAgent.Refused("you are on a multiplayer server; agent input is off there"
                    + " (screenshots and hot-swap still work)");
        }
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> restartNeeded(String reason, List<String> classes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("needsRestart", true);
        m.put("reason", reason);
        m.put("classes", classes);
        return m;
    }

    // ---------------------------------------------------------------- reflection

    private static Class<?> gameClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, mcClass.getClassLoader());
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }

    private static Method method(Class<?> c, String name, Class<?>[] types) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }

    private static Object get(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }

    private static Object getStatic(Class<?> c, String name) throws Exception {
        return field(c, name).get(null);
    }

    private static void setStatic(Class<?> c, String name, Object value) throws Exception {
        field(c, name).set(null, value);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        try {
            return method(target.getClass(), name, types).invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            if (c instanceof Error) throw (Error) c;
            throw e;
        }
    }

    private static Object callStatic(Class<?> c, String name) throws Exception {
        try {
            return method(c, name, NONE).invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }
}
