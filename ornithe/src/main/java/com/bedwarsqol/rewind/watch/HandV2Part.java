package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import java.util.Arrays;

/**
 * Your hand and inventory on a format-v2 recording (PLAN "C. Playback of v2 data", HandState v2 inputs; Phase 13,
 * TASK C9): after the sink put the self track's hand on the camera for world tick k (the v1 rules, unchanged), this
 * part takes the same tick from {@link HandState} with the recording's extension records and puts the v2 answers over
 * it, each only when the recording holds that record kind (a v1 recording: nothing, C10):
 * <ul>
 *   <li><b>Swing</b> (OUT C0A): the swing fields vanilla's hand reads.</li>
 *   <li><b>Inventory</b> (INV): all 41 slots, 36 main, 4 armour and the cursor stack. A slot is written when the
 *       camera's stack is not equal to the recorded one ({@code ItemStack.areItemStacksEqual}: item, count, damage,
 *       tag), so a stack an S2F put there that matches stays (with its pickup animation), and anything that differs,
 *       an older EQUIP stack or a window-0 S2F the recording later overrode, takes the recorded stack. The INV
 *       records are the live client's inventory at the end of each tick, so this is the state vanilla showed.</li>
 *   <li><b>Item use</b> (USE): the recorded count on the held stack, while the TICK flag says an item is in use.</li>
 * </ul>
 * Everything is a function of the tick (lookups by tick in {@link ExtTrack}), so a seek reaches the same state.
 * Register once with {@link #register()} (Phase 16 calls it from {@code BedwarsQol}). Client thread.
 */
public final class HandV2Part implements PlaybackPart {

    private static final HandV2Part INSTANCE = new HandV2Part();

    private HandState hand = new HandState();
    /** The recorded stacks as decoded at their last change, one per INV slot (copied onto the camera). */
    private final ItemStack[] recorded = new ItemStack[RewindFormat.INV_SLOTS];

    /** Registers the part with playback (once; repeated calls do nothing). Client thread. */
    public static void register() {
        RewindPlayback.register(INSTANCE);
    }

    @Override
    public void restart() {
        hand = new HandState(); // a new connection is fed from tick 0, as the sink's own hand state
        Arrays.fill(recorded, null);
    }

    @Override
    public void exit() {
        restart();
    }

    @Override
    public void tick(long tick, long timeUs) {
        LoadedRecording r = RewindPlayback.session().recording();
        Timeline tl = RewindPlayback.session().timeline();
        if (r == null || tl == null || !HandState.usesExt(r.extTrack())) {
            return;
        }
        PlaybackSink sink = RewindPlayback.currentSink();
        if (!hand.sample(r.selfTrack(), r.extTrack(), tl.ticks(), tick, swingEnd(sink == null ? null : sink.puppet()))) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer instanceof RewindCamera) {
            apply(mc.thePlayer, hand, recorded);
        }
    }

    /** This tick's v2 answers onto {@code camera}: inventory first, then the item in use on the held stack, swing. */
    static void apply(EntityPlayer camera, HandState h, ItemStack[] recorded) {
        if (h.extInventory()) {
            InventoryPlayer inv = camera.inventory;
            for (int slot = 0; slot < RewindFormat.INV_SLOTS; slot++) {
                if (h.invChanged(slot)) {
                    recorded[slot] = RewindSelfPuppet.decode(h.invItem(slot));
                }
                if (h.invItem(slot) == null) {
                    continue; // no INV record for it yet
                }
                ItemStack want = recorded[slot];
                if (!ItemStack.areItemStacksEqual(want, stack(inv, slot))) {
                    put(inv, slot, want == null ? null : want.copy());
                }
            }
        }
        if (h.extUse() && h.inUse()) {
            ItemStack held = camera.inventory.getCurrentItem();
            if (held != null) {
                camera.clearItemInUse(); // setItemInUse only takes a new stack object
                camera.setItemInUse(held, h.useCount(held.getMaxItemUseDuration()));
            }
        }
        if (h.extSwing()) {
            camera.swingProgressInt = h.swingProgressInt();
            camera.isSwingInProgress = h.swingInProgress();
            camera.prevSwingProgress = h.prevSwingProgress();
            camera.swingProgress = h.swingProgress();
        }
    }

    private static ItemStack stack(InventoryPlayer inv, int slot) {
        if (slot < RewindFormat.INV_MAIN_SLOTS) {
            return inv.mainInventory[slot];
        }
        if (slot < RewindFormat.INV_CURSOR) {
            return inv.armorInventory[slot - RewindFormat.INV_ARMOR_FIRST];
        }
        return inv.getItemStack();
    }

    private static void put(InventoryPlayer inv, int slot, ItemStack s) {
        if (slot < RewindFormat.INV_MAIN_SLOTS) {
            inv.mainInventory[slot] = s;
        } else if (slot < RewindFormat.INV_CURSOR) {
            inv.armorInventory[slot - RewindFormat.INV_ARMOR_FIRST] = s;
        } else {
            inv.setItemStack(s);
        }
    }

    /** The swing's length from the recorded player's haste and mining fatigue (on the puppet, where S1D lands). */
    private static int swingEnd(RewindSelfPuppet p) {
        if (p == null) {
            return HandState.SWING_END;
        }
        PotionEffect haste = p.getActivePotionEffect(Potion.digSpeed);
        PotionEffect fatigue = p.getActivePotionEffect(Potion.digSlowdown);
        return HandState.swingEnd(haste == null ? -1 : haste.getAmplifier(),
                fatigue == null ? -1 : fatigue.getAmplifier());
    }
}
