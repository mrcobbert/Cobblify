package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.player.inventory.LocalBlockIntercommunication;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.AnimalChest;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S2DPacketOpenWindow;
import net.minecraft.network.play.server.S2EPacketCloseWindow;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S30PacketWindowItems;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Recorded screens (PLAN "Recorded screens", Phase 9; TASK B9, B10, B13, coordinator amendment 2): chest and shop
 * windows the server opened, and the death screen, held as real vanilla screen objects that are drawn under the
 * viewer bar in your view, never become {@code mc.currentScreen}, take no input and send nothing.
 *
 * <p>Harness: vanilla's own {@link NetHandlerPlayClient} on the socketless {@link PlaybackConnection} processes
 * vanilla's S2D, S2E, S2F and S30, exactly as {@code PlaybackSink.window} hands them over. The client is a
 * {@link Minecraft} built without its constructor (no window, no GL) whose thread is the test thread, so
 * {@code PacketThreadUtil} runs every handler inline; the client player is a {@link RewindCamera} built the same
 * way, with vanilla's {@link InventoryPlayer} and a stand-in for its 45-slot {@code ContainerPlayer} (crafting 0-4,
 * armour 5-8, main 9-35, hotbar 36-44 on the same inventory indices vanilla uses).
 */
public class RecordedScreensTest {

    private static final GameProfile PROFILE = new GameProfile(new UUID(3L, 4L), "FakeViewer");
    private static final int W = 427;
    private static final int H = 240;

    @BeforeClass
    public static void bootstrap() {
        PrintStream out = System.out;
        Bootstrap.register();
        System.setOut(out);
    }

    /** A client with no constructor run; only {@link #isUnicode} is answered (the scaled-size math reads it). */
    static final class Mc extends Minecraft {
        Mc() {
            super(null); // never run: instances come from allocateInstance
        }

        @Override
        public boolean isUnicode() {
            return false;
        }
    }

