package com.bedwarsqol.feature;

import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;
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
 * Regressions for {@link ChatSplice}: the name locator (the {@code >>> … joined the lobby! <<<} bug)
 * and the component-tree splice that places an FKDR holder ahead of a named player — including the
 * {@code /party list} roster contract, where each member's bracket must land in front of that
 * member's own rank tag.
 */
public class ChatSpliceTest {

    // ---- name location --------------------------------------------------------------------------

    /** The bug: an FKDR bracket prepended in front of the `>>>` used to break the anchored regex. */
    @Test
    public void locatesNameAfterFkdrAndArrows() {
        String flat = "[2.02] >>> [MVP+] Notch joined the lobby! <<<";
        int idx = ChatSplice.locateName(flat, "Notch");
        assertEquals(flat.indexOf("Notch"), idx);
    }

    @Test
    public void locatesNameInColonLine() {
        String flat = "[MVP+] Notch: gg wp";
        assertEquals(flat.indexOf("Notch"), ChatSplice.locateName(flat, "Notch"));
    }

    @Test
    public void skipsNameInsideBrackets() {
        // The token also appears as a rank tag; the sender slot is the un-bracketed occurrence.
        String flat = "[Bob] Bob: hi";
        assertEquals(6, ChatSplice.locateName(flat, "Bob"));
    }

    @Test
    public void wordBoundedSoBobIsNotBobby() {
        assertEquals(-1, ChatSplice.locateName("Bobby: hi", "Bob"));
    }

    @Test
    public void blankBracketsMasksSpansStable() {
        String in = "[MVP+] Bob";
        String masked = ChatSplice.blankBrackets(in);
        assertEquals("length is preserved so indices stay valid", in.length(), masked.length());
        assertEquals("bracket span is gone", -1, masked.indexOf('['));
        assertEquals("Bob keeps its original offset", in.indexOf("Bob"), masked.indexOf("Bob"));
    }

    // ---- rank-aware insert point ----------------------------------------------------------------

    @Test
    public void rankStartBacksUpOverTheAdjacentRankSpan() {
        String flat = "[MVP+] Notch: gg";
        assertEquals(0, ChatSplice.rankStart(flat, flat.indexOf("Notch")));
    }

    @Test
    public void rankStartHoldsAtTheNameWhenUnranked() {
        String flat = "Notch: gg";
        assertEquals(0, ChatSplice.rankStart(flat, 0));
    }

    /** A bracket that is not adjacent (another member's name and a dot intervene) is not this one's rank. */
    @Test
    public void rankStartIgnoresANonAdjacentBracket() {
        String flat = "Party Members: [MVP+] wnmv ● Zebra ●";
        int zebra = flat.indexOf("Zebra");
        assertEquals(zebra, ChatSplice.rankStart(flat, zebra));
    }

    // ---- component-tree splice ------------------------------------------------------------------

