package com.bedwarsqol.rewind;

import com.bedwarsqol.hud.TabListVisibility;
import com.bedwarsqol.mixin.EntityRendererFovInvoker;
import com.bedwarsqol.mixin.GuiChatRewindAccessor;
import com.bedwarsqol.rewind.watch.PlaybackGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.scoreboard.Scoreboard;
import org.lwjgl.input.Mouse;

/**
 * The per-frame half of Rewind's format v2 client state (PLAN section A, Phase 3): VIEW, MOUSE, CHAT_INPUT, FOV and
 * INPUT, each written only when it changed, plus TAP for each mouse press the Keystrokes HUD is told about. Read only.
 *
 * <ul>
 *   <li><b>VIEW</b>: perspective (F5), hidden HUD (F1), F3, the tab list showing (vanilla's rule, as
 *       {@code TabHudGate} asks it), smooth camera, and the open screen's kind ({@link #screenKind}).</li>
 *   <li><b>MOUSE</b>: only while a screen is open; the cursor's offset from the screen centre in quarter GUI pixels
 *       ({@link #mouseDx}, {@link #mouseDy}), at most every {@link #MOUSE_INTERVAL_NS}. The first frame of an open
 *       screen writes it at once; a position that changed too soon is written once the interval has passed.</li>
 *   <li><b>CHAT_INPUT</b>: only while chat is open, the input text and cursor, measured from an empty bar.</li>
 *   <li><b>FOV</b>: the final world FOV through {@code EntityRenderer.getFOVModifier(partial, true)}
 *       ({@link EntityRendererFovInvoker}), so every mod's change (Zoom, Dynamic FOV, potions, bow, water) is in it;
 *       read at most every {@link #FOV_INTERVAL_NS}, as the camera sample.</li>
 *   <li><b>INPUT</b>: W, A, S, D, jump and the two mouse buttons as the Keystrokes HUD reads them.</li>
 *   <li><b>TAP</b>: {@link #mousePressed} is the receiving end of {@code BedwarsHudRenderer.onMouse}: one record per
 *       press, stamped when the event is processed, so a click shorter than a frame is still stored.</li>
 * </ul>
 * VIEW and FOV are stated on a segment's first frame; INPUT is measured from nothing held.
 *
 * <p><b>Cost.</b> Client thread only. The capture itself allocates nothing per frame, changed or not; the FOV read
 * runs vanilla's {@code getFOVModifier} (which allocates a {@code BlockPos} for its water check, and Cobblify's Zoom
 * injector its callback info) at most every 4 ms.
 *
 * <p><b>Wiring.</b> Inert until a sink is {@link #install installed} (Phase 6): every entry point returns at once with
 * no sink or while a recording plays. Phase 6 calls {@link #resetSegment()} when a TRACK segment starts and
 * {@link #frame(Minecraft, float, long)} once per rendered frame of a recorded segment; Phase 4 calls
 * {@link #mousePressed(int)} from the HUD's mouse listener.
 */
public final class ViewCapture {

    /** MOUSE is written at most this often while the cursor moves. */
    static final long MOUSE_INTERVAL_NS = 8_000_000L;
    /** The FOV is read at most this often (the camera sample's limit). */
    static final long FOV_INTERVAL_NS = 4_000_000L;

    private static StateCapture.Sink sink;
    private static final ViewCapture INSTANCE = new ViewCapture();
    private static final Frame FRAME = new Frame();

    /** Installs (or, with null, removes) the sink. Client thread. */
    public static void install(StateCapture.Sink s) {
        sink = s;
    }

    /** A TRACK segment starts: the next frame states VIEW and FOV again; INPUT, MOUSE and chat start over. */
    public static void resetSegment() {
        INSTANCE.reset();
    }

