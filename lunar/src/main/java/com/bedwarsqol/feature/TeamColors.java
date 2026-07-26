package com.bedwarsqol.feature;

import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;

/**
 * Bedwars team color from the scoreboard nametag signal (first {@code §[0-9a-f]} in
 * {@link ScorePlayerTeam#formatPlayerName}). Shared by chat alert surfaces; other features keep
 * their private copies to avoid unrelated diffs.
 */
public final class TeamColors {

    private TeamColors() {}

    /**
     * First Minecraft color code digit/letter for {@code name}'s rendered nametag, or {@code 0}
     * when the world/scoreboard/team/color is unavailable.
     */
    public static char code(String name) {
        if (name == null || name.isEmpty()) return 0;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return 0;
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return 0;
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

    /** {@code "§" + code} for a known team color, else yellow {@code "§e"}. */
    public static String nameColor(String name) {
        char c = code(name);
        return c == 0 ? "§e" : ("§" + c);
    }
}
