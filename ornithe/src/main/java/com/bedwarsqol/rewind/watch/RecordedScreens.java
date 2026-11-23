package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.export.ExportLayers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.IMerchant;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.IInteractionObject;
import net.minecraft.world.WorldSettings;
import org.lwjgl.opengl.GL11;

import java.util.Set;

/**
 * Recorded screens (PLAN "Recorded screens", Phase 9; TASK B9, B10, B13; coordinator amendment 2): the chest and shop
 * windows the server opened and the death screen, as real vanilla screen objects that are drawn under the viewer bar
 * in your view and are never opened. Installed as the camera's {@link RecordedScreenHolder} and registered as a
 * {@link PlaybackPart} by {@link #register()} (final wiring: Phase 16).
 *
 * <p><b>Windows.</b> {@code NetHandlerPlayClient.handleOpenWindow} calls the camera's {@code display*}, then writes
 * the window id to {@code thePlayer.openContainer} in every branch. So every {@code display*} here installs, before it
 * returns, a container built on the camera's own inventory: a {@link GuiChest}'s container for the windows vanilla
 * draws as {@code GuiChest} (chests, Hypixel's shops: {@link #openChest}, which also initializes the screen at once,
 * with no draw in between, so an S30 in the same drain fills its 54 + 36 slots, round 2 B3), and a container with
 * vanilla's slot numbering (the window's own slots, then main 9-35, then the hotbar) for the windows not shown
 * (villager, horse, hopper, furnace, crafting table...). Either way the S2F/S30 the server sends through the window's
 * id for a player slot (a shop purchase) reaches the camera's inventory, in every view, drawn or not (amendment 2).
 * Out-of-range slots are ignored, so a malformed window packet is never a playback failure.
 *
 * <p><b>Closes.</b> The server's close (S2E, through the camera's {@code closeScreenAndDropStack}), a new window and a
 * boundary end the window: the cursor stack is dropped (vanilla's {@code closeScreenAndDropStack}) and the inventory
 * container is open again. The player's own close, which v1 never stored, comes from the {@link #closeSource()}
 * ({@link WindowCloseRule}: the v1 heuristic, or the recorded VIEW screen kind on a v2 recording; Phase 12 makes the
 * v2 close exact and adds the inventory screen): it stops the drawing and drops the cursor stack, and the container
 * stays routed until the next S2E, window or boundary, so a close guessed too early still lets the shop's slot
 * updates through (when the guess is right the server uses window 0, which vanilla routes to the inventory container
 * anyway).
 *
 * <p><b>Death screen.</b> Recorded health at or below 0 in a game mode other than spectator holds a vanilla
 * {@link GuiGameOver} (and ends an open window, as vanilla's {@code GuiContainer.updateScreen} does) until health is
 * above 0 again or the next boundary (the respawn's S07). Its buttons turn enabled after 20 ticks, as vanilla's do,
 * and do nothing.
 *
 * <p><b>Never opened.</b> The held screens are never {@code mc.currentScreen}, so vanilla routes no input to them;
 * on top of that every input, update and close handler is a no-op ({@link HeldChest}, {@link HeldGameOver},
 * {@link HeldInventory}), so nothing they hold can send, close or open anything. Drawn only in your view with the
 * clean view off, at the {@link #cursorX}/{@link #cursorY} position (v1: off screen, so no hover and no carried
 * stack). Window state follows the tick feed (displays, closes and ticks), so a seek lands on the screens straight
 * play shows.
 *
 * <p><b>v2 recordings</b> (VIEW records; Phase 12, TASK C5, C6, B9) follow the recorded screen kind <b>by playback
 * time</b> ({@link ViewTrack}), so a seek lands on it too: the death screen is held exactly while the recorded screen
 * is the death screen (health no longer opens it); a window is drawn from its open until the first VIEW at or after
 * its tick that names something else ({@link #closedByView}: the exact close, in that frame); the inventory screen
 * ({@link HeldInventory}, vanilla's {@code GuiInventory} of the camera) while the recorded screen is the inventory;
 * the chat input bar ({@link ChatBar}) with the recorded text while it is chat. The cursor, vanilla's hover
 * highlight, tooltip and carried stack follow the recorded mouse, and {@link CursorPainter} draws the arrow there.
 */
