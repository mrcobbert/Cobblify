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
 * outcome is reported as unresolved once, to whichever of the next game start or {@link #reset}
 * comes first, and counts nothing.
 *
 * <p>Which game a line belongs to is decided by {@link #onGameTick}, which every event entry point
 * runs first with a {@link Sample} of the client at that moment. A new Bed Wars game is always
 * reached through its pregame queue; {@code /rejoin} puts the player straight into the running match.
 * So a game that becomes active in a world that showed the queue is a new game, and one that becomes
 * active in a new world without a queue, while the last game the player entered is unfinished, is
 * that same game rejoined. A game the player entered (wore armour in) and left before any end
 * signal is a loss, as Hypixel records it, counted when the next game opens. The player's own
 * team being eliminated ({@link #onTeamEliminated}) settles the loss at once.
 *
 * <p>Winstreak is the player's live streak rather than a session counter: a settled win adds one,
 * a settled loss zeroes it (and the VICTORY-after-loss correction restores it), the Bed Wars lobby
 * hologram re-seeds it via {@link #seedWinstreak}, a manual {@link #reset} keeps it, and only
 * leaving Hypixel ({@link #endSession}) clears it. Without a seed it counts from zero.
 */
public final class SessionStats {

    public enum Outcome { UNKNOWN, WIN, LOSS }

    /** What a game-entry check returned; anything but NONE is worth a diagnostic line. */
    public enum GameStart { NONE, NEW, REJOINED, NEW_AFTER_ABANDONED, NEW_AFTER_UNRESOLVED }

    /**
     * The client at one moment, as far as game identity needs it: the GameSessionTracker id (only for
     * the event latch), a serial the adapter bumps whenever the world object changes, whether a Bed
     * Wars game is active, whether the pregame queue is showing, whether the player wears any armour
     * (Lunar's test for "really in the game"), and the player's team colour word from the scoreboard
     * (null when unknown). Taking the team with every sample means it is known before the first
     * event of a game is applied.
     */
    public static final class Sample {
        public final int sessionId;
        public final int world;
        public final boolean active;
        public final boolean inQueue;
        public final boolean armoured;
        public final String ownTeam;

        public Sample(int sessionId, int world, boolean active, boolean inQueue, boolean armoured, String ownTeam) {
            this.sessionId = sessionId;
            this.world = world;
            this.active = active;
            this.inQueue = inQueue;
            this.armoured = armoured;
            this.ownTeam = ownTeam;
        }
    }

    private static final int NO_ID = Integer.MIN_VALUE;

    // ---- game block ----
    private int gameKills;
    private int gameFinals;
    private int gameBeds;
    private int gameSessionId = NO_ID;
    private Outcome outcome = Outcome.UNKNOWN;
    private boolean gameEndSeen;
    private boolean settled;

    // ---- game identity ----
    /** World serial of the open game block. */
    private int gameWorld = NO_ID;
    /** World serial in which the pregame queue was last seen, until a game opens there. */
    private int queueWorld = NO_ID;
    /** The player wore armour in this game: leaving it before the end is a loss. */
    private boolean entered;
    /** This game's settled outcome is in the current tally (not one zeroed by a manual reset). */
    private boolean countedInTally;
    private boolean unresolvedReported;
    /** Team colour word the player plays for in this game, latched once they are out. */
    private String ownTeam;
    /** The player was final-killed, eliminated, or saw their team eliminated in this game. */
    private boolean selfOut;

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
    /** Nick from the last "You are now nicked as X!" line this session; memory only. */
    private String chatNick;

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
     * Decide which game the client is in now and open a new game block when one began. Idempotent
     * for an unchanged sample; every event entry point that takes a {@link Sample} runs it first, so
     * a line from a game that just became active is never credited to the previous one.
     */
    public GameStart onGameTick(Sample s) {
        if (s == null) return GameStart.NONE;
        if (s.inQueue) queueWorld = s.world;
        if (!s.active) return GameStart.NONE;
        GameStart start;
        if (queueWorld == s.world) {
            start = openGame(s); // reached through this world's pregame queue
        } else if (s.world == gameWorld) {
            // Still the open game's world. Only a pregame queue starts another game here: the
            // GameSessionTracker id can move under the same game (it lags a queue-opened game by up
            // to a tick, and rises again on an active-objective flicker), so it only rebinds the latch.
            gameSessionId = s.sessionId;
            if (s.armoured && !settled && !gameEndSeen) entered = true;
            start = GameStart.NONE;
        } else if (entered && !gameEndSeen) {
            // Straight into a running match with no queue: the unfinished game, rejoined. A
            // spectator comes back without armour, so none is needed here.
            gameSessionId = s.sessionId;
            gameWorld = s.world;
            start = GameStart.REJOINED;
        } else {
            start = openGame(s);
        }
        if (s.armoured) observeOwnTeam(s.ownTeam);
        return start;
    }

    private GameStart openGame(Sample s) {
        GameStart result = GameStart.NEW;
        if (entered && !gameEndSeen && !settled) {
            // The player walked out before the end: Hypixel records that as a loss.
            if (outcome != Outcome.WIN) outcome = Outcome.LOSS;
            gameEndSeen = true;
            settle();
            result = GameStart.NEW_AFTER_ABANDONED;
        } else if (gameEndSeen && !settled && !unresolvedReported) {
            result = GameStart.NEW_AFTER_UNRESOLVED;
        }
        gameKills = 0;
        gameFinals = 0;
        gameBeds = 0;
        gameSessionId = s.sessionId;
        gameWorld = s.world;
        queueWorld = NO_ID;
        outcome = Outcome.UNKNOWN;
        gameEndSeen = false;
        settled = false;
        countedInTally = false;
        unresolvedReported = false;
        entered = s.armoured;
        ownTeam = null;
        selfOut = false;
        return result;
    }

    /** The session id the current game block belongs to (Integer.MIN_VALUE before any game). */
    public int gameSessionId() { return gameSessionId; }

    /** The world serial the current game block belongs to (Integer.MIN_VALUE before any game). */
    public int gameWorld() { return gameWorld; }

    /** The team colour word recorded for this game, or null. */
    public String ownTeam() { return ownTeam; }

    /**
     * The player's team colour word while they are still in the game. Ignored once they were
     * final-killed, eliminated or saw their team eliminated, because Hypixel may then move them to a
     * spectator team.
     */
    public void observeOwnTeam(String team) {
        if (team != null && !selfOut) ownTeam = team;
    }

    /**
     * A {@code TEAM ELIMINATED > <team> Team has been eliminated!} line. When it names the player's
     * own team (its colour, or "Your"), the game is lost whatever happens next: the loss is counted
     * at once, and later end signals add nothing. Returns whether it was the player's team.
     */
    public boolean onTeamEliminated(Sample s, String team) {
        onGameTick(s);
        if (team == null) return false;
        boolean ours = team.equalsIgnoreCase("Your") || (ownTeam != null && team.equalsIgnoreCase(ownTeam));
        if (!ours) return false;
        selfOut = true;
        if (!settled && outcome != Outcome.WIN) {
            outcome = Outcome.LOSS;
            countOutcome();
        }
        return true;
    }

    /**
     * A Hypixel nick line, as {@link SessionChatLine#parseNickChange} reports it: a name sets the
     * remembered nick, {@code ""} clears it, {@code null} (any other line) is ignored. A manual
     * {@link #reset} keeps it; leaving Hypixel clears it.
     */
    public void onNickChange(String nick) {
        if (nick == null) return;
        chatNick = nick.isEmpty() ? null : nick;
    }

    /** The nick learned from chat this session, or null. */
    public String chatNick() { return chatNick; }

    /** Lobby hologram value; negative (no value) is ignored. */
    public void seedWinstreak(int value) {
        if (value >= 0) winstreak = value;
    }

    /** Run the game-entry check for {@code s}, then feed {@code kind}. Returns the check's result. */
    public GameStart onEvent(Sample s, SessionChatLine.Kind kind) {
        GameStart start = onGameTick(s);
        onEvent(kind);
        return start;
    }

    /** Feed one classified event (chat line or title) to the open game. Null is ignored. */
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
                selfOut = true;
                break;
            case BED_BREAK:
                gameBeds++;
                beds++;
                break;
            case BED_LOST:
                bedsLost++;
                break;
            case WIN:
                if (settled && outcome == Outcome.LOSS && countedInTally) {
                    // A loss was settled before the victory title arrived: move it. A loss counted
                    // before a manual reset is no longer in this tally and stays as it is.
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
                selfOut = true;
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
        countOutcome();
    }

    private void countOutcome() {
        settled = true;
        countedInTally = true;
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

    /**
     * Leaving Hypixel: a full {@link #reset} that also drops the winstreak, the chat nick and the open
     * game, so nothing carries into the next connection.
     */
    public boolean endSession() {
        boolean unresolved = reset();
        winstreak = 0;
        streakBeforeLoss = 0;
        chatNick = null;
        outcome = Outcome.UNKNOWN;
        gameEndSeen = false;
        settled = false;
        unresolvedReported = false;
        gameSessionId = NO_ID;
        gameWorld = NO_ID;
        queueWorld = NO_ID;
        entered = false;
        ownTeam = null;
        selfOut = false;
        wasOnHypixel = false;
        return unresolved;
    }

    /**
     * Zero the tally and stop the clock; the winstreak is kept (it is the player's live streak, not
     * a session figure). The current game keeps its state: an undecided game still counts its
     * outcome (or its abandonment) in the new tally, and a decided one stays decided, so it can never
     * be counted again. Returns true when the current game ended unresolved and was not reported yet.
     */
    public boolean reset() {
        boolean unresolved = gameEndSeen && !settled && !unresolvedReported;
        if (unresolved) unresolvedReported = true;
        gameKills = 0;
        gameFinals = 0;
        gameBeds = 0;
        countedInTally = false;
        wins = 0;
        losses = 0;
        finalKills = 0;
        finalDeaths = 0;
        kills = 0;
        deaths = 0;
        beds = 0;
        bedsLost = 0;
        // wasOnHypixel stays: leaving Hypixel after a manual reset must still end the session.
        sessionStartMs = -1L;
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
