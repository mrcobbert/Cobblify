package com.bedwarsqol.feature;

import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.weavemc.api.event.ChatEvent;
import net.weavemc.api.event.SubscribeEvent;

import java.util.List;

/**
 * Always-on chat capture for the Cobblify lobby dashboard. Independent of every feature toggle (the
 * launcher needs the roster whether or not Party Join Alert / queue alerts are on), so it does its own
 * minimal, side-effect-free capture rather than piggy-backing on the toggle-gated alert handlers:
 *
 * <ul>
 *   <li>a {@code /party list} reply → the retained "your party" roster;</li>
 *   <li>a typed line in the pregame queue → that queue's set of real, named players (the queue tab list
 *       is obfuscated, so a typed line is the only reliable name there).</li>
 * </ul>
 *
 * Both feed {@link LobbyChatState}, which {@code LobbySnapshot} reads on the tick. Skips the mod's own
 * output ({@link ModChat}) so a stats card or alert is never mistaken for a player line.
 */
public final class LobbyChatWatch {

    @SubscribeEvent
    public void onChat(ChatEvent.Received event) {
        if (event == null || event.getMessage() == null) return;
        if (ModChat.isMarked(event.getMessage())) return;
        if (!HypixelContext.isOnHypixel()) return;

        List<String> roster = ChatSender.rosterMembers(event.getMessage());
        if (!roster.isEmpty()) {
            LobbyChatState.publishParty(roster);
            return;
        }
        if (!HypixelContext.isInBedwarsQueue()) return;
        String sender = ChatSender.typedChatName(event.getMessage());
        if (sender == null || isSelf(sender)) return;
        LobbyChatState.recordQueueTyper(GameSessionTracker.currentSessionId(), sender);
    }

    private static boolean isSelf(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer != null && name.equalsIgnoreCase(mc.thePlayer.getName());
    }
}
