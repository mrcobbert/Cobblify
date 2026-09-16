package com.bedwarsqol.feature;

import net.minecraft.util.ChatComponentStyle;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.List;

/**
 * Component-level helpers for Stack Spam Messages: the gray {@code (xN)} counter lives as a
 * {@link Counter} sibling appended to the <i>original</i> received component, never to a copy, so the
 * stored line keeps its identity and {@link ChatNameTags}' spliced holders keep back-patching it.
 * {@link ChatStackCore} holds the platform-neutral rules; {@code GuiNewChatMixin} wires both.
 *
 * <p>Only vanilla-style components ({@link ChatComponentStyle}) are ever mutated: their sibling list
 * is the vanilla {@code ArrayList}, so an add followed by a remove cannot fail between the two steps.
 * Every read here is guarded — a foreign {@link IChatComponent} implementation that throws while
 * flattening simply makes the line unstackable.
 */
@SuppressWarnings("unchecked")
public final class ChatStack {

    private ChatStack() {}

    /** The {@code (xN)} sibling. A marker type, so any stored line can be recognised as stacked later. */
    public static final class Counter extends ChatComponentText {
        public final int n;

        public Counter(int n) {
            super(" (x" + n + ")");
            this.n = n;
            getChatStyle().setColor(EnumChatFormatting.GRAY);
        }

        @Override
        public Counter createCopy() {
            Counter copy = new Counter(n);
            copy.setChatStyle(getChatStyle().createShallowCopy());
            for (IChatComponent s : (List<IChatComponent>) getSiblings()) copy.appendSibling(s.createCopy());
            return copy;
        }
    }

    /** Whether the stacker may mutate {@code c}: a vanilla-style component with the vanilla sibling list. */
    public static boolean stackable(IChatComponent c) {
        return c instanceof ChatComponentStyle;
    }

    /** {@code c.getFormattedText()}, or null when the component throws (foreign implementations). */
    public static String safeFormatted(IChatComponent c) {
        if (c == null) return null;
        try {
            return c.getFormattedText();
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code c.getUnformattedText()} — what vanilla logs — or null when the component throws. */
    public static String safeUnformatted(IChatComponent c) {
        if (c == null) return null;
        try {
            return c.getUnformattedText();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The last {@link Counter} among {@code c}'s direct siblings, or null. */
    public static Counter counterOf(IChatComponent c) {
        if (c == null) return null;
        List<IChatComponent> sibs = (List<IChatComponent>) c.getSiblings();
        for (int i = sibs.size() - 1; i >= 0; i--) {
            if (sibs.get(i) instanceof Counter) return (Counter) sibs.get(i);
        }
        return null;
    }

    /**
     * The formatted text of {@code c} with its counter detached — the comparison key a repeat must
     * match. Read live, so holders that {@link ChatNameTags} has patched since the line printed count.
     * Null when the component throws.
     */
    public static String baseText(IChatComponent c) {
        Counter counter = counterOf(c);
        if (counter == null) return safeFormatted(c);
        List<IChatComponent> sibs = (List<IChatComponent>) c.getSiblings();
        int at = indexOfIdentity(sibs, counter);
        sibs.remove(at);
        try {
            return safeFormatted(c);
        } finally {
            sibs.add(at, counter);
        }
    }

    /**
     * Append a fresh counter for {@code n}, then detach the previous one; returns the detached one
     * (or null). Add-then-remove on the vanilla list cannot fail between the two steps; should a
     * foreign sibling list throw part-way, the partial step is undone best-effort before rethrowing.
     */
    public static Counter setCounter(IChatComponent c, int n) {
        Counter old = counterOf(c);
        Counter fresh = new Counter(n);
        c.appendSibling(fresh); // vanilla: parent-style link, then siblings.add; nothing changes if add throws
        if (old != null) {
            List<IChatComponent> sibs = (List<IChatComponent>) c.getSiblings();
            try {
                sibs.remove(indexOfIdentity(sibs, old));
            } catch (Throwable t) {
                try {
                    int i = indexOfIdentity(sibs, fresh);
                    if (i >= 0) sibs.remove(i);
                } catch (Throwable ignored) { }
                throw t;
            }
        }
        return old;
    }

    /** Undo {@link #setCounter}: drop the current counter and re-attach {@code previous} if any. */
    public static void restoreCounter(IChatComponent c, Counter previous) {
        Counter current = counterOf(c);
        List<IChatComponent> sibs = (List<IChatComponent>) c.getSiblings();
        if (current != null) {
            int i = indexOfIdentity(sibs, current);
            if (i >= 0) sibs.remove(i);
        }
        if (previous != null) c.appendSibling(previous);
    }

    /** {@code c} itself when it carries no counter, else a copy without it — what Copy Chat copies. */
    public static IChatComponent withoutCounter(IChatComponent c) {
        Counter counter = counterOf(c);
        if (counter == null) return c;
        List<IChatComponent> sibs = (List<IChatComponent>) c.getSiblings();
        int at = indexOfIdentity(sibs, counter);
        sibs.remove(at);
        try {
            return c.createCopy();
        } finally {
            sibs.add(at, counter);
        }
    }

    /** {@code ChatComponentText.equals} compares by value; the counter must be found by identity. */
    private static int indexOfIdentity(List<IChatComponent> sibs, IChatComponent target) {
        for (int i = 0; i < sibs.size(); i++) if (sibs.get(i) == target) return i;
        return -1;
    }
}
