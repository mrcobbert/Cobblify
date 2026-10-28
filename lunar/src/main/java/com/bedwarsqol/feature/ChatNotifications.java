package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.weavemc.api.event.ChatEvent;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;

import java.util.List;

/**
 * Inc Alert: a double pling when, during an active Bedwars game, a teammate (scoreboard team color
 * match, the same signal {@link SweatReport} uses) or any {@code Party >} line says "inc"/"incoming"
 * as a word. Pairs with {@link IncSender}, whose keybind sends {@code /pc INC}. Bed Alert and BG Alert
 * do the same for "bed" and "bg", each with its own sound; a line plays at most one of the three, the
 * first enabled one of Inc, Bed, BG ({@link TeamCall}).
 *
 * <p>Lunar tree divergence: the Forge tree's ChatNotifications also carries a name-mention alert
 * under a Chat Notifications master; Lunar Client covers the generic chat QOL natively, so here the
 * three call alerts stand alone on {@code chatNotifyInc}, {@code chatNotifyBed} and
 * {@code chatNotifyBg}. Weave routes the mod's own {@code addChatMessage} lines through the same
 * {@code ChatEvent.Received} hook as server chat, so the {@link ModChat} mark check is load-bearing,
 * not belt-and-braces.
 */
public class ChatNotifications {

    /** Debounce and note timing for the Inc, Bed and BG calls. */
    private final TeamCallAlerts calls = new TeamCallAlerts();

    @SubscribeEvent
    public void onChat(ChatEvent.Received event) {
        if (event == null || event.getMessage() == null) return;
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !(cfg.chatNotifyInc || cfg.chatNotifyBed || cfg.chatNotifyBg)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.thePlayer.getGameProfile() == null) return;
        IChatComponent msg = event.getMessage();
        if (ModChat.isMarked(msg)) return;

        String sender = ChatSender.typedChatName(msg);
        if (sender == null) return; // typed chat only
        String me = mc.thePlayer.getGameProfile().getName();
        if (me == null || sender.equalsIgnoreCase(me)) return;

        String raw = EnumChatFormatting.getTextWithoutFormattingCodes(msg.getUnformattedText());
        if (raw == null) return;
        raw = raw.trim();
        int colon = raw.indexOf(':');
        if (colon < 0 || colon + 1 >= raw.length()) return;
        String body = raw.substring(colon + 1);

        TeamCall call = TeamCall.match(body, cfg.chatNotifyInc, cfg.chatNotifyBed, cfg.chatNotifyBg);
        if (call != null
                && HypixelContext.isInActiveBedwarsGame()
                && (raw.startsWith("Party >") || isTeammate(mc, sender, me))) {
            play(calls.start(call, System.currentTimeMillis()));
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
        play(calls.tick());
    }

    private static void play(List<TeamCall.Note> notes) {
        if (notes.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        for (TeamCall.Note n : notes) mc.thePlayer.playSound(n.sound, 1.0f, n.pitch);
    }

    /** Same-scoreboard-team check via nametag color, the shared team signal on Hypixel. */
    private static boolean isTeammate(Minecraft mc, String sender, String me) {
        Scoreboard board = mc.theWorld != null ? mc.theWorld.getScoreboard() : null;
        if (board == null) return false;
        char mine = teamColor(board, me);
        return mine != 0 && teamColor(board, sender) == mine;
    }

    /** The first Minecraft color code in a player's rendered nametag = their team color. */
    private static char teamColor(Scoreboard board, String name) {
        ScorePlayerTeam team = board.getPlayersTeam(name);
        if (team == null) return 0;
        String formatted = ScorePlayerTeam.formatPlayerName(team, name);
        if (formatted == null) return 0;
        for (int i = 0; i + 1 < formatted.length(); i++) {
            if (formatted.charAt(i) == '§') {
                char c = Character.toLowerCase(formatted.charAt(i + 1));
                if ("0123456789abcdef".indexOf(c) >= 0) return c;
            }
        }
        return 0;
    }
}
