package com.bedwarsqol.feature;

/**
 * Lunar-style Bedwars session tally: a per-game block (kills, finals, beds) and a session block
 * (wins/losses, finals/final deaths, kills/deaths, beds broken/lost, winstreak, elapsed clock).
 * Pure state machine; the platform adapter feeds it {@link SessionChatLine.Kind} events,
 * game-start edges, ticks and the lobby winstreak seed.
 *
 * <p>Session semantics follow Lunar's Hypixel Bedwars mod: counters start at zero when the client
 * starts, the session ends when the player leaves the Hypixel server, and it can be reset on demand.
 * A lobby hop inside Hypixel keeps the session. Nothing is persisted.
 *
 * <p>Outcome: WIN and LOSS signals set the game's outcome (WIN overrides LOSS, LOSS never
 * overrides WIN — an eliminated player whose team comes back is a Hypixel win). ELIMINATED is a
 * provisional loss that does not end the game. The game end is marked by the "1st Killer" row OR by
 * a WIN/LOSS signal itself — the VICTORY!/GAME OVER! title only ever appears when the game is over,
 * and Hypixel prints no killer row for a game with zero kills (an opponent who leaves before first
 * blood). The win or loss is recorded exactly once, when both are known, in either order;
 * a VICTORY that lands after a loss was already settled (eliminated, killer row, then the team's
 * comeback title) moves that game from the losses to the wins. A game that ended without a known
 * outcome is reported as unresolved to the caller of the next {@link #onGameStart} /
 * {@link #reset} and counts nothing.
 *
 * <p>Winstreak is the player's live streak rather than a session counter: a settled win adds one,
 * a settled loss zeroes it (and the VICTORY-after-loss correction restores it), the Bed Wars lobby
 * hologram re-seeds it via {@link #seedWinstreak}, a manual {@link #reset} keeps it, and only
 * leaving Hypixel ({@link #endSession}) clears it. Without a seed it counts from zero.
 */
public final class SessionStats {

    public enum Outcome { UNKNOWN, WIN, LOSS }

    // ---- game block ----
    private int gameKills;
    private int gameFinals;
    private int gameBeds;
    private int gameSessionId = Integer.MIN_VALUE;
    private Outcome outcome = Outcome.UNKNOWN;
    private boolean gameEndSeen;
    private boolean settled;

    // ---- session block ----
    private int wins;
    private int losses;
    private int finalKills;
    private int finalDeaths;
    private int kills;
    private int deaths;
    private int beds;
    private int bedsLost;
    private int winstreak;
    /** Streak as it stood when a loss was settled, so a late VICTORY can restore it. */
    private int streakBeforeLoss;
    private long sessionStartMs = -1L;
    private boolean wasOnHypixel;

    // ---- game block ----

    public int gameKills() { return gameKills; }
    public int gameFinals() { return gameFinals; }
    public int gameBeds() { return gameBeds; }
    public Outcome outcome() { return outcome; }

    // ---- session block ----

    public int wins() { return wins; }
    public int losses() { return losses; }
    public int finalKills() { return finalKills; }
    public int finalDeaths() { return finalDeaths; }
    public int kills() { return kills; }
    public int deaths() { return deaths; }
    public int beds() { return beds; }
    public int bedsLost() { return bedsLost; }
    public int winstreak() { return winstreak; }
    /** Settled games this session (wins + losses); an unresolved game counts nowhere. */
    public int games() { return wins + losses; }
    /** Wall-clock millis when the session clock started, or -1 before the first tick on Hypixel. */
    public long sessionStartMs() { return sessionStartMs; }

    /** Win/loss ratio with the {@code BedwarsStats.ModeStats} convention: a zero denominator returns the numerator. */
    public double wlr() { return ratio(wins, losses); }
    public double fkdr() { return ratio(finalKills, finalDeaths); }
    public double kdr() { return ratio(kills, deaths); }
    public double bblr() { return ratio(beds, bedsLost); }

    static double ratio(int n, int d) {
        return d == 0 ? n : (double) n / d;
    }

    /**
     * A new game began (a fresh {@code GameSessionTracker} id while in an active game). Zeroes the
     * game block. Returns true when the previous game ended without a recorded outcome.
     */
    public boolean onGameStart(int sessionId) {
        boolean unresolved = gameEndSeen && !settled;
        gameKills = 0;
        gameFinals = 0;
        gameBeds = 0;
        gameSessionId = sessionId;
        outcome = Outcome.UNKNOWN;
        gameEndSeen = false;
        settled = false;
        return unresolved;
    }

