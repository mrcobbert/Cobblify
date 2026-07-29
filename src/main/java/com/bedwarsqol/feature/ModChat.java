package com.bedwarsqol.feature;

import net.minecraft.util.IChatComponent;

/**
 * Marks chat lines the mod prints itself so {@link ChatNameTags} leaves them alone. Without this the
 * mod's own output — most visibly the {@code /bw <player>} stats card, whose {@code Overall}/{@code
 * Modes} sub-headers read as lone usernames — would get a bogus FKDR/{@code [New]} bracket prepended.
 *
 * <p>The mark is a chat-style {@code insertion} sentinel: it is never rendered, is written only by us,
 * and never appears on genuine Hypixel chat. A content-agnostic stamp is necessary because Weave routes
 * <i>every</i> line through one {@code ChatEvent.Received} hook on {@code GuiNewChat.printChatMessage…}
 * — server broadcasts and the mod's own {@code addChatMessage} calls alike — so there is otherwise no
 * way to tell "a message we authored" from "a message another player sent". (Forge only fires
 * {@code ClientChatReceivedEvent} for server packets, so it never mis-tags local lines; the stamp is
 * harmless there and keeps both trees identical.)
 */
public final class ModChat {

    private ModChat() {}

    /** Distinctive, never-rendered sentinel written to the insertion slot of each mod-authored line. */
    private static final String MARK = "bwqol self";

    /** Stamp {@code c} as mod-authored and return it, for inline use inside an {@code addChatMessage(...)} call. */
    public static <T extends IChatComponent> T mark(T c) {
        // Best-effort: marking must never be able to break the message it decorates. If the insertion
        // slot is somehow unavailable we simply skip the stamp (the worst case is the mod's own line
        // getting an unwanted tag), rather than let an exception propagate out of a chat/command path.
        if (c != null) {
            try { c.getChatStyle().setInsertion(MARK); } catch (Throwable ignored) { }
        }
        return c;
    }

    /** True when {@code c} was stamped by {@link #mark} — i.e. the mod printed it, so it must not be annotated. */
    public static boolean isMarked(IChatComponent c) {
        if (c == null) return false;
        try { return MARK.equals(c.getChatStyle().getInsertion()); }
        catch (Throwable ignored) { return false; }
    }
}
