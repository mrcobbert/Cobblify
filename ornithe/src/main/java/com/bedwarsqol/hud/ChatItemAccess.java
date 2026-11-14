package com.bedwarsqol.hud;

/**
 * Implemented on vanilla's {@code GuiNewChat} by {@code mixin/GuiNewChatMixin}: what the chat item's box
 * needs from the chat that vanilla keeps private.
 */
public interface ChatItemAccess {

    /** Wrapped rows the chat holds ({@code drawnChatLines.size()}). */
    int bedwarsqol$drawnLineCount();

    /** Rows scrolled up from the newest ({@code scrollPos}). */
    int bedwarsqol$scrollPos();
}
