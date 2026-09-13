package com.bedwarsqol.feature;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The four Hypixel Bedwars in-game broadcasts that change a player's standing on the launcher
 * overlay, parsed from a colour-stripped chat line. Anything else is {@code null}.
 *
 * <p>Every pattern is anchored to the whole line, so a line a player typed can never match: player
 * chat always carries a {@code Name:} or {@code [RED]} first token, and channel prefixes
 * ({@code Party >}, {@code Guild >}) fail the lowercase second-token rule on the final-kill shape.
 * Names are only ever <i>matched</i> against a roster built from the tab list, never added from
 * here, so the anonymised pregame join/quit broadcasts stay harmless even if a caller forgets to
 * gate on the active game.
 *
 * <p>Shapes are taken from upstream client sources (AxolotlClient's Bedwars module, PixelStats),
 * not observed here; a mismatch is a missed transition, never a false one.
 */
public final class GameChatLine {

    public enum Kind { DISCONNECT, RECONNECT, FINAL_KILL, TEAM_ELIMINATED }

    private static final String NAME = "([A-Za-z0-9_]{3,16})";
    private static final Pattern DISCONNECT = Pattern.compile("^" + NAME + " disconnected\\.$");
    private static final Pattern RECONNECT = Pattern.compile("^" + NAME + " reconnected\\.$");
    /** Victim is always the first token of a death line; the second token is a lowercase verb. */
    private static final Pattern FINAL_KILL = Pattern.compile("^" + NAME + " [a-z]+\\b.* FINAL KILL!$");
    private static final Pattern TEAM_ELIMINATED =
            Pattern.compile("^TEAM ELIMINATED > ([A-Za-z]+) Team has been eliminated!$");

    public final Kind kind;
    /** Player named by a DISCONNECT / RECONNECT / FINAL_KILL line; null for TEAM_ELIMINATED. */
    public final String name;
    /** Team colour word ({@code Red}, {@code Blue}, …) of a TEAM_ELIMINATED line; null otherwise. */
    public final String team;

    private GameChatLine(Kind kind, String name, String team) {
        this.kind = kind;
        this.name = name;
        this.team = team;
    }

    /** Parse a colour-stripped line (leading/trailing whitespace ignored). Null when not a standing change. */
    public static GameChatLine parse(String plain) {
        if (plain == null) return null;
        String line = plain.trim();
        if (line.isEmpty()) return null;
        Matcher m = DISCONNECT.matcher(line);
        if (m.matches()) return new GameChatLine(Kind.DISCONNECT, m.group(1), null);
        m = RECONNECT.matcher(line);
        if (m.matches()) return new GameChatLine(Kind.RECONNECT, m.group(1), null);
        m = FINAL_KILL.matcher(line);
        if (m.matches()) return new GameChatLine(Kind.FINAL_KILL, m.group(1), null);
        m = TEAM_ELIMINATED.matcher(line);
        if (m.matches()) return new GameChatLine(Kind.TEAM_ELIMINATED, null, m.group(1));
        return null;
    }
}
