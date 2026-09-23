package com.bedwarsqol.feature;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

/**
 * Marks chat lines the mod prints itself so {@link ChatNameTags} leaves them alone. Without this the
 * mod's own output — most visibly the {@code /bw <player>} stats card, whose {@code Overall}/{@code
 * Modes} sub-headers read as lone usernames — would get a bogus FKDR/{@code [New]} bracket prepended.
 *
 * <p>A content-agnostic stamp is necessary because Weave routes <i>every</i> line through one
 * {@code ChatEvent.Received} hook on {@code GuiNewChat.printChatMessage…} — server broadcasts and the
 * mod's own {@code addChatMessage} calls alike — so there is otherwise no way to tell "a message we
 * authored" from "a message another player sent". (Forge only fires {@code ClientChatReceivedEvent}
 * for server packets, so it never mis-tags local lines; the stamp is harmless there and keeps both
 * trees identical.)
 *
 * <p>The stamp is an empty sibling whose style carries a sentinel {@code insertion}. It used to be the
 * insertion of the line's own style, which vanilla types into the chat box on shift-click. An empty
 * sibling has zero width, so the chat's click hit-test can never return it; and the sentinel is made
 * only of characters the chat field filters out, so even a chat GUI that did hit it would insert
 * nothing. Being part of the component tree, it survives {@code createCopy()} and JSON (its explicit
 * {@code obfuscated=false} keeps vanilla from serialising the style away, since
 * {@code ChatStyle.isEmpty()} ignores the insertion).
 */
public final class ModChat {

    private ModChat() {}

    /**
     * Sentinel insertion on the marker sibling. Every character fails
     * {@code ChatAllowedCharacters.isAllowedCharacter} (controls, {@code §}, DEL), so a text field
     * that is handed it inserts nothing.
     */
    private static final String MARK = "\u0000§\u0001\u007f\u0002";

    /** Stamp {@code c} as mod-authored and return it, for inline use inside an {@code addChatMessage(...)} call. */
    public static <T extends IChatComponent> T mark(T c) {
        // Best-effort: marking must never be able to break the message it decorates. If the sibling
        // cannot be added we simply skip the stamp (the worst case is the mod's own line getting an
        // unwanted tag), rather than let an exception propagate out of a chat/command path.
        if (c != null && !isMarked(c)) {
            try {
                ChatComponentText marker = new ChatComponentText("");
                marker.getChatStyle().setObfuscated(Boolean.FALSE).setInsertion(MARK);
                c.appendSibling(marker);
            } catch (Throwable ignored) { }
        }
        return c;
    }

    /**
     * True when {@code c}, or any component inside it, was stamped by {@link #mark} — i.e. the mod
     * printed it, so it must not be annotated. Walks the whole tree so a line another client wraps
     * (a timestamp prefix around the original) still reads as ours.
     */
    public static boolean isMarked(IChatComponent c) {
        if (c == null) return false;
        try {
            for (IChatComponent part : c) {
                if (isMarker(part)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isMarker(IChatComponent part) {
        if (part == null || !part.getUnformattedTextForChat().isEmpty()) return false;
        ChatStyle style = part.getChatStyle();
        return style != null && MARK.equals(style.getInsertion());
    }
}