    /** Once per rendered frame of a recorded segment, after the camera sample's partial ticks are known. */
    public static void frame(Minecraft mc, float partialTicks, long nanoTime) {
        StateCapture.Sink s = sink;
        if (s == null || PlaybackGate.muted() || mc == null) {
            return;
        }
        try {
            GameSettings gs = mc.gameSettings;
            if (gs == null) {
                return;
            }
            GuiScreen screen = mc.currentScreen;
            Frame f = FRAME;
            f.nanoTime = nanoTime;
            f.perspective = gs.thirdPersonView;
            f.flags = (gs.hideGUI ? RewindFormat.VIEW_HIDE_GUI : 0)
                    | (gs.showDebugInfo ? RewindFormat.VIEW_F3 : 0)
                    | (tabListShowing(mc, gs) ? RewindFormat.VIEW_TAB : 0)
                    | (gs.smoothCamera ? RewindFormat.VIEW_SMOOTH_CAMERA : 0);
            f.screenKind = screenKind(screen);
            f.keys = keys(gs, screen == null);
            f.fovRead = mc.entityRenderer != null && INSTANCE.fovDue(nanoTime);
            if (f.fovRead) {
                f.fov = ((EntityRendererFovInvoker) mc.entityRenderer).bedwarsqol$getFOVModifier(partialTicks, true);
            }
            f.screenOpen = screen != null;
            if (screen != null && mc.displayWidth > 0 && mc.displayHeight > 0) {
                f.mouseDx = mouseDx(Mouse.getX(), mc.displayWidth, screen.width);
                f.mouseDy = mouseDy(Mouse.getY(), mc.displayHeight, screen.height);
            }
            f.chatOpen = screen instanceof GuiChat;
            if (f.chatOpen) {
                GuiTextField field = ((GuiChatRewindAccessor) screen).bedwarsqol$getInputField();
                f.chatText = field == null ? "" : field.getText();
                f.chatCursor = field == null ? 0 : field.getCursorPosition();
            } else {
                f.chatText = null;
                f.chatCursor = 0;
            }
            INSTANCE.frame(s, f);
        } catch (Throwable t) {
            s.failed("view", t);
        }
    }

    /**
     * A mouse press the Keystrokes HUD was told about ({@code BedwarsHudRenderer.onMouse}, button 0 left, 1 right):
     * one TAP record now. Other buttons are not stored. Inert with no sink or while a recording plays.
     */
    public static void mousePressed(int button) {
        StateCapture.Sink s = sink;
        if (s == null || PlaybackGate.muted()) {
            return;
        }
        INSTANCE.tap(s, button);
    }

    /** Vanilla's tab-list rule, asked as {@code TabHudGate} asks it ({@link TabListVisibility#showing}). */
    private static boolean tabListShowing(Minecraft mc, GameSettings gs) {
        if (mc.thePlayer == null || mc.theWorld == null || gs.keyBindPlayerList == null
                || !gs.keyBindPlayerList.isKeyDown()) {
            return false;
        }
        NetHandlerPlayClient net = mc.thePlayer.sendQueue;
        int players = net == null ? 0 : net.getPlayerInfoMap().size();
        Scoreboard board = mc.theWorld.getScoreboard();
        boolean listObjective = board != null && board.getObjectiveInDisplaySlot(0) != null;
        return TabListVisibility.showing(true, mc.isIntegratedServerRunning(), players, listObjective);
    }

    /** The held state the Keystrokes HUD reads: movement binds, and the physical mouse buttons only in game. */
    private static int keys(GameSettings gs, boolean inGame) {
        int k = 0;
        if (down(gs.keyBindForward)) k |= RewindFormat.INPUT_W;
        if (down(gs.keyBindLeft)) k |= RewindFormat.INPUT_A;
        if (down(gs.keyBindBack)) k |= RewindFormat.INPUT_S;
        if (down(gs.keyBindRight)) k |= RewindFormat.INPUT_D;
        if (down(gs.keyBindJump)) k |= RewindFormat.INPUT_JUMP;
        if (inGame && Mouse.isButtonDown(0)) k |= RewindFormat.INPUT_LMB;
        if (inGame && Mouse.isButtonDown(1)) k |= RewindFormat.INPUT_RMB;
        return k;
    }

    private static boolean down(KeyBinding kb) {
        return kb != null && kb.isKeyDown();
    }

