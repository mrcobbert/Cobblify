package com.bedwarsqol.rewind;

import com.bedwarsqol.rewind.ViewCaptureTest.Rec;
import com.bedwarsqol.rewind.ViewCaptureTest.Recorder;
import io.netty.buffer.Unpooled;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A9 (PLAN Phase 3): USE stores the full item-use count only off the countdown (a bow draw starts at 72000, review
 * B1); INV stores every change of the 36 main slots, 4 armour slots and the cursor, including a damage change on the
 * same stack object and, within 20 ticks, a tag edited in place (review I1); a segment starts with all 41; while a
 * window is open its own slots are stored on change, a slot emptied by a local click at that tick, before any server
 * packet (review I4). Real vanilla stacks and containers, a recording sink, no client.
 */
public class InventoryCaptureTest {

    @BeforeClass
    public static void bootstrap() {
        PrintStream out = System.out;
        Bootstrap.register();
        System.setOut(out);
    }

    /** The player's inventory container: only compared by reference. */
    static final class Own extends Container {
        @Override
        public boolean canInteractWith(EntityPlayer playerIn) {
            return true;
        }
    }

    static InventoryPlayer fullInventory() {
        InventoryPlayer inv = new InventoryPlayer(null);
        Item[] kinds = {Items.iron_sword, Item.getItemFromBlock(Blocks.wool), Items.gold_ingot, Items.iron_ingot,
                Items.emerald, Items.diamond, Items.bow, Items.arrow, Item.getItemFromBlock(Blocks.planks)};
        for (int i = 0; i < 36; i++) {
            Item k = kinds[i % kinds.length];
            inv.mainInventory[i] = new ItemStack(k, k.getItemStackLimit() > 1 ? 1 + i : 1, k == Items.iron_sword ? 3 : 0);
        }
        inv.mainInventory[0].addEnchantment(Enchantment.sharpness, 1); // a tagged stack
        inv.armorInventory[0] = new ItemStack(Items.iron_boots);
        inv.armorInventory[1] = new ItemStack(Items.iron_leggings);
        inv.armorInventory[2] = new ItemStack(Items.chainmail_chestplate);
        inv.armorInventory[3] = new ItemStack(Items.leather_helmet);
        return inv;
    }

    static byte[] slotBytes(ItemStack s) {
        PacketBuffer b = new PacketBuffer(Unpooled.buffer());
        b.writeItemStackToBuffer(s);
        return Arrays.copyOfRange(b.array(), b.arrayOffset() + b.readerIndex(), b.arrayOffset() + b.writerIndex());
    }

    private static int invSlot(Rec r) {
        return new ExtCodec.Inv().read(r.body, 0, r.body.length).slot;
    }

    private static byte[] invBytes(Rec r) {
        ExtCodec.Inv d = new ExtCodec.Inv().read(r.body, 0, r.body.length);
        return Arrays.copyOfRange(d.buf, d.slotOff, d.slotOff + d.slotLen);
    }

    private static boolean tick(StateCapture c, Recorder s, InventoryPlayer inv, Container open, Container own) {
        return c.tick(s, 0, inv, open, own, null, null);
    }

    // ------------------------------------------------------------------ INV

    @Test
    public void theSegmentsFirstTickWritesAll41SlotsWithVanillasSlotBytes() {
        InventoryPlayer inv = fullInventory();
        inv.setItemStack(new ItemStack(Items.ender_pearl, 2));
        Own own = new Own();
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        assertTrue(tick(c, s, inv, own, own));
        List<Rec> recs = s.of(RewindFormat.TAG_INV);
        assertEquals(RewindFormat.INV_SLOTS, recs.size());
        for (int i = 0; i < RewindFormat.INV_SLOTS; i++) {
            ItemStack want = i < 36 ? inv.mainInventory[i] : i < 40 ? inv.armorInventory[i - 36] : inv.getItemStack();
            assertEquals(i, invSlot(recs.get(i)));
            assertArrayEquals("slot " + i, slotBytes(want), invBytes(recs.get(i)));
        }
        s.clear();
        for (int t = 0; t < 100; t++) {
            assertTrue(tick(c, s, inv, own, own));
        }
        assertEquals("unchanged ticks (deep compares included) write nothing", 0, s.recs.size());
    }