    /** {@code ContainerPlayer}'s 45-slot layout on the camera's inventory. */
    static final class PlayerContainer extends Container {
        PlayerContainer(InventoryPlayer inv) {
            InventoryBasic craft = new InventoryBasic("craft", false, 5);
            for (int i = 0; i < 5; i++) {
                addSlotToContainer(new Slot(craft, i, 0, 0));
            }
            for (int i = 0; i < 4; i++) {
                addSlotToContainer(new Slot(inv, inv.getSizeInventory() - 1 - i, 0, 0));
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
    }

    /** The context a test controls: the view, the feed position, the recording's tables, health and game mode. */
    static final class Ctx implements RecordedScreens.Context {
        final ViewState view = new ViewState();
        TickFeeder feeder;
        long tick;
        long timeUs;
        SelfTrack track;
        ExtRecords ext = ExtRecords.EMPTY;
        TickTable table;
        float health = 20f;
        boolean spectator;
        long renderUs;
        private ExtRecords extTrackOf;
        private ExtTrack extTrack = ExtTrack.EMPTY;

        @Override
        public ViewState view() {
            return view;
        }

        @Override
        public ExtTrack extTrack() {
            if (extTrackOf != ext) {
                extTrackOf = ext;
                extTrack = ExtTrack.of(ext);
            }
            return extTrack;
        }

        @Override
        public long renderTimeUs() {
            return renderUs;
        }

        @Override
        public int segment() {
            return 0;
        }

        @Override
        public long feedTick() {
            return feeder != null ? feeder.currentTick() : tick;
        }

        @Override
        public long feedTimeUs() {
            return feeder != null ? feeder.recordTimeUs() : timeUs;
        }

        @Override
        public SelfTrack track() {
            return track;
        }

        @Override
        public ExtRecords ext() {
            return ext;
        }

        @Override
        public TickTable table() {
            return table;
        }

        @Override
        public float health(RewindCamera camera) {
            return health;
        }

        @Override
        public boolean recordedSpectator() {
            return spectator;
        }
    }

    private Field theMinecraft;
    private Object previousMc;
    private Mc mc;
    private RewindCamera camera;
    private PlaybackConnection connection;
    private NetHandlerPlayClient handler;
    private Ctx ctx;
    private RecordedScreens screens;

    @Before
    public void setUp() throws Exception {
        mc = (Mc) allocate(Mc.class);
        mc.displayWidth = W * 2;
        mc.displayHeight = H * 2;
        mc.gameSettings = (GameSettings) allocate(GameSettings.class);
        mc.gameSettings.guiScale = 2;
        set(Minecraft.class, mc, "mcThread", Thread.currentThread());
        theMinecraft = Minecraft.class.getDeclaredField("theMinecraft");
        theMinecraft.setAccessible(true);
        previousMc = theMinecraft.get(null);
        theMinecraft.set(null, mc);
        connection = PlaybackConnection.open(mc, PROFILE);
        handler = connection.handler();
        mc.playerController = new PlayerControllerMP(mc, handler);
        camera = newCamera();
        ctx = new Ctx();
        screens = new RecordedScreens(ctx);
        RewindCamera.setScreenHolder(screens);
    }

    @After
    public void tearDown() throws Exception {
        RewindCamera.setScreenHolder(null);
        theMinecraft.set(null, previousMc);
        connection.close();
    }

    /** A fresh camera (S01/S07 make one) as the client player, its inventory filled, its own container open. */
    private RewindCamera newCamera() throws Exception {
        RewindCamera c = (RewindCamera) allocate(RewindCamera.class);
        c.inventory = new InventoryPlayer(c);
        Item[] kinds = {Items.iron_sword, Item.getItemFromBlock(Blocks.wool), Items.gold_ingot, Items.iron_ingot,
                Items.emerald, Items.bow, Items.arrow};
        for (int i = 0; i < 36; i++) {
            Item k = kinds[i % kinds.length];
            c.inventory.mainInventory[i] = new ItemStack(k, k.getItemStackLimit() > 1 ? 1 + i : 1);
        }
        c.inventory.armorInventory[0] = new ItemStack(Items.iron_boots);
        c.inventory.armorInventory[3] = new ItemStack(Items.leather_helmet);
        c.inventoryContainer = new PlayerContainer(c.inventory);
        c.openContainer = c.inventoryContainer;
        set(EntityPlayerSP.class, c, "sendQueue", handler);
        set(EntityLivingBase.class, c, "activePotionsMap", new HashMap<Integer, PotionEffect>());
        mc.thePlayer = c;
        return c;
    }

    private void feed(Packet<INetHandlerPlayClient> p) {
        p.processPacket(handler);
    }

    private static IChatComponent title(String s) {
        return new ChatComponentText(s);
    }

    private static List<ItemStack> chestThenPlayer(ItemStack[] chest, InventoryPlayer inv) {
        List<ItemStack> all = new ArrayList<ItemStack>(Arrays.asList(chest));
        for (int i = 9; i < 36; i++) {
            all.add(copy(inv.mainInventory[i]));
        }
        for (int i = 0; i < 9; i++) {
            all.add(copy(inv.mainInventory[i]));
        }
        return all;
    }

    private static ItemStack copy(ItemStack s) {
        return s == null ? null : s.copy();
    }

    /** item id, count and damage of each slot of the camera's inventory (36 main + 4 armour) and its cursor. */
    private static String[] inventory(InventoryPlayer inv) {
        String[] out = new String[41];
        for (int i = 0; i < 36; i++) {
            out[i] = describe(inv.mainInventory[i]);
        }
        for (int i = 0; i < 4; i++) {
            out[36 + i] = describe(inv.armorInventory[i]);
        }
        out[40] = describe(inv.getItemStack());
        return out;
    }

    private static String describe(ItemStack s) {
        return s == null ? "-" : Item.getIdFromItem(s.getItem()) + "x" + s.stackSize + ":" + s.getMetadata();
    }

    // ------------------------------------------------------------------ open lifecycle (round 2 B3)

    /**
     * The 90-slot double chest filled by an S30 in the same drain as its S2D, before any frame is drawn: the window's
     * own container is the camera's open container when the handler writes the id, so the S30's 90 stacks fill the
     * 54 chest slots and the 36 player slots, and the inventory container is untouched. On today's order (construct
     * only, initialize at the first draw) the id lands on the 45-slot inventory container and the S30 throws at slot
     * 45 after overwriting crafting, armour and inventory.
     */
    @Test
    public void aDoubleChestFilledBeforeTheFirstDrawFillsItsNinetySlotsAndLeavesTheInventoryIntact() {
        String[] before = inventory(camera.inventory);
        Container own = camera.inventoryContainer;
        ItemStack[] chest = new ItemStack[54];
        for (int i = 0; i < 54; i++) {
            chest[i] = new ItemStack(Items.diamond, 1 + i % 64);
        }
        List<ItemStack> contents = chestThenPlayer(chest, camera.inventory);
        // One drain, no frame in between: S2D, then its S30.
        feed(new S2DPacketOpenWindow(7, "minecraft:container", title("Large Chest"), 54));
        feed(new S30PacketWindowItems(7, contents));

        assertNotSame("the window's container was open when the handler wrote its id", own, camera.openContainer);
        assertEquals(90, camera.openContainer.inventorySlots.size());
        assertEquals(7, camera.openContainer.windowId);
        assertEquals("the inventory container keeps window id 0", 0, own.windowId);

        for (int i = 0; i < 54; i++) {
            assertEquals("chest slot " + i, 1 + i % 64, camera.openContainer.getSlot(i).getStack().stackSize);
        }
        assertArrayEquals("the player's own inventory is left intact", before, inventory(camera.inventory));
        for (int i = 0; i < 5; i++) {
            assertNull("crafting slot " + i, own.getSlot(i).getStack());
        }
        GuiScreen held = screens.held();
        assertTrue(held instanceof GuiChest);
        assertSame(((GuiContainer) held).inventorySlots, camera.openContainer);
        assertNull("never the current screen", mc.currentScreen);
    }

    /** The S2D handler installs the container and initializes the screen before it returns (round 2 B3). */
    @Test
    public void theCamerasChestCallInstallsTheContainerAndInitializesTheScreenBeforeItReturns() throws IOException {
        String camSrc = PlaybackIsolationScanTest.strip(PlaybackIsolationScanTest.read(
                new File(PlaybackIsolationScanTest.mainRoot(), "com/bedwarsqol/rewind/watch/RewindCamera.java")));
        String chestCall = body(camSrc, "public void displayGUIChest(IInventory inventory)");
        assertTrue(chestCall, chestCall.contains("screens.displayChest(this, inventory)"));

        String src = PlaybackIsolationScanTest.strip(PlaybackIsolationScanTest.read(
                new File(PlaybackIsolationScanTest.mainRoot(), "com/bedwarsqol/rewind/watch/RecordedScreens.java")));
        String open = body(src, "private void openChest(RewindCamera camera, IInventory lower)");
        int install = open.indexOf("camera.openContainer = ");
        int init = open.indexOf(".setWorldAndResolution(");
        assertTrue("installs the container: " + open, install >= 0);
        assertTrue("initializes the screen: " + open, init >= 0);
        assertTrue("container first, then the screen's init", install < init);

        // and at run time, with no draw in between
        feed(new S2DPacketOpenWindow(3, "minecraft:chest", title("Item Shop"), 54));
        GuiContainer held = (GuiContainer) screens.held();
        assertEquals("initialized at the scaled size", W, held.width);
        assertEquals(H, held.height);
    }

    // ------------------------------------------------------------------ packets

    @Test
    public void s2dHoldsAChestWithTheWindowIdS30AndS2fFillItAndS2eClearsItAndRestoresTheContainer() {
        feed(new S2DPacketOpenWindow(5, "minecraft:chest", title("Chest"), 27));
        GuiScreen held = screens.held();
        assertTrue(held instanceof GuiChest);
        assertEquals(5, camera.openContainer.windowId);
        assertEquals(63, camera.openContainer.inventorySlots.size());
        ItemStack[] chest = new ItemStack[27];
        chest[4] = new ItemStack(Items.ender_pearl, 2);
        feed(new S30PacketWindowItems(5, chestThenPlayer(chest, camera.inventory)));
        feed(new S2FPacketSetSlot(5, 10, new ItemStack(Items.golden_apple, 3)));
        feed(new S2FPacketSetSlot(-1, -1, new ItemStack(Items.stick)));
        assertEquals(2, camera.openContainer.getSlot(4).getStack().stackSize);
        assertEquals(3, camera.openContainer.getSlot(10).getStack().stackSize);
        assertNotNull("S2F window -1 sets the cursor stack", camera.inventory.getItemStack());

        feed(new S2EPacketCloseWindow(5));
        assertNull(screens.held());
        assertNull(screens.window());
        assertSame(camera.inventoryContainer, camera.openContainer);
        assertNull("a close drops the cursor stack, as vanilla's closeScreenAndDropStack", camera.inventory.getItemStack());
        assertEquals(0, connection.discarded());
    }

    @Test
    public void aNewS2dReplacesTheHeldWindow() {
        feed(new S2DPacketOpenWindow(5, "minecraft:chest", title("Item Shop"), 54));
        GuiScreen first = screens.held();
        feed(new S2DPacketOpenWindow(6, "minecraft:chest", title("Quick Buy"), 54));
        assertNotSame(first, screens.held());
        assertEquals(6, camera.openContainer.windowId);
        feed(new S2FPacketSetSlot(5, 0, new ItemStack(Items.diamond))); // the old window's id: ignored
        assertNull(camera.openContainer.getSlot(0).getStack());
    }

    /**
     * Coordinator amendment 2: the server updates the player's slots through the open shop window's id; those
     * updates reach the camera's inventory (the hotbar), in your view and also where the window is not drawn.
     */
    @Test
    public void aShopPurchaseUpdatesTheHotbarThroughTheWindowIdInEveryView() {
        for (int mode = 0; mode < 3; mode++) {
            if (mode == 1) {
                ctx.view.selectFree();
            } else if (mode == 2) {
                ctx.view.selectYou();
                ctx.view.toggleClean();
            }
            String label = mode == 0 ? "your view" : mode == 1 ? "free camera" : "clean view";
            int id = 10 + mode;
            feed(new S2DPacketOpenWindow(id, "minecraft:chest", title("Item Shop"), 54));
            assertEquals(label, mode == 0 ? screens.held() : null, screens.screenToDraw());
            int hotbar0 = 54 + 27; // the window's slots: 54 own, 27 main, then the hotbar
            feed(new S2FPacketSetSlot(id, hotbar0, new ItemStack(Items.gold_ingot, 5 + mode)));
            feed(new S2FPacketSetSlot(id, hotbar0 + 3, new ItemStack(Items.stone_sword)));
            feed(new S2EPacketCloseWindow(id));
            assertEquals(label, "266x" + (5 + mode) + ":0", describe(camera.inventory.mainInventory[0]));
            assertEquals(label, Item.getIdFromItem(Items.stone_sword),
                    Item.getIdFromItem(camera.inventory.mainInventory[3].getItem()));
            assertSame(label, camera.inventoryContainer, camera.openContainer);
        }
    }

    /** Windows that are not drawn still get a container on the camera's inventory with vanilla's slot numbering. */
    @Test
    public void windowsThatAreNotDrawnStillRouteThePlayersSlotsToTheHotbar() throws Exception {
        Container own = camera.inventoryContainer;
        // a villager: 3 own slots, not held
        feed(new S2DPacketOpenWindow(21, "minecraft:villager", title("Villager"), 3));
        assertNull("a villager window is not held", screens.held());
        assertNotSame("but the id never lands on the inventory container", own, camera.openContainer);
        assertEquals(0, own.windowId);
        assertEquals(21, camera.openContainer.windowId);
        feed(new S2FPacketSetSlot(21, 3 + 27 + 2, new ItemStack(Items.emerald, 9)));
        assertEquals("388x9:0", describe(camera.inventory.mainInventory[2]));
        feed(new S30PacketWindowItems(21, new ArrayList<ItemStack>(Arrays.asList(new ItemStack[39]))));
        assertNull(camera.inventory.mainInventory[2]);

        // a hopper (a container window vanilla draws with its own screen): 5 own slots
        feed(new S2DPacketOpenWindow(22, "minecraft:hopper", title("Hopper"), 5));
        assertNull(screens.held());
        feed(new S2FPacketSetSlot(22, 5, new ItemStack(Items.apple, 4))); // the first main slot (index 9)
        assertEquals("260x4:0", describe(camera.inventory.mainInventory[9]));

        // a crafting table (slotless S2D): result + 9 grid slots
        feed(new S2DPacketOpenWindow(23, "minecraft:crafting_table", title("Crafting"), 0));
        assertNull(screens.held());
        assertEquals(23, camera.openContainer.windowId);
        feed(new S2FPacketSetSlot(23, 10 + 27 + 8, new ItemStack(Items.bread, 2)));
        assertEquals("297x2:0", describe(camera.inventory.mainInventory[8]));

        // a horse (its S2D needs the horse entity; the camera's call is what the handler makes)
        camera.displayGUIHorse(null, new AnimalChest("Horse", 17));
        assertEquals(17 + 36, camera.openContainer.inventorySlots.size());

        // out-of-range slots never throw (a bad packet is not a playback failure)
        camera.openContainer.windowId = 24;
        feed(new S2FPacketSetSlot(24, 400, new ItemStack(Items.apple)));
        feed(new S30PacketWindowItems(24, new ArrayList<ItemStack>(Arrays.asList(new ItemStack[90]))));

        feed(new S2EPacketCloseWindow(24));
        assertSame(own, camera.openContainer);
        camera.displayGui(new LocalBlockIntercommunication("minecraft:anvil", title("Repair")));
        assertEquals(3 + 36, camera.openContainer.inventorySlots.size());
        camera.displayGui(new LocalBlockIntercommunication("minecraft:enchanting_table", title("Enchant")));
        assertEquals(2 + 36, camera.openContainer.inventorySlots.size());
        assertEquals(0, connection.discarded());
    }

    // ------------------------------------------------------------------ v1 close rule (review B3)

    private static final long T0 = 1_000_000L;

    private static long head(long k) {
        return T0 + k * 50_000L;
    }

    private void tableAndTrack(long... cameraTimes) {
        int ticks = 60;
        long[] heads = new long[ticks];
        for (int i = 0; i < ticks; i++) {
            heads[i] = head(i);
        }
        ctx.table = TickTable.v2(T0, heads, new int[ticks], new int[ticks], new int[ticks], ticks);
        SelfTrackEncoder enc = new SelfTrackEncoder();
        SelfTrack.Builder b = new SelfTrack.Builder().segment(1);
        int c = 0;
        for (int i = 0; i < ticks; i++) {
            long t = head(i) + 10_000L;
            while (c < cameraTimes.length && cameraTimes[c] <= t) {
                byte[] body = new byte[8];
                RewindFormat.putFloat(body, 0, 5f + c);
                b.record(cameraTimes[c], RewindFormat.TAG_CAMERA, body, 0, 8);
                c++;
            }
            int n = enc.tick(i, 64, 0, 0f, 0f, 0f, 0, 0, i == 31 ? 1 : 0, 0); // a swing starts in tick 30
            b.record(t, RewindFormat.TAG_TICK, enc.buffer(), 0, n);
        }
        ctx.track = b.build();
    }

    /** Opens window {@code id} in the feed of tick {@code k} (stamped 5 ms before its head), then runs ticks. */
    private void openAt(int id, long k) {
        ctx.tick = k;
        ctx.timeUs = head(k) - 5_000L;
        feed(new S2DPacketOpenWindow(id, "minecraft:chest", title("Item Shop"), 54));
    }

    private long firstTickWithoutWindow(long from, long to) {
        for (long k = from; k <= to; k++) {
            screens.tick(k, head(k));
            if (screens.held() == null) {
                return k;
            }
        }
        return -1;
    }

    @Test
    public void v1WindowClosesAtTheFirstCameraTurnMoreThan100msAfterTheOpenNotAt40ms() {
        tableAndTrack(head(10) + 35_000L, head(14) + 20_000L);
        openAt(5, 10);
        assertEquals(15, firstTickWithoutWindow(10, 25));
    }

    @Test
    public void v1WindowClosesAtASwingStartAndAtABoundary() {
        tableAndTrack();
        openAt(5, 20);
        assertEquals("the swing starting in tick 30", 30, firstTickWithoutWindow(20, 40));

        openAt(6, 40);
        screens.tick(40, head(40));
        assertNotNull(screens.held());
        screens.boundary();
        assertNull("an S01/S07 (a new camera) ends it", screens.held());
        assertNull(screens.window());
    }

    /**
     * After a close the player made (no packet: the heuristic), the window is no longer drawn, but its container
     * stays routed until the server's close, the next window or a boundary: if the guess was early (a server teleport
     * turned the view), the shop's later slot updates still reach the hotbar; if it was right, the server uses window
     * 0 from then on, which vanilla routes to the inventory container anyway.
     */
    @Test
    public void afterAHeuristicCloseLaterSlotUpdatesThroughTheWindowStillReachTheHotbar() {
        tableAndTrack(head(12));
        openAt(9, 10);
        assertEquals(12, firstTickWithoutWindow(10, 20));
        assertNull(screens.screenToDraw());
        feed(new S2FPacketSetSlot(9, 54 + 27 + 1, new ItemStack(Items.iron_ingot, 7)));
        assertEquals("265x7:0", describe(camera.inventory.mainInventory[1]));
        feed(new S2FPacketSetSlot(0, 36 + 1, new ItemStack(Items.iron_ingot, 6)));
        assertEquals("window 0 goes to the inventory container", "265x6:0",
                describe(camera.inventory.mainInventory[1]));
        feed(new S2EPacketCloseWindow(9));
        assertSame(camera.inventoryContainer, camera.openContainer);
    }

    @Test
    public void onARecordingWithViewRecordsTheViewScreenKindClosesItAndNoHeuristicDoes() {
        tableAndTrack(head(12), head(13));
        ExtCodec codec = new ExtCodec();
        ExtRecords.Builder e = new ExtRecords.Builder();
        int n = codec.view(0, 0, RewindFormat.SCREEN_CHEST);
        e.addEncoded(0, head(10) + 8_000L, RewindFormat.TAG_VIEW, codec.buffer(), n);
        n = codec.view(0, 0, RewindFormat.SCREEN_NONE);
        e.addEncoded(0, head(36) + 8_000L, RewindFormat.TAG_VIEW, codec.buffer(), n);
        ctx.ext = e.build();
        openAt(5, 10);
        assertEquals("no camera or swing close (the swing in tick 30 too); the VIEW record at 36 + 8 ms", 37,
                firstTickWithoutWindow(10, 50));
    }

    // ------------------------------------------------------------------ death screen (B9)

    @Test
    public void healthZeroHoldsTheDeathScreenUntilTheRespawnAndClosesAnOpenWindow() {
        tableAndTrack();
        openAt(5, 10);
        screens.tick(10, head(10));
        ctx.health = 0f;
        screens.tick(11, head(11));
        assertTrue(screens.held() instanceof GuiGameOver);
        assertSame("dying closes the window, as vanilla's GuiContainer.updateScreen does", camera.inventoryContainer,
                camera.openContainer);
        screens.tick(12, head(12));
        assertTrue(screens.screenToDraw() instanceof GuiGameOver);
        ctx.view.selectFree();
        assertNull("not drawn in the free camera", screens.screenToDraw());
        ctx.view.selectYou();
        screens.boundary(); // S07: the respawn
        ctx.health = 20f;
        screens.tick(13, head(13));
        assertNull(screens.held());

        ctx.health = 0f;
        screens.tick(14, head(14));
        assertTrue(screens.held() instanceof GuiGameOver);
        ctx.health = 20f; // health above 0 again (a server that heals instead of respawning)
        screens.tick(15, head(15));
        assertNull(screens.held());
    }

    @Test
    public void noDeathScreenInRecordedSpectatorMode() {
        ctx.spectator = true;
        ctx.health = 0f;
        screens.tick(1, head(1));
        assertNull(screens.held());
    }

    @Test
    public void heldScreensAreDrawnOnlyInYourViewWithTheCleanViewOff() {
        feed(new S2DPacketOpenWindow(5, "minecraft:chest", title("Chest"), 27));
        GuiScreen held = screens.held();
        assertSame(held, screens.screenToDraw());
        ctx.view.selectFree();
        assertNull(screens.screenToDraw());
        ctx.view.ride(77);
        assertNull("another player's view", screens.screenToDraw());
        ctx.view.selectYou();
        ctx.view.toggleClean();
        assertNull("the clean view", screens.screenToDraw());
        ctx.view.toggleClean();
        assertSame(held, screens.screenToDraw());
        assertEquals("v1: no recorded mouse, so no hover and no carried stack on screen", RecordedScreens.OFF_SCREEN,
                screens.cursorX(W));
    }

    // ------------------------------------------------------------------ isolation (B13)

    private static final String[][] HANDLERS = {
            {"mouseClicked", "int,int,int"},
            {"mouseReleased", "int,int,int"},
            {"mouseClickMove", "int,int,int,long"},
            {"keyTyped", "char,int"},
            {"actionPerformed", "GuiButton"},
            {"handleComponentClick", "IChatComponent"},
            {"sendChatMessage", "String"},
            {"sendChatMessage", "String,boolean"},
            {"confirmClicked", "boolean,int"},
            {"updateScreen", ""},
            {"onGuiClosed", ""},
            {"handleInput", ""},
            {"handleMouseInput", ""},
            {"handleKeyboardInput", ""},
    };

    /** Calls every input, update and close handler of {@code screen} through reflection (vanilla never would). */
    private void driveEveryHandler(GuiScreen screen) throws Exception {
        Object[][] calls = {
                {W / 2, H / 2, 0}, {W / 2, H / 2, 0}, {W / 2, H / 2, 0, 50L}, {'e', 18}, {null},
                {new ChatComponentText("/x")}, {"/x"}, {"/x", true}, {true, 0}, {}, {}, {}, {}, {}};
        for (int i = 0; i < HANDLERS.length; i++) {
            Method m = find(screen.getClass(), HANDLERS[i][0], HANDLERS[i][1]);
            Object[] args = calls[i];
            if ("actionPerformed".equals(HANDLERS[i][0])) {
                args = new Object[]{new GuiButton(0, 0, 0, "Respawn")};
            }
            try {
                m.invoke(screen, args);
            } catch (InvocationTargetException e) {
                throw new AssertionError(HANDLERS[i][0] + " ran vanilla code: " + e.getCause(), e.getCause());
            }
        }
        // keys the player would use: Esc, E, a hotbar number; and a click on a slot
        find(screen.getClass(), "keyTyped", "char,int").invoke(screen, (char) 27, 1);
        find(screen.getClass(), "keyTyped", "char,int").invoke(screen, '1', 2);
        if (screen instanceof GuiContainer) {
            Container c = ((GuiContainer) screen).inventorySlots;
            int last = Math.min(60, c.inventorySlots.size() - 1);
            find(screen.getClass(), "handleMouseClick", "Slot,int,int,int").invoke(screen, c.getSlot(0), 0, 0, 0);
            find(screen.getClass(), "handleMouseClick", "Slot,int,int,int").invoke(screen, c.getSlot(last), last, 0,
                    1);
            find(screen.getClass(), "checkHotbarKeys", "int").invoke(screen, 2);
        }
    }

    @Test
    public void heldScreensAreNeverCurrentTakeNoInputAndSendNothing() throws Exception {
        feed(new S2DPacketOpenWindow(5, "minecraft:chest", title("Item Shop"), 54));
        ItemStack[] chest = new ItemStack[54];
        chest[0] = new ItemStack(Items.diamond_sword);
        feed(new S30PacketWindowItems(5, chestThenPlayer(chest, camera.inventory)));
        GuiScreen held = screens.held();
        Container window = camera.openContainer;
        String[] before = inventory(camera.inventory);

        driveEveryHandler(held);

        assertEquals("nothing reached the playback channel", 0, connection.discarded());
        assertNull("never mc.currentScreen", mc.currentScreen);
        assertSame("still open: no handler closed it", window, camera.openContainer);
        assertSame(held, screens.held());
        assertArrayEquals(before, inventory(camera.inventory));
        assertEquals(Item.getIdFromItem(Items.diamond_sword), Item.getIdFromItem(window.getSlot(0).getStack().getItem()));

        ctx.health = 0f;
        screens.tick(1, head(1));
        GuiScreen death = screens.held();
        assertTrue(death instanceof GuiGameOver);
        driveEveryHandler(death);
        assertEquals("the death screen's buttons do nothing", 0, connection.discarded());
        assertNull(mc.currentScreen);
        assertSame(death, screens.held());
    }

    /** The harness sees a send: vanilla's own GuiChest, driven the same way, sends C0E into the discard. */
    @Test
    public void controlVanillasOwnChestScreenWouldSendAClickIntoTheDiscard() throws Exception {
        GuiChest vanilla = new GuiChest(camera.inventory, new InventoryBasic("Chest", false, 27));
        vanilla.setWorldAndResolution(mc, W, H);
        vanilla.inventorySlots.windowId = 5;
        find(GuiChest.class, "handleMouseClick", "Slot,int,int,int").invoke(vanilla,
                vanilla.inventorySlots.getSlot(0), 0, 0, 0);
        assertEquals(1, connection.discarded());
    }

    @Test
    public void recordedScreensNeverOpenAScreenOrSend() throws IOException {
        for (String file : new String[]{"RecordedScreens", "ViewPart", "FovPart", "CursorPainter"}) {
            String src = PlaybackIsolationScanTest.strip(PlaybackIsolationScanTest.read(new File(
                    PlaybackIsolationScanTest.mainRoot(), "com/bedwarsqol/rewind/watch/" + file + ".java")));
            for (String banned : new String[]{"displayGuiScreen", "currentScreen =", "addToSendQueue", "sendPacket",
                    "playerController", "windowClick", "sendChatMessage("}) {
                if (file.equals("RecordedScreens") && banned.equals("sendChatMessage(")) {
                    continue; // the held screens override vanilla's sendChatMessage with no-ops
                }
                assertFalse(file + ": " + banned, src.contains(banned));
            }
        }
    }

    /**
     * Phase 12 (B13, C5, C6): the inventory screen and the chat input bar are drawn, never opened: never
     * {@code mc.currentScreen}, every input handler a no-op, nothing reaches the playback channel, and typing into the
     * bar's field (vanilla never would) does not outlive the next frame's recorded text.
     */
    @Test
    public void theInventoryScreenAndTheChatBarAreNeverCurrentTakeNoInputAndSendNothing() throws Exception {
        ExtRecords.Builder b = new ExtRecords.Builder();
        view(b, 100 * MS, RewindFormat.SCREEN_INVENTORY);
        view(b, 200 * MS, RewindFormat.SCREEN_NONE);
        view(b, 300 * MS, RewindFormat.SCREEN_CHAT);
        chat(b, 310 * MS, "/ping", 5);
        ctx.ext = b.build();
        ctx.renderUs = 150 * MS;
        GuiScreen inv = screens.held();
        assertTrue(inv instanceof GuiInventory);
        String[] before = inventory(camera.inventory);
        Container open = camera.openContainer;

        driveEveryHandler(inv);

        assertEquals("nothing reached the playback channel", 0, connection.discarded());
        assertNull("never mc.currentScreen", mc.currentScreen);
        assertSame("no handler closed or replaced the open container", open, camera.openContainer);
        assertSame(inv, screens.held());
        assertArrayEquals(before, inventory(camera.inventory));

        ctx.renderUs = 320 * MS;
        GuiTextField field = screens.chatField(W, H);
        assertEquals("/ping", field.getText());
        field.writeText("x"); // what a key typed into it would do (textboxKeyTyped needs a live keyboard)
        field.deleteFromCursor(-3);
        assertEquals("/ping", screens.chatField(W, H).getText());
        assertEquals(0, connection.discarded());
        assertNull(mc.currentScreen);
    }

    // ------------------------------------------------------------------ Phase 12: v2 screens (C5, C6, B9 on v2)

    private static final long MS = 1_000L;
    private static final ExtCodec CODEC = new ExtCodec();

    private static void view(ExtRecords.Builder b, long us, int kind) {
        b.addEncoded(0, us, RewindFormat.TAG_VIEW, CODEC.buffer(), CODEC.view(0, 0, kind));
    }

    private static void mouse(ExtRecords.Builder b, long us, int dx, int dy) {
        b.addEncoded(0, us, RewindFormat.TAG_MOUSE, CODEC.buffer(), CODEC.mouse(dx, dy));
    }

    private static void chat(ExtRecords.Builder b, long us, String text, int cursor) {
        b.addEncoded(0, us, RewindFormat.TAG_CHAT_INPUT, CODEC.buffer(), CODEC.chatInput(text, cursor));
    }

    /** C5: the inventory screen (E) is drawn from the recorded open to the recorded close, on the camera's own slots. */
    @Test
    public void onAV2RecordingTheInventoryScreenIsDrawnFromTheRecordedOpenToTheRecordedClose() throws Exception {
        ExtRecords.Builder b = new ExtRecords.Builder();
        view(b, 100 * MS, RewindFormat.SCREEN_NONE);
        view(b, 200 * MS, RewindFormat.SCREEN_INVENTORY);
        view(b, 300 * MS, RewindFormat.SCREEN_NONE);
        view(b, 400 * MS, RewindFormat.SCREEN_INVENTORY);
        ctx.ext = b.build();
        Container own = camera.openContainer;

        ctx.renderUs = 150 * MS;
        assertNull(screens.held());
        ctx.renderUs = 250 * MS;
        GuiScreen inv = screens.held();
        assertTrue("vanilla's inventory screen", inv instanceof GuiInventory);
        assertSame("on the camera's own container", camera.inventoryContainer, ((GuiContainer) inv).inventorySlots);
        assertEquals("initialized at the scaled size", W, inv.width);
        assertEquals(H, inv.height);
        assertSame("holding it never changes the open container", own, camera.openContainer);
        assertSame(inv, screens.screenToDraw());
        ctx.renderUs = 260 * MS;
        assertSame("one screen while it stays open", inv, screens.held());

        ctx.view.selectFree();
        assertNull("not in the free camera", screens.screenToDraw());
        ctx.view.selectYou();
        ctx.view.cyclePerspective();
        assertSame("F5 keeps recorded screens", inv, screens.screenToDraw());
        ctx.view.toggleClean();
        assertNull("not in the clean view", screens.screenToDraw());
        ctx.view.selectYou();

        ctx.renderUs = 350 * MS;
        assertNull("the recorded close", screens.held());
        ctx.renderUs = 450 * MS;
        GuiScreen again = screens.held();
        assertTrue(again instanceof GuiInventory);
        assertNotSame("a new open is a new screen, as vanilla's E", inv, again);

        screens.boundary();
        RewindCamera fresh = newCamera(); // S07: a new camera
        GuiScreen onFresh = screens.held();
        assertSame("a new camera's inventory", fresh.inventoryContainer, ((GuiContainer) onFresh).inventorySlots);
        assertNull(mc.currentScreen);
    }

    /** A v1 recording (no VIEW records) never holds an inventory screen. */
    @Test
    public void aV1RecordingHoldsNoInventoryScreen() {
        ctx.renderUs = 5_000 * MS;
        assertNull(screens.held());
        assertFalse(screens.chatShown());
    }

    /**
     * C5: the cursor is the recorded MOUSE, at the exact GUI pixel vanilla handed {@code drawScreen} while recording
     * ({@code Mouse.getX() * scaledW / displayW}, {@code scaledH - Mouse.getY() * scaledH / displayH - 1}), through the
     * recorder's own encoding, for every display size, GUI scale and position tried.
     */
    @Test
    public void theCursorIsTheRecordedMouseAtVanillasExactPixel() throws Exception {
        Class<?> capture = Class.forName("com.bedwarsqol.rewind.ViewCapture");
        Method encodeX = capture.getDeclaredMethod("mouseDx", int.class, int.class, int.class);
        Method encodeY = capture.getDeclaredMethod("mouseDy", int.class, int.class, int.class);
        encodeX.setAccessible(true);
        encodeY.setAccessible(true);
        int[][] displays = {{854, 480}, {1366, 768}, {1920, 1080}, {2560, 1440}, {3456, 2234}, {1001, 777}};
        int cases = 0;
        for (int[] d : displays) {
            for (int guiScale = 0; guiScale <= 4; guiScale++) {
                mc.displayWidth = d[0];
                mc.displayHeight = d[1];
                mc.gameSettings.guiScale = guiScale;
                net.minecraft.client.gui.ScaledResolution sr = new net.minecraft.client.gui.ScaledResolution(mc);
                int sw = sr.getScaledWidth();
                int sh = sr.getScaledHeight();
                int[] xs = {0, 1, d[0] / 3, d[0] / 2, d[0] - 2, d[0] - 1};
                int[] ys = {0, 1, d[1] / 3, d[1] / 2, d[1] - 2, d[1] - 1};
                for (int mx : xs) {
                    for (int my : ys) {
                        int dx = (Integer) encodeX.invoke(null, mx, d[0], sw);
                        int dy = (Integer) encodeY.invoke(null, my, d[1], sh);
                        ExtRecords.Builder b = new ExtRecords.Builder();
                        view(b, 10 * MS, RewindFormat.SCREEN_CHEST);
                        mouse(b, 20 * MS, dx, dy);
                        ctx.ext = b.build();
                        ctx.renderUs = 30 * MS;
                        String at = d[0] + "x" + d[1] + " scale " + guiScale + " mouse " + mx + "," + my;
                        assertEquals(at, mx * sw / d[0], screens.cursorX(sw));
                        assertEquals(at, sh - my * sh / d[1] - 1, screens.cursorY(sh));
                        cases++;
                    }
                }
            }
        }
        assertEquals(6 * 5 * 36, cases);
    }

    @Test
    public void theCursorIsCentredBeforeTheScreensFirstMouseRecordAndOffScreenWithoutAScreenOrAMouseRecord() {
        ExtRecords.Builder b = new ExtRecords.Builder();
        view(b, 10 * MS, RewindFormat.SCREEN_CHEST);
        mouse(b, 11 * MS, 400, 200);
        view(b, 30 * MS, RewindFormat.SCREEN_NONE);
        view(b, 40 * MS, RewindFormat.SCREEN_INVENTORY);
        ctx.ext = b.build();
        ctx.renderUs = 20 * MS;
        assertEquals((2 * W + 400) / 4, screens.cursorX(W));
        assertEquals((2 * H + 200) / 4, screens.cursorY(H));
        ctx.renderUs = 35 * MS;
        assertEquals("no screen open", RecordedScreens.OFF_SCREEN, screens.cursorX(W));
        ctx.renderUs = 45 * MS;
        assertEquals("a new screen opens with the cursor centred, not where the chest left it", W / 2,
                screens.cursorX(W));
        assertEquals(H / 2, screens.cursorY(H));

        b = new ExtRecords.Builder();
        view(b, 10 * MS, RewindFormat.SCREEN_INVENTORY);
        ctx.ext = b.build();
        ctx.renderUs = 20 * MS;
        assertEquals("a recording without MOUSE records", RecordedScreens.OFF_SCREEN, screens.cursorX(W));
    }

    /** C5: the recorded mouse is over the slot it was over (vanilla's hover highlight and tooltip read this slot). */
    @Test
    public void theRecordedMouseHoversTheSlotItWasOver() throws Exception {
        feed(new S2DPacketOpenWindow(5, "minecraft:chest", title("Item Shop"), 54));
        GuiContainer held = (GuiContainer) screens.held();
        int left = (Integer) get(GuiContainer.class, held, "guiLeft");
        int top = (Integer) get(GuiContainer.class, held, "guiTop");
        Method slotAt = GuiContainer.class.getDeclaredMethod("getSlotAtPosition", int.class, int.class);
        slotAt.setAccessible(true);
        for (int index : new int[]{0, 13, 53, 54, 89}) {
            Slot s = held.inventorySlots.getSlot(index);
            int x = left + s.xDisplayPosition + 8;
            int y = top + s.yDisplayPosition + 8;
            ExtRecords.Builder b = new ExtRecords.Builder();
            view(b, 10 * MS, RewindFormat.SCREEN_CHEST);
            mouse(b, 20 * MS, 4 * x + 1 - 2 * W, 4 * y + 2 - 2 * H);
            ctx.ext = b.build();
            ctx.renderUs = 30 * MS;
            assertSame("slot " + index, s, slotAt.invoke(held, screens.cursorX(W), screens.cursorY(H)));
        }
    }

    /** The v2 close is exact: drawing stops in the first frame at or after the recorded close, not at the next tick. */
    @Test
    public void onAV2RecordingTheWindowStopsBeingDrawnInTheFrameOfTheRecordedClose() {
        tableAndTrack();
        ExtRecords.Builder b = new ExtRecords.Builder();
        view(b, head(10) + 8_000L, RewindFormat.SCREEN_CHEST);
        view(b, head(20) + 10_000L, RewindFormat.SCREEN_NONE);
        ctx.ext = b.build();
        openAt(5, 10);
        ctx.renderUs = head(10) + 2_000L;
        assertTrue("drawn from the open, before the first VIEW that names it", screens.screenToDraw() instanceof GuiChest);
        for (long k = 10; k <= 20; k++) {
            screens.tick(k, head(k));
        }
        ctx.renderUs = head(20) + 5_000L;
        assertTrue(screens.screenToDraw() instanceof GuiChest);
        ctx.renderUs = head(20) + 10_000L;
        assertNull("the frame of the recorded close, before tick 21", screens.screenToDraw());
        assertNotNull("the container stays routed until the server's close", screens.window());
        ctx.renderUs = head(20) + 5_000L;
        assertTrue("by playback time: a frame before the close still draws it", screens.screenToDraw() instanceof GuiChest);
        screens.tick(21, head(21));
        assertNull(screens.held());
    }

    /** B9 on v2: the death screen is held exactly while the recorded screen is the death screen. */
    @Test
    public void onAV2RecordingTheDeathScreenFollowsTheRecordedScreenKind() {
        tableAndTrack();
        ExtRecords.Builder b = new ExtRecords.Builder();
        view(b, head(10) + 8_000L, RewindFormat.SCREEN_DEATH);
        view(b, head(40) + 8_000L, RewindFormat.SCREEN_NONE);
        ctx.ext = b.build();
        ctx.health = 0f;
        screens.tick(10, head(10));
        ctx.renderUs = head(10) + 2_000L;
        assertNull("health alone does not open it on v2: the recorded screen does", screens.held());
        ctx.renderUs = head(10) + 9_000L;
        assertTrue(screens.held() instanceof GuiGameOver);
        assertFalse(screens.deathButtonsEnabled());
        ctx.health = 20f;
        screens.tick(11, head(11));
        ctx.renderUs = head(29) + 9_000L;
        assertTrue("health above 0 does not end it", screens.held() instanceof GuiGameOver);
        assertFalse("19 ticks after the open", screens.deathButtonsEnabled());
        ctx.renderUs = head(30) + 9_000L;
        assertTrue("20 ticks after the open, as vanilla's updateScreen", screens.deathButtonsEnabled());
        ctx.renderUs = head(40) + 9_000L;
        assertNull("the recorded close (Respawn)", screens.held());
    }

    /** C6: chat typing shows vanilla's input bar (its rectangle and text field) with the recorded text and cursor. */
    @Test
    public void chatTypingShowsVanillasInputBarWithTheRecordedText() {
        ExtRecords.Builder b = new ExtRecords.Builder();
        view(b, 100 * MS, RewindFormat.SCREEN_CHAT);
        chat(b, 120 * MS, "gg wp", 5);
        chat(b, 140 * MS, "gg", 2);
        view(b, 200 * MS, RewindFormat.SCREEN_NONE);
        view(b, 300 * MS, RewindFormat.SCREEN_CHAT);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            longText.append('a');
        }
        chat(b, 400 * MS, longText.toString(), 150);
        ctx.ext = b.build();

        ctx.renderUs = 50 * MS;
        assertFalse(screens.chatShown());
        ctx.renderUs = 110 * MS;
        assertTrue(screens.chatShown());
        assertEquals("an empty bar before the first keystroke", "", screens.chatText());
        ctx.renderUs = 130 * MS;
        assertEquals("gg wp", screens.chatText());
        assertEquals(5, screens.chatCursor());
        ctx.renderUs = 150 * MS;
        assertEquals("gg", screens.chatText());
        assertEquals(2, screens.chatCursor());
        GuiTextField field = screens.chatField(W, H);
        assertEquals("vanilla's field: x 4", 4, field.xPosition);
        assertEquals("y height - 12", H - 12, field.yPosition);
        assertEquals("width - 4 wide", W - 4, field.getWidth());
        assertFalse(field.getEnableBackgroundDrawing());
        assertTrue(field.isFocused());
        assertEquals("gg", field.getText());
        assertEquals(2, field.getCursorPosition());
        assertNull("no screen is held for chat", screens.held());

        ctx.view.selectFree();
        assertFalse("your view only", screens.chatShown());
        ctx.view.selectYou();
        ctx.view.toggleClean();
        assertFalse("not in the clean view", screens.chatShown());
        ctx.view.selectYou();

        ctx.renderUs = 250 * MS;
        assertFalse(screens.chatShown());
        ctx.renderUs = 310 * MS;
        assertTrue(screens.chatShown());
        assertEquals("a new chat starts empty", "", screens.chatText());
        ctx.renderUs = 410 * MS;
        assertEquals("a long line (Cobblify's chat length) is shown whole", 150,
                screens.chatField(W, H).getText().length());
        assertNull(mc.currentScreen);
        assertEquals(0, connection.discarded());
    }

