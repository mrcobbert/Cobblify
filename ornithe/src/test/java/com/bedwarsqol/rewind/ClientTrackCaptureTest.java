package com.bedwarsqol.rewind;

import com.bedwarsqol.config.ClientSettings;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.ItemStack;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.management.ManagementFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A12 for Phase 3: the per-tick capture (USE, INV, CONT, SETTINGS) and the per-frame capture (VIEW, MOUSE,
 * CHAT_INPUT, FOV, INPUT) allocate 0 bytes in steady state: over 100k unchanged ticks and frames with a full inventory
 * of real stacks (tagged ones included; the once-per-second deep tag compare is measured separately), and over 100k
 * ticks and frames whose counts, damage, FOV, cursor and keys change, on untagged stacks. Thread-allocated bytes.
 */
public class ClientTrackCaptureTest {

    private static final int N = 100_000;
    private static final long MS = 1_000_000L;

    @BeforeClass
    public static void bootstrap() {
        InventoryCaptureTest.bootstrap();
    }

    /** Counts appends; allocates nothing. */
    static final class Counting implements StateCapture.Sink {
        long records;
        long bytes;

        @Override
        public boolean append(int tag, byte[] buf, int len) {
            records++;
            bytes += len;
            return true;
        }

        @Override
        public void failed(String where, Throwable t) {
            throw new AssertionError(where, t);
        }
    }

    /** A FOV source that allocates nothing and counts its reads. */
    static final class FakeFov implements ViewCapture.FovSource {
        float value = 70f;
        long reads;

        @Override
        public float fov(float partialTicks) {
            reads++;
            return value;
        }
    }

    /** Keeps the meter check's arrays from being optimised away. */
    static volatile Object sink;

    private static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static long allocated() {
        return MX.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    interface Body {
        void run(int i);
    }

    /** Fewest bytes the thread allocated over {@code n} calls, best of three rounds after a warm-up. */
    private static long measure(Body body, int n) {
        for (int i = 0; i < 3 * n; i++) {
            body.run(i);
        }
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            long a = allocated();
            for (int i = 0; i < n; i++) {
                body.run(i);
            }
            best = Math.min(best, allocated() - a);
        }
        return best;
    }

    private static void meterWorks() {
        long a = allocated();
        for (int i = 0; i < N; i++) {
            sink = new byte[1 + (i & 7)];
        }
        long seen = allocated() - a;
        assertTrue("allocation meter works: " + seen, seen >= N * 16L);
    }

    /** A full inventory with ten enchanted (tagged) stacks among real ones. */
    private static InventoryPlayer taggedInventory() {
        InventoryPlayer inv = InventoryCaptureTest.fullInventory();
        for (int i = 9; i < 36; i += 3) {
            if (inv.mainInventory[i].getItem().isDamageable()) {
                inv.mainInventory[i].addEnchantment(Enchantment.unbreaking, 1);
            }
        }
        return inv;
    }

    private static ContainerChest shop(InventoryPlayer inv) {
        InventoryBasic box = new InventoryBasic("Item Shop", false, 54);
        for (int i = 0; i < 54; i += 2) {
            box.setInventorySlotContents(i, new ItemStack(i % 4 == 0 ? Items.iron_ingot : Items.stone_sword, 1));
        }
        ContainerChest c = new ContainerChest(inv, box, null);
        c.windowId = 4;
        return c;
    }

    @Test(timeout = 300_000)
    public void unchangedTicksAndFramesAllocateNothing() {
        meterWorks();
        final InventoryPlayer inv = taggedInventory();
        final InventoryCaptureTest.Own own = new InventoryCaptureTest.Own();
        final ContainerChest shop = shop(inv);
        final GameSettings gs = SnapshotCaptureTest.gameSettings();
        final ClientSettings cfg = SnapshotCaptureTest.clientSettings();
        final SettingsSnapshot snap = new SettingsSnapshot();
        final StateCapture state = new StateCapture(0); // deep compares off here; measured below
        final Counting out = new Counting();
        // the IDENTITY lookup each tick: a handler whose own entry has not arrived (waiting), and one where it has
        final net.minecraft.client.network.NetHandlerPlayClient waiting = SnapshotCaptureTest.handler();
        SnapshotCaptureTest.listed(waiting, new com.mojang.authlib.GameProfile(
                java.util.UUID.fromString("00000000-0000-0000-0000-0000000000aa"), "Other"));
        final net.minecraft.client.network.NetHandlerPlayClient listed = SnapshotCaptureTest.handler();
        SnapshotCaptureTest.listed(listed, SnapshotCaptureTest.profile());

        long empty = measure(new Body() {
            @Override
            public void run(int i) {
            }
        }, N);

        long ticks = measure(new Body() {
            @Override
            public void run(int i) {
                snap.read(gs, 480, 270, 1920, 1080, cfg);
                state.tick(out, 0, inv, (i & 1024) == 0 ? own : shop, own, snap,
                        StateCapture.ownTabProfile((i & 4096) == 0 ? waiting : listed, SnapshotCaptureTest.SELF));
            }
        }, N);
        long recordsAfterTicks = out.records;

        final ViewCapture view = new ViewCapture();
        final ViewCapture.Frame f = new ViewCapture.Frame();
        final FakeFov fov = new FakeFov();
        f.fovSource = fov; // through the real rate limit (code review 1, I2)
        f.keys = RewindFormat.INPUT_W;
        final String typed = "gg wp";
        long frames = measure(new Body() {
            @Override
            public void run(int i) {
                f.nanoTime += 5 * MS; // monotonic across measure's rounds, as the rate limits need
                int phase = (i >> 10) % 3; // in game, a chest open, chat open: each held for 1,024 frames
                f.screenOpen = phase != 0;
                f.screenKind = phase == 0 ? RewindFormat.SCREEN_NONE : phase == 1 ? RewindFormat.SCREEN_CHEST
                        : RewindFormat.SCREEN_CHAT;
                f.mouseDx = 40;
                f.mouseDy = -8;
                f.chatOpen = phase == 2;
                f.chatText = phase == 2 ? typed : null;
                f.chatCursor = 5;
                view.frame(out, f);
            }
        }, N);

        System.out.println("ClientTrackCapture unchanged: ticks " + ticks + " B, frames " + frames + " B, empty "
                + empty + " B per " + N);
        assertTrue("records were written while windows opened and closed", recordsAfterTicks > 0);
        assertEquals("100k unchanged ticks allocate nothing", 0, Math.max(0, ticks - empty));
        assertEquals("100k unchanged frames allocate nothing", 0, Math.max(0, frames - empty));
    }