    /** The session id the current game block belongs to (Integer.MIN_VALUE before any game). */
    public int gameSessionId() { return gameSessionId; }

    /** Lobby hologram value; negative (no value) is ignored. */
    public void seedWinstreak(int value) {
        if (value >= 0) winstreak = value;
    }

    /** Feed one classified event (chat line or title). Null is ignored. */
    public void onEvent(SessionChatLine.Kind kind) {
        if (kind == null) return;
        switch (kind) {
            case KILL:
                gameKills++;
                kills++;
                break;
            case FINAL_KILL:
                gameFinals++;
                finalKills++;
                break;
            case DEATH:
                deaths++;
                break;
            case FINAL_DEATH:
                finalDeaths++;
                break;
            case BED_BREAK:
                gameBeds++;
                beds++;
                break;
            case BED_LOST:
                bedsLost++;
                break;
            case WIN:
                if (settled && outcome == Outcome.LOSS) {
                    // The killer row settled a loss before the victory title arrived: move it.
                    losses--;
                    wins++;
                    winstreak = streakBeforeLoss + 1;
                }
                outcome = Outcome.WIN;
                gameEndSeen = true;
                settle();
                break;
            case LOSS:
                if (outcome != Outcome.WIN) outcome = Outcome.LOSS;
                gameEndSeen = true;
                settle();
                break;
            case ELIMINATED:
                if (outcome != Outcome.WIN) outcome = Outcome.LOSS;
                settle();
                break;
            case GAME_END:
                gameEndSeen = true;
                settle();
                break;
            default:
                break;
        }
    }

    private void settle() {
        if (settled || !gameEndSeen || outcome == Outcome.UNKNOWN) return;
        settled = true;
        if (outcome == Outcome.WIN) {
            wins++;
            winstreak++;
        } else {
            losses++;
            streakBeforeLoss = winstreak;
            winstreak = 0;
        }
    }

    /**
     * Per-tick driver. The first tick on Hypixel after a reset starts the clock; the first tick off
     * Hypixel after being on ends the session (Lunar: logging off clears it). Returns true when
     * that transition reset an unresolved game.
     */
    public boolean onTick(boolean onHypixel, long nowMs) {
        boolean unresolved = false;
        if (onHypixel) {
            if (sessionStartMs < 0L) sessionStartMs = nowMs;
            wasOnHypixel = true;
        } else if (wasOnHypixel) {
            unresolved = endSession();
        }
        return unresolved;
    }

    /** Leaving Hypixel: a full {@link #reset} that also drops the winstreak. */
    public boolean endSession() {
        boolean unresolved = reset();
        winstreak = 0;
        streakBeforeLoss = 0;
        return unresolved;
    }

    /**
     * Zero the tally and stop the clock; the winstreak is kept (it is the player's live streak, not
     * a session figure). Returns true when the current game ended unresolved.
     */
    public boolean reset() {
        boolean unresolved = gameEndSeen && !settled;
        gameKills = 0;
        gameFinals = 0;
        gameBeds = 0;
        outcome = Outcome.UNKNOWN;
        gameEndSeen = false;
        settled = false;
        wins = 0;
        losses = 0;
        finalKills = 0;
        finalDeaths = 0;
        kills = 0;
        deaths = 0;
        beds = 0;
        bedsLost = 0;
        sessionStartMs = -1L;
        wasOnHypixel = false;
        return unresolved;
    }

    /** Elapsed session time as {@code mm:ss} or {@code h:mm:ss}; {@code 00:00} before the clock starts. */
    public String elapsed(long nowMs) {
        if (sessionStartMs < 0L) return "00:00";
        long s = Math.max(0L, (nowMs - sessionStartMs) / 1000L);
        long h = s / 3600L, m = (s % 3600L) / 60L, sec = s % 60L;
        if (h > 0) return h + ":" + two(m) + ":" + two(sec);
        return two(m) + ":" + two(sec);
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : Long.toString(v);
    }

    /** Fixed two-decimal ratio text, e.g. {@code 3.00}. */
    public static String formatRatio(double r) {
        long scaled = Math.round(r * 100.0);
        return (scaled / 100) + "." + two(scaled % 100);
    }
}