    // ------------------------------------------------------------------ real recordings (opt-in)

    /**
     * Opt-in, counts only ({@code COBBLIFY_REWIND_SAMPLES}): every window packet of every real recording goes through
     * vanilla's handlers and the recorded screens without a failure, with the v1 close rule on each world tick; and a
     * seek (a fresh camera fed to the target tick) holds the same window, container, id and inventory as straight
     * play at that tick.
     */
    @Test
    public void realRecordingsApplyEveryWindowPacketAndASeekLandsOnTheSameScreens() throws Exception {
        String dir = System.getenv("COBBLIFY_REWIND_SAMPLES");
        Assume.assumeTrue("set COBBLIFY_REWIND_SAMPLES to run", dir != null && !dir.trim().isEmpty());
        File[] files = new File(dir.trim()).listFiles((d, name) -> name.endsWith(".cbr"));
        Assume.assumeTrue("no .cbr files", files != null && files.length > 0);
        Arrays.sort(files);
        PrintStream out = System.out;
        long allWindowPackets = 0;
        long allFailures = 0;
        for (int n = 0; n < files.length; n++) {
            LoadedRecording r = LoadedRecording.load(files[n], RealRecordingDryRunTest.jsonFor(files[n]));
            assertNull("file #" + (n + 1), r.problem());
            long end = r.timeline().endTick();
            Run straight = new Run(r, end);
            straight.feedTo(end);
            long[] targets = {end / 5, end * 2 / 5, end * 3 / 5, end * 4 / 5, end};
            int equal = 0;
            for (long target : targets) {
                Run seek = new Run(r, end);
                seek.feedTo(target);
                assertEquals("file #" + (n + 1) + " tick " + target, straight.state[(int) target],
                        seek.state[(int) target]);
                equal++;
            }
            out.println("rewind screens file " + (n + 1) + ": windowPackets=" + straight.windowPackets
                    + " failures=" + straight.failures + (straight.firstFailure == null ? "" : " first="
                    + straight.firstFailure) + " opens=" + straight.opens + " drawnOpens=" + straight.drawnOpens
                    + " shownTicks=" + straight.shownTicks + " seeksEqual=" + equal + "/" + targets.length);
            allWindowPackets += straight.windowPackets;
            allFailures += straight.failures;
            r.close();
        }
        out.println("rewind screens total: files=" + files.length + " windowPackets=" + allWindowPackets
                + " failures=" + allFailures);
        assertEquals("window packets vanilla's handlers or the screens failed on", 0, allFailures);
    }

