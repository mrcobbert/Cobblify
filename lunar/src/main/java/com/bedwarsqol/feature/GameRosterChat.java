package com.bedwarsqol.feature;

import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.api.event.ChatEvent;
import net.weavemc.api.event.SubscribeEvent;

/**
 * Feeds in-game chat to {@link GameRoster} so the launcher overlay learns who disconnected,
 * reconnected or was eliminated. Only the active game is listened to: the pregame queue's
 * join/quit broadcasts carry anonymised names and must never reach the roster.
 *
 * <p>Read-only: the event is never modified. Weave has no subscriber priority or cancelled-event
 * opt-in, so a chat-cleaner mod that swallows a line first may hide it here; the roster's 30 s
 * MISSING fallback covers that.
 */
public final class GameRosterChat {

    @SubscribeEvent
    public void onChat(ChatEvent.Received event) {
        if (event == null || event.getMessage() == null) return;
        try {
            if (!HypixelContext.isOnHypixel() || !HypixelContext.isInActiveBedwarsGame()) return;
            String msg = EnumChatFormatting.getTextWithoutFormattingCodes(event.getMessage().getUnformattedText());
            if (msg == null) return;
            GameRoster.INSTANCE.onChat(GameSessionTracker.currentSessionId(), System.currentTimeMillis(), msg);
        } catch (Throwable ignored) {
            // never propagate into the chat pipeline
        }
    }
}
