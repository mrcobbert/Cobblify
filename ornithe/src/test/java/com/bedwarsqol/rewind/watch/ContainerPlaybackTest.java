package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import io.netty.buffer.Unpooled;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S2DPacketOpenWindow;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S30PacketWindowItems;
import net.minecraft.util.ChatComponentText;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Your own clicks in a chest or shop window (PLAN Phase 11, review I4; TASK C5, container part). Live, a click in a
 * window changes the window's slots at once ({@code PlayerControllerMP.windowClick} runs {@code slotClick} locally)
 * and the server's S2F answers later. The recorder stores every change of the open window's own slots at the end of
 * the tick (CONT: window id, slot, stack). Playback applies CONT in the local phase of its tick to the recorded window
 * held on the camera (Phase 9's container), so the change shows in the tick of the click, before the server's answer,
 * and a later S2F still wins.
 *
 * <p>Harness: the real {@link TickFeeder} over a v2 recording; vanilla's handler processes the recorded S2D, S30 and
 * S2F (written by vanilla's writers) through Phase 9's {@link RecordedScreens} holder, as {@code PlaybackSink.window}
 * hands them over; {@link ContainerPart} reads the camera from the client ({@code mc.thePlayer}).
 */
public class ContainerPlaybackTest {

    private static final long MS = 1_000L;
    private static final long EO = 40 * MS;
    private static final int WINDOW = 7;
    private static final Set<Integer> WINDOWS = new HashSet<Integer>(Arrays.asList(0x2D, 0x2E, 0x2F, 0x30));

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        PrintStream out = System.out;
        Bootstrap.register();
        System.setOut(out);
    }

    private BlockPredictionTest.Client client;
    private RecordedScreens screens;

    @Before
    public void setUp() throws Exception {
        client = new BlockPredictionTest.Client();
        screens = new RecordedScreens(new RecordedScreensTest.Ctx());
        RewindCamera.setScreenHolder(screens);
    }

    @After
    public void tearDown() throws Exception {
        RewindCamera.setScreenHolder(null);
        client.close();
    }

    private static byte[] bytes(int id, Packet<?> p) throws Exception {
        PacketBuffer b = new PacketBuffer(Unpooled.buffer());
        b.writeVarIntToBuffer(id);
        p.writePacketData(b);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        return out;
    }

    private static byte[] cont(int window, int slot, ItemStack stack) {
        PacketBuffer item = new PacketBuffer(Unpooled.buffer());
        item.writeItemStackToBuffer(stack);
        byte[] st = new byte[item.readableBytes()];
        item.readBytes(st);
        ExtCodec c = new ExtCodec();
        int n = c.cont(window, slot, st, 0, st.length);
        return ExtRecords.body(c.buffer(), n);
    }

    /** The chest's 27 slots: slot i holds i + 1 emeralds; then the player's 36 (empty). */
    private static List<ItemStack> contents() {
        List<ItemStack> all = new ArrayList<ItemStack>();
        for (int i = 0; i < 27; i++) {
            all.add(new ItemStack(Items.emerald, i + 1));
        }
        for (int i = 0; i < 36; i++) {
            all.add(null);
        }
        return all;
    }

    /**
     * S01 at 40 (ordinal 1), S2D at 44 (2), S30 at 45 (3), the server's S2F for slot 3 at 160 (4); heads every 50 ms
     * from 50 to 400 with handled counts 3, 0, 0, 1, 0...; then {@code more}.
     */
    private LoadedRecording recording(ItemStack answer, ExtRecords.Builder more) throws Exception {
        TickFeederTest.Rec rec = new TickFeederTest.Rec()
                .segment(1, TickFeederTest.standardHeader0(), TickFeederTest.standardHeader1())
                .at(EO, RecordingFixture.joinGame(0))
                .at(44 * MS, bytes(0x2D, new S2DPacketOpenWindow(WINDOW, "minecraft:container",
                        new ChatComponentText("Chest"), 27)))
                .at(45 * MS, bytes(0x30, new S30PacketWindowItems(WINDOW, contents())))
                .at(160 * MS, bytes(0x2F, new S2FPacketSetSlot(WINDOW, 3, answer)))
                .at(450 * MS, TickFeederTest.p(0x03, 1));
        long[] heads = new long[8];
        int[] inc = new int[8];
        for (int i = 0; i < heads.length; i++) {
            heads[i] = 50 + 50 * i;
        }
        inc[0] = 3;
        inc[3] = 1;
        ExtRecords.Builder b = TickFeederTest.heads(heads, inc, null);
        if (more != null) {
            ExtRecords m = more.build();
            for (int i = 0; i < m.size(); i++) {
                b.add(m.segmentIndex(i), m.timeUs(i), m.tag(i), m.buffer(), m.offset(i), m.length(i));
            }
        }
        return rec.load(tmp.newFolder(), EO, 500 * MS).withExt(b.build());
    }

    /** Your click at 101 takes the 4 emeralds out of slot 3 (CONT: slot 3 empty). */
    private static ExtRecords.Builder clickTakesSlot3() {
        return new ExtRecords.Builder().add(0, 101 * MS, RewindFormat.TAG_CONT, cont(WINDOW, 3, null));
    }

    /** Plays ticks 0..to; after each tick in {@code watch}, slot 3 of the open window ("-" empty, else the count). */
    private Map<Long, String> play(LoadedRecording r, long to, PlaybackPart part, long... watch) {
        TickFeeder feeder = new TickFeeder(r);
        BlockPredictionTest.VanillaSink sink = part == null
                ? new BlockPredictionTest.VanillaSink(client.handler, WINDOWS)
                : new BlockPredictionTest.VanillaSink(client.handler, WINDOWS, part);
        feeder.feedHeaders(sink);
        Map<Long, String> seen = new TreeMap<Long, String>();
        List<Long> w = new ArrayList<Long>();
        for (long k : watch) {
            w.add(k);
        }
        for (long k = 0; k <= to; k++) {
            sink.feed(feeder, k);
            if (w.contains(k)) {
                seen.put(k, slot(3));
            }
        }
        return seen;
    }

    private String slot(int i) {
        Container c = client.camera.openContainer;
        ItemStack s = c.getSlot(i).getStack();
        return s == null ? "-" : Integer.toString(s.stackSize);
    }

    private ContainerPart part() {
        return new ContainerPart(() -> client.camera);
    }

    @Test
    public void yourClickShowsAtOnceAndStaysUntilTheServersAnswerWhichWins() throws Exception {
        LoadedRecording r = recording(new ItemStack(Items.emerald, 4), clickTakesSlot3()); // the server refuses
        TickTable t = r.timeline().ticks();
        long k100 = t.tickOf(100 * MS);
        long k150 = t.tickOf(150 * MS);
        long k200 = t.tickOf(200 * MS);
        Map<Long, String> seen = play(r, k200, part(), k100 - 1, k100, k150, k200);
        assertEquals("the window is filled before the click", "4", seen.get(k100 - 1));
        assertEquals("your click shows in its own tick, before the server's answer", "-", seen.get(k100));
        assertEquals("a slot emptied by CONT stays empty until the later S2F", "-", seen.get(k150));
        assertEquals("the server's later S2F wins", "4", seen.get(k200));
        assertEquals("the other slots are untouched", "5", slot(4));
        assertSame("the recorded window is the camera's open container", screens.window(),
                client.camera.openContainer);
    }

    @Test
    public void aConfirmedClickStaysEmpty() throws Exception {
        LoadedRecording r = recording(null, clickTakesSlot3());
        TickTable t = r.timeline().ticks();
        long k100 = t.tickOf(100 * MS);
        long k200 = t.tickOf(200 * MS);
        Map<Long, String> seen = play(r, k200, part(), k100, k200);
        assertEquals("-", seen.get(k100));
        assertEquals("-", seen.get(k200));
    }

    /** Without the part (today) the click shows only when the server answers: what C5 fixes. */
    @Test
    public void withoutThePartTheClickWaitsForTheServer() throws Exception {
        LoadedRecording r = recording(null, clickTakesSlot3());
        TickTable t = r.timeline().ticks();
        long k100 = t.tickOf(100 * MS);
        long k200 = t.tickOf(200 * MS);
        Map<Long, String> seen = play(r, k200, null, k100, k200);
        assertEquals("4", seen.get(k100));
        assertEquals("-", seen.get(k200));
    }

    /** A stack put into a slot (a shift-click from the inventory, a placed stack) shows the same way. */
    @Test
    public void aStackPutIntoASlotShowsWithItsItemCountAndDamage() throws Exception {
        ItemStack sword = new ItemStack(Items.iron_sword, 1, 17);
        ExtRecords.Builder b = new ExtRecords.Builder().add(0, 101 * MS, RewindFormat.TAG_CONT, cont(WINDOW, 3, sword));
        LoadedRecording r = recording(sword.copy(), b);
        long k100 = r.timeline().ticks().tickOf(100 * MS);
        play(r, k100, part());
        ItemStack s = client.camera.openContainer.getSlot(3).getStack();
        assertNotNull(s);
        assertSame(Items.iron_sword, s.getItem());
        assertEquals(1, s.stackSize);
        assertEquals(17, s.getItemDamage());
    }

    /** A CONT for another window, with no window open, or past the window's slots changes nothing. */
    @Test
    public void aRecordForAnotherWindowNoWindowOrAnOutOfRangeSlotIsIgnored() throws Exception {
        ContainerPart part = part();
        byte[] none = cont(WINDOW, 3, null);
        part.local(RewindFormat.TAG_CONT, 0, none, 0, none.length); // no window open: the inventory container
        assertNull("the inventory container is not a recorded window", client.camera.openContainer.getSlot(3)
                .getStack());

        LoadedRecording r = recording(new ItemStack(Items.emerald, 4), null);
        play(r, r.timeline().ticks().tickOf(100 * MS), part);
        byte[] other = cont(WINDOW + 1, 3, null);
        part.local(RewindFormat.TAG_CONT, 0, other, 0, other.length);
        byte[] far = cont(WINDOW, 500, null);
        part.local(RewindFormat.TAG_CONT, 0, far, 0, far.length);
        ExtCodec c = new ExtCodec();
        byte[] use = ExtRecords.body(c.buffer(), c.use(5));
        part.local(RewindFormat.TAG_USE, 0, use, 0, use.length);
        assertEquals("4", slot(3));
    }

    /** The camera's own inventory is never touched by a CONT, whatever its window id says. */
    @Test
    public void theInventoryContainerIsNeverWritten() throws Exception {
        client.camera.inventory.mainInventory[3] = new ItemStack(Item.getItemById(1), 9);
        ContainerPart part = part();
        byte[] zero = cont(0, 39, null); // window 0, slot 39 = hotbar 3 in the inventory container
        part.local(RewindFormat.TAG_CONT, 0, zero, 0, zero.length);
        assertEquals(9, client.camera.inventory.mainInventory[3].stackSize);
    }

    /** Seek equivalence: a rebuild from tick 0 reaches the window contents straight play reaches. */
    @Test
    public void afterASeekTheWindowEqualsStraightPlay() throws Exception {
        LoadedRecording r = recording(new ItemStack(Items.emerald, 4), clickTakesSlot3());
        TickTable t = r.timeline().ticks();
        for (long target : new long[]{t.tickOf(100 * MS), t.tickOf(150 * MS), t.tickOf(300 * MS)}) {
            play(r, target, part());
            String straight = window();
            client.close();
            client = new BlockPredictionTest.Client(); // a backward seek: a fresh connection and camera
            screens.restart();
            play(r, target, part());
            assertEquals("tick " + target, straight, window());
        }
    }

    private String window() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 27; i++) {
            sb.append(slot(i)).append(' ');
        }
        return sb.toString();
    }

    /** Without CONT records (v1, or a v2 game with no click) the window shows what the server sent, as before. */
    @Test
    public void withoutContRecordsTheWindowIsTheServers() throws Exception {
        LoadedRecording r = recording(new ItemStack(Items.emerald, 4), null);
        assertEquals(false, r.ext().has(RewindFormat.TAG_CONT));
        play(r, r.timeline().ticks().tickOf(100 * MS), part());
        assertEquals("4", slot(3));
    }

    @Test
    public void registerAddsThePartOnce() {
        ContainerPart.register();
        ContainerPart.register();
        assertEquals(1, Collections.frequency(RewindPlayback.parts(), ContainerPart.instance()));
    }
}