public final class RecordedScreens implements RecordedScreenHolder, PlaybackPart {

    /** Where the cursor is drawn when the recording holds no mouse position (v1). */
    static final int OFF_SCREEN = -10_000;
    /** Vanilla's {@code GuiGameOver.updateScreen} enables the buttons on its 20th tick. */
    static final int DEATH_BUTTON_TICKS = 20;

    /** What the screens read from the playback session (a test supplies its own). */
    interface Context {
        ViewState view();

        /** The tick being fed ({@code TickFeeder.currentTick}). */
        long feedTick();

        /** The record being fed's time ({@code TickFeeder.recordTimeUs}). */
        long feedTimeUs();

        SelfTrack track();

        ExtRecords ext();

        TickTable table();

        /** The recorded player's health (on the camera, where S06 lands). */
        float health(RewindCamera camera);

        /** The recorded game mode is spectator. */
        boolean recordedSpectator();

        /** The recording's extension records by tag ({@code LoadedRecording.extTrack}). */
        ExtTrack extTrack();

        /** Playback time, the time the picture shows ({@code PlaybackSession.renderTimeUs}). */
        long renderTimeUs();

        /** The segment of the tick the picture shows ({@code TickTable.segment}; -1 unknown). */
        int segment();

        /**
         * The layers an export frame asks for ({@link ExportLayers}), or null when this frame is not an export frame
         * (watching draws everything as recorded).
         */
        default Set<ExportLayers.Layer> exportLayers() {
            return null;
        }
    }

    private static final Context SESSION = new Context() {
        @Override
        public ViewState view() {
            return RewindPlayback.view();
        }

        @Override
        public long feedTick() {
            TickFeeder f = RewindPlayback.session().feeder();
            return f == null ? TickFeeder.HEADER : f.currentTick();
        }

        @Override
        public long feedTimeUs() {
            TickFeeder f = RewindPlayback.session().feeder();
            return f == null ? 0 : f.recordTimeUs();
        }

        @Override
        public SelfTrack track() {
            LoadedRecording r = RewindPlayback.session().recording();
            return r == null ? null : r.selfTrack();
        }

        @Override
        public ExtRecords ext() {
            LoadedRecording r = RewindPlayback.session().recording();
            return r == null ? ExtRecords.EMPTY : r.ext();
        }

        @Override
        public TickTable table() {
            Timeline t = RewindPlayback.session().timeline();
            return t == null ? null : t.ticks();
        }

        @Override
        public float health(RewindCamera camera) {
            return camera.getHealth();
        }

        @Override
        public boolean recordedSpectator() {
            PlaybackSink s = RewindPlayback.currentSink();
            return s != null && s.recordedGameType() == WorldSettings.GameType.SPECTATOR;
        }

        @Override
        public ExtTrack extTrack() {
            LoadedRecording r = RewindPlayback.session().recording();
            return r == null ? ExtTrack.EMPTY : r.extTrack();
        }

        @Override
        public long renderTimeUs() {
            return RewindPlayback.session().renderTimeUs();
        }

        @Override
        public int segment() {
            return ViewPart.segmentNow(RewindPlayback.session());
        }

        @Override
        public Set<ExportLayers.Layer> exportLayers() {
            return com.bedwarsqol.rewind.export.RewindExport.frameLayers();
        }
    };

    private static final RecordedScreens INSTANCE = new RecordedScreens(SESSION);

    private final Context context;
    /** The container the last {@code display*} installed: routed until a hard close. */
    private Container window;
    /** The window's screen while it is drawn (null for windows not shown and after the player's close). */
    private HeldChest windowScreen;
    private long openTick;
    private long openUs;
    private HeldGameOver deathScreen;
    private int deathTicks;
    private WindowCloseRule.Source source;
    private TickTable sourceTable;
    /** v2: the recorded VIEW, MOUSE and CHAT_INPUT at playback time (Phase 12). */
    private final ViewTrack recorded = new ViewTrack();
    /** v2: the inventory screen while the recorded screen is the inventory, on {@link #inventoryOwner}'s slots. */
    private HeldInventory inventory;
    private RewindCamera inventoryOwner;
    private long inventorySinceUs;
    /** v2: the chat input bar while the recorded screen is chat. */
    /** The recorded chat input bar (a test counts its draws). */
    ChatBar chatBar = new ChatBar();

