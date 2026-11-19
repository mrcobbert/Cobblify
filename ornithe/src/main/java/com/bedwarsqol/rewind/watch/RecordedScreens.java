package com.bedwarsqol.rewind.watch;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiChest;
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
 * on top of that every input, update and close handler is a no-op ({@link HeldChest}, {@link HeldGameOver}), so
 * nothing they hold can send, close or open anything. Drawn only in your view with the clean view off, at the
 * {@link #cursorX}/{@link #cursorY} position (v1: off screen, so no hover and no carried stack; Phase 12: the
 * recorded mouse). All state follows the tick feed (displays, closes and ticks), so a seek lands on the screens
 * straight play shows.
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
    }

    /** World tick k is about to run: the death screen from the recorded health, then the player's close. */
    @Override
    public void tick(long tick, long timeUs) {
        RewindCamera camera = camera();
        if (camera == null) {
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
        GuiScreen s = screenToDraw();
        Minecraft mc = Minecraft.getMinecraft();
        if (s == null || mc == null || !(mc.thePlayer instanceof RewindCamera)) {
            return;
        }
        if (s.width != width || s.height != height || !((Held) s).initialized()) {
            s.setWorldAndResolution(mc, width, height); // first draw (death screen) or a resize
        }
        if (s == deathScreen) {
            deathScreen.buttonsEnabled(deathTicks >= DEATH_BUTTON_TICKS);
        }
        s.drawScreen(cursorX(width), cursorY(height), partial);
        // what vanilla's container screen leaves behind, put back for the viewer bar drawn next
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
    }

    /** The cursor's x for the held screen. v1 has no mouse position: off screen. Phase 12: the recorded MOUSE. */
    int cursorX(int width) {
        return OFF_SCREEN;
    }

    /** The cursor's y; see {@link #cursorX}. */
    int cursorY(int height) {
        return OFF_SCREEN;
    }

    private static RewindCamera camera() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer instanceof RewindCamera ? (RewindCamera) mc.thePlayer : null;
    }

    // ================================================================== read by tests and later phases

    /** The screen held now: the death screen over a window, or null. */
    GuiScreen held() {
        return deathScreen != null ? deathScreen : windowScreen;
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