    // ------------------------------------------------------------------ pure parts (tests)

    private static Class<?> kindClass;
    private static int kindOfClass;

    /** The VIEW screen kind of {@code screen} ({@code RewindFormat.SCREEN_*}). */
    static int screenKind(GuiScreen screen) {
        if (screen == null) {
            return RewindFormat.SCREEN_NONE;
        }
        Class<?> c = screen.getClass();
        if (c != kindClass) {
            kindOfClass = screenKind(c);
            kindClass = c;
        }
        return kindOfClass;
    }

    /** The VIEW screen kind of a screen class. */
    static int screenKind(Class<?> c) {
        if (c == null) {
            return RewindFormat.SCREEN_NONE;
        }
        if (GuiChat.class.isAssignableFrom(c)) return RewindFormat.SCREEN_CHAT;
        if (GuiInventory.class.isAssignableFrom(c)) return RewindFormat.SCREEN_INVENTORY;
        if (GuiChest.class.isAssignableFrom(c)) return RewindFormat.SCREEN_CHEST;
        if (GuiContainer.class.isAssignableFrom(c)) return RewindFormat.SCREEN_OTHER_CONTAINER;
        if (GuiGameOver.class.isAssignableFrom(c)) return RewindFormat.SCREEN_DEATH;
        if (GuiIngameMenu.class.isAssignableFrom(c)) return RewindFormat.SCREEN_ESC_MENU;
        if (c.getName().startsWith("com.bedwarsqol.")) return RewindFormat.SCREEN_COBBLIFY;
        return RewindFormat.SCREEN_OTHER;
    }

    /**
     * The cursor's x offset from the screen centre in quarter GUI pixels: {@code floor((2 x scaledW + dx) / 4)} is
     * the x vanilla hands {@code drawScreen} ({@code Mouse.getX() * scaledW / displayW}).
     */
    static int mouseDx(int mouseX, int displayW, int scaledW) {
        long q = (long) mouseX * scaledW * RewindFormat.MOUSE_UNITS_PER_GUI_PX / displayW;
        return (int) (q - 2L * scaledW);
    }

    /**
     * The cursor's y offset from the screen centre in quarter GUI pixels, downwards: {@code floor((2 x scaledH + dy)
     * / 4)} is the y vanilla hands {@code drawScreen} ({@code scaledH - Mouse.getY() * scaledH / displayH - 1}).
     */
    static int mouseDy(int mouseY, int displayH, int scaledH) {
        long q = (long) mouseY * scaledH * RewindFormat.MOUSE_UNITS_PER_GUI_PX / displayH;
        long y4 = (long) RewindFormat.MOUSE_UNITS_PER_GUI_PX * scaledH - 1 - q;
        return (int) (y4 - 2L * scaledH);
    }

    // ------------------------------------------------------------------ instance (testable without a client)

    /** One frame's inputs, filled by {@link #frame(Minecraft, float, long)} (reused, never allocated per frame). */
    static final class Frame {
        long nanoTime;
        int perspective;
        int flags;
        int screenKind;
        int keys;
        /** The FOV was read this frame ({@link #fovDue}); {@link #fov} holds it. */
        boolean fovRead;
        float fov;
        boolean screenOpen;
        int mouseDx;
        int mouseDy;
        boolean chatOpen;
        String chatText;
        int chatCursor;
    }

    private final ExtCodec codec = new ExtCodec(64);

    private boolean viewKnown;
    private int lastPerspective;
    private int lastFlags;
    private int lastKind;

    private boolean fovKnown;
    private int lastFovBits;
    private boolean fovReadOnce;
    private long lastFovReadNs;

    private int lastKeys;

    private boolean mouseKnown;
    private int lastDx;
    private int lastDy;
    private long lastMouseNs;

    private boolean chatKnown;
    private String lastText;
    private int lastCursor;

    void reset() {
        viewKnown = false;
        fovKnown = false;
        fovReadOnce = false;
        lastKeys = 0;
        mouseKnown = false;
        chatKnown = false;
        lastText = null;
    }

