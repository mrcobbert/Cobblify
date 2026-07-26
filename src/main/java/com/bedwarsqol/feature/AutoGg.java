package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Auto GG: says "gg" once in chat each time a BedWars game ends.
 *
 * <p>Routed through {@link OutgoingChat}. Validity is session/server/feature based — party membership
 * changes do not cancel a pending AutoGG.
 */
public final class AutoGg {

    private static final String MESSAGE = "gg";
    private static final int TICK_INTERVAL = 10;
    private static final int SEND_DELAY_SLOTS = 2;
    private static final int REARM_GRACE_SLOTS = 3;

    private int ticks;
    private boolean playedGame;
    private boolean fired;
    private int notActiveSlots;
    private int pendingSend;
    private int pendingSessionId;

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        rearm(true);
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event == null || event.message == null) return;
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.autoGg) return;
        if (fired || !playedGame || !HypixelContext.isOnHypixel()) return;
        String msg = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (msg == null || !msg.trim().startsWith("1st Killer")) return;
        fired = true;
        pendingSend = SEND_DELAY_SLOTS;
        pendingSessionId = GameSessionTracker.currentSessionId();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++ticks < TICK_INTERVAL) return;
        ticks = 0;

        if (pendingSend > 0) {
            if (!stillValidPending()) {
                pendingSend = 0;
            } else if (--pendingSend == 0) {
                ClientSettings cfg = BedwarsQol.config;
                if (cfg != null && cfg.autoGg && stillValidPending()) {
                    OutgoingChat.get().submitAutoGg(MESSAGE);
                }
            }
        }

        boolean inActive = HypixelContext.isOnHypixel() && HypixelContext.isInActiveBedwarsGame();
        if (inActive) {
            playedGame = true;
            notActiveSlots = 0;
        } else if (++notActiveSlots >= REARM_GRACE_SLOTS) {
            rearm(false);
        }
    }

    private boolean stillValidPending() {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.autoGg) return false;
        if (!HypixelContext.isOnHypixel()) return false;
        return pendingSessionId == GameSessionTracker.currentSessionId();
    }

    private void rearm(boolean clearQueued) {
        fired = false;
        playedGame = false;
        notActiveSlots = 0;
        if (clearQueued) {
            pendingSend = 0;
            OutgoingChat.get().cancelKind(OutgoingChatKind.AUTOGG);
        }
    }
}