    @Test(timeout = 300_000)
    public void changingCountsDamageAndViewAllocateNothing() {
        meterWorks();
        final InventoryPlayer inv = InventoryCaptureTest.fullInventory();
        final InventoryCaptureTest.Own own = new InventoryCaptureTest.Own();
        final ContainerChest shop = shop(inv);
        final GameSettings gs = SnapshotCaptureTest.gameSettings();
        final ClientSettings cfg = SnapshotCaptureTest.clientSettings();
        final SettingsSnapshot snap = new SettingsSnapshot();
        final StateCapture state = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        final Counting out = new Counting();
        final ItemStack wool = inv.mainInventory[1];
        final ItemStack sword = inv.mainInventory[9];
        final ItemStack shopSword = shop.getSlot(2).getStack();
        long empty = measure(new Body() {
            @Override
            public void run(int i) {
            }
        }, N);

        long before = out.records;
        long ticks = measure(new Body() {
            @Override
            public void run(int i) {
                wool.stackSize = 1 + (i & 31);       // blocks placed and picked up
                sword.setItemDamage(i & 63);          // the sword wears
                shopSword.setItemDamage(i & 7);       // a window slot changes in place
                gs.fovSetting = 70f + (i & 3);        // a setting changes
                snap.read(gs, 480, 270, 1920, 1080, cfg);
                state.tick(out, (i & 7) == 0 ? 72000 : 0, inv, shop, own, snap, null);
            }
        }, N);
        assertTrue("every tick wrote records: " + (out.records - before), out.records - before >= 4L * 4 * N);

        final ViewCapture view = new ViewCapture();
        final ViewCapture.Frame f = new ViewCapture.Frame();
        final FakeFov fov = new FakeFov();
        f.fovSource = fov;
        f.screenOpen = true;
        f.screenKind = RewindFormat.SCREEN_CHEST;
        before = out.records;
        long frames = measure(new Body() {
            @Override
            public void run(int i) {
                f.nanoTime += 9 * MS;                  // past the 8 ms mouse limit every frame (monotonic)
                fov.value = 70f + (i & 15) * 0.25f;    // sprint FOV easing
                f.mouseDx = i & 255;                   // the cursor moves
                f.mouseDy = -(i & 127);
                f.keys = (i & 1) == 0 ? RewindFormat.INPUT_W : RewindFormat.INPUT_W | RewindFormat.INPUT_LMB;
                f.perspective = (i >> 4) % 3;
                view.frame(out, f);
            }
        }, N);
        assertTrue("every frame wrote records", out.records - before >= 4L * 3 * N);
        assertTrue("the FOV was read through the rate limit: " + fov.reads, fov.reads > 0);

        System.out.println("ClientTrackCapture changing: ticks " + ticks + " B, frames " + frames + " B, empty "
                + empty + " B per " + N);
        assertEquals("100k changing ticks allocate nothing", 0, Math.max(0, ticks - empty));
        assertEquals("100k changing frames allocate nothing", 0, Math.max(0, frames - empty));
    }

    /** The once-per-second deep tag compare is the one steady-state allocation; measured, and bounded. */
    @Test(timeout = 300_000)
    public void theDeepTagCompareIsMeasuredSeparately() {
        final InventoryPlayer inv = taggedInventory();
        int tagged = 0;
        for (int i = 0; i < 36; i++) {
            if (inv.mainInventory[i] != null && inv.mainInventory[i].getTagCompound() != null) {
                tagged++;
            }
        }
        final InventoryCaptureTest.Own own = new InventoryCaptureTest.Own();
        final StateCapture deepEveryTick = new StateCapture(1);
        final Counting out = new Counting();
        deepEveryTick.tick(out, 0, inv, own, own, null, null);
        long before = out.records;
        long bytes = measure(new Body() {
            @Override
            public void run(int i) {
                deepEveryTick.tick(out, 0, inv, own, own, null, null);
            }
        }, 10_000);
        double perDeepTick = bytes / 10_000.0;
        System.out.println("ClientTrackCapture deep tag compare: " + perDeepTick + " B per deep tick, " + tagged
                + " tagged slots (production: one deep tick in " + StateCapture.DEEP_COMPARE_TICKS + ")");
        assertEquals("an unchanged tag writes nothing", before, out.records);
        assertTrue("bounded: " + perDeepTick, perDeepTick <= 256.0 * tagged);
    }
}
