package com.bedwarsqol.feature;

import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

/**
 * Carries an alert's primary tag reason on its hover component without changing rendered text.
 * Forge's Copy Chat handler reads this metadata when the full alert line is right-clicked.
 */
public final class AlertCopyReason {

    private static final String PREFIX = "bwqol\u0000alert-reason:";

    private AlertCopyReason() {}

    public static <T extends IChatComponent> T attach(T hoverText, String reason) {
        if (hoverText == null || reason == null || reason.trim().isEmpty()) return hoverText;
        hoverText.getChatStyle().setInsertion(PREFIX + reason.trim());
        return hoverText;
    }

    public static String fromAlert(IChatComponent message) {
        if (!ModChat.isMarked(message)) return null;
        ChatStyle style = message.getChatStyle();
        if (style == null) return null;
        HoverEvent hover = style.getChatHoverEvent();
        if (hover == null || hover.getAction() != HoverEvent.Action.SHOW_TEXT || hover.getValue() == null) {
            return null;
        }
        String insertion = hover.getValue().getChatStyle().getInsertion();
        if (insertion == null || !insertion.startsWith(PREFIX)) return null;
        String reason = insertion.substring(PREFIX.length()).trim();
        return reason.isEmpty() ? null : reason;
    }

    public static String appendToPlain(IChatComponent message, String plain) {
        if (plain == null) return null;
        String reason = fromAlert(message);
        return reason == null ? plain : plain + " \u2014 Reason: " + reason;
    }
}