    @Test
    public void aCountChangeWritesOneRecordForThatSlot() {
        InventoryPlayer inv = fullInventory();
        Own own = new Own();
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        tick(c, s, inv, own, own);
        s.clear();
        inv.mainInventory[4].stackSize--; // the same object, one emerald spent
        tick(c, s, inv, own, own);
        tick(c, s, inv, own, own);
        assertEquals(1, s.recs.size());
        assertEquals(4, invSlot(s.recs.get(0)));
        assertArrayEquals(slotBytes(inv.mainInventory[4]), invBytes(s.recs.get(0)));
        inv.mainInventory[7] = null; // a slot emptied
        tick(c, s, inv, own, own);
        assertEquals(2, s.recs.size());
        assertArrayEquals(slotBytes(null), invBytes(s.recs.get(1)));
    }

    @Test
    public void theSameStackWithItsDamageChangedInPlaceWritesOneInv() {
        InventoryPlayer inv = fullInventory();
        Own own = new Own();
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        tick(c, s, inv, own, own);
        s.clear();
        ItemStack sword = inv.mainInventory[9]; // untagged iron sword
        sword.setItemDamage(sword.getItemDamage() + 1);
        tick(c, s, inv, own, own);
        tick(c, s, inv, own, own);
        assertEquals(1, s.recs.size());
        assertEquals(9, invSlot(s.recs.get(0)));
        assertArrayEquals(slotBytes(sword), invBytes(s.recs.get(0)));
    }

    @Test
    public void aTagEditedInPlaceWritesOneInvWithin20Ticks() {
        InventoryPlayer inv = fullInventory();
        Own own = new Own();
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        tick(c, s, inv, own, own);
        tick(c, s, inv, own, own);
        s.clear();
        ItemStack sword = inv.mainInventory[0];
        sword.getTagCompound().setString("edited", "in place"); // same stack, same tag object, same count/damage
        for (int t = 0; t < StateCapture.DEEP_COMPARE_TICKS; t++) {
            tick(c, s, inv, own, own);
        }
        assertEquals("one record within a second", 1, s.recs.size());
        assertEquals(0, invSlot(s.recs.get(0)));
        assertArrayEquals(slotBytes(sword), invBytes(s.recs.get(0)));
        for (int t = 0; t < 3 * StateCapture.DEEP_COMPARE_TICKS; t++) {
            tick(c, s, inv, own, own);
        }
        assertEquals("and only one", 1, s.recs.size());
        // a tag replaced by another object is seen at once
        sword.setTagCompound(new net.minecraft.nbt.NBTTagCompound());
        tick(c, s, inv, own, own);
        assertEquals(2, s.recs.size());
    }

    @Test
    public void armourAndTheCursorStackUseTheirSlotNumbers() {
        InventoryPlayer inv = fullInventory();
        Own own = new Own();
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        tick(c, s, inv, own, own);
        s.clear();
        inv.armorInventory[3] = new ItemStack(Items.diamond_helmet);
        tick(c, s, inv, own, own);
        inv.setItemStack(new ItemStack(Items.gold_ingot, 7)); // picked up with the mouse
        tick(c, s, inv, own, own);
        inv.setItemStack(null); // put down
        tick(c, s, inv, own, own);
        assertEquals(3, s.recs.size());
        assertEquals(RewindFormat.INV_ARMOR_FIRST + 3, invSlot(s.recs.get(0)));
        assertEquals(RewindFormat.INV_CURSOR, invSlot(s.recs.get(1)));
        assertArrayEquals(slotBytes(new ItemStack(Items.gold_ingot, 7)), invBytes(s.recs.get(1)));
        assertEquals(RewindFormat.INV_CURSOR, invSlot(s.recs.get(2)));
        assertArrayEquals(slotBytes(null), invBytes(s.recs.get(2)));
    }

