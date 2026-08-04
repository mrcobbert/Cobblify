package com.bedwarsqol.feature;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

import java.util.List;

/**
 * Inserts an empty, mutable holder component immediately before a named player in a chat line's
 * component tree, so {@link ChatNameTags} can back-patch an FKDR bracket into it once the stats land.
 *
 * <p><b>Where the holder goes.</b> Ahead of the rank tag the name wears, so a line reads
 * {@code "[1.25] [MVP++] Dewier"} — the order every chat line puts the FKDR in — rather than
 * {@code "[MVP++] [1.25] Dewier"}. An unranked player gets the holder immediately before the name.
 *
 * <p><b>Why it is its own class.</b> A {@code /party list} roster line carries several named players
 * and needs one holder in front of each, so placement is driven per-name rather than per-line.
 * Splitting it out also keeps the pure name-location rules ({@link #locateName}, {@link #rankStart},
 * {@link #blankBrackets}) unit-testable without Minecraft, and {@link ChatNameTags} differs between
 * the Forge and Lunar trees while this does not.
 */
public final class ChatSplice {

    private ChatSplice() {}

    // ---- name-slot splice (called by ChatNameTags at receive) -----------------------------------

    /**
     * Insert {@code holder} (an empty, mutable component {@link ChatNameTags} back-patches) as a
     * sibling immediately before {@code sender}'s rank tag in {@code root}, falling back to the name
     * itself for an unranked player, splitting the covering leaf when the insert point sits mid-leaf.
     * Returns false (FKDR untouched) when the name can't be located in a sibling — {@link ChatNameTags}
     * has already hoisted any root-own text into a sibling by the time it calls us, so the name is
     * normally reachable. Never throws.
     */
    public static boolean spliceBeforeRankedName(IChatComponent root, String sender,
                                                 ChatComponentText holder) {
        try {
            if (root instanceof ChatComponentTranslation) return false; // vanilla <Name> path; not our shapes
            String flat = root.getUnformattedText();
            int target = locateName(flat, sender);
            if (target < 0) return false;
            target = rankStart(flat, target);
            int rootOwn = safeOwnLen(root);
            if (target < rootOwn) return false; // name still in root own text (hoist failed) — degrade
            int[] cursor = {rootOwn};
            return insertBeforeName(root, target, cursor, holder);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Recursively find the leaf covering flattened offset {@code target}; splice {@code holder} before it. */
    private static boolean insertBeforeName(IChatComponent parent, int target, int[] cursor,
                                            ChatComponentText holder) {
        List<IChatComponent> sibs = (List<IChatComponent>) parent.getSiblings();
        for (int i = 0; i < sibs.size(); i++) {
            IChatComponent child = sibs.get(i);
            if (child instanceof ChatComponentTranslation) return false; // don't splice inside translations
            int ownStart = cursor[0];
            int ownEnd = ownStart + safeOwnLen(child);
            if (target >= ownStart && target < ownEnd && child instanceof ChatComponentText) {
                insertSplitting(sibs, i, (ChatComponentText) child, target - ownStart, holder);
                return true;
            }
            cursor[0] = ownEnd;
            if (insertBeforeName(child, target, cursor, holder)) return true;
        }
        return false;
    }

    /** Replace {@code sibs[i]} (a leaf) with {@code before / holder / after}, keeping its style + children. */
    private static void insertSplitting(List<IChatComponent> sibs, int i, ChatComponentText leaf, int k,
                                        ChatComponentText holder) {
        // The common Hypixel shape gives the sender its own styled leaf. Keep that exact object so
        // Lunar's copy-on-add path cannot lose styling while converting a replacement component.
        if (k == 0) {
            sibs.add(i, holder);
            return;
        }
        String text = leaf.getUnformattedTextForChat();
        ChatStyle style = leaf.getChatStyle();
        ChatComponentText before = new ChatComponentText(text.substring(0, k));
        ChatComponentText after = new ChatComponentText(text.substring(k)); // carries the name; keep its hover
        if (style != null) {
            before.setChatStyle(style.createDeepCopy());
            after.setChatStyle(style.createDeepCopy());
        }
        for (IChatComponent s : (List<IChatComponent>) leaf.getSiblings()) after.appendSibling(s);
        sibs.set(i, before);
        sibs.add(i + 1, holder);
        sibs.add(i + 2, after);
    }

    // ---- pure name location (unit-tested) -------------------------------------------------------

    /**
     * The start index of {@code sender} in {@code flat} (the line's unformatted text), or -1. Bracket
     * spans ({@code [rank]}, the FKDR {@code [x.xx]}) are masked so a name is never matched inside one,
     * and the match must be word-bounded so {@code "Bob"} doesn't hit inside {@code "Bobby"}. The first
     * such occurrence is the sender slot for every Hypixel shape {@link ChatSender} recognises.
     */
    static int locateName(String flat, String sender) {
        if (flat == null || sender == null || sender.isEmpty()) return -1;
        String masked = blankBrackets(flat); // index-stable: [..] spans become spaces
        int from = 0;
        while (from <= masked.length() - sender.length()) {
            int idx = masked.indexOf(sender, from);
            if (idx < 0) return -1;
            if (wordBounded(masked, idx, sender.length())) return idx;
            from = idx + 1;
        }
        return -1;
    }

    /**
     * Where a tag belongs for a name that wears a rank: the start of the {@code [..]} span immediately
     * before {@code nameStart} (spaces may sit between), else {@code nameStart} itself. On a roster line
     * a member's own rank is the only bracket that can be adjacent — the previous member's name and
     * status dot break the run for everyone else.
     */
    static int rankStart(String flat, int nameStart) {
        int i = nameStart - 1;
        while (i >= 0 && flat.charAt(i) == ' ') i--;
        if (i < 0 || flat.charAt(i) != ']') return nameStart;
        int open = flat.lastIndexOf('[', i);
        return open < 0 ? nameStart : open;
    }

    private static boolean wordBounded(String s, int start, int len) {
        int end = start + len;
        boolean leftOk = start == 0 || !isNameChar(s.charAt(start - 1));
        boolean rightOk = end >= s.length() || !isNameChar(s.charAt(end));
        return leftOk && rightOk;
    }

    private static boolean isNameChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    /** Replace every {@code [..]} span (brackets included) with spaces, preserving length/indices. */
    static String blankBrackets(String s) {
        char[] c = s.toCharArray();
        boolean in = false;
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '[') { in = true; c[i] = ' '; }
            else if (c[i] == ']') { c[i] = ' '; in = false; }
            else if (in) c[i] = ' ';
        }
        return new String(c);
    }

    /** Own (non-child) text length of a component, treating null/foreign as 0. */
    private static int safeOwnLen(IChatComponent c) {
        try {
            String own = c.getUnformattedTextForChat();
            return own == null ? 0 : own.length();
        } catch (Throwable t) {
            return 0;
        }
    }
}
