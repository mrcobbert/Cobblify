package com.bedwarsqol.feature;

import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Feeds {@link SessionStats} from the client: chat lines and titles are classified by
 * {@link SessionChatLine} against the local player's name and current nick, and the tick drives
 * the session clock and the leave-Hypixel reset. Every chat line, title and tick first hands the
 * core a {@link SessionStats.Sample} of the client (world, active game, pregame queue, armour), so
 * the core can tell a new game from a rejoined one before it counts anything. In the Bed Wars lobby
 * the "Bed Wars Profile" hologram's {@code Current Winstreak} armour stand seeds the streak.
 * Observe-only: no chat event is cancelled or edited, nothing is sent. Always running (the toggle
 * only hides the HUD) so a session that started before the box was enabled is not lost.
 *
 * <p>The chat hook runs at the highest priority and also receives cancelled events, so a
 * chat-cleaner mod that hides a kill, death or bed line cannot hide it from the tally (the same
 * subscription {@code GameRosterChat} uses). Action-bar messages, which Forge sends through the
 * same event, are skipped. Both entry points catch their own errors: the tally never breaks chat
 * or titles.
 */
public final class SessionStatsWatch {

    private static final int TICK_INTERVAL = 10;
    private static final SessionStats CORE = new SessionStats();

    private int ticks;
    /** The world object last sampled and its serial; the serial changes whenever the world does. */
    private static Object sampledWorld;
    private static int worldSerial;

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
            if (!HypixelContext.isOnHypixel()) return;
            CORE.onTick(true, System.currentTimeMillis()); // on Hypixel now, before any state lands
            SessionStats.Sample sample = sample();
            logStart(CORE.onGameTick(sample), sample);
            if (!acceptingEvents()) return;
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(title);
            SessionChatLine.Kind kind = SessionChatLine.parseTitle(plain);
            if (kind != null) DiagLog.log("session: title " + plain.trim() + " -> " + kind);
            CORE.onEvent(sample, kind);
        } catch (RuntimeException e) {
            DiagLog.log("session: title handler failed " + e);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onChat(ClientChatReceivedEvent event) {
        if (event == null || event.message == null) return;
        if (event.type == 2) return; // action bar
        try {
            if (ModChat.isMarked(event.message)) return;
            onLine(EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText()));
        } catch (RuntimeException e) {
            DiagLog.log("session: chat handler failed " + e);
        }
    }

    /** One colour-stripped chat line, from either platform's chat hook. */
    private static void onLine(String plain) {
        if (plain == null || !HypixelContext.isOnHypixel()) return;
        // Mark the session as on Hypixel before any state lands, so leaving Hypixel clears it even if
        // no periodic tick ran in between.
        CORE.onTick(true, System.currentTimeMillis());
        CORE.onNickChange(SessionChatLine.parseNickChange(plain));
        SessionStats.Sample sample = sample();
        logStart(CORE.onGameTick(sample), sample);
        if (!acceptingEvents()) return;
        GameChatLine teamLine = GameChatLine.parse(plain);
        if (teamLine != null && teamLine.kind == GameChatLine.Kind.TEAM_ELIMINATED) {
            String own = CORE.ownTeam();
            boolean ours = CORE.onTeamEliminated(sample, teamLine.team);
            DiagLog.log("session: TEAM ELIMINATED " + teamLine.team + " own=" + (own == null ? "unknown" : own)
                    + (ours ? " -> own team, loss" : " -> other team"));
            return;
        }
        String self = selfName();
        String nick = nickName();
        SessionChatLine.Kind kind = SessionChatLine.parse(plain, self, nick);
        if (plain.indexOf(':') < 0 && plain.contains(" - ")) {
            // End-of-game rows (winner row, killer rows) keep their real shape in the log, without our names.
            DiagLog.log("session: row \"" + SessionChatLine.maskSelf(plain, self, nick) + "\" -> " + kind);
        }
        if (kind == SessionChatLine.Kind.ELIMINATED) DiagLog.log("session: You have been eliminated!");
        CORE.onEvent(sample, kind);
    }

    /**
     * The client now, for the core's game-entry check. The queue flag is only read outside an active
     * game (HypixelContext's queue test already requires that); armour and the team colour only
     * inside one, the team only while armoured, so a spectator team is never taken for the player's.
     */
    private static SessionStats.Sample sample() {
        Minecraft mc = Minecraft.getMinecraft();
        Object world = mc == null ? null : mc.theWorld;
        if (world != sampledWorld) {
            sampledWorld = world;
            worldSerial++;
        }
        boolean active = HypixelContext.isInActiveBedwarsGame();
        boolean armoured = active && wearingArmour(mc);
        return new SessionStats.Sample(GameSessionTracker.currentSessionId(), worldSerial, active,
                !active && HypixelContext.isInBedwarsQueue(), armoured, armoured ? ownTeamWord() : null);
    }

    private static boolean wearingArmour(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) return false;
        for (ItemStack piece : mc.thePlayer.inventory.armorInventory) {
            if (piece != null) return true;
        }
        return false;
    }

    private static void logStart(SessionStats.GameStart start, SessionStats.Sample s) {
        if (start == SessionStats.GameStart.NONE) return;
        DiagLog.log("session: game " + start + " world=" + s.world + " sid=" + s.sessionId
                + " armoured=" + s.armoured);
    }

    /** The player's team colour word from the scoreboard, under the nick first while nicked. */
    private static String ownTeamWord() {
        String nick = nickName();
        char code = nick == null ? 0 : TeamColors.code(nick);
        if (code == 0) code = TeamColors.code(selfName());
        return SessionChatLine.teamForColourCode(code);
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
            sampledWorld = null; // never keep a world alive after leaving Hypixel
            return;
        }
        SessionStats.Sample sample = sample();
        logStart(CORE.onGameTick(sample), sample);
        if (HypixelContext.isInBedwars() && !sample.active) {
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

    /**
     * Whether the box draws now: enabled, always in the HUD editor, otherwise on any server, and in
     * Hold Key to Show mode only while the Session Stats key is held with no screen open
     * ({@link SessionHoldKey#hudVisible}).
     */
    public static boolean visible(ClientSettings cfg, boolean example) {
        if (cfg == null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        boolean screenOpen = mc != null && mc.currentScreen != null;
        boolean keyDown = cfg.sessionStatsHoldKey && !screenOpen && holdKeyDown(cfg.sessionStatsKeyCode);
        return SessionHoldKey.hudVisible(cfg.sessionStatsEnabled, example, cfg.sessionStatsHoldKey,
                cfg.sessionStatsKeyCode, screenOpen, keyDown);
    }

    /** Whether the Session Stats key is held right now, read straight from the keyboard or mouse. */
    private static boolean holdKeyDown(int code) {
        if (code == 0) return false;
        if (SessionHoldKey.isMouse(code)) {
            int button = SessionHoldKey.mouseButton(code);
            return button >= 0 && button < Mouse.getButtonCount() && Mouse.isButtonDown(button);
        }
        return code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }
}
