package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;

import java.io.IOException;

/**
 * Your own clicks in a chest or shop window (PLAN Phase 11, review I4; TASK C5, container part). Live, a click changes
 * the window's slots at once ({@code windowClick} runs {@code slotClick} on the client) and the server's S2F answers
 * later; the recorder stores every change of the open window's own slots at the end of its tick (CONT: window id,
 * slot number in that window, protocol-47 stack).
 *
 * <p>Each CONT is applied in the local phase of its tick, after that tick's NET records, to the recorded window held
 * on the camera (Phase 9's container in {@code camera.openContainer}: {@code WindowChest} when drawn,
 * {@code PlayerSlots} when not) when its window id matches: {@code putStackInSlot(slot, stack)}. So your click shows in
 * its own tick, before the server's answer, and a later S2F (fed in its handled order) still wins. A record for
 * another window, with only the inventory container open, or past the window's slots changes nothing; the camera's own
 * inventory is never written here (that is the INV record's job). The window follows the feed, so a seek's catch-up
 * reaches the contents straight play reaches.
 *
 * <p>Register once with {@link #register()} (Phase 16). Client thread; never sends.
 */
public final class ContainerPart implements PlaybackPart {

    /** What the part reads from the client. */
    interface Source {
        /** The camera (the playback client player), or null. */
        EntityPlayer player();
    }

    private static final Source CLIENT = () -> {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer instanceof RewindCamera ? mc.thePlayer : null;
    };

    private static final ContainerPart INSTANCE = new ContainerPart(CLIENT);

    private final Source source;
    private final ExtCodec.Cont record = new ExtCodec.Cont();

    ContainerPart(Source source) {
        this.source = source;
    }

    /** Registers the part with playback (once; repeated calls do nothing). Client thread. */
    public static void register() {
        RewindPlayback.register(INSTANCE);
    }

    /** The registered instance. */
    static ContainerPart instance() {
        return INSTANCE;
    }

    @Override
    public void local(int tag, long timeUs, byte[] buf, int off, int len) {
        if (tag != RewindFormat.TAG_CONT) {
            return;
        }
        record.read(buf, off, len);
        EntityPlayer player = source.player();
        Container window = player == null ? null : player.openContainer;
        if (window == null || window == player.inventoryContainer || (window.windowId & 0xFF) != record.windowId
                || record.slot >= window.inventorySlots.size()) {
            return;
        }
        ItemStack stack;
        try {
            stack = new PacketBuffer(Unpooled.wrappedBuffer(record.buf, record.slotOff, record.slotLen))
                    .readItemStackFromBuffer();
        } catch (IOException e) {
            return; // an unreadable stack (bad NBT) is skipped, as an unknown state id is
        }
        window.putStackInSlot(record.slot, stack);
    }
}