    /** One playback of a real recording through the window path, with a per-tick state fingerprint. */
    private final class Run implements PacketSink {
        final TickFeeder feeder;
        final Ctx c = new Ctx();
        final RecordedScreens s = new RecordedScreens(c);
        final long[] state;
        long windowPackets;
        long failures;
        String firstFailure;
        long opens;
        long drawnOpens;
        long shownTicks;
        private Container lastWindow;

        Run(LoadedRecording r, long end) throws Exception {
            feeder = new TickFeeder(r);
            c.feeder = feeder;
            c.track = r.selfTrack();
            c.ext = r.ext();
            c.table = r.timeline().ticks();
            state = new long[(int) end + 1];
            RewindCamera.setScreenHolder(s);
            camera = newCamera();
            feeder.feedHeaders(this);
        }

        void feedTo(long target) {
            RewindCamera.setScreenHolder(s);
            for (long k = feeder.nextTick(); k <= target; k++) {
                feeder.feedForTick(k, this);
            }
        }

        private void process(byte[] buf, int off, int len) {
            int id = RewindFormat.packetId(buf, off, len);
            try {
                VanillaPacketDecoder.decode(buf, off, len).processPacket(handler);
            } catch (Exception e) {
                failures++;
                if (firstFailure == null) {
                    firstFailure = "0x" + Integer.toHexString(id) + "/" + e.getClass().getSimpleName();
                }
            }
        }

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            if (packetId == 0x2F || packetId == 0x30) { // window 0 (the policy passes it): the inventory container
                process(buf, off, len);
            }
            return true;
        }

