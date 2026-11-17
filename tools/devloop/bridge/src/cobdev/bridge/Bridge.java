package cobdev.bridge;

import com.google.common.util.concurrent.ListenableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.ISaveFormat;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.IntUnaryOperator;

/**
 * The Minecraft-facing half of cobdev: a dev-only Fabric mod, compiled against the ornithe project's
 * MCP-named classpath (so every Minecraft name below is compile-checked), remapped like any mod, and
 * loaded with {@code -Dfabric.addMods} by tools/devloop/start.sh. Never part of the Cobblify jar.
 *
 * <p>The Java agent ({@code tools/devloop/agent}) owns HTTP and hot-swap. It finds this class through
 * the system property {@code cobdev.bridge} (a {@link BiFunction}, so only JDK types cross the class
 * loader boundary) and forwards every game request here.
 */
public final class Bridge {

    private static final String MOD_PREFIX = Policy.MOD_PREFIX;
    private static final long CURSOR_HOLD_MS = 20_000;

    /** Virtual cursor in display pixels (LWJGL origin), held until {@link #cursorUntil}. */
    private static volatile int[] cursor;
    private static volatile long cursorUntil;
    private static final Set<Integer> heldKeys = ConcurrentHashMap.newKeySet();
    /** Raw holds: keyboard state only, re-written every few ms, never the binding (keyhold --raw). */
    private static final Set<Integer> rawKeys = ConcurrentHashMap.newKeySet();

    private Bridge() {
    }

