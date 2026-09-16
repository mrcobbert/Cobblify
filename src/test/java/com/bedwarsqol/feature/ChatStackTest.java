package com.bedwarsqol.feature;

import com.bedwarsqol.feature.ChatStack.Counter;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChatStackTest {

    /** The Hypixel shape: empty root, content in siblings, plus ChatNameTags' empty tail holder. */
    private static ChatComponentText hypixelLine(String text) {
        ChatComponentText root = new ChatComponentText("");
        ChatComponentText body = new ChatComponentText(text);
        body.getChatStyle().setColor(EnumChatFormatting.WHITE);
        root.appendSibling(body);
        root.appendSibling(new ChatComponentText("")); // ChatNameTags suffix holder
        return root;
    }

    // ---- safe reads -----------------------------------------------------------------------------

    /** A bare IChatComponent implementation that throws on every flatten. */
    private static final class Hostile implements IChatComponent {
        @Override public IChatComponent setChatStyle(ChatStyle style) { return this; }
        @Override public ChatStyle getChatStyle() { return new ChatStyle(); }
        @Override public IChatComponent appendText(String text) { return this; }
        @Override public IChatComponent appendSibling(IChatComponent component) { return this; }
        @Override public String getUnformattedTextForChat() { throw new IllegalStateException("boom"); }
        @Override public String getUnformattedText() { throw new IllegalStateException("boom"); }
        @Override public String getFormattedText() { throw new IllegalStateException("boom"); }
        @Override public List<IChatComponent> getSiblings() { return new ArrayList<IChatComponent>(); }
        @Override public IChatComponent createCopy() { return this; }
        @Override public Iterator<IChatComponent> iterator() { return getSiblings().iterator(); }
    }

    @Test
    public void safeReadsReturnNullWhenTheComponentThrows() {
        assertNull(ChatStack.safeFormatted(new Hostile()));
        assertNull(ChatStack.safeUnformatted(new Hostile()));
        assertNull(ChatStack.safeFormatted(null));
        assertEquals("§fgg§r", ChatStack.safeFormatted(new ChatComponentText("gg").setChatStyle(
                new ChatStyle().setColor(EnumChatFormatting.WHITE))));
        assertEquals("gg", ChatStack.safeUnformatted(new ChatComponentText("gg")));
    }

    @Test
    public void onlyVanillaStyleComponentsAreStackable() {
        assertTrue(ChatStack.stackable(new ChatComponentText("x")));
        assertTrue(ChatStack.stackable(new ChatComponentTranslation("chat.type.text", "a", "b")));
        assertFalse(ChatStack.stackable(new Hostile()));
        assertFalse(ChatStack.stackable(null));
        // A ChatComponentStyle subclass that swapped its sibling list for anything but the vanilla
        // ArrayList (a subclass included) is not trusted with an in-place edit.
        assertFalse(ChatStack.stackable(new Foreign(new ThrowsOnAdd())));
        assertFalse(ChatStack.stackable(new Foreign(new MutatesThenThrowsOnRemove())));
        assertFalse(ChatStack.stackable(new Foreign(java.util.Collections.<IChatComponent>emptyList())));
        assertTrue(ChatStack.stackable(new Foreign(new ArrayList<IChatComponent>())));
    }

    // ---- setCounter -----------------------------------------------------------------------------

    @Test
    public void setCounterAppendsInPlaceAfterExistingHolders() {
        ChatComponentText line = hypixelLine("Dewier: gg");
        String before = line.getFormattedText();
        IChatComponent holder = line.getSiblings().get(1);

        Counter first = ChatStack.setCounter(line, 2);
        assertNull("no previous counter", first);
        List<IChatComponent> sibs = line.getSiblings();
        assertEquals(3, sibs.size());
        assertSame("holder keeps its slot", holder, sibs.get(1));
        assertTrue(sibs.get(2) instanceof Counter);
        assertEquals(2, ((Counter) sibs.get(2)).n);
        assertEquals(EnumChatFormatting.GRAY, sibs.get(2).getChatStyle().getColor());
        assertEquals(before + "§7 (x2)§r", line.getFormattedText());
        assertEquals("Dewier: gg (x2)", line.getUnformattedText());
    }

    @Test
    public void setCounterReplacesRatherThanAccumulates() {
        ChatComponentText line = hypixelLine("A");
        ChatStack.setCounter(line, 2);
        Counter c2 = ChatStack.counterOf(line);
        Counter previous = ChatStack.setCounter(line, 3);
        assertSame(c2, previous);
        assertEquals(3, line.getSiblings().size());
        assertEquals(3, ChatStack.counterOf(line).n);
        assertEquals("A (x3)", line.getUnformattedText());
        // Only one Counter remains.
        int counters = 0;
        for (IChatComponent s : line.getSiblings()) if (s instanceof Counter) counters++;
        assertEquals(1, counters);
    }

    @Test
    public void componentIdentityIsPreservedAcrossStacks() {
        ChatComponentText line = hypixelLine("A");
        IChatComponent holder = line.getSiblings().get(1);
        ChatStack.setCounter(line, 2);
        ChatStack.setCounter(line, 3);
        // The holder ChatNameTags keeps a reference to is still reachable and live.
        assertSame(holder, line.getSiblings().get(1));
        ((ChatComponentText) holder).appendText(" (Nicked)");
        assertEquals("A (Nicked) (x3)", line.getUnformattedText());
    }

    // ---- baseText -------------------------------------------------------------------------------

    @Test
    public void baseTextIsTheLiveTextWithoutTheCounter() {
        ChatComponentText line = hypixelLine("A");
        String before = line.getFormattedText();
        assertEquals(before, ChatStack.baseText(line));
        ChatStack.setCounter(line, 2);
        assertEquals(before, ChatStack.baseText(line));
        assertEquals("counter re-attached after the read", 2, ChatStack.counterOf(line).n);
        assertEquals("A (x2)", line.getUnformattedText());
        // Live: a holder patched after stacking changes the key, as it changes the screen.
        ((ChatComponentText) line.getSiblings().get(1)).appendText("!");
        ChatComponentText expected = hypixelLine("A");
        ((ChatComponentText) expected.getSiblings().get(1)).appendText("!");
        assertEquals(expected.getFormattedText(), ChatStack.baseText(line));
        assertEquals("A! (x2)", line.getUnformattedText());
        assertNull(ChatStack.baseText(new Hostile()));
    }

    // ---- restoreCounter -------------------------------------------------------------------------

    @Test
    public void restoreCounterReturnsToTheExactPriorState() {
        ChatComponentText line = hypixelLine("A");
        ChatStack.setCounter(line, 2);
        Counter c2 = ChatStack.counterOf(line);
        String text2 = line.getFormattedText();
        List<IChatComponent> snapshot = new ArrayList<IChatComponent>(line.getSiblings());

        Counter previous = ChatStack.setCounter(line, 3);
        assertSame(c2, previous);
        ChatStack.restoreCounter(line, previous);

        assertEquals(text2, line.getFormattedText());
        assertEquals(snapshot, line.getSiblings());
        assertSame(c2, ChatStack.counterOf(line));
    }

    @Test
    public void restoreCounterWithNoPreviousRemovesTheCounterEntirely() {
        ChatComponentText line = hypixelLine("A");
        String before = line.getFormattedText();
        Counter previous = ChatStack.setCounter(line, 2);
        assertNull(previous);
        ChatStack.restoreCounter(line, null);
        assertEquals(before, line.getFormattedText());
        assertNull(ChatStack.counterOf(line));
        assertEquals(2, line.getSiblings().size());
    }

    // ---- failure inside setCounter (foreign sibling lists) --------------------------------------

    /** A ChatComponentText whose sibling list is replaced, as a foreign subclass might. */
    private static final class Foreign extends ChatComponentText {
        Foreign(List<IChatComponent> siblings) {
            super("");
            this.siblings = siblings;
        }
    }

    private static final class ThrowsOnAdd extends ArrayList<IChatComponent> {
        @Override public boolean add(IChatComponent c) { throw new UnsupportedOperationException("add"); }
        void seed(IChatComponent c) { super.add(c); }
    }

    /** Removes, then throws: the corruption shape a detach-to-read helper cannot survive. */
    private static final class MutatesThenThrowsOnRemove extends ArrayList<IChatComponent> {
        @Override public IChatComponent remove(int i) {
            super.remove(i);
            throw new UnsupportedOperationException("remove");
        }
    }

    private static final class ThrowsOnFirstRemove extends ArrayList<IChatComponent> {
        boolean armed = true;
        @Override public IChatComponent remove(int i) {
            if (armed) { armed = false; throw new UnsupportedOperationException("remove"); }
            return super.remove(i);
        }
    }

    @Test
    public void addFailureLeavesTheComponentUntouched() {
        ThrowsOnAdd list = new ThrowsOnAdd();
        Foreign line = new Foreign(list);
        Counter old = new Counter(2);
        list.seed(old);
        String before = line.getFormattedText();
        try {
            ChatStack.setCounter(line, 3);
            fail("expected the add to throw");
        } catch (UnsupportedOperationException expected) { }
        assertEquals(before, line.getFormattedText());
        assertEquals(1, line.getSiblings().size());
        assertSame(old, ChatStack.counterOf(line));
    }

    @Test
    public void removeFailureUndoesTheFreshCounter() {
        ThrowsOnFirstRemove list = new ThrowsOnFirstRemove();
        Foreign line = new Foreign(list);
        Counter old = new Counter(2);
        line.appendSibling(old);
        String before = line.getFormattedText();
        try {
            ChatStack.setCounter(line, 3);
            fail("expected the remove to throw");
        } catch (UnsupportedOperationException expected) { }
        assertEquals(before, line.getFormattedText());
        assertEquals(1, line.getSiblings().size());
        assertSame(old, ChatStack.counterOf(line));
    }

    @Test
    public void readsNeverMutateEvenOnAMutatingThenThrowingList() {
        MutatesThenThrowsOnRemove list = new MutatesThenThrowsOnRemove();
        Foreign line = new Foreign(list);
        line.appendSibling(new ChatComponentText("A"));
        Counter old = new Counter(2);
        line.appendSibling(old);
        String before = line.getFormattedText();

        assertEquals(new Foreign(new ArrayList<IChatComponent>()).appendSibling(new ChatComponentText("A")).getFormattedText(),
                ChatStack.baseText(line));
        IChatComponent copy = ChatStack.withoutCounter(line);
        assertEquals("A", copy.getUnformattedText());
        assertNull(ChatStack.counterOf(copy));

        assertEquals("original untouched", before, line.getFormattedText());
        assertSame(old, ChatStack.counterOf(line));
        assertEquals(2, line.getSiblings().size());
    }

    // ---- withoutCounter -------------------------------------------------------------------------

    @Test
    public void withoutCounterStripsAnyStackedLineAndLeavesTheOriginalIntact() {
        ChatComponentText a = hypixelLine("A");
        ChatStack.setCounter(a, 2);
        // The chain moves on: B is captured; A is no longer the target but is still on screen.
        ChatComponentText b = hypixelLine("B");
        assertNull(ChatStack.counterOf(b));

        IChatComponent copy = ChatStack.withoutCounter(a);
        assertNotSame(a, copy);
        assertEquals("A", copy.getUnformattedText());
        assertNull(ChatStack.counterOf(copy));
        assertEquals("original untouched", "A (x2)", a.getUnformattedText());
        assertNotNull(ChatStack.counterOf(a));

        assertSame("no counter: same object", b, ChatStack.withoutCounter(b));
    }

    @Test
    public void counterCopiesStayCounters() {
        Counter c = new Counter(4);
        IChatComponent copy = c.createCopy();
        assertTrue(copy instanceof Counter);
        assertEquals(4, ((Counter) copy).n);
        assertEquals(EnumChatFormatting.GRAY, copy.getChatStyle().getColor());
    }
}