    RecordedScreens(Context context) {
        this.context = context;
    }

    /**
     * Installs the recorded screens: the camera's window calls come here, window packets reach vanilla's handlers
     * ({@link #takesWindows}), and the screens draw in the viewer's underlay. Client thread, once (Phase 16).
     */
    public static void register() {
        RewindCamera.setScreenHolder(INSTANCE);
        RewindPlayback.register(INSTANCE);
    }

    // ================================================================== holder (the camera's window calls)

    @Override
    public boolean takesWindows() {
        return true;
    }

    @Override
    public void displayChest(RewindCamera camera, IInventory inventory) {
        if (drawnAsChest(inventory)) {
            openChest(camera, inventory);
        } else {
            openHidden(camera, inventory.getSizeInventory());
        }
    }

    @Override
    public void displayGui(RewindCamera camera, IInteractionObject object) {
        openHidden(camera, ownSlots(object == null ? null : object.getGuiID()));
    }

    @Override
    public void displayHorse(RewindCamera camera, EntityHorse horse, IInventory inventory) {
        openHidden(camera, inventory == null ? 0 : inventory.getSizeInventory());
    }

    @Override
    public void displayMerchant(RewindCamera camera, IMerchant merchant) {
        openHidden(camera, 3); // ContainerMerchant: two inputs and the result
    }

    /** S2E (vanilla's {@code closeScreenAndDropStack}) or the camera's {@code closeScreen}. */
    @Override
    public void close(RewindCamera camera) {
        closeWindow(camera);
    }

    /**
     * {@code EntityPlayerSP.displayGUIChest}'s choice: {@code GuiChest} for chests, generic containers (shops) and any
     * id it does not know; its own screen for hopper, furnace, brewing stand, beacon, dispenser and dropper.
     */
    static boolean drawnAsChest(IInventory inventory) {
        String id = inventory instanceof IInteractionObject ? ((IInteractionObject) inventory).getGuiID()
                : "minecraft:container";
        return !("minecraft:hopper".equals(id) || "minecraft:furnace".equals(id)
                || "minecraft:brewing_stand".equals(id) || "minecraft:beacon".equals(id)
                || "minecraft:dispenser".equals(id) || "minecraft:dropper".equals(id));
    }

    /** Own slots of the container vanilla builds for a slotless block window ({@code displayGui}). */
    static int ownSlots(String guiId) {
        if ("minecraft:crafting_table".equals(guiId)) {
            return 10; // ContainerWorkbench: result + 3 x 3
        }
        if ("minecraft:anvil".equals(guiId)) {
            return 3; // ContainerRepair: two inputs and the result
        }
        if ("minecraft:enchanting_table".equals(guiId)) {
            return 2; // ContainerEnchantment: item and lapis
        }
        return 0;
    }

