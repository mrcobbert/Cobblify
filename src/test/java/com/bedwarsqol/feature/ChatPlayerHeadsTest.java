package com.bedwarsqol.feature;

import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Regressions for the head support class after unifying heads onto the FKDR pipeline. Covers the name
 * locator (the {@code >>> … joined the lobby! <<<} bug), the per-skin sentinel registry, and the
 * component-tree splice that places the head holder immediately before the name.
 */
public class ChatPlayerHeadsTest {

    // ---- name location --------------------------------------------------------------------------

    /** The bug: an FKDR bracket prepended in front of the `>>>` used to break the anchored regex. */
    @Test
    public void locatesNameAfterFkdrAndArrows() {
        String flat = "[2.02] >>> [MVP+] Notch joined the lobby! <<<";
        int idx = ChatPlayerHeads.locateName(flat, "Notch");
        assertEquals(flat.indexOf("Notch"), idx);
    }

    @Test
    public void locatesNameInColonLine() {
        String flat = "[MVP+] Notch: gg wp";
        assertEquals(flat.indexOf("Notch"), ChatPlayerHeads.locateName(flat, "Notch"));
    }

    @Test
    public void skipsNameInsideBrackets() {
        // The token also appears as a rank tag; the sender slot is the un-bracketed occurrence.
        String flat = "[Bob] Bob: hi";
        assertEquals(6, ChatPlayerHeads.locateName(flat, "Bob"));
    }

    @Test
    public void wordBoundedSoBobIsNotBobby() {
        assertEquals(-1, ChatPlayerHeads.locateName("Bobby: hi", "Bob"));
    }

    @Test
    public void blankBracketsMasksSpansStable() {
        String in = "[MVP+] Bob";
        String masked = ChatPlayerHeads.blankBrackets(in);
        assertEquals("length is preserved so indices stay valid", in.length(), masked.length());
        assertEquals("bracket span is gone", -1, masked.indexOf('['));
        assertEquals("Bob keeps its original offset", in.indexOf("Bob"), masked.indexOf("Bob"));
    }

    // ---- sentinel registry ----------------------------------------------------------------------

    @Test
    public void sentinelRegistryAllocatesReusesAndClears() {
        ChatPlayerHeads.clear();
        ResourceLocation a = new ResourceLocation("skins/a");
        ResourceLocation b = new ResourceLocation("skins/b");
        char ca = ChatPlayerHeads.sentinelFor(a);
        char cb = ChatPlayerHeads.sentinelFor(b);
        assertNotEquals("distinct skins get distinct codepoints", ca, cb);
        assertEquals("same skin reuses its codepoint", ca, ChatPlayerHeads.sentinelFor(a));
        assertTrue(ChatPlayerHeads.isSentinel(ca));
        assertTrue(ChatPlayerHeads.isSentinel(cb));
        assertFalse("an unallocated PUA char is not a sentinel", ChatPlayerHeads.isSentinel('\uE0FE'));
        assertFalse(ChatPlayerHeads.isSentinel('A'));
        ChatPlayerHeads.clear();
        assertFalse("clear drops allocations", ChatPlayerHeads.isSentinel(ca));
    }

    /** Chat lines outlive the world change, so a full registry recycles by use order instead of wiping. */
    @Test
    public void sentinelRegistryRecyclesLeastRecentlyUsedWhenFull() {
        ChatPlayerHeads.clear();
        int slots = (ChatPlayerHeads.SENTINEL_MAX - ChatPlayerHeads.SENTINEL_MIN) + 1;
        char[] codes = new char[slots];
        for (int i = 0; i < slots; i++) {
            codes[i] = ChatPlayerHeads.sentinelFor(new ResourceLocation("skins/" + i));
        }
        assertEquals("the range is full", ChatPlayerHeads.SENTINEL_MAX, codes[slots - 1]);
        char kept = ChatPlayerHeads.sentinelFor(new ResourceLocation("skins/0")); // re-touch: now most recent
        assertEquals(codes[0], kept);

        char fresh = ChatPlayerHeads.sentinelFor(new ResourceLocation("skins/new"));
        assertNotEquals("a full registry still hands out a codepoint", 0, fresh);
        assertEquals("the re-touched skin was not the victim", codes[0], ChatPlayerHeads.sentinelFor(
                new ResourceLocation("skins/0")));
        assertEquals("the least-recently-used slot was the one recycled", codes[1], fresh);
        assertNotEquals("the evicted skin lost its old codepoint", codes[1], ChatPlayerHeads.sentinelFor(
                new ResourceLocation("skins/1")));
    }

