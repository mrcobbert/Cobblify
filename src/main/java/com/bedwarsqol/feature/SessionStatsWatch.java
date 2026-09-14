package com.bedwarsqol.feature;

import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Feeds {@link SessionStats} from the client: chat lines and titles are classified by
 * {@link SessionChatLine} against the local player's name, the tick drives the session clock and the
 * leave-Hypixel reset, and a new {@link GameSessionTracker} id inside an active game starts a new
 * game block. Observe-only: no chat event is cancelled or edited. Always running (the toggle only
 * hides the HUD) so a session that started before the box was enabled is not lost.
 */
public final class SessionStatsWatch {

    private static final int TICK_INTERVAL = 10;
    private static final SessionStats CORE = new SessionStats();

    private int ticks;
    private int lastSessionId = Integer.MIN_VALUE;

    /** The live tally; read on the client thread by the HUD and the command. */
    public static SessionStats core() {
        return CORE;
    }

    /** Manual reset (settings row / command). */
    public static void reset() {
        if (CORE.reset()) DiagLog.log("session: unresolved game end sid=" + CORE.gameSessionId() + " (reset)");
    }

    /** Called from the GuiIngame title inject with the raw (possibly formatted) title text. */
    public static void onTitle(String title) {
        if (title == null || !HypixelContext.isOnHypixel()) return;
        CORE.onEvent(SessionChatLine.parseTitle(EnumChatFormatting.getTextWithoutFormattingCodes(title)));
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event == null || event.message == null) return;
        if (ModChat.isMarked(event.message)) return;
        if (!HypixelContext.isOnHypixel()) return;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        CORE.onEvent(SessionChatLine.parse(plain, selfName()));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++ticks < TICK_INTERVAL) return;
        ticks = 0;

        Minecraft mc = Minecraft.getMinecraft();
        boolean onHypixel = mc != null && mc.theWorld != null && HypixelContext.isOnHypixel();
        if (CORE.onTick(onHypixel, System.currentTimeMillis())) {
            DiagLog.log("session: unresolved game end sid=" + CORE.gameSessionId() + " (left Hypixel)");
        }
        if (!onHypixel) {
            lastSessionId = Integer.MIN_VALUE;
            return;
        }
        int sid = GameSessionTracker.currentSessionId();
        if (sid != lastSessionId && HypixelContext.isInActiveBedwarsGame()) {
            lastSessionId = sid;
            if (CORE.onGameStart(sid)) DiagLog.log("session: unresolved game end sid=" + sid + " (next game)");
        }
    }

    private static String selfName() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null || mc.thePlayer == null ? null : mc.thePlayer.getName();
    }

    /** Whether the box is currently allowed to draw (enabled, on Hypixel, In Game Only honoured). */
    public static boolean visible(ClientSettings cfg, boolean example) {
        if (cfg == null || !cfg.sessionStatsEnabled) return false;
        if (example) return true;
        if (!HypixelContext.isOnHypixel()) return false;
        return !cfg.sessionStatsInGameOnly || HypixelContext.isInActiveBedwarsGame();
    }
}