    /** True when the FOV should be read this frame (at most every {@link #FOV_INTERVAL_NS}); marks the read. */
    boolean fovDue(long nanoTime) {
        if (fovReadOnce && nanoTime - lastFovReadNs < FOV_INTERVAL_NS) {
            return false;
        }
        fovReadOnce = true;
        lastFovReadNs = nanoTime;
        return true;
    }

    /** One frame. Returns false when the sink failed or threw (a throw is reported to {@link StateCapture.Sink#failed}). */
    boolean frame(StateCapture.Sink s, Frame f) {
        try {
            return view(s, f) && fov(s, f) && input(s, f) && mouse(s, f) && chat(s, f);
        } catch (Throwable t) {
            s.failed("view", t);
            return false;
        }
    }

    /** One TAP for mouse button 0 (left) or 1 (right); other buttons write nothing. */
    boolean tap(StateCapture.Sink s, int button) {
        try {
            int b = button == 0 ? RewindFormat.TAP_LMB : button == 1 ? RewindFormat.TAP_RMB : -1;
            if (b < 0) {
                return true;
            }
            int n = codec.tap(b);
            return s.append(RewindFormat.TAG_TAP, codec.buffer(), n);
        } catch (Throwable t) {
            s.failed("tap", t);
            return false;
        }
    }

    private boolean view(StateCapture.Sink s, Frame f) {
        if (viewKnown && f.perspective == lastPerspective && f.flags == lastFlags && f.screenKind == lastKind) {
            return true;
        }
        viewKnown = true;
        lastPerspective = f.perspective;
        lastFlags = f.flags;
        lastKind = f.screenKind;
        int n = codec.view(f.perspective, f.flags, f.screenKind);
        return s.append(RewindFormat.TAG_VIEW, codec.buffer(), n);
    }

    private boolean fov(StateCapture.Sink s, Frame f) {
        if (!f.fovRead) {
            return true;
        }
        int bits = Float.floatToIntBits(f.fov);
        if (fovKnown && bits == lastFovBits) {
            return true;
        }
        fovKnown = true;
        lastFovBits = bits;
        int n = codec.fov(f.fov);
        return s.append(RewindFormat.TAG_FOV, codec.buffer(), n);
    }

    private boolean input(StateCapture.Sink s, Frame f) {
        if (f.keys == lastKeys) {
            return true;
        }
        lastKeys = f.keys;
        int n = codec.input(f.keys);
        return s.append(RewindFormat.TAG_INPUT, codec.buffer(), n);
    }

    private boolean mouse(StateCapture.Sink s, Frame f) {
        if (!f.screenOpen) {
            mouseKnown = false;
            return true;
        }
        if (mouseKnown) {
            if (f.mouseDx == lastDx && f.mouseDy == lastDy) {
                return true;
            }
            if (f.nanoTime - lastMouseNs < MOUSE_INTERVAL_NS) {
                return true; // written once the interval has passed, if still different
            }
        }
        mouseKnown = true;
        lastDx = f.mouseDx;
        lastDy = f.mouseDy;
        lastMouseNs = f.nanoTime;
        int n = codec.mouse(f.mouseDx, f.mouseDy);
        return s.append(RewindFormat.TAG_MOUSE, codec.buffer(), n);
    }

    private boolean chat(StateCapture.Sink s, Frame f) {
        if (!f.chatOpen) {
            chatKnown = false;
            lastText = null;
            return true;
        }
        String text = f.chatText == null ? "" : f.chatText;
        if (!chatKnown) {
            chatKnown = true; // measured from the empty bar a new GuiChat starts with
            lastText = "";
            lastCursor = 0;
        }
        if (f.chatCursor == lastCursor && (text == lastText || text.equals(lastText))) {
            lastText = text;
            return true;
        }
        lastText = text;
        lastCursor = f.chatCursor;
        int n = codec.chatInput(text, f.chatCursor);
        return s.append(RewindFormat.TAG_CHAT_INPUT, codec.buffer(), n);
    }
}
