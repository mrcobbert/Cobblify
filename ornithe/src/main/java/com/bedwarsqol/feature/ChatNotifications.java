package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import com.bedwarsqol.ornithe.ChatReceivedEvent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Chat Notifications: sound alerts driven by incoming typed chat. Sub-alerts under one master:
 *
 * <p><b>Mention</b>: a pling when another player's message body contains your username as a whole
 * word, case-insensitive, on any server. Only typed lines ({@code sender: message}) count — the
 * colon-less server broadcasts (kill feed, join lines) constantly carry your own name in a Bedwars
 * game and would ping every death.
 *
 * <p><b>Inc</b>: a distinct double pling when, during an active Bedwars game, a teammate (scoreboard
 * team color match, the same signal {@link SweatReport} uses) or any {@code Party >} line says
 * "inc"/"incoming" as a word. Pairs with {@link IncSender}, whose keybind sends {@code /pc INC}.
 *
 * <p><b>Bed</b> and <b>BG</b>: the same scope as Inc for the words "bed" and "bg", each with its own
 * sound. A line plays at most one of the three, the first enabled one of Inc, Bed, BG
 * ({@link TeamCall}).
 *
 * <p>Your own messages and the mod's own {@link ModChat}-marked output never alert, and each alert
 * type is debounced so a spam wall can't chain sounds.
 */
public class ChatNotifications {

    /** Minimum gap between two mention sounds. */
    private static final long DEBOUNCE_MS = 1500L;

    private long lastMentionMs;
    /** Debounce and note timing for the Inc, Bed and BG calls. */
    private final TeamCallAlerts calls = new TeamCallAlerts();

    /** Whole-word matcher for the local player's name, rebuilt if the profile name changes. */
    private Pattern mentionWord;
    private String mentionName;

    public void onChat(ChatReceivedEvent event) {
        if (event.type == 2) return; // action bar
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.chatNotifications) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.thePlayer.getGameProfile() == null) return;
        IChatComponent msg = event.message;
        if (msg == null || ModChat.isMarked(msg)) return;

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

        long now = System.currentTimeMillis();
        if (cfg.chatNotifyMention && now - lastMentionMs > DEBOUNCE_MS && mentions(body, me)) {
            lastMentionMs = now;
            mc.thePlayer.playSound("note.pling", 1.0f, 1.0f);
        }
        TeamCall call = TeamCall.match(body, cfg.chatNotifyInc, cfg.chatNotifyBed, cfg.chatNotifyBg);
        if (call != null
                && HypixelContext.isInActiveBedwarsGame()
                && (raw.startsWith("Party >") || isTeammate(mc, sender, me))) {
            play(calls.start(call, now));
        }
    }

    public void onClientTick() {
        play(calls.tick());
    }

    private static void play(List<TeamCall.Note> notes) {
        if (notes.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        for (TeamCall.Note n : notes) mc.thePlayer.playSound(n.sound, 1.0f, n.pitch);
    }

    private boolean mentions(String body, String me) {
        if (mentionWord == null || !me.equals(mentionName)) {
            mentionName = me;
            mentionWord = Pattern.compile(
                    "(?i)(?<![A-Za-z0-9_])" + Pattern.quote(me) + "(?![A-Za-z0-9_])");
        }
        return mentionWord.matcher(body).find();
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
