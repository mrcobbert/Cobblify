package com.bedwarsqol.rewind;

import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;

import java.util.Arrays;

/**
 * The player's own TRACK records (PLAN "Self track"), which the server never sends back: a TICK on each client tick
 * with the fields that changed, an EQUIP when the held item or a worn armour piece changed, and CAMERA samples (the
 * rendered yaw and pitch) that the caller rate-limits. Also writes the wiring's terminal records (MARKER, GAME_START,
 * GAME_END, INFO) into the same producer, so every TRACK record of a connection comes from this one thread.
 *
 * <p>Client thread only. A TICK or CAMERA allocates nothing; an EQUIP serializes the stack through one reused
 * {@link PacketBuffer} only when it changed (vanilla's NBT writer allocates). Each method returns false only when the
 * producer had no chunk for the record (the wiring turns that into a game-scoped failure).
 */
public final class SelfTrackSampler {

    /** EQUIP slots: 0 held, then {@code armorInventory[0..3]} = boots to helmet. */
    private static final int SLOTS = RewindFormat.EQUIP_HELMET + 1;

    private final SelfTrackEncoder enc = new SelfTrackEncoder();
    private final ItemStack[] lastStacks = new ItemStack[SLOTS];
    private boolean equipForced = true;
    private PacketBuffer itemBuf;

    /** A new segment: the next TICK is full and every EQUIP slot is written once again. */
    public void resetSegment() {
        enc.resetSegment();
        Arrays.fill(lastStacks, null);
        equipForced = true;
    }

    /** One TICK record if any field changed since the previous one of this segment. */
    public boolean tick(RecordChunk.Producer track, long nowUs, double x, double y, double z, float yaw, float pitch,
                        float headYaw, int flags, int heldSlot, int swingProgressInt, int hurtTime) {
        int n = enc.tick(x, y, z, yaw, pitch, headYaw, flags, heldSlot, swingProgressInt, hurtTime);
        return n == 0 || track.appendTrack(nowUs, RewindFormat.TAG_TICK, enc.buffer(), 0, n);
    }

    /** TICK and EQUIP for {@code p} (the local player). */
    public boolean player(RecordChunk.Producer track, long nowUs, EntityPlayer p) {
        int flags = 0;
        if (p.onGround) flags |= RewindFormat.FLAG_ON_GROUND;
        if (p.isSneaking()) flags |= RewindFormat.FLAG_SNEAKING;
        if (p.isSprinting()) flags |= RewindFormat.FLAG_SPRINTING;
        if (p.isUsingItem()) flags |= RewindFormat.FLAG_USING_ITEM;
        if (p.isInvisible()) flags |= RewindFormat.FLAG_INVISIBLE;
        if (p.isBurning()) flags |= RewindFormat.FLAG_BURNING;
        if (p.isRiding()) flags |= RewindFormat.FLAG_RIDING;
        boolean ok = tick(track, nowUs, p.posX, p.posY, p.posZ, p.rotationYaw, p.rotationPitch, p.rotationYawHead,
                flags, p.inventory.currentItem, p.swingProgressInt, p.hurtTime);
        ok &= equip(track, nowUs, RewindFormat.EQUIP_HELD, p.inventory.getCurrentItem());
        for (int i = 0; i < 4; i++) {
            ok &= equip(track, nowUs, RewindFormat.EQUIP_BOOTS + i, p.inventory.armorInventory[i]);
        }
        equipForced = false;
        return ok;
    }

    private boolean equip(RecordChunk.Producer track, long nowUs, int slot, ItemStack stack) {
        if (!equipForced && ItemStack.areItemStacksEqual(stack, lastStacks[slot])) {
            return true;
        }
        lastStacks[slot] = stack == null ? null : stack.copy();
        PacketBuffer b = itemBuf;
        if (b == null) {
            b = itemBuf = new PacketBuffer(Unpooled.buffer(256));
        }
        b.clear();
        b.writeItemStackToBuffer(stack);
        return equipBytes(track, nowUs, slot, b.array(), b.arrayOffset() + b.readerIndex(), b.readableBytes());
    }

    /** The EQUIP record for protocol-47 slot bytes, if they changed (package-private for tests). */
    boolean equipBytes(RecordChunk.Producer track, long nowUs, int slot, byte[] item, int off, int len) {
        int n = enc.equip(slot, item, off, len); // may grow the encoder buffer: read buffer() after it
        return n == 0 || track.appendTrack(nowUs, RewindFormat.TAG_EQUIP, enc.buffer(), 0, n);
    }

    /** One CAMERA record if the rendered rotation changed since the last sample of this segment. */
    public boolean camera(RecordChunk.Producer track, long nowUs, float yaw, float pitch) {
        int n = enc.camera(yaw, pitch);
        return n == 0 || track.appendTrack(nowUs, RewindFormat.TAG_CAMERA, enc.buffer(), 0, n);
    }

    // Every method encodes first and reads enc.buffer() after: an encode call may replace the buffer with a
    // larger one, and Java evaluates call arguments left to right (code review 1, I3).

    /** A MARKER record ({@code RewindFormat.MARKER_*}). */
    public boolean marker(RecordChunk.Producer track, long nowUs, int kind) {
        int n = enc.marker(kind);
        return track.appendTrack(nowUs, RewindFormat.TAG_MARKER, enc.buffer(), 0, n);
    }

    /**
     * The GAME_START record, written at {@code nowUs} (when the game was opened) and naming the logical game
     * start {@code gameStartUs} (the visit start, or the Bed Wars active edge), which may be earlier.
     */
    public boolean gameStart(RecordChunk.Producer track, long nowUs, long gameStartUs) {
        int n = enc.gameStart(nowUs - gameStartUs);
        return track.appendTrack(nowUs, RewindFormat.TAG_GAME_START, enc.buffer(), 0, n);
    }

    /** A GAME_END record ({@code RewindFormat.GAME_END_*}). */
    public boolean gameEnd(RecordChunk.Producer track, long nowUs, int result) {
        int n = enc.gameEnd(result);
        return track.appendTrack(nowUs, RewindFormat.TAG_GAME_END, enc.buffer(), 0, n);
    }

    /** An INFO record ({@code RewindFormat.INFO_*}); a null or empty value writes nothing. */
    public boolean info(RecordChunk.Producer track, long nowUs, int key, String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        int n = enc.info(key, value);
        return track.appendTrack(nowUs, RewindFormat.TAG_INFO, enc.buffer(), 0, n);
    }
}