    static void register() {
        BiFunction<String, Map<String, Object>, Object> handler = (path, q) -> {
            try {
                return route(path, q);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        System.getProperties().put("cobdev.bridge", handler);
        startHoldThread();
        System.out.println("[cobdev] bridge ready (mouse backend: " + Input.backend() + ")");
    }

    // ------------------------------------------------------------------------------- routing

    private static Object route(String path, Map<String, Object> q) throws Exception {
        switch (path) {
            case "/status":
                return status();
            case "/main":
                return onMainTask(q);
            case "/shot":
                return shot(intParam(q, "wait", 300));
            case "/reinit":
                return onMain(() -> {
                    if (mc().currentScreen == null) throw new Refused("no screen is open");
                    reinitScreen();
                    return ok();
                });
            case "/open":
                return open(str(q, "class"), q.containsKey("arg") ? Integer.valueOf(str(q, "arg")) : null);
            case "/close":
                return onMain(() -> {
                    requireLocal();
                    mc().displayGuiScreen(null);
                    return ok();
                });
            case "/click":
                return click(intParam(q, "x", -1), intParam(q, "y", -1), intParam(q, "button", 0), (String) q.get("mods"));
            case "/down":
            case "/up":
                return mouse(q, intParam(q, "button", 0), path.equals("/down"), 0);
            case "/drag":
                return mouse(q, -1, false, 0);
            case "/scroll":
                return mouse(intParam(q, "x", -1), intParam(q, "y", -1), -1, false, intParam(q, "amount", 0));
            case "/hover":
                if ("1".equals(q.get("off"))) {
                    cursor = null;
                    return ok();
                }
                return onMain(() -> {
                    holdCursor(toDisplay(intParam(q, "x", -1), intParam(q, "y", -1)));
                    return ok();
                });
            case "/type":
                return onMain(() -> {
                    requireLocal();
                    GuiScreen screen = modScreen();
                    for (char ch : str(q, "text").toCharArray()) keyTyped(screen, ch, 0);
                    return ok();
                });
            case "/key":
                return onMain(() -> {
                    requireLocal();
                    int[] mods = mods(q);
                    String ch = (String) q.get("char");
                    GuiScreen screen = modScreen();
                    boolean[] was = Input.pressKeys(mods);
                    try {
                        keyTyped(screen, ch == null || ch.isEmpty() ? '\0' : ch.charAt(0), intParam(q, "code", 0));
                    } finally {
                        Input.restoreKeys(mods, was);
                    }
                    return ok();
                });
            case "/scale":
                return scale(intParam(q, "n", 0));
            case "/size":
                return size(intParam(q, "w", 0), intParam(q, "h", 0));
            case "/world":
                return "1".equals(q.get("leave")) ? leaveWorld() : world();
            case "/refresh":
                return onMain(() -> {
                    mc().refreshResources();
                    return ok();
                }, 60_000);
            case "/shutdown":
                return onMain(() -> {
                    mc().shutdown();
                    return ok();
                });
            case "/field":
                return field(str(q, "path"), (String) q.get("value"));
            case "/invoke":
                return invoke(str(q, "path"), list(q.get("args")));
            case "/has":
                return Mods.has(str(q, "class"), (String) q.get("member"));
            case "/command":
                return command(str(q, "command"));
            case "/keyhold":
                return keyhold((String) q.get("name"), (String) q.get("state"), "1".equals(q.get("raw")));
            case "/probe":
                return Probes.run(str(q, "name"), list(q.get("args")));
            default:
                throw new Refused("unknown endpoint " + path);
        }
    }

    // ------------------------------------------------------------------ readiness and threading

    static Minecraft mc() {
        Minecraft m = Minecraft.getMinecraft();
        if (m == null || (m.currentScreen == null && m.theWorld == null)) {
            throw new Refused("the game is still starting");
        }
        return m;
    }

    static <T> T onMain(Callable<T> work, long timeoutMs) throws Exception {
        ListenableFuture<T> f = mc().addScheduledTask(work);
        return await(f, timeoutMs, "the game thread");
    }

    static <T> T onMain(Callable<T> work) throws Exception {
        return onMain(work, 10_000);
    }

    static <T> T await(Future<T> f, long timeoutMs, String who) throws Exception {
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(false);
            throw new Refused(who + " did not answer within " + timeoutMs
                    + " ms (frozen, or minimized and throttled by macOS?)");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            if (c instanceof Error) throw (Error) c;
            throw e;
        }
    }

    /** The agent's hot-swap: redefine on the game thread, then rebuild the open screen. */
    private static Object onMainTask(Map<String, Object> q) throws Exception {
        @SuppressWarnings("unchecked")
        Callable<Object> task = (Callable<Object>) q.get("task");
        boolean reinit = "1".equals(q.get("reinit"));
        return onMain(() -> {
            Object r = task.call();
            if (reinit && mc().currentScreen != null) reinitScreen();
            return r;
        });
    }

    // ------------------------------------------------------------------------------ endpoints

    private static Map<String, Object> status() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            mc();
        } catch (Refused e) {
            out.put("ready", false);
            out.put("reason", e.getMessage());
            return out;
        }
        out.put("ready", true);
        try {
            out.putAll(onMain(() -> {
                Map<String, Object> s = new LinkedHashMap<>();
                Minecraft m = mc();
                int[] sr = scaled();
                s.put("screen", m.currentScreen == null ? null : m.currentScreen.getClass().getName());
                s.put("title", m.currentScreen instanceof GuiMainMenu);
                s.put("display", m.displayWidth + "x" + m.displayHeight);
                s.put("gui", sr[0] + "x" + sr[1]);
                s.put("scaleFactor", sr[2]);
                s.put("guiScaleSetting", m.gameSettings.guiScale);
                s.put("inWorld", m.theWorld != null);
                s.put("onServer", onServer(m));
                s.put("cursorHeld", cursor != null && System.currentTimeMillis() < cursorUntil);
                s.put("mouseBackend", Input.backend());
                return s;
            }, 3_000));
            out.put("responsive", true);
        } catch (Refused e) {
            out.put("responsive", false);
            out.put("reason", e.getMessage());
        }
        return out;
    }

    private static Map<String, Object> open(String className, Integer arg) throws Exception {
        Refused.check(Policy.modClass(className));
        return onMain(() -> {
            requireLocal();
            Class<?> c;
            try {
                c = Class.forName(className, true, Bridge.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new Refused(className + " is not in this build");
            }
            if (!GuiScreen.class.isAssignableFrom(c)) throw new Refused(className + " is not a GuiScreen");
            Constructor<?> ctor = arg == null ? c.getDeclaredConstructor() : c.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            mc().displayGuiScreen((GuiScreen) (arg == null ? ctor.newInstance() : ctor.newInstance(arg)));
            return ok();
        });
    }

    private static Map<String, Object> click(int x, int y, int button, String modNames) throws Exception {
        Object[] p = onMain(() -> {
            requireLocal();
            int[] mods = mods(modNames);
            int[] d = toDisplay(x, y);
            holdCursor(d);
            inject(modScreen(), d[0], d[1], button, true, 0, mods);
            return new Object[]{d, mods};
        });
        int[] d = (int[]) p[0];
        int[] mods = (int[]) p[1];
        // Release on the next frame, as a real click would; it goes to whatever screen the press left open.
        return onMain(() -> {
            GuiScreen screen = mc().currentScreen;
            if (screen != null && screen.getClass().getName().startsWith(MOD_PREFIX)) {
                inject(screen, d[0], d[1], button, false, 0, mods);
            }
            return ok();
        });
    }

    /**
     * {@code down}, {@code up} and {@code drag}: X Y are GUI coordinates, or with {@code device=1} display
     * pixels from the top-left, so a scripted drag can move one screen pixel. {@code mods} holds modifier
     * keys for this one event. Button -1 with a button still held in the screen is vanilla's drag event
     * (mouseClickMove).
     */
    private static Map<String, Object> mouse(Map<String, Object> q, int button, boolean down, int notches) throws Exception {
        int x = intParam(q, "x", -1);
        int y = intParam(q, "y", -1);
        boolean device = "1".equals(q.get("device"));
        return onMain(() -> {
            requireLocal();
            int[] mods = mods(q);
            int[] d;
            if (device) {
                Minecraft m = mc();
                Refused.check(Policy.device(x, y, m.displayWidth, m.displayHeight));
                d = new int[]{x, m.displayHeight - 1 - y}; // LWJGL origin is the bottom-left
            } else {
                d = toDisplay(x, y);
            }
            holdCursor(d);
            inject(modScreen(), d[0], d[1], button, down, notches, mods);
            return ok();
        });
    }

    private static Map<String, Object> mouse(int x, int y, int button, boolean down, int notches) throws Exception {
        return onMain(() -> {
            requireLocal();
            int[] d = toDisplay(x, y);
            holdCursor(d);
            inject(modScreen(), d[0], d[1], button, down, notches, new int[0]);
            return ok();
        });
    }

    /** The {@code mods} parameter's key codes (game thread: the server check reads the client state). */
    private static int[] mods(Map<String, Object> q) {
        return mods((String) q.get("mods"));
    }

    private static int[] mods(String names) {
        Minecraft m = mc();
        return Policy.modifiers(names, m.theWorld != null, m.isSingleplayer());
    }

    private static Map<String, Object> scale(int n) throws Exception {
        if (n < 0 || n > 4) throw new Refused("GUI scale is 0 (auto) to 4");
        return onMain(() -> {
            mc().gameSettings.guiScale = n;
            if (mc().currentScreen != null) reinitScreen();
            return ok();
        });
    }

    /** Resize the game window to {@code w} x {@code h} display px, the way vanilla's fullscreen toggle does. */
    private static Map<String, Object> size(int w, int h) throws Exception {
        if (w < 320 || h < 240) throw new Refused("the window must be at least 320x240");
        return onMain(() -> {
            Display.setLocation(0, 0);
            Display.setDisplayMode(new DisplayMode(w, h));
            // Minecraft.resize is private; looked up by its dev name, so this endpoint is dev-only.
            Method resize;
            try {
                resize = Minecraft.class.getDeclaredMethod("resize", int.class, int.class);
            } catch (NoSuchMethodException e) {
                throw new Refused("size needs the dev (MCP-named) client");
            }
            resize.setAccessible(true);
            resize.invoke(mc(), w, h);
            Map<String, Object> out = ok();
            out.put("window", Display.getWidth() + "x" + Display.getHeight() + " at " + Display.getX() + "," + Display.getY());
            out.put("desktop", String.valueOf(Display.getDesktopDisplayMode()));
            try {
                out.put("pixelScale", Display.class.getMethod("getPixelScaleFactor").invoke(null));
            } catch (NoSuchMethodException e) {
                out.put("pixelScale", null); // Pylon's Display has none
            }
            return out;
        });
    }

    /**
     * Note: scheduled tasks run while the game holds its task-queue lock, and launchIntegratedServer
     * waits for the server thread. A server-thread handler that called Minecraft.addScheduledTask
     * would block on that lock and hang the game.
     */
    private static Map<String, Object> world() throws Exception {
        return onMain(() -> {
            requireLocal();
            Minecraft m = mc();
            // Stable frames for screenshot diffs: no moving clouds, no view bobbing.
            m.gameSettings.clouds = 0; // 0 = off in 1.8.9
            m.gameSettings.viewBobbing = false;
            if (m.theWorld != null) {
                // Report which save is open: the smoke refuses to edit any save but its own.
                Map<String, Object> open = ok();
                IntegratedServer server = m.getIntegratedServer();
                open.put("save", server == null ? null : server.getFolderName());
                return open;
            }
            ISaveFormat saves = m.getSaveLoader();
            WorldSettings settings = null;
            if (!saves.canLoadWorld("cobdev")) {
                settings = new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT);
                settings.enableCommands();
            }
            m.launchIntegratedServer("cobdev", "Cobblify Dev", settings);
            Map<String, Object> opened = ok();
            opened.put("save", "cobdev");
            return opened;
        }, 90_000);
    }

    /**
     * Leaves the singleplayer world for the title screen, as Save and Quit does. Only the smoke's own
     * save; the integrated server finishes saving on its own thread afterwards, so wait a few seconds
     * before {@code /world} opens it again.
     */
    private static Map<String, Object> leaveWorld() throws Exception {
        return onMain(() -> {
            requireLocal();
            Minecraft m = mc();
            if (m.theWorld == null) return ok();
            IntegratedServer server = m.getIntegratedServer();
            String save = server == null ? null : server.getFolderName();
            if (!"cobdev".equals(save)) throw new Refused("the open world is save " + save + ", not cobdev; only cobdev's own save is left");
            m.theWorld.sendQuittingDisconnectingPacket();
            m.loadWorld(null);
            m.displayGuiScreen(new GuiMainMenu());
            return ok();
        }, 60_000);
    }

    private static Map<String, Object> field(String path, String value) throws Exception {
        return onMain(() -> {
            requireLocal();
            return value == null ? Mods.get(path) : Mods.set(path, value);
        });
    }

    private static Map<String, Object> invoke(String path, List<String> args) throws Exception {
        return onMain(() -> {
            requireLocal();
            return Mods.invoke(path, args);
        });
    }

    private static Map<String, Object> command(String command) throws Exception {
        Refused.check(Policy.command(command));
        return onMain(() -> {
            requireSingleplayerWorld();
            mc().thePlayer.sendChatMessage(command);
            return ok();
        });
    }

    /**
     * Holds (or releases) a key binding Cobblify registered, re-applied every few ms until released. With
     * {@code raw}, holds the key's keyboard state instead and never touches the binding: what a real press
     * and release produce while a screen is open (Edit HUD reads Tab and the movement keys that way).
     */
    private static Map<String, Object> keyhold(String name, String state, boolean raw) throws Exception {
        return onMain(() -> {
            Minecraft m = mc();
            // Releasing is always allowed: a hold must be endable from anywhere (review I2).
            Refused.check(Policy.keyhold(state, m.theWorld != null, m.isSingleplayer()));
            List<Object> listed = new ArrayList<>();
            KeyBinding match = null;
            for (KeyBinding kb : mc().gameSettings.keyBindings) {
                boolean holdable = Policy.keyBinding(kb.getKeyCategory(), kb.getKeyDescription(), raw) == null;
                if (holdable && name != null && kb.getKeyDescription().equalsIgnoreCase(name)) match = kb;
                if (Policy.keyBinding(kb.getKeyCategory(), kb.getKeyDescription()) != null) continue;
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("name", kb.getKeyDescription());
                k.put("code", kb.getKeyCode());
                k.put("down", kb.isKeyDown());
                listed.add(k);
            }
            Map<String, Object> out = ok();
            out.put("bindings", listed);
            if (name == null || name.isEmpty()) return out; // list only
            if (match == null) {
                throw new Refused(raw
                        ? "no key binding named " + name + " a raw hold may use (Cobblify's, key.playerlist, "
                                + Policy.RAW_MOVEMENT_KEYS + ")"
                        : "no Cobblify key binding named " + name + " (category " + Policy.KEY_CATEGORY
                                + "; registered: " + listed.size() + ")");
            }
            if (match.getKeyCode() == 0) throw new Refused(name + " is unbound in Controls");
            boolean on = "on".equals(state);
            if (!on && !"off".equals(state)) throw new Refused("keyhold NAME on|off");
            if (raw) {
                Refused.check(Policy.rawKeyCode(name, match.getKeyCode()));
                if (on) rawKeys.add(match.getKeyCode());
                else rawKeys.remove(match.getKeyCode());
                Input.rawKey(match.getKeyCode(), on);
                out.put("raw", true);
                out.put("held", on);
                out.put("keyDown", Input.keyDown(match.getKeyCode()));
                out.put("down", match.isKeyDown()); // the binding: a raw hold never sets it
                return out;
            }
            if (on) heldKeys.add(match.getKeyCode());
            else heldKeys.remove(match.getKeyCode());
            KeyBinding.setKeyBindState(match.getKeyCode(), on);
            // A real key-down also counts one press (vanilla runTick: setKeyBindState, then onTick), which
            // isPressed() consumes: Toggle Sprint reacts to presses, Zoom to the held state.
            if (on) KeyBinding.onTick(match.getKeyCode());
            out.put("held", on);
            out.put("down", match.isKeyDown());
            return out;
        });
    }

    /** The last fully rendered frame, as PNG, after waiting {@code waitMs} for animations to settle. */
    private static byte[] shot(int waitMs) throws Exception {
        onMain(() -> null);
        if (waitMs > 0) Thread.sleep(Math.min(waitMs, 10_000));
        return png(onMain(Bridge::readFrame));
    }

    /**
     * The main framebuffer as {width, height, BGRA pixels bottom row first}. Game thread only: called from a scheduled
     * task, where the main framebuffer still holds the whole previous frame (Motion Blur's {@code mb-shot} relies on it).
     */
    static Object[] readFrame() {
        if (!OpenGlHelper.isFramebufferEnabled()) {
            throw new Refused("framebuffers are off in video settings; screenshots need them");
        }
        Framebuffer fb = mc().getFramebuffer();
        int w = fb.framebufferWidth;
        int h = fb.framebufferHeight;
        // At this point in the loop the main framebuffer still holds the whole previous frame.
        IntBuffer buf = BufferUtils.createIntBuffer(w * h);
        fb.bindFramebuffer(false);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        fb.unbindFramebuffer();
        int[] px = new int[w * h];
        buf.get(px);
        return new Object[]{w, h, px};
    }

    /** A {@link #readFrame} result as PNG (any thread). */
    static byte[] png(Object[] frame) throws Exception {
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

    // ------------------------------------------------------------------------ input plumbing

    private static void startHoldThread() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Minecraft m = Minecraft.getMinecraft();
                    boolean holdCursor = cursor != null && System.currentTimeMillis() < cursorUntil;
                    if (m != null && (holdCursor || !heldKeys.isEmpty() || !rawKeys.isEmpty())) {
                        m.addScheduledTask(() -> {
                            try {
                                if (!Policy.keepHolding(m.theWorld != null, m.isSingleplayer())) {
                                    releaseAll();
                                    return;
                                }
                                int[] c = cursor;
                                if (c != null && System.currentTimeMillis() < cursorUntil) Input.pointer(c[0], c[1]);
                                for (int code : heldKeys) KeyBinding.setKeyBindState(code, true);
                                // poll() refills the keyboard buffer every frame; Pylon also drops all
                                // keys when the window loses focus.
                                for (int code : rawKeys) Input.rawKey(code, true);
                            } catch (Exception ignored) {
                                // the next round tries again
                            }
                        }).get(1, TimeUnit.SECONDS);
                    }
                    Thread.sleep(4);
                } catch (Throwable e) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "cobdev-hold");
        t.setDaemon(true);
        t.start();
    }

    /** Ends every hold (keys, raw keys and cursor): runs on the game thread when the client reaches a server. */
    private static void releaseAll() {
        for (Integer code : heldKeys) KeyBinding.setKeyBindState(code, false);
        heldKeys.clear();
        for (Integer code : rawKeys) {
            try {
                Input.rawKey(code, false);
            } catch (Exception ignored) {
                // the server poll refills the buffer from the real keyboard anyway
            }
        }
        rawKeys.clear();
        cursor = null;
    }

    static void holdCursor(int[] display) throws Exception {
        cursor = display;
        cursorUntil = System.currentTimeMillis() + CURSOR_HOLD_MS;
        Input.pointer(display[0], display[1]);
    }

    /** One mouse event into the screen (B's overlay probes call this; no modifier keys). */
    static void inject(GuiScreen screen, int x, int y, int button, boolean pressed, int notches) throws Exception {
        inject(screen, x, y, button, pressed, notches, new int[0]);
    }

    /** One mouse event into the screen, with {@code mods} held for exactly this event (released in finally). */
    static void inject(GuiScreen screen, int x, int y, int button, boolean pressed, int notches, int[] mods)
            throws Exception {
        boolean[] was = Input.pressKeys(mods);
        try {
            Input.event(x, y, button, pressed, notches);
            screen.handleMouseInput();
        } finally {
            Input.clearWheel();
            Input.restoreKeys(mods, was);
        }
    }

    /** {@code GuiScreen.keyTyped(char,int)} is protected: found by signature, so it works in dev and prod. */
    private static void keyTyped(GuiScreen screen, char ch, int code) throws Exception {
        Method m = Probes.unique(GuiScreen.class, void.class, char.class, int.class);
        try {
            m.invoke(screen, ch, code);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw e;
        }
    }

    /** GUI coordinates to the display pixel vanilla maps back onto them (middle of the run). */
    static int[] toDisplay(int gx, int gy) {
        Minecraft m = mc();
        int dw = m.displayWidth;
        int dh = m.displayHeight;
        int[] sr = scaled();
        int sw = sr[0];
        int sh = sr[1];
        int x = middle(v -> v * sw / dw, gx, dw);
        int y = middle(v -> sh - v * sh / dh - 1, gy, dh);
        if (x < 0 || y < 0) throw new Refused("(" + gx + "," + gy + ") is outside the " + sw + "x" + sh + " GUI");
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

    private static int[] scaled() {
        ScaledResolution sr = new ScaledResolution(mc());
        return new int[]{sr.getScaledWidth(), sr.getScaledHeight(), sr.getScaleFactor()};
    }

    private static void reinitScreen() {
        int[] sr = scaled();
        mc().currentScreen.setWorldAndResolution(mc(), sr[0], sr[1]);
    }

    private static GuiScreen modScreen() {
        GuiScreen screen = mc().currentScreen;
        if (screen == null || !screen.getClass().getName().startsWith(MOD_PREFIX)) {
            throw new Refused("input only goes to Cobblify screens; the current screen is "
                    + (screen == null ? "none" : screen.getClass().getName()));
        }
        return screen;
    }

    static boolean onServer(Minecraft m) {
        return m.theWorld != null && !m.isSingleplayer();
    }

    static void requireLocal() {
        Minecraft m = mc();
        Refused.check(Policy.local(m.theWorld != null, m.isSingleplayer()));
    }

    static void requireSingleplayerWorld() {
        Minecraft m = mc();
        Refused.check(Policy.singleplayerWorld(m.theWorld != null, m.isSingleplayer(),
                m.getIntegratedServer() != null && m.thePlayer != null));
    }

    // ------------------------------------------------------------------------------- helpers

    static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static String str(Map<String, Object> q, String key) {
        Object v = q.get(key);
        if (v == null || v.toString().isEmpty()) throw new Refused("missing parameter: " + key);
        return v.toString();
    }

    private static int intParam(Map<String, Object> q, String key, int def) {
        Object v = q.get(key);
        return v == null || v.toString().isEmpty() ? def : Integer.parseInt(v.toString());
    }

    /** Arguments arrive as one string separated by the unit separator (U+001F), which no argument contains. */
    private static List<String> list(Object v) {
        if (v == null || v.toString().isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(v.toString().split("\u001f", -1)));
    }
}