    @Test
    public void anEqualUntaggedStackObjectWritesNothing() {
        InventoryPlayer inv = fullInventory();
        Own own = new Own();
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        tick(c, s, inv, own, own);
        s.clear();
        inv.mainInventory[2] = inv.mainInventory[2].copy(); // the server resent the same stack
        tick(c, s, inv, own, own);
        assertEquals(0, s.recs.size());
        inv.mainInventory[2].stackSize++; // and the new object is the one followed from now on
        tick(c, s, inv, own, own);
        assertEquals(1, s.recs.size());
    }

    @Test
    public void aSegmentResetWritesAll41Again() {
        InventoryPlayer inv = fullInventory();
        Own own = new Own();
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        tick(c, s, inv, own, own);
        tick(c, s, inv, own, own);
        assertEquals(41, s.of(RewindFormat.TAG_INV).size());
        c.reset();
        tick(c, s, inv, own, own);
        assertEquals(82, s.of(RewindFormat.TAG_INV).size());
    }

    // ------------------------------------------------------------------ USE

    @Test
    public void useIsWrittenOnlyOffTheCountdownAndABowDrawStartsAt72000() {
        InventoryPlayer inv = fullInventory();
        Own own = new Own();
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        int[] counts = new int[50];
        int k = 0;
        counts[k++] = 0;
        counts[k++] = 0;
        counts[k++] = 72000; // bow draw starts
        counts[k++] = 71999;
        counts[k++] = 71998;
        counts[k++] = 71997;
        counts[k++] = 0; // released
        counts[k++] = 0;
        counts[k++] = 32; // eating starts
        for (int v = 31; v >= 0; v--) {
            counts[k++] = v; // eaten to the end: the countdown reaches 0 by itself
        }
        counts[k++] = 0;
        for (int i = 0; i < k; i++) {
            assertTrue(c.tick(s, counts[i], inv, own, own, null, null));
        }
        List<Rec> use = s.of(RewindFormat.TAG_USE);
        int[] got = new int[use.size()];
        for (int i = 0; i < got.length; i++) {
            got[i] = new ExtCodec.Use().read(use.get(i).body, 0, use.get(i).body.length).count;
        }
        assertArrayEquals(new int[]{72000, 0, 32}, got);
    }

    // ------------------------------------------------------------------ CONT

    private static ContainerChest chest(InventoryPlayer inv, InventoryBasic box, int windowId) {
        ContainerChest chest = new ContainerChest(inv, box, null);
        chest.windowId = windowId;
        return chest;
    }

    @Test
    public void anOpenWindowStoresItsOwnFilledSlotsAndNotThePlayersOnes() {
        InventoryPlayer inv = fullInventory();
        Own own = new Own();
        InventoryBasic box = new InventoryBasic("Item Shop", false, 54);
        box.setInventorySlotContents(0, new ItemStack(Item.getItemFromBlock(Blocks.wool), 16));
        box.setInventorySlotContents(19, new ItemStack(Items.stone_sword));
        box.setInventorySlotContents(53, new ItemStack(Items.arrow, 8));
        ContainerChest chest = chest(inv, box, 7);
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        tick(c, s, inv, own, own);
        assertEquals("no window: no CONT", 0, s.of(RewindFormat.TAG_CONT).size());
        s.clear();
        tick(c, s, inv, chest, own);
        List<Rec> cont = s.of(RewindFormat.TAG_CONT);
        assertEquals("the window's filled slots, measured from the empty window S2D opens", 3, cont.size());
        int[] slots = new int[3];
        for (int i = 0; i < 3; i++) {
            ExtCodec.Cont d = new ExtCodec.Cont().read(cont.get(i).body, 0, cont.get(i).body.length);
            assertEquals(7, d.windowId);
            slots[i] = d.slot;
            assertArrayEquals(slotBytes(box.getStackInSlot(d.slot)), Arrays.copyOfRange(d.buf, d.slotOff,
                    d.slotOff + d.slotLen));
        }
        assertArrayEquals(new int[]{0, 19, 53}, slots);
        assertEquals("the player's own slots in the window are INV's", 0, s.of(RewindFormat.TAG_INV).size());
        s.clear();
        for (int t = 0; t < 40; t++) {
            tick(c, s, inv, chest, own);
        }
        assertEquals(0, s.recs.size());
    }

