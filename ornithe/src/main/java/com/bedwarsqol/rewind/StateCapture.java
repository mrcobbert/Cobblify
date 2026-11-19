package com.bedwarsqol.rewind;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.rewind.watch.PlaybackGate;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The per-tick half of Rewind's format v2 client state (PLAN section A, Phase 3): USE, INV, CONT, SETTINGS and
 * IDENTITY, appended as extension records through a {@link Sink}. Read only: nothing here changes game state.
 *
 * <ul>
 *   <li><b>USE</b>: the full {@code itemInUseCount} whenever it does not follow from the previous tick (neither one
 *       less, nor 0 after 0), so a bow draw is stored as 72000 and the countdown is derived.</li>
 *   <li><b>INV</b>: slots 0-35 main, 36-39 armour (boots to helmet), 40 the cursor stack. Per slot the last stack
 *       reference, item, count, damage and tag reference are kept and compared every tick (vanilla changes damage and
 *       count on the same object), and every {@link #DEEP_COMPARE_TICKS} ticks a tagged slot's tag is compared with
 *       a copy kept at its last write, which catches a tag edited in place within a second. A stack that is a
 *       different but equal object (same item, count and damage; for a tagged one, tag content equal to that copy,
 *       compared only when the reference changed) is followed without a record. The segment's first tick writes all
 *       41.</li>
 *   <li><b>CONT</b>: while {@code openContainer} is not the inventory container, the window's own slots (not the
 *       player's inventory, which INV covers) the same way, measured from the empty window vanilla's S2D creates, so
 *       a window's first tick writes its filled slots and a local click's prediction is stored at its tick, before
 *       the server answers.</li>
 *   <li><b>SETTINGS</b> ({@link SettingsSnapshot}): every key on the segment's first tick, then the changed keys.</li>
 *   <li><b>IDENTITY</b>: own UUID, name and {@code textures} property (value and signature), read from the player's
 *       own tab entry ({@link #ownTabProfile}; the login profile never has the property). Written once the entry is
 *       there (it may arrive after the segment's first tick; nothing is written or allocated while waiting), and again
 *       within the segment only if a new entry carries different textures.</li>
 * </ul>
 *
 * <p><b>Cost.</b> Client thread only. An unchanged tick allocates nothing, and neither does a change on an untagged
 * stack. Serializing a tagged stack (vanilla's NBT writer, plus the kept tag copy), the deep compare (an iterator per
 * tagged slot, once a second, and for a tagged stack replaced by another object) and reading a new tab entry's
 * textures for IDENTITY are the allocating cases.
 *
 * <p><b>Wiring.</b> Inert until a sink is {@link #install installed} (Phase 6): {@link #tick(Minecraft)} returns at
 * once with no sink or while a recording plays. The wiring calls {@link #resetSegment()} when a TRACK segment
 * starts and {@link #tick(Minecraft)} at the end of each client tick of a recorded segment. A throw inside capture
 * is reported to {@link Sink#failed} and the tick goes on.
 */
public final class StateCapture {

    /** Where the client-state capture appends; Phase 6 implements it over the TRACK producer. */
    public interface Sink {
        /**
         * Appends one extension record stamped with the TRACK clock now: {@code buf[0, len)} is
         * {@code varint bodyLength | body}, exactly what follows the tag on disk. False when the producer had no room
         * (the sink has then already failed the segment; capture stops for this call).
         */
        boolean append(int tag, byte[] buf, int len);

        /** A capture hook threw: ends recording for the game; the hooked game code goes on unchanged. */
        void failed(String where, Throwable t);
    }

    /** Ticks between deep tag compares: a tag edited in place is stored within a second. */
    static final int DEEP_COMPARE_TICKS = 20;

    private static Sink sink;
    private static final StateCapture INSTANCE = new StateCapture(DEEP_COMPARE_TICKS);
    private static final SettingsSnapshot SETTINGS = new SettingsSnapshot();

    /**
     * Installs (or, with null, removes) the sink. Client thread. Removing it also drops every kept reference (the last
     * open window, and through it the player and the world; the per-slot stacks and tags; the tab profile), so a
     * released capture retains nothing (code review 1, O3).
     */
    public static void install(Sink s) {
        sink = s;
        if (s == null) {
            INSTANCE.reset();
        }
    }

    /**
     * A TRACK segment starts: the next tick writes every SETTINGS key and all 41 INV slots again, and IDENTITY once the
     * own tab entry is there.
     */
    public static void resetSegment() {
        INSTANCE.reset();
    }

    /** Whether a sink is installed. */
    static boolean installed() {
        return sink != null;
    }

    /** End of a client tick of a recorded segment. Inert with no sink, while a recording plays, or with no player. */
    public static void tick(Minecraft mc) {
        Sink s = sink;
        if (s == null || PlaybackGate.muted() || mc == null) {
            return;
        }
        try {
            EntityPlayerSP p = mc.thePlayer;
            if (p == null) {
                return;
            }
            SETTINGS.read(mc, BedwarsQol.config);
            INSTANCE.tick(s, p.getItemInUseCount(), p.inventory, p.openContainer, p.inventoryContainer, SETTINGS,
                    ownTabProfile(p.sendQueue, p.getUniqueID()));
        } catch (Throwable t) {
            s.failed("state", t);
        }
    }

    /**
     * {@link #tick(Minecraft)} with the player's values already read (tests and the bench drive the installed sink
     * through it without a client). Same guards.
     */
    static void tick(int useCount, InventoryPlayer playerInv, Container open, Container own, SettingsSnapshot settings,
                     GameProfile tabProfile) {
        Sink s = sink;
        if (s == null || PlaybackGate.muted()) {
            return;
        }
        INSTANCE.tick(s, useCount, playerInv, open, own, settings, tabProfile);
    }

    /**
     * The profile of the player's own tab entry ({@code NetHandlerPlayClient.getPlayerInfo(uuid)}), the one S38 filled
     * with the signed {@code textures} property and the one vanilla draws the skin from; null while the entry has not
     * arrived. The login profile the player entity holds never has properties (code review 1, I1). A map lookup:
     * allocates nothing.
     */
    static GameProfile ownTabProfile(NetHandlerPlayClient net, UUID self) {
        if (net == null || self == null) {
            return null;
        }
        NetworkPlayerInfo info = net.getPlayerInfo(self);
        return info == null ? null : info.getGameProfile();
    }

    /** Whether capture still holds a reference to game objects (tests). */
    static boolean holdsReferences() {
        return INSTANCE.holds();
    }

    // ------------------------------------------------------------------ instance (testable without a client)

    private final int deepEvery;
    private final ExtCodec codec = new ExtCodec();
    private final PacketBuffer itemBuf = new PacketBuffer(Unpooled.buffer(256));
    private final Slots inv = new Slots(RewindFormat.INV_SLOTS);
    private final Slots cont = new Slots(128);

    private boolean segmentStart = true;
    /** The tab profile IDENTITY was last checked against (a new S38 ADD_PLAYER makes a new one), or null. */
    private GameProfile identityProfile;
    /** The textures value and signature of this segment's last IDENTITY; null before the first. */
    private String identitySkin;
    private String identitySig;
    private long ticks;
    private int lastUse;
    private Container window;
    private int windowId;

    /** {@code deepEvery}: ticks between deep tag compares; 0 never compares (tests measure it apart). */
    StateCapture(int deepEvery) {
        this.deepEvery = deepEvery;
    }

    boolean holds() {
        return window != null || identityProfile != null || inv.holds() || cont.holds();
    }

    void reset() {
        segmentStart = true;
        identityProfile = null;
        identitySkin = null;
        identitySig = null;
        ticks = 0;
        lastUse = 0;
        window = null;
        windowId = 0;
        inv.clear();
        cont.clear();
    }

    /**
     * One tick: IDENTITY, SETTINGS, INV, USE, CONT. {@code tabProfile} is the own tab entry's profile
     * ({@link #ownTabProfile}), null while it has not arrived. Returns false when the sink failed or threw (a throw is
     * reported to {@link Sink#failed} once).
     */
    boolean tick(Sink s, int useCount, InventoryPlayer playerInv, Container open, Container own,
                 SettingsSnapshot settings, GameProfile tabProfile) {
        try {
            boolean first = segmentStart;
            boolean deep = deepEvery > 0 && ticks % deepEvery == 0;
            if (tabProfile != null && tabProfile != identityProfile && !identity(s, tabProfile)) {
                return false;
            }
            if (settings != null) {
                int n = settings.write(codec, first);
                if (n > 0 && !s.append(RewindFormat.TAG_SETTINGS, codec.buffer(), n)) {
                    return false;
                }
            }
            if (playerInv != null && !inventory(s, playerInv, first, deep)) {
                return false;
            }
            if (!use(s, useCount)) {
                return false;
            }
            if (!container(s, open, own, playerInv, deep)) {
                return false;
            }
            segmentStart = false;
            ticks++;
            return true;
        } catch (Throwable t) {
            s.failed("state", t);
            return false;
        }
    }

    /**
     * A tab profile not checked yet (the segment's first sight of the entry, or a new entry from a later S38): writes
     * IDENTITY when this segment has none yet or the textures differ from the last one written. Reads the property map
     * (allocates), so it runs only when the entry object changed, never per tick.
     */
    private boolean identity(Sink s, GameProfile profile) {
        identityProfile = profile;
        String skin = "";
        String sig = "";
        for (Property p : profile.getProperties().get("textures")) {
            skin = p.getValue() == null ? "" : p.getValue();
            sig = p.getSignature() == null ? "" : p.getSignature();
            break;
        }
        if (skin.equals(identitySkin) && sig.equals(identitySig)) {
            return true;
        }
        UUID id = profile.getId();
        int n = codec.identity(id == null ? 0L : id.getMostSignificantBits(),
                id == null ? 0L : id.getLeastSignificantBits(), profile.getName(), skin, sig);
        if (!s.append(RewindFormat.TAG_IDENTITY, codec.buffer(), n)) {
            return false;
        }
        identitySkin = skin;
        identitySig = sig;
        return true;
    }

    private boolean inventory(Sink s, InventoryPlayer p, boolean force, boolean deep) {
        for (int i = 0; i < RewindFormat.INV_SLOTS; i++) {
            ItemStack st = i < RewindFormat.INV_MAIN_SLOTS ? p.mainInventory[i]
                    : i < RewindFormat.INV_CURSOR ? p.armorInventory[i - RewindFormat.INV_ARMOR_FIRST]
                    : p.getItemStack();
            if (force || inv.changed(i, st, deep)) {
                int len = serialize(st);
                int n = codec.inv(i, itemBuf.array(), itemBuf.arrayOffset() + itemBuf.readerIndex(), len);
                if (!s.append(RewindFormat.TAG_INV, codec.buffer(), n)) {
                    return false;
                }
                inv.keep(i, st);
            }
        }
        return true;
    }

    private boolean use(Sink s, int count) {
        int prev = lastUse;
        lastUse = count;
        if (count == prev - 1 || (count == 0 && prev == 0)) {
            return true;
        }
        int n = codec.use(count);
        return s.append(RewindFormat.TAG_USE, codec.buffer(), n);
    }

    private boolean container(Sink s, Container open, Container own, InventoryPlayer playerInv, boolean deep) {
        if (open == null || open == own) {
            window = null;
            return true;
        }
        if (open != window || open.windowId != windowId) {
            window = open;
            windowId = open.windowId;
            cont.clear(); // measured from the empty window S2D creates
        }
        List<Slot> slots = open.inventorySlots;
        for (int k = 0, size = slots.size(); k < size; k++) {
            Slot slot = slots.get(k);
            if (slot == null || slot.inventory == playerInv) {
                continue;
            }
            int idx = slot.slotNumber;
            if (idx < 0) {
                continue;
            }
            cont.ensure(idx + 1);
            ItemStack st = slot.getStack();
            if (cont.changed(idx, st, deep)) {
                int len = serialize(st);
                int n = codec.cont(windowId & 0xFF, idx, itemBuf.array(),
                        itemBuf.arrayOffset() + itemBuf.readerIndex(), len);
                if (!s.append(RewindFormat.TAG_CONT, codec.buffer(), n)) {
                    return false;
                }
                cont.keep(idx, st);
            }
        }
        return true;
    }

    /** Vanilla's protocol-47 slot bytes of {@code st} into {@link #itemBuf}; returns their length. */
    private int serialize(ItemStack st) {
        PacketBuffer b = itemBuf;
        b.clear();
        b.writeItemStackToBuffer(st);
        return b.readableBytes();
    }

    /** What was last written per slot, compared without allocating. */
    static final class Slots {
        private ItemStack[] ref;
        private Item[] item;
        private int[] size;
        private int[] damage;
        private NBTTagCompound[] tagRef;
        private NBTTagCompound[] tagCopy;

        Slots(int n) {
            ref = new ItemStack[n];
            item = new Item[n];
            size = new int[n];
            damage = new int[n];
            tagRef = new NBTTagCompound[n];
            tagCopy = new NBTTagCompound[n];
        }

        /** Every slot empty (the state a new segment or window is measured from). */
        void clear() {
            Arrays.fill(ref, null);
            Arrays.fill(item, null);
            Arrays.fill(size, 0);
            Arrays.fill(damage, 0);
            Arrays.fill(tagRef, null);
            Arrays.fill(tagCopy, null);
        }

        boolean holds() {
            for (int i = 0; i < ref.length; i++) {
                if (ref[i] != null || item[i] != null || tagRef[i] != null || tagCopy[i] != null) {
                    return true;
                }
            }
            return false;
        }

        /** Grows to {@code n} slots (only for a window larger than any vanilla one). */
        void ensure(int n) {
            if (n <= ref.length) {
                return;
            }
            int m = Math.max(n, ref.length * 2);
            ref = Arrays.copyOf(ref, m);
            item = Arrays.copyOf(item, m);
            size = Arrays.copyOf(size, m);
            damage = Arrays.copyOf(damage, m);
            tagRef = Arrays.copyOf(tagRef, m);
            tagCopy = Arrays.copyOf(tagCopy, m);
        }

        boolean changed(int i, ItemStack st, boolean deep) {
            ItemStack r = ref[i];
            if (st == null) {
                return r != null;
            }
            if (r == null) {
                return true;
            }
            NBTTagCompound tag = st.getTagCompound();
            if (st != r) {
                if (st.getItem() != item[i] || st.stackSize != size[i] || st.getItemDamage() != damage[i]
                        || (tag == null) != (tagRef[i] == null)) {
                    return true;
                }
                // Another object, same item, count and damage (a server resend). Untagged: equal. Tagged: compare the
                // content with the copy kept at the last write, only now that the reference changed (code review 1,
                // O4); the kept copy stays, so a later in-place edit of the new tag is still caught by the deep compare.
                if (tag != null && !tag.equals(tagCopy[i])) {
                    return true;
                }
                ref[i] = st; // an equal stack in another object: follow it, nothing to store
                tagRef[i] = tag;
                return false;
            }
            if (st.getItem() != item[i] || st.stackSize != size[i] || st.getItemDamage() != damage[i]
                    || tag != tagRef[i]) {
                return true;
            }
            return deep && tag != null && !tag.equals(tagCopy[i]);
        }

        void keep(int i, ItemStack st) {
            ref[i] = st;
            if (st == null) {
                item[i] = null;
                size[i] = 0;
                damage[i] = 0;
                tagRef[i] = null;
                tagCopy[i] = null;
                return;
            }
            item[i] = st.getItem();
            size[i] = st.stackSize;
            damage[i] = st.getItemDamage();
            NBTTagCompound tag = st.getTagCompound();
            tagRef[i] = tag;
            tagCopy[i] = tag == null ? null : (NBTTagCompound) tag.copy();
        }
    }
}