        @Override
        public boolean window(int packetId, byte[] buf, int off, int len) {
            windowPackets++;
            process(buf, off, len);
            return true;
        }

        @Override
        public void boundary(int packetId, byte[] buf, int off, int len) {
            try {
                camera = newCamera(); // vanilla's S01/S07 make a new client player
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            s.boundary();
        }

        @Override
        public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
            return true;
        }

        @Override
        public void sample(long tick, long timeUs) {
            s.tick(tick, timeUs);
            Container w = s.window();
            if (w != null && w != lastWindow) {
                opens++;
                if (s.held() != null) {
                    drawnOpens++;
                }
            }
            lastWindow = w;
            if (s.held() != null) {
                shownTicks++;
            }
            long h = 17;
            h = h * 31 + (s.held() == null ? 0 : s.held() instanceof GuiChest ? 1 : 2);
            h = h * 31 + (w == null ? -1 : w.windowId);
            h = h * 31 + (camera.openContainer == camera.inventoryContainer ? 1 : 0);
            h = h * 31 + Arrays.hashCode(inventory(camera.inventory));
            if (w != null) {
                for (int i = 0; i < w.inventorySlots.size(); i++) {
                    h = h * 31 + describe(w.getSlot(i).getStack()).hashCode();
                }
            }
            if (tick >= 0 && tick < state.length) {
                state[(int) tick] = h;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The method {@code name(types)} declared on {@code type} or a superclass (invoked virtually). */
    private static Method find(Class<?> type, String name, String types) {
        String[] want = types.isEmpty() ? new String[0] : types.split(",");
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterTypes().length != want.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < want.length; i++) {
                    ok &= m.getParameterTypes()[i].getSimpleName().equals(want[i]);
                }
                if (ok) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new AssertionError("no " + name + "(" + types + ") on " + type);
    }

    /** The body of the method whose declaration starts with {@code signature}, braces included. */
    private static String body(String src, String signature) {
        int at = src.indexOf(signature);
        assertTrue("declares " + signature, at >= 0);
        int open = src.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return src.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced " + signature);
    }

    private static Object get(Class<?> owner, Object target, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** An instance of {@code type} with no constructor run ({@code sun.misc.Unsafe.allocateInstance}). */
    static Object allocate(Class<?> type) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Field f = u.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return u.getMethod("allocateInstance", Class.class).invoke(f.get(null), type);
    }
}
