package com.bedwarsqol.feature;

import net.minecraft.util.ChatComponentStyle;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Component-level helpers for Stack Spam Messages: the gray {@code (xN)} counter lives as a
 * {@link Counter} sibling appended to the <i>original</i> received component, never to a copy, so the
 * stored line keeps its identity and {@link ChatNameTags}' spliced holders keep back-patching it.
 * {@link ChatStackCore} holds the platform-neutral rules; {@code GuiNewChatMixin} wires both.
 *
 * <p>Only vanilla-style components are ever mutated: a {@link ChatComponentStyle} whose sibling list
 * is exactly {@code java.util.ArrayList} (a subclass may swap the protected field), and the edit goes
 * straight to that list — never through an overridable {@code appendSibling} — so once the reads have
 * succeeded nothing between the add and the remove can throw. Reads never mutate: {@link #baseText}
 * strips the counter's formatted suffix and {@link #withoutCounter} edits a copy. A foreign
 * {@link IChatComponent} that throws while flattening simply makes the line unstackable.
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

    /**
     * Whether the stacker may mutate {@code c}: a vanilla-style component whose sibling list is the
     * vanilla {@code ArrayList} itself — not a subclass, not a wrapper — so list edits cannot throw
     * part-way. Never throws.
     */
    public static boolean stackable(IChatComponent c) {
        if (!(c instanceof ChatComponentStyle)) return false;
        try {
            List<IChatComponent> sibs = (List<IChatComponent>) c.getSiblings();
            return sibs != null && sibs.getClass() == ArrayList.class;
        } catch (Throwable t) {
            return false;
        }
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

    /** The last {@link Counter} among {@code c}'s direct siblings, or null; null too when reading throws. */
    public static Counter counterOf(IChatComponent c) {
        if (c == null) return null;
        try {
            return counterOf((List<IChatComponent>) c.getSiblings());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Counter counterOf(List<IChatComponent> sibs) {
        if (sibs == null) return null;
        for (int i = sibs.size() - 1; i >= 0; i--) {
            if (sibs.get(i) instanceof Counter) return (Counter) sibs.get(i);
        }
        return null;
    }

    /**
     * The formatted text of {@code c} with its counter detached — the comparison key a repeat must
     * match. Read live, so holders that {@link ChatNameTags} has patched since the line printed count.
     * Read-only: the counter is the last direct sibling, so its own formatted text is the exact
     * suffix of the whole; that suffix is stripped rather than the counter detached. Null when the
     * component throws or the suffix does not match (then the line is simply not stacked).
     */
    public static String baseText(IChatComponent c) {
        String full = safeFormatted(c);
        if (full == null) return null;
        Counter counter = counterOf(c);
        if (counter == null) return full;
        String tail = safeFormatted(counter);
        if (tail == null || !full.endsWith(tail)) return null;
        return full.substring(0, full.length() - tail.length());
    }

    /**
     * Append a fresh counter for {@code n}, then detach the previous one; returns the detached one
     * (or null). No overridable mutator runs: the two steps vanilla's {@code appendSibling} performs
     * (parent-style link, then add) are done directly on the {@code ArrayList} that
     * {@link #stackable} verified — a final class whose add/remove cannot fail part-way — so once
     * the reads at the top have succeeded the edit cannot throw. Refuses any other list.
     */
    public static Counter setCounter(IChatComponent c, int n) {
        ArrayList<IChatComponent> sibs = vanillaSiblings(c); // virtual read; nothing mutated if it throws
        ChatStyle parent = c.getChatStyle();                  // virtual read; likewise
        Counter old = counterOf(sibs);
        Counter fresh = new Counter(n);
        fresh.getChatStyle().setParentStyle(parent);
        sibs.add(fresh);
        if (old != null) sibs.remove(indexOfIdentity(sibs, old));
        return old;
    }

    /** Undo {@link #setCounter}: drop the current counter and re-attach {@code previous} if any. */
    public static void restoreCounter(IChatComponent c, Counter previous) {
        ArrayList<IChatComponent> sibs = vanillaSiblings(c);
        Counter current = counterOf(sibs);
        if (current != null) sibs.remove(indexOfIdentity(sibs, current));
        if (previous != null) sibs.add(previous); // its parent style is still linked to c
    }

    /** The component's sibling list, only if it is exactly the vanilla {@code ArrayList}. */
    private static ArrayList<IChatComponent> vanillaSiblings(IChatComponent c) {
        List<IChatComponent> sibs = (List<IChatComponent>) c.getSiblings();
        if (sibs == null || sibs.getClass() != ArrayList.class) {
            throw new IllegalStateException("not a vanilla sibling list: " + (sibs == null ? null : sibs.getClass()));
        }
        return (ArrayList<IChatComponent>) sibs;
    }

    /**
     * {@code c} itself when it carries no counter, else a copy without it — what Copy Chat copies.
     * The original is never touched: the counter is removed from the copy ({@link Counter#createCopy}
     * keeps the marker type, so it is still recognisable there). Never throws: a component that
     * cannot be read or copied is returned as-is, exactly as Copy Chat treated every line before.
     */
    public static IChatComponent withoutCounter(IChatComponent c) {
        if (counterOf(c) == null) return c;
        try {
            IChatComponent copy = c.createCopy();
            List<IChatComponent> sibs = (List<IChatComponent>) copy.getSiblings();
            for (int i = sibs.size() - 1; i >= 0; i--) {
                if (sibs.get(i) instanceof Counter) {
                    sibs.remove(i);
                    break;
                }
            }
            return copy;
        } catch (Throwable t) {
            return c;
        }
    }

    /** {@code ChatComponentText.equals} compares by value; the counter must be found by identity. */
    private static int indexOfIdentity(List<IChatComponent> sibs, IChatComponent target) {
        for (int i = 0; i < sibs.size(); i++) if (sibs.get(i) == target) return i;
        return -1;
    }
}