    // ---- component-tree splice ------------------------------------------------------------------

    @Test
    public void splicesHolderImmediatelyBeforeHoveredName() {
        ChatComponentText root = new ChatComponentText("");
        root.appendSibling(new ChatComponentText("[MVP+] "));
        ChatComponentText name = new ChatComponentText("Notch");
        name.setChatStyle(new ChatStyle().setChatHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("card"))));
        root.appendSibling(name);
        root.appendSibling(new ChatComponentText(": gg"));

        ChatComponentText holder = new ChatComponentText("");
        assertTrue(ChatPlayerHeads.spliceHeadHolder(root, "Notch", holder));

        // The holder must sit immediately before a leaf that renders "Notch" and still carries the hover.
        List<IChatComponent> leaves = new ArrayList<IChatComponent>();
        collectLeaves(root, leaves);
        int hi = -1;
        for (int i = 0; i < leaves.size(); i++) if (leaves.get(i) == holder) hi = i;
        assertNotEquals("holder is in the tree", -1, hi);
        IChatComponent afterName = leaves.get(hi + 1);
        assertEquals("Notch", afterName.getUnformattedTextForChat());
        assertSame("an already-separated name keeps its exact client-styled component", name, afterName);
        assertNotNull("name keeps its rank-card hover", afterName.getChatStyle().getChatHoverEvent());
        assertSame(holder, leaves.get(hi));
    }

    /**
     * A {@code /party list} roster line carries several names and gets one holder in front of each.
     * The holders are empty when spliced, so the line's text never shifts and every name's offset is
     * still valid for the splices that follow it.
     */
    @Test
    public void splicesOneHolderBeforeEachRosterName() {
        ChatComponentText root = new ChatComponentText("");
        root.appendSibling(new ChatComponentText("Party Members: [MVP+] wnmv ● [VIP] Zebra ●"));

        ChatComponentText first = new ChatComponentText("");
        ChatComponentText second = new ChatComponentText("");
        assertTrue(ChatPlayerHeads.spliceHeadHolder(root, "wnmv", first));
        assertTrue(ChatPlayerHeads.spliceHeadHolder(root, "Zebra", second));

        List<IChatComponent> leaves = new ArrayList<IChatComponent>();
        collectLeaves(root, leaves);
        assertTrue("each holder sits immediately before its own member",
                startsAfter(leaves, first, "wnmv") && startsAfter(leaves, second, "Zebra"));
        assertEquals("the line still reads the same once the holders are empty",
                "Party Members: [MVP+] wnmv ● [VIP] Zebra ●", root.getUnformattedText());
    }

    /** True when the leaf right after {@code holder} begins with {@code name}. */
    private static boolean startsAfter(List<IChatComponent> leaves, IChatComponent holder, String name) {
        for (int i = 0; i < leaves.size() - 1; i++) {
            if (leaves.get(i) == holder) {
                return leaves.get(i + 1).getUnformattedTextForChat().startsWith(name);
            }
        }
        return false;
    }

    @Test
    public void spliceFailsClosedWhenNameAbsent() {
        ChatComponentText root = new ChatComponentText("");
        root.appendSibling(new ChatComponentText("Server: restarting"));
        assertFalse(ChatPlayerHeads.spliceHeadHolder(root, "Notch", new ChatComponentText("")));
    }

    private static void collectLeaves(IChatComponent c, List<IChatComponent> out) {
        out.add(c);
        for (Object s : c.getSiblings()) collectLeaves((IChatComponent) s, out);
    }
}