    /**
     * Inside the S2D handler, before it writes the window id: (1) build the screen on the camera's inventory, (2)
     * install its container as the camera's open container, (3) initialize the screen at the scaled size
     * ({@code GuiContainer.initGui} installs the same container again). Nothing waits for a draw.
     */
    private void openChest(RewindCamera camera, IInventory lower) {
        HeldChest gui = new HeldChest(camera, camera.inventory, lower);
        camera.openContainer = gui.inventorySlots;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer == camera) {
            ScaledResolution size = new ScaledResolution(mc);
            gui.setWorldAndResolution(mc, size.getScaledWidth(), size.getScaledHeight());
        }
        opened(gui.inventorySlots, gui);
    }

    /** A window that is not drawn: a container with vanilla's slot numbering, so player slots still route. */
    private void openHidden(RewindCamera camera, int ownSlots) {
        PlayerSlots c = new PlayerSlots(camera.inventory, ownSlots);
        camera.openContainer = c;
        opened(c, null);
    }

    private void opened(Container c, HeldChest gui) {
        window = c;
        windowScreen = gui;
        openTick = context.feedTick();
        openUs = context.feedTimeUs();
    }

    /** A hard close: the window ends, the cursor stack is dropped and the inventory container is open again. */
    private void closeWindow(RewindCamera camera) {
        if (camera != null) {
            camera.inventory.setItemStack(null);
            camera.openContainer = camera.inventoryContainer;
        }
        window = null;
        windowScreen = null;
    }

    /** The player's own close: no longer drawn, the cursor stack dropped; the container stays routed. */
    private void playerClosed(RewindCamera camera) {
        if (camera != null) {
            camera.inventory.setItemStack(null);
        }
        windowScreen = null;
    }

    // ================================================================== part

    @Override
    public void restart() {
        forget();
    }

    /** S01/S07: a new camera, whose own container is open; nothing carries over. */
    @Override
    public void boundary() {
        forget();
    }

    @Override
    public void exit() {
        forget();
    }

    private void forget() {
        window = null;
        windowScreen = null;
        deathScreen = null;
        deathTicks = 0;
        source = null;
        sourceTable = null;
        recorded.reset();
        inventory = null;
        inventoryOwner = null;
        chatBar.forget();
    }

    /** World tick k is about to run: the death screen from the recorded health, then the player's close. */
    @Override
    public void tick(long tick, long timeUs) {
        RewindCamera camera = camera();
        if (camera == null) {
            return;
        }
        if (exact()) {
            // v2: the recorded screen kind decides what is drawn (held()); dying still ends a window, as vanilla's
            // GuiContainer.updateScreen does, and the player's close keeps its tick-scoped side (cursor stack)
            if (context.health(camera) <= 0f && !context.recordedSpectator() && window != null) {
                closeWindow(camera);
            }
            if (windowScreen != null && closeSource().closedBy(openTick, openUs, tick)) {
                playerClosed(camera);
            }
            return;
        }
        if (context.health(camera) <= 0f && !context.recordedSpectator()) {
            if (deathScreen == null) {
                if (window != null) {
                    closeWindow(camera);
                }
                deathScreen = new HeldGameOver();
                deathTicks = 0;
            }
            deathTicks++;
            return;
        }
        deathScreen = null;
        deathTicks = 0;
        if (windowScreen != null && closeSource().closedBy(openTick, openUs, tick)) {
            playerClosed(camera);
        }
    }

    /** The close source of the recording being played (rebuilt when the tick table changes). */
    private WindowCloseRule.Source closeSource() {
        TickTable table = context.table();
        if (source == null || sourceTable != table) {
            source = WindowCloseRule.forRecording(context.track(), context.ext(), table);
            sourceTable = table;
        }
        return source;
    }

    /** The viewer's underlay: the held screen, in your view with the clean view off. */
    @Override
    public void drawUnder(int width, int height, int mouseX, int mouseY, float partial) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !(mc.thePlayer instanceof RewindCamera)) {
            return;
        }
        GuiScreen s = screenToDraw();
        boolean chat = s == null && chatShown();
        if (s == null && !chat) {
            return;
        }
        int x = cursorX(width);
        int y = cursorY(height);
        if (s != null) {
            if (s.width != width || s.height != height || !((Held) s).initialized()) {
                s.setWorldAndResolution(mc, width, height); // first draw (death screen) or a resize
            }
            if (s == deathScreen) {
                deathScreen.buttonsEnabled(deathButtonsEnabled());
            }
            s.drawScreen(x, y, partial);
        } else {
            drawChatInput(width, height);
        }
        if (x != OFF_SCREEN && y != OFF_SCREEN) {
            CursorPainter.draw(cursorGuiX(width), cursorGuiY(height), new ScaledResolution(mc).getScaleFactor());
        }
        // what vanilla's container screen leaves behind, put back for the viewer bar drawn next
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
    }

    /**
     * The recorded chat input bar with its typed text, unless this is an export frame with Chat off (Rewind Export
     * review I4, TASK P5: the typed text is private player data). @return true if it was drawn
     */
    boolean drawChatInput(int width, int height) {
        Set<ExportLayers.Layer> layers = context.exportLayers();
        if (layers != null && !layers.contains(ExportLayers.Layer.CHAT_INPUT)) {
            return false;
        }
        chatBar.draw(chatField(width, height), width, height, chatBlinkTicks());
        return true;
    }

    /**
     * The cursor's x for the held screen, in the GUI pixels vanilla hands {@code drawScreen}. v1 has no mouse
     * position: off screen (no hover, no carried stack drawn). v2: the recorded MOUSE, which is the offset from the
     * screen centre in quarter GUI pixels ({@code ViewCapture.mouseDx}), so {@code floor((2 x width + dx) / 4)} is
     * the exact pixel vanilla drew at when the window had this size, and stays centred when it has another; centred
     * before the open screen's first MOUSE (vanilla centres the cursor when a screen opens); off screen while no
     * screen is open or when the recording has no MOUSE records.
     */
    int cursorX(int width) {
        if (!cursorKnown()) {
            return OFF_SCREEN;
        }
        return Math.floorDiv(2 * width + (recorded.mouseShown() ? recorded.mouseDx() : 0),
                RewindFormat.MOUSE_UNITS_PER_GUI_PX);
    }

    /** The cursor's y; see {@link #cursorX} ({@code ViewCapture.mouseDy}, downwards). */
    int cursorY(int height) {
        if (!cursorKnown()) {
            return OFF_SCREEN;
        }
        return Math.floorDiv(2 * height + (recorded.mouseShown() ? recorded.mouseDy() : 0),
                RewindFormat.MOUSE_UNITS_PER_GUI_PX);
    }

    /** The arrow's tip in GUI coordinates at the recorded quarter pixel (where the system cursor was drawn). */
    private float cursorGuiX(int width) {
        return (2 * width + (recorded.mouseShown() ? recorded.mouseDx() : 0))
                / (float) RewindFormat.MOUSE_UNITS_PER_GUI_PX;
    }

    private float cursorGuiY(int height) {
        return (2 * height + (recorded.mouseShown() ? recorded.mouseDy() : 0))
                / (float) RewindFormat.MOUSE_UNITS_PER_GUI_PX;
    }

    /** A v2 recording with MOUSE records whose recorded screen is open now. */
    private boolean cursorKnown() {
        ExtTrack t = context.extTrack();
        if (t == null || !t.has(RewindFormat.TAG_MOUSE) || !exact()) {
            return false;
        }
        sync();
        return recorded.hasView() && recorded.screenKind() != RewindFormat.SCREEN_NONE;
    }

    private static RewindCamera camera() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer instanceof RewindCamera ? (RewindCamera) mc.thePlayer : null;
    }

    // ================================================================== read by tests and later phases

    /**
     * The screen held now. v1: the death screen over a window, or null. v2 ({@link #exact}), by playback time: the
     * death screen while the recorded screen is the death screen; else the window from its open until the recorded
     * close ({@link #closedByView}); else the inventory screen while the recorded screen is the inventory; else null.
     */
    GuiScreen held() {
        if (!exact()) {
            return deathScreen != null ? deathScreen : windowScreen;
        }
        sync();
        int kind = recorded.hasView() ? recorded.screenKind() : RewindFormat.SCREEN_NONE;
        if (kind == RewindFormat.SCREEN_DEATH) {
            if (deathScreen == null) {
                deathScreen = new HeldGameOver();
            }
            return deathScreen;
        }
        deathScreen = null;
        if (windowScreen != null && !closedByView()) {
            return windowScreen;
        }
        if (kind == RewindFormat.SCREEN_INVENTORY) {
            return inventoryScreen();
        }
        inventory = null;
        return null;
    }

    /** The recording holds VIEW records (v2): screens follow the recorded screen kind and close exactly. */
    private boolean exact() {
        ExtTrack t = context.extTrack();
        return t != null && t.has(RewindFormat.TAG_VIEW);
    }

    /** The recorded view at playback time. */
    private void sync() {
        recorded.at(context.extTrack(), context.segment(), context.renderTimeUs());
    }

    /**
     * v2's exact close: a VIEW record stamped at or after the head of the open's tick (the handler ran before that
     * head, the recorder samples the screen after it) and at or before playback time names a screen that is not a
     * container. Drawing stops in the first frame that reaches it; the tick-scoped side (the cursor stack, the
     * container's routing) follows {@link #closeSource()} as on v1.
     */
    private boolean closedByView() {
        if (!recorded.hasView()) {
            return false;
        }
        int kind = recorded.screenKind();
        if (kind == RewindFormat.SCREEN_CHEST || kind == RewindFormat.SCREEN_OTHER_CONTAINER) {
            return false;
        }
        TickTable table = context.table();
        long from = table == null ? openUs : table.timeOf(openTick);
        return recorded.kindSinceUs() >= from;
    }

    /** The inventory screen of the current camera, one per recorded open (vanilla builds one per E). */
    private GuiScreen inventoryScreen() {
        RewindCamera camera = camera();
        if (camera == null) {
            return null;
        }
        if (inventory == null || inventoryOwner != camera || inventorySinceUs != recorded.kindSinceUs()) {
            inventory = new HeldInventory(camera);
            inventoryOwner = camera;
            inventorySinceUs = recorded.kindSinceUs();
            Minecraft mc = Minecraft.getMinecraft();
            ScaledResolution size = new ScaledResolution(mc);
            inventory.setWorldAndResolution(mc, size.getScaledWidth(), size.getScaledHeight());
        }
        return inventory;
    }

    /** What {@link #drawUnder} draws now: {@link #held()} in your view with the clean view off, else null. */
    GuiScreen screenToDraw() {
        ViewState v = context.view();
        if (v == null || !v.yourView() || v.clean()) {
            return null;
        }
        return held();
    }

    /** The routed window container, or null. */
    Container window() {
        return window;
    }

    /** v2: chat is open in the recording now, drawn in your view with the clean view off (C6). */
    boolean chatShown() {
        ViewState v = context.view();
        if (v == null || !v.yourView() || v.clean() || !exact()) {
            return false;
        }
        sync();
        return recorded.chatShown();
    }

    /** The recorded chat text now (empty before this chat's first keystroke). Private player data: never logged. */
    String chatText() {
        sync();
        return recorded.chatText();
    }

    int chatCursor() {
        sync();
        return recorded.chatCursor();
    }

    /** Vanilla's chat input field ({@code GuiChat.initGui}'s) holding the recorded text and cursor now. */
    GuiTextField chatField(int width, int height) {
        sync();
        Minecraft mc = Minecraft.getMinecraft();
        return chatBar.field(mc == null ? null : mc.fontRendererObj, width, height, recorded.kindSinceUs(),
                recorded.chatText(), recorded.chatCursor());
    }

    /** Ticks the chat bar has been open (vanilla's {@code updateCursorCounter} runs once per tick). */
    private long chatBlinkTicks() {
        return ViewTrack.ticksBetween(context.table(), recorded.kindSinceUs(), context.renderTimeUs());
    }

    /**
     * The death screen's buttons: vanilla's {@code GuiGameOver.updateScreen} enables them on its 20th tick. v1: 20
     * world ticks at health 0. v2: 20 recorded ticks since the recorded death screen opened.
     */
    boolean deathButtonsEnabled() {
        if (!exact()) {
            return deathTicks >= DEATH_BUTTON_TICKS;
        }
        sync();
        return ViewTrack.ticksBetween(context.table(), recorded.kindSinceUs(), context.renderTimeUs())
                >= DEATH_BUTTON_TICKS;
    }

    // ================================================================== held screens

    /** A held screen: knows whether it was initialized. */
    private interface Held {
        boolean initialized();
    }

    /**
     * Vanilla's {@link GuiChest}, drawn as is, on a {@link WindowChest} container; every input, update and close
     * handler does nothing (it is never the current screen anyway).
     */
    static final class HeldChest extends GuiChest implements Held {
        private boolean initialized;

        HeldChest(EntityPlayer player, InventoryPlayer upper, IInventory lower) {
            super(upper, lower);
            this.inventorySlots = new WindowChest(upper, lower, player);
        }

        @Override
        public void initGui() {
            super.initGui();
            initialized = true;
        }

        @Override
        public boolean initialized() {
            return initialized;
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int button) {
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
        }

        @Override
        protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        }

        @Override
        protected void handleMouseClick(Slot slot, int slotId, int button, int type) {
        }

        @Override
        protected void keyTyped(char c, int key) {
        }

        @Override
        protected boolean checkHotbarKeys(int key) {
            return false;
        }

        @Override
        protected void actionPerformed(GuiButton button) {
        }

        @Override
        protected boolean handleComponentClick(IChatComponent component) {
            return false;
        }

        @Override
        public void sendChatMessage(String msg) {
        }

        @Override
        public void sendChatMessage(String msg, boolean addToChat) {
        }

        @Override
        public void confirmClicked(boolean result, int id) {
        }

        @Override
        public void updateScreen() {
        }

        @Override
        public void onGuiClosed() {
        }

        @Override
        public void handleInput() {
        }

        @Override
        public void handleMouseInput() {
        }

        @Override
        public void handleKeyboardInput() {
        }
    }

    /**
     * Vanilla's {@link GuiInventory} of the camera (Phase 12, C5), drawn as is: its own crafting grid, armour, main
     * inventory and hotbar on the camera's {@code inventoryContainer}, the player model looking at the recorded
     * cursor, the cursor stack at the cursor. Its {@code initGui} lays it out as vanilla's does (centred, moved right
     * by active effects) without vanilla's two side effects: it never makes its container the camera's open container
     * (the recorded windows own that) and never swaps itself for the creative inventory (which opens a screen). Every
     * input, update and close handler does nothing.
     */
    static final class HeldInventory extends GuiInventory implements Held {
        private boolean initialized;

        HeldInventory(EntityPlayer player) {
            super(player);
        }

        @Override
        public void initGui() {
            buttonList.clear();
            guiLeft = (width - xSize) / 2;
            guiTop = (height - ySize) / 2;
            updateActivePotionEffects();
            initialized = true;
        }

        @Override
        public boolean initialized() {
            return initialized;
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int button) {
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
        }

        @Override
        protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        }

        @Override
        protected void handleMouseClick(Slot slot, int slotId, int button, int type) {
        }

        @Override
        protected void keyTyped(char c, int key) {
        }

        @Override
        protected boolean checkHotbarKeys(int key) {
            return false;
        }

        @Override
        protected void actionPerformed(GuiButton button) {
        }

        @Override
        protected boolean handleComponentClick(IChatComponent component) {
            return false;
        }

        @Override
        public void sendChatMessage(String msg) {
        }

        @Override
        public void sendChatMessage(String msg, boolean addToChat) {
        }

        @Override
        public void confirmClicked(boolean result, int id) {
        }

        @Override
        public void updateScreen() {
        }

        @Override
        public void onGuiClosed() {
        }

        @Override
        public void handleInput() {
        }

        @Override
        public void handleMouseInput() {
        }

        @Override
        public void handleKeyboardInput() {
        }
    }

    /**
     * The chat input bar (Phase 12, C6): what {@code GuiChat.drawScreen} draws for its input, vanilla's rectangle
     * ({@code 2, height - 14} to {@code width - 2, height - 2}, half-transparent black) and vanilla's
     * {@link GuiTextField} as {@code GuiChat.initGui} sets it up, holding the recorded text and cursor. Not a screen:
     * no {@code GuiChat} is built, so nothing turns on key repeat, reads the live mouse for chat hovers or can send;
     * the field is given the recorded text again every frame. One field per recorded chat, so its scroll follows the
     * typing as vanilla's did; the cursor blinks on the recorded ticks.
     */
    static class ChatBar {
        private GuiTextField field;
        private Object font;
        private int width;
        private int height;
        private long sinceUs = Long.MIN_VALUE;
        private long blinkTicks;

        void forget() {
            field = null;
            sinceUs = Long.MIN_VALUE;
        }

        GuiTextField field(net.minecraft.client.gui.FontRenderer fr, int w, int h, long chatSinceUs, String text,
                           int cursor) {
            if (field == null || font != fr || width != w || height != h || sinceUs != chatSinceUs) {
                field = new GuiTextField(0, fr, 4, h - 12, w - 4, 12);
                field.setEnableBackgroundDrawing(false);
                field.setFocused(true);
                field.setCanLoseFocus(false);
                font = fr;
                width = w;
                height = h;
                sinceUs = chatSinceUs;
                blinkTicks = 0;
            }
            String t = text == null ? "" : text;
            field.setMaxStringLength(Math.max(VANILLA_CHAT_LENGTH, t.length()));
            if (!t.equals(field.getText())) {
                field.setText(t);
            }
            field.setCursorPosition(cursor);
            return field;
        }

        void draw(GuiTextField f, int w, int h, long ticks) {
            if (ticks < blinkTicks) {
                blinkTicks = 0; // a seek back inside this chat: the blink restarts
            }
            for (long n = (ticks - blinkTicks) % 12; n > 0; n--) {
                f.updateCursorCounter(); // only the counter mod 12 shows (6 on, 6 off)
            }
            blinkTicks = ticks;
            Gui.drawRect(2, h - 14, w - 2, h - 2, Integer.MIN_VALUE);
            f.drawTextBox();
        }
    }

    /** {@code GuiChat.initGui}'s input length. */
    static final int VANILLA_CHAT_LENGTH = 100;

    /** Vanilla's {@link GuiGameOver}, drawn as is; its buttons do nothing. */
    static final class HeldGameOver extends GuiGameOver implements Held {
        private boolean initialized;

        @Override
        public void initGui() {
            super.initGui();
            initialized = true;
        }

        @Override
        public boolean initialized() {
            return initialized;
        }

        /** Vanilla enables the buttons on its 20th {@code updateScreen}; here the recorded ticks decide. */
        void buttonsEnabled(boolean enabled) {
            for (GuiButton b : buttonList) {
                b.enabled = enabled;
            }
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int button) {
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
        }

        @Override
        protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        }

        @Override
        protected void keyTyped(char c, int key) {
        }

        @Override
        protected void actionPerformed(GuiButton button) {
        }

        @Override
        protected boolean handleComponentClick(IChatComponent component) {
            return false;
        }

        @Override
        public void sendChatMessage(String msg) {
        }

        @Override
        public void sendChatMessage(String msg, boolean addToChat) {
        }

        @Override
        public void confirmClicked(boolean result, int id) {
        }

        @Override
        public void updateScreen() {
        }

        @Override
        public void onGuiClosed() {
        }

        @Override
        public void handleInput() {
        }

        @Override
        public void handleMouseInput() {
        }

        @Override
        public void handleKeyboardInput() {
        }
    }

    /** Vanilla's chest container; a slot outside it is ignored instead of thrown. */
    static final class WindowChest extends ContainerChest {
        WindowChest(IInventory upper, IInventory lower, EntityPlayer player) {
            super(upper, lower, player);
        }

        @Override
        public void putStackInSlot(int slot, ItemStack stack) {
            if (slot >= 0 && slot < inventorySlots.size()) {
                super.putStackInSlot(slot, stack);
            }
        }

        @Override
        public void putStacksInSlots(ItemStack[] stacks) {
            int n = Math.min(stacks.length, inventorySlots.size());
            for (int i = 0; i < n; i++) {
                getSlot(i).putStack(stacks[i]);
            }
        }
    }

    /**
     * A window that is not drawn: {@code own} slots of its own, then main 9-35 and the hotbar 0-8 of the camera's
     * inventory, the numbering every vanilla container window uses after its own slots.
     */
    static final class PlayerSlots extends Container {
        PlayerSlots(InventoryPlayer inv, int own) {
            int n = Math.max(0, own);
            InventoryBasic box = new InventoryBasic("", false, Math.max(1, n));
            for (int i = 0; i < n; i++) {
                addSlotToContainer(new Slot(box, i, 0, 0));
            }
            for (int i = 0; i < 27; i++) {
                addSlotToContainer(new Slot(inv, 9 + i, 0, 0));
            }
            for (int i = 0; i < 9; i++) {
                addSlotToContainer(new Slot(inv, i, 0, 0));
            }
        }

        @Override
        public boolean canInteractWith(EntityPlayer player) {
            return true;
        }

        @Override
        public void putStackInSlot(int slot, ItemStack stack) {
            if (slot >= 0 && slot < inventorySlots.size()) {
                super.putStackInSlot(slot, stack);
            }
        }

        @Override
        public void putStacksInSlots(ItemStack[] stacks) {
            int n = Math.min(stacks.length, inventorySlots.size());
            for (int i = 0; i < n; i++) {
                getSlot(i).putStack(stacks[i]);
            }
        }
    }
}