    @Test
    public void aWindowSlotEmptiedByALocalClickIsStoredAtThatTick() {
        InventoryPlayer inv = fullInventory();
        Own own = new Own();
        InventoryBasic box = new InventoryBasic("Chest", false, 27);
        box.setInventorySlotContents(5, new ItemStack(Items.diamond, 3));
        ContainerChest chest = chest(inv, box, 2);
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        tick(c, s, inv, chest, own);
        s.clear();
        // the click's own prediction: the stack leaves the slot for the cursor (no server packet yet)
        ItemStack taken = chest.getSlot(5).getStack();
        chest.getSlot(5).putStack(null);
        inv.setItemStack(taken);
        tick(c, s, inv, chest, own);
        List<Rec> cont = s.of(RewindFormat.TAG_CONT);
        assertEquals(1, cont.size());
        ExtCodec.Cont d = new ExtCodec.Cont().read(cont.get(0).body, 0, cont.get(0).body.length);
        assertEquals(2, d.windowId);
        assertEquals(5, d.slot);
        assertArrayEquals(slotBytes(null), Arrays.copyOfRange(d.buf, d.slotOff, d.slotOff + d.slotLen));
        assertEquals("and the cursor stack in the same tick", 1, s.of(RewindFormat.TAG_INV).size());
        assertEquals(RewindFormat.INV_CURSOR, invSlot(s.of(RewindFormat.TAG_INV).get(0)));

        // a count changed in place and a tag edited in place in the window are seen too
        box.setInventorySlotContents(6, new ItemStack(Items.iron_sword));
        tick(c, s, inv, chest, own);
        assertEquals(2, s.of(RewindFormat.TAG_CONT).size());
        box.getStackInSlot(6).setItemDamage(9);
        tick(c, s, inv, chest, own);
        assertEquals(3, s.of(RewindFormat.TAG_CONT).size());
    }

    @Test
    public void aClosedWindowWritesNothingAndANewWindowStartsFromEmpty() {
        InventoryPlayer inv = fullInventory();
        Own own = new Own();
        InventoryBasic box = new InventoryBasic("Chest", false, 27);
        box.setInventorySlotContents(1, new ItemStack(Items.gold_ingot, 4));
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        ContainerChest first = chest(inv, box, 1);
        tick(c, s, inv, first, own);
        assertEquals(1, s.of(RewindFormat.TAG_CONT).size());
        tick(c, s, inv, own, own); // closed
        box.setInventorySlotContents(1, null);
        tick(c, s, inv, own, own);
        assertEquals(1, s.of(RewindFormat.TAG_CONT).size());
        box.setInventorySlotContents(1, new ItemStack(Items.gold_ingot, 4));
        ContainerChest second = chest(inv, box, 2); // the same chest opened again: a new window
        tick(c, s, inv, second, own);
        assertEquals(2, s.of(RewindFormat.TAG_CONT).size());
        second.windowId = 3; // the server reused the container object under a new id
        tick(c, s, inv, second, own);
        assertEquals(3, s.of(RewindFormat.TAG_CONT).size());
    }

    // ------------------------------------------------------------------ failure

    @Test
    public void aThrowingSinkEndsTheTickAndIsReportedOnce() {
        InventoryPlayer inv = fullInventory();
        Own own = new Own();
        StateCapture c = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        Recorder s = new Recorder();
        s.throwOnAppend = true;
        assertFalse(tick(c, s, inv, own, own));
        assertEquals(1, s.failures.size());
    }
}