    /**
     * A name that already starts its own leaf is spliced in front of that leaf whole — the component
     * is never rebuilt, because Lunar's copy-on-add path can lose styling while converting one.
     */
    @Test
    public void keepsTheLeafIntactWhenTheNameAlreadyStartsIt() {
        ChatComponentText root = new ChatComponentText("");
        ChatComponentText line = new ChatComponentText("Notch: gg");
        line.setChatStyle(new ChatStyle().setChatHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("card"))));
        root.appendSibling(line);

        ChatComponentText holder = new ChatComponentText("");
        assertTrue(ChatSplice.spliceBeforeRankedName(root, "Notch", holder));

        List<IChatComponent> leaves = new ArrayList<IChatComponent>();
        collectLeaves(root, leaves);
        int hi = indexOf(leaves, holder);
        assertNotEquals("holder is in the tree", -1, hi);
        assertSame("the leaf is spliced in front of, never rebuilt", line, leaves.get(hi + 1));
        assertEquals("the line still reads the same once the holder is empty",
                "Notch: gg", root.getUnformattedText());
    }

    /**
     * The leaf-splitting branch: a name sitting <i>mid</i>-leaf forces the covering leaf to be cut in
     * two around the holder. Both halves must inherit the original's style, and any children the leaf
     * carried must follow the half that carries the name.
     */
    @Test
    public void splitsTheCoveringLeafAndKeepsStyleOnBothHalves() {
        ChatComponentText root = new ChatComponentText("");
        ChatComponentText line = new ChatComponentText("Party Members: wnmv ● Zebra ●");
        line.setChatStyle(new ChatStyle().setChatHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("card"))));
        ChatComponentText trailingChild = new ChatComponentText(" (leader)");
        line.appendSibling(trailingChild);
        root.appendSibling(line);

        ChatComponentText holder = new ChatComponentText("");
        assertTrue(ChatSplice.spliceBeforeRankedName(root, "Zebra", holder));

        List<IChatComponent> leaves = new ArrayList<IChatComponent>();
        collectLeaves(root, leaves);
        int hi = indexOf(leaves, holder);
        assertNotEquals("holder is in the tree", -1, hi);

        IChatComponent before = leaves.get(hi - 1);
        IChatComponent after = leaves.get(hi + 1);
        assertEquals("the leaf was cut immediately before the name",
                "Party Members: wnmv ● ", before.getUnformattedTextForChat());
        assertEquals("the name half carries the rest of the text",
                "Zebra ●", after.getUnformattedTextForChat());
        assertNotNull("the name half keeps the leaf's hover",
                after.getChatStyle().getChatHoverEvent());
        assertNotNull("the leading half keeps the leaf's hover too",
                before.getChatStyle().getChatHoverEvent());
        assertTrue("the leaf's own children follow the name half",
                after.getSiblings().contains(trailingChild));
        assertEquals("the line still reads the same once the holder is empty",
                "Party Members: wnmv ● Zebra ● (leader)", root.getUnformattedText());
    }

    /**
     * The real placement contract: the bracket goes ahead of the rank the name wears, so the line reads
     * {@code "[1.25] [MVP+] Notch"} rather than {@code "[MVP+] [1.25] Notch"}. The already-separated
     * name component must survive untouched — Lunar's copy-on-add path loses styling on replacements.
     */
    @Test
    public void splicesAheadOfTheRankTheNameWears() {
        ChatComponentText root = new ChatComponentText("");
        ChatComponentText rank = new ChatComponentText("[MVP+] ");
        root.appendSibling(rank);
        ChatComponentText name = new ChatComponentText("Notch");
        name.setChatStyle(new ChatStyle().setChatHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("card"))));
        root.appendSibling(name);
        root.appendSibling(new ChatComponentText(": gg"));

        ChatComponentText holder = new ChatComponentText("");
        assertTrue(ChatSplice.spliceBeforeRankedName(root, "Notch", holder));

        List<IChatComponent> leaves = new ArrayList<IChatComponent>();
        collectLeaves(root, leaves);
        int hi = indexOf(leaves, holder);
        assertNotEquals("holder is in the tree", -1, hi);
        assertSame("the holder sits ahead of the rank, not between rank and name", rank, leaves.get(hi + 1));
        IChatComponent afterName = leaves.get(hi + 2);
        assertSame("an already-separated name keeps its exact client-styled component", name, afterName);
        assertNotNull("name keeps its rank-card hover", afterName.getChatStyle().getChatHoverEvent());
    }

    /**
     * A {@code /party list} roster line carries several names and gets one holder in front of each,
     * ahead of that member's own rank. The holders are empty when spliced, so the line's text never
     * shifts and every name's offset is still valid for the splices that follow it.
     */
    @Test
    public void splicesOneHolderAheadOfEachRosterMembersRank() {
        ChatComponentText root = new ChatComponentText("");
        root.appendSibling(new ChatComponentText("Party Members: [MVP+] wnmv ● [VIP] Zebra ●"));

        ChatComponentText first = new ChatComponentText("");
        ChatComponentText second = new ChatComponentText("");
        assertTrue(ChatSplice.spliceBeforeRankedName(root, "wnmv", first));
        assertTrue(ChatSplice.spliceBeforeRankedName(root, "Zebra", second));

        List<IChatComponent> leaves = new ArrayList<IChatComponent>();
        collectLeaves(root, leaves);
        assertTrue("each holder sits ahead of its own member's rank",
                startsAfter(leaves, first, "[MVP+] wnmv") && startsAfter(leaves, second, "[VIP] Zebra"));
        assertEquals("the line still reads the same once the holders are empty",
                "Party Members: [MVP+] wnmv ● [VIP] Zebra ●", root.getUnformattedText());
    }

    @Test
    public void spliceFailsClosedWhenNameAbsent() {
        ChatComponentText root = new ChatComponentText("");
        root.appendSibling(new ChatComponentText("Server: restarting"));
        assertFalse(ChatSplice.spliceBeforeRankedName(root, "Notch", new ChatComponentText("")));
    }

    /** True when the leaf right after {@code holder} begins with {@code text}. */
    private static boolean startsAfter(List<IChatComponent> leaves, IChatComponent holder, String text) {
        int i = indexOf(leaves, holder);
        return i >= 0 && i + 1 < leaves.size()
                && leaves.get(i + 1).getUnformattedTextForChat().startsWith(text);
    }

    private static int indexOf(List<IChatComponent> leaves, IChatComponent target) {
        for (int i = 0; i < leaves.size(); i++) if (leaves.get(i) == target) return i;
        return -1;
    }

    private static void collectLeaves(IChatComponent c, List<IChatComponent> out) {
        out.add(c);
        for (Object s : c.getSiblings()) collectLeaves((IChatComponent) s, out);
    }
}
