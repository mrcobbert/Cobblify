package com.bedwarsqol.feature;

import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.api.event.ChatEvent;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;

/**
 * Feeds {@link SessionStats} from the client: chat lines and titles are classified by
 * {@link SessionChatLine} against the local player's name, the tick drives the session clock and the
 * leave-Hypixel reset, and a new {@link GameSessionTracker} id inside an active game starts a new
 * game block. In the Bed Wars lobby the "Bed Wars Profile" hologram's {@code Current Winstreak}
 * armour stand seeds the streak. Observe-only: no chat event is cancelled or edited, nothing is
 * sent. Always running (the toggle only hides the HUD) so a session that started before the box
 * was enabled is not lost.
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

    /**
     * Only BedWars lines count: the sidebar must say Bed Wars (with HypixelContext's short grace across
     * sidebar rebuilds), or the current world session must be the one whose game block is open — the
     * end-of-game rows and title arrive on the same game server, so the latch keeps them even if the
     * sidebar has already changed. A SkyWars kill or a Duels VICTORY! is never counted.
     */
    private static boolean acceptingEvents() {
        if (!HypixelContext.isOnHypixel()) return false;
        return HypixelContext.isInBedwars() || GameSessionTracker.currentSessionId() == CORE.gameSessionId();
    }

    /** Called from the GuiIngame title inject with the raw (possibly formatted) title text. */
    public static void onTitle(String title) {
        if (title == null || !acceptingEvents()) return;
        CORE.onEvent(SessionChatLine.parseTitle(EnumChatFormatting.getTextWithoutFormattingCodes(title)));
    }

    @SubscribeEvent
    public void onChat(ChatEvent.Received event) {
        if (event == null || event.getMessage() == null) return;
        if (ModChat.isMarked(event.getMessage())) return;
        if (!acceptingEvents()) return;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(event.getMessage().getUnformattedText());
        CORE.onEvent(SessionChatLine.parse(plain, selfName()));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
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
        } else if (HypixelContext.isInBedwars() && !HypixelContext.isInActiveBedwarsGame()) {
            // Hub or pregame queue: the queue has no such stand and costs one pass over a small list.
            int seed = lobbyWinstreak(mc);
            if (seed >= 0) CORE.seedWinstreak(seed);
        }
    }

    /**
     * The {@code Current Winstreak: N} row of the lobby stats hologram (invisible armour stands with
     * custom names, the same shape {@code GeneratorTracker} reads in-game), or -1 when absent.
     */
    private static int lobbyWinstreak(Minecraft mc) {
        if (mc.theWorld == null) return -1;
        for (Object o : mc.theWorld.loadedEntityList) {
            if (!(o instanceof EntityArmorStand)) continue;
            String raw = ((Entity) o).getCustomNameTag();
            if (raw == null || raw.isEmpty()) continue;
            int v = SessionChatLine.parseWinstreakHologram(EnumChatFormatting.getTextWithoutFormattingCodes(raw));
            if (v >= 0) return v;
        }
        return -1;
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
