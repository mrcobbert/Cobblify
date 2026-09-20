package com.bedwarsqol.feature;

import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Feeds in-game chat to {@link GameRoster} so the launcher overlay learns who disconnected,
 * reconnected or was eliminated. Only the active game is listened to: the pregame queue's
 * join/quit broadcasts carry anonymised names and must never reach the roster.
 *
 * <p>Subscribed at the highest priority and to cancelled events too, so a chat-cleaner mod that
 * hides death lines cannot hide them from the roster. Read-only: the event is never modified.
 */
public final class GameRosterChat {

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onChat(ClientChatReceivedEvent event) {
        if (event == null || event.message == null) return;
        try {
            if (!HypixelContext.isOnHypixel() || !HypixelContext.isInActiveBedwarsGame()) return;
            String msg = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
            if (msg == null) return;
            GameRoster.INSTANCE.onChat(GameSessionTracker.currentSessionId(), System.currentTimeMillis(), msg);
        } catch (Throwable ignored) {
            // never propagate into the chat pipeline
        }
    }
}
