package com.bedwarsqol.feature;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies one colour-stripped Hypixel Bedwars line for the session tally, from the LOCAL
 * player's point of view. Anything that is not one of the recognised events is {@code null}.
 *
 * <p>Attribution is vocabulary-free. Hypixel rotates ~160 cosmetic death messages, but every one
 * shares a shape: the victim is the first token and the killer is named exactly once at the end,
 * either as the last token after a preposition ({@code by Self.}, {@code fighting Self.},
 * {@code escape Self.}) or as the owner of a possessive ({@code Self's Golem.}, {@code Self's
 * holiday spirit.}, {@code Self's final #12}). Only a whole token that equals the local name is
 * ever credited, so a stray word can never be mistaken for the player unless the player is
 * literally named that word AND stands in the killer position — the position rule is what keeps a
 * player called {@code Golem} from being credited for {@code Steve was fried by Alex's Golem.}
 *
 * <p>Player-typed chat is excluded structurally: every player line carries {@code Name:} before
 * its text and none of the system lines here contain a colon, except the {@code Winners:} roll-call
 * which is matched by its anchored shape.
 *
 * <p>Shapes come from upstream sources, not from observation here: AxolotlClient's
 * {@code BedwarsMessages} (death / bed / game-end templates) and the {@code 1mshy/bedwars} and
 * {@code crow-1.8.9} mods (outcome tokens). The outcome TITLE ({@link #parseTitle}) is the primary
 * win/loss signal; the chat outcome shapes are the fallback.
 */
public final class SessionChatLine {

    public enum Kind {
        /** Local player killed someone (not a final). */
        KILL,
        /** Local player final-killed someone. */
        FINAL_KILL,
        /** Local player died (not a final). */
        DEATH,
        /** Local player was final-killed. */
        FINAL_DEATH,
        /** Local player broke a bed. */
        BED_BREAK,
        /** The local player's team lost its bed ("BED DESTRUCTION > Your Bed …"). */
        BED_LOST,
        /** Local player's team won. */
        WIN,
        /** Local player's team lost: the game is over. */
        LOSS,
        /** Local player was eliminated mid-game ("You have been eliminated!"); the game goes on. */
        ELIMINATED,
        /** The end-of-game "1st Killer" summary row. */
        GAME_END
    }

    private static final String NAME = "[A-Za-z0-9_]{3,16}";

    /**
     * Victim first (optionally possessive: "Steve's heart was pierced by …"), then a body that
     * normally ends in "." or in the "final #n" cosmetic — the terminator is optional because one
     * sourced template ("was shot into limbo by {player}") carries none — then optional FINAL KILL!.
     */
    private static final Pattern DEATH_LINE =
            Pattern.compile("^(" + NAME + ")(?:'s)? (.+?)(?:\\.|#[0-9,]+\\.?)?( FINAL KILL!)?$");
    private static final Pattern PREPOSITION =
            Pattern.compile("(?:^|\\s)(?:by|to|with|for|of|from|against|fighting|escape|meet|seeing) (" + NAME + ")$");
    private static final Pattern POSSESSIVE = Pattern.compile("(?:^|\\s)(" + NAME + ")'s(?:\\s|$)");
    private static final Pattern SELF_DEATH_NO_KILLER = Pattern.compile(
            "^(?:fell into the void|died|hit the ground too hard|burned to death|drowned|was blown up"
                    + "|was struck by lightning|withered away|was pricked to death)$");
    private static final Pattern BED = Pattern.compile("^BED DESTRUCTION > ([A-Za-z]+) Bed (.+)$");
    /**
     * Bed Wars lobby stats hologram row (armour-stand name, colour codes already stripped). Grouped
     * thousands or at most nine plain digits, so a match always fits an int.
     */
    private static final Pattern WINSTREAK_HOLOGRAM =
            Pattern.compile("^Current Winstreak: ([0-9]{1,3}(?:,[0-9]{3})*|[0-9]{1,9})$");
    private static final Pattern WINNERS = Pattern.compile("^Winners?(?: -|:) (.+)$");
    /** AxolotlClient BedwarsMessages.GAME_END, leading whitespace tolerated. */
    private static final Pattern GAME_END =
            Pattern.compile("^\\s*1st Killer - ?\\[?\\w*\\+*]? \\w+ - \\d+(?: Kills?)?$");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^A-Za-z0-9_]+");

    private SessionChatLine() {
    }

    /**
     * Classify a colour-stripped chat line. {@code self} is the local player's name; a null or empty
     * self disables attribution (only WIN/LOSS/GAME_END can then match).
     */
    public static Kind parse(String plain, String self) {
        if (plain == null) return null;
        String line = plain.trim();
        if (line.isEmpty()) return null;

        // Colon-free system lines first; the only colon-bearing system shape is the winners roll-call.
        if (line.equals("VICTORY!") || line.equals("You won!")) return Kind.WIN;
        if (line.equals("GAME OVER!")) return Kind.LOSS;
        if (line.equals("You have been eliminated!")) return Kind.ELIMINATED;
        if (GAME_END.matcher(line).matches()) return Kind.GAME_END;
        Matcher w = WINNERS.matcher(line);
        if (w.matches()) return hasToken(w.group(1), self) ? Kind.WIN : null;
        if (line.indexOf(':') >= 0) return null; // player-typed

        Matcher b = BED.matcher(line);
        if (b.matches()) {
            // Hypixel names the victim team by colour for everyone else and as "Your" for that team
            // itself (Open-Meowtils BedTracker.java:110, Raven-Scripts session.java:304), so the local
            // team's loss needs no team lookup and no self name.
            if (b.group(1).equalsIgnoreCase("Your")) return Kind.BED_LOST;
            if (self == null || self.isEmpty()) return null;
            // Same killer-position rule as death lines: "… by Self!", "… by Self's holiday spirit!",
            // "… after seeing Self!". Never an incidental word elsewhere in the cosmetic.
            String rest = b.group(2).trim();
            if (rest.endsWith("!")) rest = rest.substring(0, rest.length() - 1).trim();
            String breaker = killerOf(rest);
            return breaker != null && breaker.equalsIgnoreCase(self) ? Kind.BED_BREAK : null;
        }

        if (self == null || self.isEmpty()) return null;

        Matcher d = DEATH_LINE.matcher(line);
        if (!d.matches()) return null;
        String victim = d.group(1);
        String tail = d.group(2).trim();
        boolean fin = d.group(3) != null;
        boolean victimIsSelf = victim.equalsIgnoreCase(self);
        String killer = killerOf(tail);
        boolean killerIsSelf = killer != null && killer.equalsIgnoreCase(self);

        if (killerIsSelf && !victimIsSelf) return fin ? Kind.FINAL_KILL : Kind.KILL;
        if (victimIsSelf) {
            if (fin) return Kind.FINAL_DEATH;
            if (killer != null || SELF_DEATH_NO_KILLER.matcher(tail).matches()) return Kind.DEATH;
        }
        return null;
    }

    /** Map a colour-stripped title to an outcome: gold VICTORY! to winners, red GAME OVER! to the rest. */
    public static Kind parseTitle(String plainTitle) {
        if (plainTitle == null) return null;
        String t = plainTitle.trim();
        if (t.equals("VICTORY!")) return Kind.WIN;
        if (t.equals("GAME OVER!")) return Kind.LOSS;
        return null;
    }

    /**
     * The player's current winstreak as shown on the Bed Wars lobby "Bed Wars Profile" hologram
     * ({@code Current Winstreak: 1,204}; Raven-Scripts session.java:104-112, HypixelRecreation
     * NPCStats.java:49). {@code -1} for any other text, and never an exception: the tick handler
     * feeds it every armour-stand name it sees.
     */
    public static int parseWinstreakHologram(String plain) {
        if (plain == null) return -1;
        Matcher m = WINSTREAK_HOLOGRAM.matcher(plain.trim());
        if (!m.matches()) return -1;
        try {
            return Integer.parseInt(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The one token a death-line tail can name as the killer: the last token when it follows a
     * preposition, or the owner of a possessive. Null when the shape names nobody in that position.
     */
    static String killerOf(String tail) {
        Matcher p = PREPOSITION.matcher(tail);
        if (p.find()) return p.group(1);
        Matcher o = POSSESSIVE.matcher(tail);
        if (o.find()) return o.group(1);
        return null;
    }

    /** Whole-token, case-insensitive membership; never a prefix or substring match. */
    static boolean hasToken(String text, String self) {
        if (text == null || self == null || self.isEmpty()) return false;
        String want = self.toLowerCase(Locale.ROOT);
        for (String tok : TOKEN_SPLIT.split(text)) {
            if (!tok.isEmpty() && tok.toLowerCase(Locale.ROOT).equals(want)) return true;
        }
        return false;
    }
}
