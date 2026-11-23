package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.gui.sheet.McCanvas;
import com.bedwarsqol.gui.sheet.SheetColors;
import com.bedwarsqol.rewind.export.ExportJob;
import com.bedwarsqol.rewind.export.ExportPanelModel;
import com.bedwarsqol.rewind.export.ExportRange;
import com.bedwarsqol.rewind.export.ExportSettings;
import com.bedwarsqol.rewind.export.RewindExport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * The viewer (G36, G37, G43, G45; TASK A8, A9, A10, A11): the recorded world with the bar over it. Layout and
 * hit tests are {@link ViewerLayout}'s, drawing {@link RewindPainter#bar} and {@link RewindPainter#flySpeed}, in GUI px
 * (one sheet unit per GUI pixel); keys are {@link ViewerKeys}'s. The bar sits at the bottom edge (it covers the
 * hotbar only while it shows) and fades 2 s after the mouse stops ({@link BarFade}). The playback parts draw
 * in two slots ({@link RewindPlayback#drawUnder} before the bar, {@link RewindPlayback#drawOver} after it).
 *
 * <ul>
 *   <li>Keys: Space (your view) and K play/pause; Left/Right 5 s, with Shift 30 s; comma/period previous/next marker
 *       ({@link MarkerNav}: 3 s before it, the marker highlighted); {@code [ ]} slower/faster; 1 your view, 2 free
 *       camera; Esc closes the camera menu, else lets the free camera's cursor go, else opens the Esc menu
 *       ({@link RewindEscScreen}); Tab held shows the
 *       recorded tab list (it holds the player-list binding); F5 cycles your view's perspective; F1 and the Clean
 *       View Key (a key or a mouse button) toggle the clean view ({@link ViewState}: no HUD, hand or recorded screen;
 *       the bar still shows on mouse move); 3 and Shift+3 ride the next and previous player (a
 *       playback part answers: {@link RiddenView}, Phase 14).</li>
 *   <li>Mouse: the bar's keys, marker icons and timeline ({@link ViewerLayout.Pointer}: a drag previews, the release
 *       seeks); right-click on the speed key steps down. The free camera holds the cursor as the game does: the
 *       mouse looks around, the wheel sets the flight speed, WASD, Space and Shift fly (Q1), and a click on the
 *       player under the crosshair rides them ({@link PickRay}). Esc lets the cursor go for the bar (every viewer
 *       key works either way); a click on the world then rides the player under the cursor, else takes the cursor
 *       back ({@link Controls#grabWanted}).</li>
 *   <li>Camera menu (Phase 14): Free camera, You, then every player another player's view can ride, each with their
 *       face and their name in their team's colour ({@link RiddenView#players()}); while a player is ridden the key
 *       shows them and their name sits in their team's colour at the top (not in the clean view).</li>
 * </ul>
 *
 * <p><b>Export mode</b> (Rewind Export TASK U1-U6): opened by the Esc menu's Export ({@link #openExport}) or the
 * gallery's ({@link #requestExport}: the game opens paused at its first marker). The bar stays up with the range's two
 * handles on the timeline ({@link ViewerLayout#export}, dragged through {@link ViewerLayout.Pointer}) and the export
 * panel above it at the right ({@link ExportPanelModel}, drawn by {@link RewindPainter#exportPanel}); seeking and the
 * camera keys still work. The panel's Export key starts {@link RewindExport}; while it runs nothing but Esc does
 * anything (Esc cancels), and the key then reads Open Folder, Failed, or Export again after a cancel. Esc: a running
 * export is cancelled first, then the camera menu closes, the free camera's cursor is let go, export mode is left,
 * and only then does the Esc menu open.
 *
 * <p>The session opens a new viewer after every rebuild behind the loading card (and after an export); what must
 * outlive one screen (marker cursor, bar fade, camera menu, export mode) lives in {@link Controls}, one per loaded
 * timeline.
 */
public final class RewindViewerScreen extends RewindScreen {

    private static final RewindPlayback.Screens SCREENS = new RewindPlayback.Screens() {
        @Override
        public GuiScreen loading(PlaybackSession session) {
            return new RewindLoadingScreen(session);
        }

        @Override
        public GuiScreen viewer(PlaybackSession session) {
            return new RewindViewerScreen(session);
        }
    };

    /** The last session's controls (keyed by its timeline, so a rebuild keeps them and a new game does not). */
    private static Controls last;

    private final PlaybackSession session;
    private final Controls controls;
    private final McCanvas canvas = new McCanvas();
    private ViewerLayout layout;
    private int scale = 1;
    /** The screen in GUI px, as the last frame laid it out. */
    private float screenW, screenH;
    private long lastFrameNanos;
    private boolean mouseSeen;
    private int lastMouseX, lastMouseY;
    private int accent = -1;
    private SheetColors.Shades accentShades = SheetColors.Shades.of(0xC0DCFF);
    /** The export panel as the last frame laid it out (null outside export mode). */
    private ExportPanelModel.Layout panelLayout;
    /** The left button went down on the panel: its release is the panel's. */
    private boolean panelPress;

    RewindViewerScreen(PlaybackSession session) {
        this.session = session;
        this.controls = controlsFor(session);
    }

    /** The Esc menu's Export: this viewer in export mode (TASK U1). */
    void openExport() {
        controls.openExport();
    }

    /**
     * The gallery's Export (TASK U2): the next viewer of recording {@code recordingId} opens in export mode, paused at
     * its first marker; null forgets the request.
     */
    static void requestExport(String recordingId) {
        Controls.exportRequest = recordingId;
    }

    /** The gallery's pending request, or null. */
    static String exportRequest() {
        return Controls.exportRequest;
    }

    /** Installs the loading card and the viewer as the session's screens. Call once, on the client thread. */
    public static void register() {
        RewindPlayback.setScreens(SCREENS);
    }

    /** The screens {@link #register} installs. */
    static RewindPlayback.Screens screens() {
        return SCREENS;
    }

    private static Controls controlsFor(PlaybackSession session) {
        Controls c = last;
        if (c == null || c.session != session || c.timeline != session.timeline()) {
            c = new Controls(session);
            last = c;
        }
        return c;
    }

    // ------------------------------------------------------------------ screen

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(false);
        lastFrameNanos = 0;
        mouseSeen = false;
    }

    @Override
    public void onGuiClosed() {
        holdCursor(false);
        controls.pointer.cancel();
        holdTabList(false);
    }

    @Override
    protected void drawRewind(int mouseX, int mouseY, float partialTicks) {
        long nowMs = System.nanoTime() / 1_000_000L;
        holdTabList(Keyboard.isKeyDown(ViewerKeys.KEY_TAB));
        fly();
        Timeline tl = session.timeline();
        if (tl == null) {
            return;
        }
        scale = new ScaledResolution(mc).getScaleFactor();
        float sw = mc.displayWidth / (float) scale, sh = mc.displayHeight / (float) scale;
        screenW = sw;
        screenH = sh;
        boolean grabbed = holdCursor(controls.grabWanted() && Display.isActive());
        if (grabbed) { // the mouse look, read as vanilla's MouseHelper reads it: once a frame
            int dx = Mouse.getDX(), dy = Mouse.getDY();
            if (dx != 0 || dy != 0) {
                ViewerCameraControls.get().look(dx, dy);
            }
        }
        int rawX = Mouse.getX(), rawY = Mouse.getY();
        if (!grabbed && mouseSeen && (rawX != lastMouseX || rawY != lastMouseY)) {
            controls.fade.mouseMoved(nowMs);
        }
        mouseSeen = !grabbed;
        lastMouseX = rawX;
        lastMouseY = rawY;
        float[] m = mouse(rawX, rawY);
        ExportPanelModel panel = controls.panel();
        if (panel != null) { // the probe, the start-up check and the key's state, every frame
            panel.probe(RewindExport.matrix(mc.displayWidth, mc.displayHeight));
            RewindExport.checkRoute();
            panel.route(RewindExport.sessionRoute(), mc.displayWidth, mc.displayHeight);
            controls.syncExport();
        }

        RewindPlayback.drawUnder(width, height, mouseX, mouseY, partialTicks);
        ClientSettings cfg = BedwarsQol.config;
        canvas.begin(scale, 0f, 0f, mc.displayHeight, scale, cfg == null || cfg.minecraftFont());
        PlayerListModel players = RiddenView.players();
        String[] names = new String[players.size()];
        int[] colours = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = players.get(i).name;
            colours[i] = players.get(i).colour;
        }
        int riding = RiddenView.ridingIndex();
        int option = controls.free() ? ViewerCameraControls.FREE
                : riding >= 0 ? ViewerLayout.FIRST_PLAYER + riding : ViewerCameraControls.YOU;
        layout = ViewerLayout.of(sw, sh, canvas, tl, names, option).export(controls.playheadUs(), controls.range());
        boolean inside = !grabbed && Mouse.isInsideWindow(); // a held cursor points at nothing
        float alpha = controls.fade.update(nowMs, inside && layout.over(m[0], m[1]),
                controls.pointer.dragging() || controls.menuOpen || controls.barPinned());
        SheetColors.Shades acc = accent(cfg);
        if (controls.free()) {
            RewindPainter.flySpeed(canvas, sw, sh, ViewerCameraControls.get().flyLevel(),
                    controls.speedBarAlpha(nowMs), acc);
        }
        RewindPainter.BarState s = new RewindPainter.BarState();
        ReplayTicks ticks = session.ticks();
        s.playing = ticks != null && !ticks.paused();
        s.timeUs = controls.pointer.dragging() ? controls.pointer.previewUs() : controls.playheadUs();
        s.speed = ticks != null ? ticks.speed() : 1f;
        s.camera = option;
        s.playerColours = colours;
        s.menuOpen = controls.menuOpen;
        if (inside && controls.fade.visible()) {
            s.hover = layout.hit(m[0], m[1], controls.menuOpen);
            float[] k = layout.knob(s.timeUs);
            s.knobHover = m[0] >= k[0] && m[0] < k[0] + k[2] && m[1] >= k[1] && m[1] < k[1] + k[3];
        }
        s.pressed = controls.pointer.pressed();
        s.dragging = controls.pointer.dragging();
        s.cursorMarker = controls.highlightedMarker();
        s.dragHandle = controls.pointer.handle();
        s.alpha = alpha;
        s.accent = acc;
        panelLayout = panel == null ? null : panel.layout(sw, layout.bar[1], canvas);
        if (panelLayout != null) { // under the bar's camera menu, which opens over it
            ExportPanelModel.Hit ph = inside && !controls.menuOpen && !controls.exportBusy()
                    ? panelLayout.hit(m[0], m[1]) : null;
            if (ph != null && ph.part != ExportPanelModel.Hit.Part.NONE) {
                s.hover = null; // the bar's parts under the panel are not hovered
                s.knobHover = false;
            }
            RewindPainter.exportPanel(canvas, panel, panelLayout, ph, acc);
        }
        RewindPainter.bar(canvas, layout, s);
        if (riding >= 0 && !session.view().clean()) {
            RewindPainter.riddenName(canvas, sw, names[riding], colours[riding]);
        }
        canvas.end();
        drawFaces(RewindPainter.faces(layout, s), alpha);
        RewindPlayback.drawOver(width, height, mouseX, mouseY, partialTicks);
    }

    private SheetColors.Shades accent(ClientSettings cfg) {
        int a = SheetColors.legible(GuiTheme.color(cfg == null ? null : cfg.guiAccent));
        if (a != accent) {
            accent = a;
            accentShades = SheetColors.Shades.of(a);
        }
        return accentShades;
    }

    /**
     * Holds the cursor for the free camera's mouse look, or lets it go at the screen's centre (as vanilla's
     * {@code MouseHelper} does). @return whether it is held now
     */
    private boolean holdCursor(boolean hold) {
        if (Mouse.isGrabbed() != hold) {
            if (hold) {
                controls.pointer.cancel();
            } else {
                Mouse.setCursorPosition(Display.getWidth() / 2, Display.getHeight() / 2);
            }
            Mouse.setGrabbed(hold);
            Mouse.getDX(); // what moved before the hold turns nothing
            Mouse.getDY();
        }
        return hold;
    }

    /** A cursor position in GUI px (sheet units), as the settings sheet reads it. */
    private float[] mouse(int x, int y) {
        return new float[]{(x + 0.5f) / scale, (mc.displayHeight - y - 0.5f) / scale};
    }

    /** Faces (skin face and hat, 8 x 8) over the head frames the bar drew: yours, or a listed player's. */
    private void drawFaces(List<float[]> faces, float alpha) {
        if (faces.isEmpty()) {
            return;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1f, 1f, 1f, alpha);
        for (float[] f : faces) {
            int option = (int) f[4];
            ResourceLocation skin = option >= ViewerLayout.FIRST_PLAYER
                    ? RiddenView.skin(option - ViewerLayout.FIRST_PLAYER) : skin();
            mc.getTextureManager().bindTexture(skin != null ? skin : DefaultPlayerSkin.getDefaultSkinLegacy());
            GlStateManager.pushMatrix();
            GlStateManager.translate(f[0], f[1], 0f);
            Gui.drawScaledCustomSizeModalRect(0, 0, 8f, 8f, 8, 8, 8, 8, 64f, 64f);
            Gui.drawScaledCustomSizeModalRect(0, 0, 40f, 8f, 8, 8, 8, 8, 64f, 64f);
            GlStateManager.popMatrix();
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
    }

    /**
     * The camera's skin (your tab entry's), else the default one. In another player's view the camera wears theirs
     * (for the arm), so your face then comes from the recorded player's puppet.
     */
    private ResourceLocation skin() {
        PlaybackSink sink = RewindPlayback.currentSink();
        AbstractClientPlayer you = RiddenView.ridingIndex() >= 0 && sink != null && sink.puppet() != null
                ? sink.puppet() : mc.thePlayer instanceof AbstractClientPlayer ? (AbstractClientPlayer) mc.thePlayer
                : null;
        if (you != null) {
            ResourceLocation r = you.getLocationSkin();
            if (r != null) {
                return r;
            }
        }
        return DefaultPlayerSkin.getDefaultSkinLegacy();
    }

    /** Holds (or releases) the player-list binding: vanilla's HUD then draws the recorded tab list. */
    private void holdTabList(boolean down) {
        Minecraft client = mc != null ? mc : Minecraft.getMinecraft();
        if (client == null || client.gameSettings == null) {
            return;
        }
        int code = client.gameSettings.keyBindPlayerList.getKeyCode();
        if (code != 0) {
            KeyBinding.setKeyBindState(code, Controls.tabListHeld(ViewerKeys.KEY_TAB, down) || bindingDown(code));
        }
    }

    /** Flies the free camera from the movement keys, in real time since the last frame (at most 0.1 s a step). */
    private void fly() {
        long now = System.nanoTime();
        double seconds = lastFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
        lastFrameNanos = now;
        if (!controls.free() || seconds <= 0) {
            return;
        }
        GameSettings g = mc.gameSettings;
        int forward = FreeCameraMath.axis(bindingDown(g.keyBindForward.getKeyCode()),
                bindingDown(g.keyBindBack.getKeyCode()));
        int strafe = FreeCameraMath.axis(bindingDown(g.keyBindLeft.getKeyCode()),
                bindingDown(g.keyBindRight.getKeyCode()));
        int vertical = FreeCameraMath.axis(Keyboard.isKeyDown(ViewerKeys.KEY_SPACE),
                Keyboard.isKeyDown(ViewerKeys.KEY_LSHIFT) || Keyboard.isKeyDown(ViewerKeys.KEY_RSHIFT));
        if (forward != 0 || strafe != 0 || vertical != 0) {
            ViewerCameraControls.get().move(forward, strafe, vertical, seconds);
        }
    }

    /** A binding's key or mouse button is down (vanilla's codes: mouse buttons are {@code button - 100}). */
    private static boolean bindingDown(int code) {
        if (code == 0) {
            return false;
        }
        if (code < 0) {
            return Mouse.isButtonDown(code + 100);
        }
        return code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }

    // ------------------------------------------------------------------ input (pumped once per frame)

    @Override
    public void handleMouseInput() {
        ViewerLayout l = layout;
        if (l == null) {
            return;
        }
        long nowMs = System.nanoTime() / 1_000_000L;
        float[] m = mouse(Mouse.getEventX(), Mouse.getEventY());
        int button = Mouse.getEventButton();
        int dx = Mouse.getEventDX(), dy = Mouse.getEventDY();
        int wheel = Mouse.getEventDWheel();
        if (controls.exportBusy()) { // an export runs: no click, drag or wheel does anything (TASK U5)
            controls.pointer.cancel();
            panelPress = false;
            return;
        }
        if (Mouse.isGrabbed()) { // the free camera holds the cursor (it looks in drawRewind): no bar under it
            if (button > 0 && Mouse.getEventButtonState()) {
                cleanViewButton(l, button, m, true);
            } else if (button == 0 && Mouse.getEventButtonState()) {
                RiddenView.pick(screenW / 2f, screenH / 2f, screenW, screenH); // the player under the crosshair
            }
            if (wheel != 0) {
                controls.scroll(wheel, nowMs);
            }
            return;
        }
        if (button != -1 || dx != 0 || dy != 0) {
            controls.fade.mouseMoved(nowMs);
        }
        if (panelMouse(button, m)) {
            return;
        }
        if (button > 0 && Mouse.getEventButtonState() && cleanViewButton(l, button, m, false)) {
            return;
        }
        if (button == 0 || button == 1) {
            boolean right = button == 1;
            if (Mouse.getEventButtonState()) {
                boolean live = controls.fade.visible();
                boolean menuWasOpen = controls.menuOpen;
                ViewerLayout.Part part = live ? l.hit(m[0], m[1], controls.menuOpen).part : ViewerLayout.Part.NONE;
                if (live) {
                    ViewerLayout.Action a = controls.pointer.down(l, m[0], m[1], controls.menuOpen, right);
                    controls.act(a);
                    if (a.kind == ViewerLayout.Action.Kind.SPEED_DOWN || isKey(controls.pointer.pressed())) {
                        click();
                    }
                }
                // a press on the world (one that only closes the menu does nothing more): the player under the
                // cursor is ridden, else the free camera takes the cursor back
                if (!right && part == ViewerLayout.Part.NONE && controls.free() && !menuWasOpen
                        && !RiddenView.pick(m[0], m[1], screenW, screenH)) {
                    controls.grabCursor();
                }
            } else if (!right) {
                controls.act(controls.pointer.up(l, m[0], m[1]));
            }
        } else if (dx != 0 || dy != 0) {
            if (controls.pointer.dragging() || controls.pointer.handle() >= 0) {
                controls.pointer.move(l, m[0]);
            }
        }
        if (wheel != 0) {
            controls.scroll(wheel, nowMs);
        }
    }

    /**
     * The export panel's share of the mouse: a press on it (the camera menu, when open, is over it) and the release
     * that follows; buttons other than the left do nothing there. @return true if the panel took the event
     */
    private boolean panelMouse(int button, float[] m) {
        ExportPanelModel panel = controls.panel();
        ExportPanelModel.Layout pl = panelLayout;
        if (panel == null || pl == null || button < 0) {
            return false;
        }
        boolean down = Mouse.getEventButtonState();
        if (button == 0 && !down && panelPress) {
            panelPress = false;
            controls.panelIntent(panel.mouseUp(pl, m[0], m[1]));
            return true;
        }
        if (!down || controls.menuOpen || pl.hit(m[0], m[1]).part == ExportPanelModel.Hit.Part.NONE) {
            return false;
        }
        if (button == 0) {
            panelPress = true;
            panel.mouseDown(pl, m[0], m[1]);
            if (panel.pressed() != null) {
                click();
            }
        }
        return true;
    }

    /**
     * A press of the mouse button the Clean View Key is bound to ({@link ViewerKeys#mouseAction}) toggles the clean
     * view, except a right click on the bar, which stays the bar's ({@code grabbed}: no bar under a held cursor).
     * @return true if it did
     */
    private boolean cleanViewButton(ViewerLayout l, int button, float[] m, boolean grabbed) {
        ClientSettings cfg = BedwarsQol.config;
        if (ViewerKeys.mouseAction(button, cfg == null ? 0 : cfg.rewindCleanViewKey) != ViewerKeys.Action.CLEAN_VIEW) {
            return false;
        }
        if (!grabbed && button == 1 && controls.fade.visible()
                && l.hit(m[0], m[1], controls.menuOpen).part != ViewerLayout.Part.NONE) {
            return false;
        }
        session.view().toggleClean();
        RewindPlayback.viewStateChanged();
        return true;
    }

    private static boolean isKey(ViewerLayout.Hit h) {
        return h != null && (h.part == ViewerLayout.Part.PLAY || h.part == ViewerLayout.Part.SPEED
                || h.part == ViewerLayout.Part.CAMERA || h.part == ViewerLayout.Part.CAMERA_OPTION);
    }

    private void click() {
        mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        ClientSettings cfg = BedwarsQol.config;
        controls.key(keyCode, isShiftKeyDown(), cfg == null ? 0 : cfg.rewindCleanViewKey, this::openEsc);
    }

    private void openEsc() {
        controls.pointer.cancel();
        RewindPlayback.display(new RewindEscScreen(session, this, RewindEscScreen.opened(session)));
    }

    // ------------------------------------------------------------------ controls (per loaded timeline)

    /**
     * The viewer's actions on the session and the cameras, and the state that outlives one viewer screen: the marker
     * cursor ({@link MarkerNav}), the bar's fade and pointer, the camera menu, the free camera's hold on the cursor
     * and the last scroll. Minecraft-free.
     */
    static final class Controls {

        /**
         * The export the viewer starts and follows ({@link GameExporter}: {@link RewindExport}); a test replaces it.
         */
        interface Exporter {
            /** The settings last exported with (the defaults the first time). */
            ExportSettings stored();

            /**
             * Exports {@code [startUs, endUs)} (the recording's clock) with {@code choice}; {@code panel}'s key fills
             * in the window meanwhile. @return null when started, else why not
             */
            String start(long startUs, long endUs, ExportPanelModel.Choice choice, ExportPanelModel panel);

            /** An export runs. */
            boolean active();

            /** The share of frames done of the running export. */
            float progress();

            /** How the last export ended ({@code DONE}, {@code CANCELLED}, {@code FAILED}), or null. */
            ExportJob.State outcome();

            /** Asks the running export to stop. */
            void cancel();

            /** Shows the folder that holds the last finished file. */
            void openFolder();

            /** Export mode opened: unfinished files a crash left in the export folder are removed (TASK F2). */
            void sweep();
        }

        private static Exporter exporter;
        /** The gallery's Export: the recording whose next viewer opens in export mode ({@link #requestExport}). */
        static String exportRequest;

        final PlaybackSession session;
        final Timeline timeline;
        final MarkerNav nav;
        final BarFade fade = new BarFade();
        final ViewerLayout.Pointer pointer = new ViewerLayout.Pointer();
        boolean menuOpen;
        /** Export mode's panel; null outside export mode. */
        private ExportPanelModel panel;
        /** An export this viewer started has not been seen ending yet. */
        private boolean started;
        /** The free camera holds the cursor (mouse look) unless Esc let it go. */
        private boolean grab;
        private boolean wasFree;
        private boolean scrolled;
        private long lastScrollMs;

        /**
         * Controls for the session's current timeline; a new session starts in your view, and in export mode when the
         * gallery asked for it (the request is used by the first controls made after it, whichever game they show).
         */
        Controls(PlaybackSession session) {
            this.session = session;
            this.timeline = session.timeline();
            this.nav = timeline == null ? null : new MarkerNav(timeline);
            ViewerCameraControls.get().select(ViewerCameraControls.YOU);
            String asked = exportRequest;
            exportRequest = null;
            if (asked != null && timeline != null && asked.equals(session.recordingId())) {
                openExportFromGallery();
            }
        }

        /** Replaces the export ({@code null}: the game's). */
        static void setExporter(Exporter e) {
            exporter = e;
        }

        static Exporter exporter() {
            Exporter e = exporter;
            return e != null ? e : GameExporter.INSTANCE;
        }

        // -------------------------------------------------------------- export mode

        boolean exportMode() {
            return panel != null;
        }

        /** The range the handles set (from the game start), or null outside export mode. */
        ExportRange range() {
            return panel == null ? null : pointer.range();
        }

        ExportPanelModel panel() {
            return panel;
        }

        /** The bar stays up (export mode). */
        boolean barPinned() {
            return panel != null;
        }

        /** An export runs: no key but Esc and no click does anything. */
        boolean exportBusy() {
            return exporter().active();
        }

        /**
         * Export mode from the viewer (the Esc menu's Export): the handles 10 s before and 5 s after the marker last
         * jumped to in this viewing, else the playhead (TASK U3); the panel with the settings last exported with.
         */
        void openExport() {
            if (timeline == null) {
                return;
            }
            int last = nav == null ? -1 : nav.lastJumped();
            long marker = last < 0 ? -1 : Math.max(0L, nav.markerUs(last) - timeline.gameStartUs());
            enterExport(ExportRange.forViewer(timeline.lengthUs(), marker, playheadUs()));
        }

        /** Export mode from the gallery (TASK U2): paused at the first marker (or the start), the handles around it. */
        private void openExportFromGallery() {
            boolean any = nav != null && nav.count() > 0;
            long first = any ? Math.max(0L, nav.markerUs(0) - timeline.gameStartUs()) : -1;
            enterExport(ExportRange.forGallery(timeline.lengthUs(), first));
            session.setPaused(true);
            session.seekTo(any ? nav.markerTick(0) : timeline.startTick());
        }

        private void enterExport(ExportRange r) {
            menuOpen = false;
            pointer.cancel();
            pointer.range(r);
            panel = new ExportPanelModel(exporter().stored());
            started = false;
            exporter().sweep();
        }

        /** Leaves export mode (the range and the panel are forgotten). */
        void leaveExport() {
            pointer.range(null);
            panel = null;
            started = false;
        }

        /** What the panel's mouse asked for. */
        void panelIntent(ExportPanelModel.Intent i) {
            if (i == null || panel == null) {
                return;
            }
            switch (i.kind) {
                case EXPORT:
                    startExport(i.choice);
                    break;
                case OPEN_FOLDER:
                    exporter().openFolder();
                    break;
                case CANCEL:
                    exporter().cancel();
                    break;
                case LEAVE:
                    leaveExport();
                    break;
                default:
                    break;
            }
        }

        /**
         * The Export key: the range on the recording's clock, never before its first tick (where an export cannot
         * start), with the panel's choice.
         */
        private void startExport(ExportPanelModel.Choice choice) {
            ExportRange r = pointer.range();
            if (choice == null || r == null || timeline == null || exporter().active()) {
                return;
            }
            long base = timeline.gameStartUs();
            long first = timeline.timeOfTick(timeline.startTick());
            long start = Math.max(base + r.startUs(), first);
            long end = base + r.endUs();
            String why = exporter().start(start, end, choice, panel);
            if (why == null) {
                started = true;
                panel.filling(0f);
            } else {
                started = false;
                panel.failed();
                com.bedwarsqol.feature.DiagLog.log("rewind-export: not started " + why);
            }
        }

        /** Each frame in export mode: the key follows the export (filling, then Open Folder, Failed or Export). */
        void syncExport() {
            if (panel == null) {
                return;
            }
            Exporter e = exporter();
            if (e.active()) {
                if (started) {
                    panel.filling(e.progress());
                }
                return;
            }
            if (!started) {
                return;
            }
            started = false;
            ExportJob.State o = e.outcome();
            if (o == ExportJob.State.DONE) {
                panel.done();
            } else if (o == ExportJob.State.FAILED) {
                panel.failed();
            } else {
                panel.idle();
            }
        }

        boolean free() {
            return ViewerCameraControls.get().camera() == ViewerCameraControls.FREE;
        }

        boolean menuOpen() {
            return menuOpen;
        }

        /**
         * Whether the cursor is to be held this frame (call once a frame): entering the free camera, however it
         * happens, takes it; Esc lets it go ({@link #key}) and a click on the world takes it back
         * ({@link #grabCursor}); every other view leaves it free.
         */
        boolean grabWanted() {
            boolean free = free();
            if (free != wasFree) {
                wasFree = free;
                grab = free;
            }
            if (grab && free) {
                menuOpen = false;
                return true;
            }
            return false;
        }

        /** A click on the world in the free camera: the mouse looks around again. */
        void grabCursor() {
            grab = free();
        }

        /** Tab shows the recorded tab list while it is down (the player-list binding is held). */
        static boolean tabListHeld(int keyCode, boolean down) {
            return down && ViewerKeys.action(keyCode, false, false) == ViewerKeys.Action.TAB_LIST;
        }

        /** The playhead from the game start, clamped to the timeline. */
        long playheadUs() {
            if (timeline == null) {
                return 0;
            }
            long t = timeline.timeOfTick(session.playheadTick()) - timeline.gameStartUs();
            return Math.max(0L, Math.min(timeline.lengthUs(), t));
        }

        /** The marker drawn highlighted (the one last jumped to, while the playhead is in its lead-in), or -1. */
        int highlightedMarker() {
            return nav == null ? -1 : nav.cursor(session.playheadTick());
        }

        /** A key press ({@link ViewerKeys}) with no Clean View Key bound; {@code openEsc} opens the Esc menu. */
        void key(int keyCode, boolean shift, Runnable openEsc) {
            key(keyCode, shift, 0, openEsc);
        }

        /** A key press; {@code cleanViewKey} is the user's Clean View Key (0 unbound). */
        void key(int keyCode, boolean shift, int cleanViewKey, Runnable openEsc) {
            if (exportBusy()) { // an export runs: Esc cancels it, nothing else does anything (TASK U5)
                if (keyCode == ViewerKeys.KEY_ESCAPE) {
                    exporter().cancel();
                }
                return;
            }
            ViewerKeys.Action a = ViewerKeys.action(keyCode, shift, free(), cleanViewKey);
            switch (a) {
                case TOGGLE_PLAY:
                    session.togglePlay();
                    break;
                case BACK_SHORT:
                case FORWARD_SHORT:
                case BACK_LONG:
                case FORWARD_LONG:
                    clearCursor();
                    session.seekBy(ViewerKeys.seekDeltaUs(a));
                    break;
                case PREVIOUS_MARKER:
                    if (nav != null) jumpTo(nav.previous(session.playheadTick()));
                    break;
                case NEXT_MARKER:
                    if (nav != null) jumpTo(nav.next(session.playheadTick()));
                    break;
                case SLOWER:
                    session.slower();
                    break;
                case FASTER:
                    session.faster();
                    break;
                case VIEW_YOU:
                    ViewerCameraControls.get().select(ViewerCameraControls.YOU);
                    break;
                case VIEW_FREE:
                    selectFree();
                    break;
                case CYCLE_PERSPECTIVE:
                    if (session.view().cyclePerspective()) {
                        RewindPlayback.viewStateChanged();
                    }
                    break;
                case CLEAN_VIEW:
                    session.view().toggleClean();
                    RewindPlayback.viewStateChanged();
                    break;
                case NEXT_PLAYER:
                    RewindPlayback.cyclePlayer(1);
                    break;
                case PREVIOUS_PLAYER:
                    RewindPlayback.cyclePlayer(-1);
                    break;
                case ESC_MENU:
                    if (menuOpen) {
                        menuOpen = false;
                    } else if (grab && free()) {
                        grab = false; // the cursor comes back, with the bar
                        fade.mouseMoved(System.nanoTime() / 1_000_000L);
                    } else if (panel != null) {
                        leaveExport();
                    } else if (openEsc != null) {
                        openEsc.run();
                    }
                    break;
                case TAB_LIST: // held: read every frame, see tabListHeld
                case NONE:
                default:
                    break;
            }
        }

        /** A bar action ({@link ViewerLayout.Pointer}). */
        void act(ViewerLayout.Action a) {
            if (a == null || exportBusy()) {
                return;
            }
            switch (a.kind) {
                case CLOSE_MENU:
                    menuOpen = false;
                    break;
                case PLAY_PAUSE:
                    session.togglePlay();
                    break;
                case SPEED_UP:
                    speedUpWrapping();
                    break;
                case SPEED_DOWN:
                    session.slower();
                    break;
                case CAMERA_MENU:
                    menuOpen = !menuOpen;
                    break;
                case CAMERA:
                    menuOpen = false;
                    if (a.index >= ViewerLayout.FIRST_PLAYER) {
                        RiddenView.ridePlayer(a.index - ViewerLayout.FIRST_PLAYER);
                    } else if (a.index == ViewerCameraControls.FREE) {
                        selectFree();
                    } else {
                        ViewerCameraControls.get().select(ViewerCameraControls.YOU);
                    }
                    break;
                case MARKER:
                    if (nav != null) jumpTo(nav.jump(a.index));
                    break;
                case SEEK:
                    if (timeline != null) {
                        clearCursor();
                        session.seekTo(SeekPlanner.tickForTime(timeline, timeline.gameStartUs() + a.timeUs));
                    }
                    break;
                case NONE:
                default:
                    break;
            }
        }

        /** Key 2 or the menu's free camera: from another player's view it starts at their eye, else at your view's. */
        private static void selectFree() {
            if (!RiddenView.freeHere()) {
                ViewerCameraControls.get().select(ViewerCameraControls.FREE);
            }
        }

        /** A wheel delta: the flight speed in the free camera (with the speed bar), nothing in your view. */
        void scroll(int wheel, long nowMs) {
            if (wheel == 0 || !free()) {
                return;
            }
            ViewerCameraControls.get().scroll(wheel);
            scrolled = true;
            lastScrollMs = nowMs;
        }

        /** The speed bar's opacity at {@code nowMs}: 1 s after a scroll, then a 200 ms fade. */
        float speedBarAlpha(long nowMs) {
            return scrolled ? FreeCameraMath.speedBarAlpha(nowMs - lastScrollMs) : 0f;
        }

        private void jumpTo(int marker) {
            if (marker >= 0) {
                session.seekTo(nav.landingTick(marker));
            }
        }

        private void clearCursor() {
            if (nav != null) nav.clear();
        }

        /** The speed key: one step up; at 4x it wraps to 0.25x (mockup). */
        private void speedUpWrapping() {
            ReplayTicks t = session.ticks();
            if (t == null) {
                return;
            }
            if (t.speedIndex() >= ReplayTicks.SPEEDS.length - 1) {
                for (int i = 0; i < ReplayTicks.SPEEDS.length && session.ticks().speedIndex() > 0; i++) {
                    session.slower();
                }
            } else {
                session.faster();
            }
        }
    }

    // ------------------------------------------------------------------ the game's export

    /** {@link Controls.Exporter} on {@link RewindExport}: the settings come from and go to the config file. */
    static final class GameExporter implements Controls.Exporter {
        static final GameExporter INSTANCE = new GameExporter();

        @Override
        public ExportSettings stored() {
            ClientSettings c = BedwarsQol.config;
            return c == null ? ExportSettings.defaults() : ExportSettings.of(c.rewindExportHeight, c.rewindExportFps,
                    c.rewindExportQuality, c.rewindExportCodec, c.rewindExportChat);
        }

        @Override
        public String start(long startUs, long endUs, ExportPanelModel.Choice choice, ExportPanelModel panel) {
            if (choice == null) {
                return "no-choice";
            }
            RewindExport.Request q = new RewindExport.Request();
            q.startUs = startUs;
            q.endUs = endUs;
            q.settings = choice.settings();
            q.panel = panel;
            String why = RewindExport.start(q);
            ClientSettings c = BedwarsQol.config;
            if (why == null && c != null) { // the values last exported with (TASK U4)
                ExportSettings s = choice.settings();
                c.rewindExportHeight = s.height();
                c.rewindExportFps = s.fps();
                c.rewindExportQuality = s.quality();
                c.rewindExportCodec = s.codec();
                c.rewindExportChat = s.chat();
                c.save();
            }
            return why;
        }

        @Override
        public boolean active() {
            return RewindExport.active();
        }

        @Override
        public float progress() {
            return RewindExport.progress();
        }

        @Override
        public ExportJob.State outcome() {
            return RewindExport.lastState();
        }

        @Override
        public void cancel() {
            RewindExport.cancel("esc");
        }

        @Override
        public void openFolder() {
            RewindExport.openFolder();
        }

        @Override
        public void sweep() {
            RewindExport.sweepPartials();
        }
    }
}
