package com.bedwarsqol.feature;

import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.api.event.ChatEvent;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;

/**
 * Feeds {@link SessionStats} from the client: chat lines and titles are classified by
 * {@link SessionChatLine} against the local player's name and current nick, the tick drives the
 * session clock and the leave-Hypixel reset, and a new {@link GameSessionTracker} id inside an
 * active game starts a new game block. In the Bed Wars lobby the "Bed Wars Profile" hologram's {@code Current Winstreak}
 * armour stand seeds the streak. Observe-only: no chat event is cancelled or edited, nothing is
 * sent. Always running (the toggle only hides the HUD) so a session that started before the box
 * was enabled is not lost.
 *
 * <p>Weave hands a cancelled {@link ChatEvent.Received} to every subscriber and never posts
 * action-bar messages, so the chat hook needs no priority or filter. Weave does not catch a
 * subscriber's exception, though, so both entry points catch their own errors: the tally never
 * breaks chat or titles.
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
        if (title == null) return;
        try {
            if (!acceptingEvents()) return;
            CORE.onEvent(SessionChatLine.parseTitle(EnumChatFormatting.getTextWithoutFormattingCodes(title)));
        } catch (RuntimeException e) {
            DiagLog.log("session: title handler failed " + e);
        }
    }

    @SubscribeEvent
    public void onChat(ChatEvent.Received event) {
        if (event == null || event.getMessage() == null) return;
        try {
            if (ModChat.isMarked(event.getMessage())) return;
            onLine(EnumChatFormatting.getTextWithoutFormattingCodes(event.getMessage().getUnformattedText()));
        } catch (RuntimeException e) {
            DiagLog.log("session: chat handler failed " + e);
        }
    }

    /** One colour-stripped chat line, from either platform's chat hook. */
    private static void onLine(String plain) {
        if (plain == null || !HypixelContext.isOnHypixel()) return;
        CORE.onNickChange(SessionChatLine.parseNickChange(plain));
        if (!acceptingEvents()) return;
        String self = selfName();
        String nick = nickName();
        SessionChatLine.Kind kind = SessionChatLine.parse(plain, self, nick);
        if (plain.indexOf(':') < 0 && plain.contains(" - ")) {
            // End-of-game rows (winner row, killer rows) keep their real shape in the log, without our names.
            DiagLog.log("session: row \"" + SessionChatLine.maskSelf(plain, self, nick) + "\" -> " + kind);
        }
        CORE.onEvent(kind);
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

    /** Where the last nick came from (tab, chat or none); logged on change, never with the name. */
    private static String nickSource = "none";

    /**
     * The name Hypixel shows for the player while they are nicked, or null. The client keeps the
     * account name for the whole connection, but Hypixel renames the player's own tab-list row, which
     * keeps their account UUID (Sk1er NickHider, Raven and proxhy read it the same way). The last
     * "You are now nicked as X!" line is the fallback.
     */
    private static String nickName() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return null;
        String real = mc.thePlayer.getName();
        NetHandlerPlayClient net = mc.getNetHandler();
        NetworkPlayerInfo own = net == null ? null : net.getPlayerInfo(mc.thePlayer.getUniqueID());
        String shown = own == null || own.getGameProfile() == null ? null : own.getGameProfile().getName();
        String chat = CORE.chatNick();
        String nick = null;
        String source = "none";
        if (shown != null && !shown.equalsIgnoreCase(real)) {
            nick = shown;
            source = "tab";
        } else if (chat != null && !chat.equalsIgnoreCase(real)) {
            nick = chat;
            source = "chat";
        }
        if (!source.equals(nickSource)) {
            nickSource = source;
            DiagLog.log("session: nick source=" + source);
        }
        return nick;
    }

    /** Whether the box is currently allowed to draw (enabled, on Hypixel, In Game Only honoured). */
    public static boolean visible(ClientSettings cfg, boolean example) {
        if (cfg == null || !cfg.sessionStatsEnabled) return false;
        if (example) return true;
        if (!HypixelContext.isOnHypixel()) return false;
        return !cfg.sessionStatsInGameOnly || HypixelContext.isInActiveBedwarsGame();
    }
}
