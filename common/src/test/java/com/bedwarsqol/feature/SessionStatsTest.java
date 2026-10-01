package com.bedwarsqol.feature;

import org.junit.Test;

import static com.bedwarsqol.feature.SessionChatLine.Kind;
import static com.bedwarsqol.feature.SessionStats.GameStart;
import static com.bedwarsqol.feature.SessionStats.Sample;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionStatsTest {

    private static final String SELF = "Self";

    private static void feed(SessionStats s, String... lines) {
        for (String l : lines) s.onEvent(SessionChatLine.parse(l, SELF));
    }

    private static Sample queue(int sid, int world) {
        return new Sample(sid, world, false, true, false, null);
    }

    private static Sample lobby(int sid, int world) {
        return new Sample(sid, world, false, false, false, null);
    }

    private static Sample active(int sid, int world, boolean armoured) {
        return new Sample(sid, world, true, false, armoured, null);
    }

    /** Active and armoured, with the scoreboard showing the player on {@code team}. */
    private static Sample activeAs(int sid, int world, String team) {
        return new Sample(sid, world, true, false, true, team);
    }

    /** A new game the way a client reaches one: its pregame queue, then active with armour, in a new world. */
    private static GameStart play(SessionStats s, int game) {
        s.onGameTick(queue(game, game));
        return s.onGameTick(active(game, game, true));
    }

    @Test
    public void countersAndRatios() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "Steve was killed by Self.", "Alex was shot by Self.",
                "Steve was knocked into the void by Self. FINAL KILL!",
                "BED DESTRUCTION > Red Bed was destroyed by Self!",
                "Self was killed by Alex.", "Self fell into the void. FINAL KILL!");
        assertEquals(2, s.gameKills());
        assertEquals(1, s.gameFinals());
        assertEquals(1, s.gameBeds());
        assertEquals(2, s.kills());
        assertEquals(1, s.deaths());
        assertEquals(1, s.finalKills());
        assertEquals(1, s.finalDeaths());
        assertEquals(1, s.beds());
        assertEquals(1.0, s.fkdr(), 1e-9);
        // BedwarsStats.ModeStats convention: zero denominator returns the numerator.
        assertEquals(0.0, s.wlr(), 1e-9);
        assertEquals(3.0, SessionStats.ratio(3, 0), 1e-9);
        assertEquals(1.5, SessionStats.ratio(3, 2), 1e-9);
        assertEquals("3.00", SessionStats.formatRatio(3.0));
        assertEquals("1.50", SessionStats.formatRatio(1.5));
        assertEquals("0.33", SessionStats.formatRatio(1.0 / 3.0));
    }

    @Test
    public void bedsLostKdrBblrAndGames() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "BED DESTRUCTION > Red Bed was destroyed by Self!", "BED DESTRUCTION > Green Bed was destroyed by Self!",
                "BED DESTRUCTION > Your Bed was destroyed by Steve!",
                "Steve was killed by Self.", "Alex was killed by Self.", "Bob was killed by Self.",
                "Self was killed by Alex.", "Self was killed by Bob.");
        assertEquals(2, s.beds());
        assertEquals(1, s.bedsLost());
        assertEquals(2.0, s.bblr(), 1e-9);
        assertEquals(1.5, s.kdr(), 1e-9);
        assertEquals(0, s.games()); // nothing settled yet
        feed(s, "GAME OVER!", "1st Killer - Alex - 9");
        assertEquals(1, s.games());
        play(s, 2);
        feed(s, "VICTORY!");
        assertEquals(2, s.games());
        // Game block never carries beds lost; a fresh game keeps the session's.
        assertEquals(0, s.gameBeds());
        assertEquals(1, s.bedsLost());
        // Zero-denominator convention holds for the new ratios too.
        SessionStats z = new SessionStats();
        feed(z, "BED DESTRUCTION > Red Bed was destroyed by Self!");
        assertEquals(1.0, z.bblr(), 1e-9);
        assertEquals(0.0, z.kdr(), 1e-9);
    }

    @Test
    public void winstreakFollowsSettledOutcomes() {
        SessionStats s = new SessionStats();
        s.onTick(true, 1_000L);
        assertEquals(0, s.winstreak());
        play(s, 1);
        feed(s, "VICTORY!");
        assertEquals(1, s.winstreak());
        play(s, 2);
        feed(s, "VICTORY!", "1st Killer - Self - 2");
        assertEquals(2, s.winstreak()); // the killer row after a settled title adds nothing
        play(s, 3);
        feed(s, "You have been eliminated!");
        assertEquals(2, s.winstreak()); // provisional loss does not settle
        feed(s, "GAME OVER!");
        assertEquals(0, s.winstreak());
        play(s, 4);
        feed(s, "VICTORY!");
        assertEquals(1, s.winstreak());
    }

    @Test
    public void winstreakSeedAndLateVictoryRestore() {
        SessionStats s = new SessionStats();
        s.onTick(true, 1_000L);
        s.seedWinstreak(7);
        assertEquals(7, s.winstreak());
        s.seedWinstreak(-1); // "no value" never overwrites
        assertEquals(7, s.winstreak());
        play(s, 1);
        feed(s, "VICTORY!");
        assertEquals(8, s.winstreak());
        // Loss settled by the killer row, then the team's comeback title: the streak is restored.
        play(s, 2);
        feed(s, "You have been eliminated!", "1st Killer - Alex - 9");
        assertEquals(1, s.losses());
        assertEquals(0, s.winstreak());
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(0, s.losses());
        assertEquals(2, s.wins());
        assertEquals(9, s.winstreak());
        feed(s, "GAME OVER!", "VICTORY!"); // nothing moves it again
        assertEquals(9, s.winstreak());
    }

    @Test
    public void manualResetKeepsTheStreakLeavingHypixelClearsIt() {
        SessionStats s = new SessionStats();
        s.onTick(true, 1_000L);
        s.seedWinstreak(4);
        play(s, 1);
        feed(s, "VICTORY!", "BED DESTRUCTION > Your Bed was destroyed by Steve!");
        assertEquals(5, s.winstreak());
        assertEquals(1, s.bedsLost());
        s.reset();
        assertEquals(5, s.winstreak());
        assertEquals(0, s.bedsLost());
        assertEquals(0, s.games());
        s.onTick(true, 2_000L);
        s.onTick(false, 3_000L);
        assertEquals(0, s.winstreak());
    }

    // A3: a Solo win and a Doubles win both count, whichever order the title and killer rows arrive in.

    /**
     * Solo: no roll-call line, only the VICTORY! title (HM WIN_VICTORY / BedwarsGameEndListener.java:38)
     * and the killer row (BM GAME_END). This is the shape Lunar's mod misses.
     */
    @Test
    public void soloWinTitleThenKillerRow() {
        SessionStats s = new SessionStats();
        play(s, 1);
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(1, s.wins()); // the title alone ends the game (zero-kill games print no killer row)
        feed(s, "                        1st Killer - [MVP+] Self - 5");
        assertEquals(1, s.wins());
        assertEquals(0, s.losses());
    }

    /**
     * Doubles: a team game ends with the same VICTORY! title (HM WIN_VICTORY; BedwarsGameEndListener.java
     * broadcasts it per team) and the killer row (BM GAME_END); Hypixel also lists the winning team
     * ("Winners: …", HM WIN_WINNERS_PREFIX). Any one of the three win signals plus the row counts once.
     */
    @Test
    public void doublesWinTitleRowAndRollCall() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "Steve was killed by Self.", "TEAM ELIMINATED > Red Team has been eliminated!");
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        feed(s, "Winners: Alex, Self", "                        1st Killer - [MVP+] Alex - 6");
        assertEquals(1, s.wins());
        assertEquals(0, s.losses());
        assertEquals(1, s.kills());
    }

    /** Doubles loss: partner and self eliminated, GAME OVER! title, killer row. */
    @Test
    public void doublesLoss() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "Alex was killed by Steve. FINAL KILL!", "Self fell into the void. FINAL KILL!",
                "You have been eliminated!", "TEAM ELIMINATED > Blue Team has been eliminated!");
        s.onEvent(SessionChatLine.parseTitle("GAME OVER!"));
        feed(s, "1st Killer - Steve - 4");
        assertEquals(0, s.wins());
        assertEquals(1, s.losses());
        assertEquals(1, s.finalDeaths());
    }

    @Test
    public void teamWinKillerRowThenWinnersLine() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "                        1st Killer - Alex - 5");
        assertEquals(0, s.wins());
        feed(s, "Winners: Alex, Self");
        assertEquals(1, s.wins());
        // Extra signals after settling never double count.
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        feed(s, "VICTORY!", "1st Killer - Alex - 5");
        assertEquals(1, s.wins());
    }

    /** Zero-kill game: Hypixel prints no "1st Killer" row (BedwarsGameEndListener.java:50-54), only the title. */
    @Test
    public void zeroKillGamesSettleOnTheTitleAlone() {
        SessionStats s = new SessionStats();
        play(s, 1);
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(1, s.wins());
        assertEquals(GameStart.NEW, play(s, 2));
        s.onEvent(SessionChatLine.parseTitle("GAME OVER!"));
        assertEquals(1, s.losses());
        assertEquals(GameStart.NEW, play(s, 3));
        // A mid-game elimination alone never settles: the game is still running.
        feed(s, "You have been eliminated!");
        assertEquals(1, s.losses());
        assertEquals(SessionStats.Outcome.LOSS, s.outcome());
        // Changed 2026-10-01: leaving that game before its end is a loss (Hypixel records one), counted
        // when the next game opens. It used to be dropped.
        assertEquals(GameStart.NEW_AFTER_ABANDONED, play(s, 4));
        assertEquals(2, s.losses());
    }

    @Test
    public void lossCountsOnce() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "You have been eliminated!");
        s.onEvent(SessionChatLine.parseTitle("GAME OVER!"));
        feed(s, "1st Killer - Alex - 9");
        assertEquals(1, s.losses());
        assertEquals(0, s.wins());
    }

    @Test
    public void eliminatedThenTeamComebackIsAWin() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "Self was killed by Alex. FINAL KILL!", "You have been eliminated!");
        assertEquals(SessionStats.Outcome.LOSS, s.outcome());
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(SessionStats.Outcome.WIN, s.outcome());
        feed(s, "GAME OVER!"); // a late loss signal never demotes a win
        assertEquals(SessionStats.Outcome.WIN, s.outcome());
        feed(s, "1st Killer - Alex - 9");
        assertEquals(1, s.wins());
        assertEquals(0, s.losses());
    }

    @Test
    public void lateVictoryMovesASettledLossToAWin() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "You have been eliminated!", "1st Killer - Alex - 9");
        assertEquals(1, s.losses());
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(0, s.losses());
        assertEquals(1, s.wins());
        assertEquals(SessionStats.Outcome.WIN, s.outcome());
        // Nothing further moves it again.
        feed(s, "GAME OVER!", "VICTORY!", "1st Killer - Alex - 9");
        assertEquals(0, s.losses());
        assertEquals(1, s.wins());
    }

    @Test
    public void unresolvedEndCountsNothingAndIsReported() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "1st Killer - Alex - 9");
        assertEquals(0, s.wins() + s.losses());
        assertEquals(GameStart.NEW_AFTER_UNRESOLVED, play(s, 2));
        assertEquals(0, s.wins() + s.losses());
        // Changed 2026-10-01: game 2 was entered and left before any end, so it is now a loss.
        assertEquals(GameStart.NEW_AFTER_ABANDONED, play(s, 3));
        assertEquals(0, s.wins());
        assertEquals(1, s.losses());
    }

    // A4: game block resets per game, session block accumulates.

    @Test
    public void gameBlockResetsPerGame() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "Steve was killed by Self.", "Steve was killed by Self. FINAL KILL!",
                "BED DESTRUCTION > Red Bed was destroyed by Self!", "VICTORY!", "1st Killer - Self - 2");
        play(s, 2);
        assertEquals(0, s.gameKills());
        assertEquals(0, s.gameFinals());
        assertEquals(0, s.gameBeds());
        assertEquals(SessionStats.Outcome.UNKNOWN, s.outcome());
        assertEquals(1, s.kills());
        assertEquals(1, s.finalKills());
        assertEquals(1, s.beds());
        assertEquals(1, s.wins());
        feed(s, "Steve was killed by Self.");
        assertEquals(1, s.gameKills());
        assertEquals(2, s.kills());
    }

    // A5: session resets off Hypixel and on manual reset; survives a lobby hop.

    @Test
    public void clockStartsOnFirstHypixelTick() {
        SessionStats s = new SessionStats();
        assertEquals("00:00", s.elapsed(5_000L));
        s.onTick(false, 1_000L);
        assertEquals(-1L, s.sessionStartMs());
        s.onTick(true, 10_000L);
        assertEquals(10_000L, s.sessionStartMs());
        assertEquals("00:05", s.elapsed(15_000L));
        assertEquals("12:34", s.elapsed(10_000L + (12 * 60 + 34) * 1000L));
        assertEquals("1:02:03", s.elapsed(10_000L + (3600 + 120 + 3) * 1000L));
    }

    @Test
    public void leavingHypixelResetsEverything() {
        SessionStats s = new SessionStats();
        s.onTick(true, 1_000L);
        play(s, 1);
        feed(s, "Steve was killed by Self.", "VICTORY!", "1st Killer - Self - 1");
        assertEquals(1, s.wins());
        // Lobby hop: still on Hypixel, nothing changes.
        s.onTick(true, 2_000L);
        assertEquals(1, s.wins());
        assertEquals(1_000L, s.sessionStartMs());
        // Disconnect / quit to title.
        assertFalse(s.onTick(false, 3_000L));
        assertEquals(0, s.wins());
        assertEquals(0, s.kills());
        assertEquals(-1L, s.sessionStartMs());
        assertEquals("00:00", s.elapsed(4_000L));
        // Idle off-Hypixel ticks do nothing more; rejoining starts a fresh clock.
        s.onTick(false, 5_000L);
        s.onTick(true, 9_000L);
        assertEquals(9_000L, s.sessionStartMs());
    }

    @Test
    public void manualReset() {
        SessionStats s = new SessionStats();
        s.onTick(true, 1_000L);
        play(s, 1);
        feed(s, "Steve was killed by Self.", "1st Killer - Alex - 4");
        assertTrue(s.reset()); // that game ended unresolved
        assertEquals(0, s.gameKills());
        assertEquals(0, s.kills());
        assertEquals(-1L, s.sessionStartMs());
        s.onTick(true, 20_000L);
        assertEquals(20_000L, s.sessionStartMs());
        assertFalse(s.reset());
    }

    /** "You are now nicked as X!" / "Your nick has been reset!" (Lunar NicknameListener), memory only. */
    @Test
    public void chatNickMemory() {
        SessionStats s = new SessionStats();
        assertNull(s.chatNick());
        s.onNickChange(null);
        assertNull(s.chatNick());
        s.onNickChange("Nicky");
        assertEquals("Nicky", s.chatNick());
        s.reset(); // a manual reset is about the tally, not the player's name
        assertEquals("Nicky", s.chatNick());
        s.onNickChange("");
        assertNull(s.chatNick());
        s.onNickChange("Nicky");
        s.onTick(true, 1_000L);
        s.onTick(false, 2_000L); // leaving Hypixel
        assertNull(s.chatNick());
    }

    // ---- leaving early, rejoining, own team eliminated (2026-10-01) ----

    /** Final death then Play Again: the usual way a solo game is left, and Hytils AutoQueue's. */
    @Test
    public void leftAfterFinalDeathCountsOneLossAtTheNextGame() {
        SessionStats s = new SessionStats();
        s.seedWinstreak(5);
        play(s, 1);
        feed(s, "Self fell into the void. FINAL KILL!", "You have been eliminated!");
        s.onGameTick(lobby(2, 2));
        assertEquals(0, s.games()); // still open: the player could /rejoin
        assertEquals(GameStart.NEW_AFTER_ABANDONED, play(s, 3));
        assertEquals(1, s.losses());
        assertEquals(1, s.games());
        assertEquals(0, s.winstreak());
        assertEquals(1, s.finalDeaths());
        assertEquals(0, s.gameKills());
    }

    /** Hypixel also records a loss for leaving while the bed still stands. */
    @Test
    public void forfeitWithTheBedAliveCountsALoss() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "Steve was killed by Self.");
        assertEquals(GameStart.NEW_AFTER_ABANDONED, play(s, 2));
        assertEquals(1, s.losses());
        assertEquals(1, s.kills());
    }

    @Test
    public void aGameNeverEnteredCountsNothing() {
        SessionStats s = new SessionStats();
        play(s, 1);
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        // A pregame queue left before the start, then a lobby.
        s.onGameTick(queue(2, 2));
        s.onGameTick(lobby(3, 3));
        // A game block that opened but never saw armour (a spectator, or a mode without armour).
        s.onGameTick(queue(4, 4));
        assertEquals(GameStart.NEW, s.onGameTick(active(4, 4, false)));
        assertEquals(GameStart.NEW, play(s, 5));
        assertEquals(1, s.wins());
        assertEquals(0, s.losses());
    }

    @Test
    public void rejoinKeepsTheGameAndCountsItOnce() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "Steve was killed by Self.");
        s.onGameTick(lobby(2, 2));
        assertEquals(GameStart.REJOINED, s.onGameTick(active(3, 3, true)));
        assertEquals(1, s.gameKills());
        assertEquals(3, s.gameSessionId());
        assertEquals(3, s.gameWorld());
        assertEquals(GameStart.NONE, s.onGameTick(active(3, 3, true)));
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(GameStart.NEW, play(s, 4));
        assertEquals(1, s.wins());
        assertEquals(0, s.losses());
    }

    /** Plan review round 1 I1: a final-killed player comes back as a spectator, without armour. */
    @Test
    public void spectatorRejoinRebindsTheGame() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "Self was killed by Alex. FINAL KILL!", "You have been eliminated!");
        s.onGameTick(lobby(2, 2));
        assertEquals(GameStart.REJOINED, s.onGameTick(active(3, 3, false)));
        assertEquals(3, s.gameSessionId());
        s.onEvent(SessionChatLine.parseTitle("VICTORY!")); // the team came back
        assertEquals(1, s.wins());
        assertEquals(0, s.losses());
    }

    /**
     * Plan review round 2 B1: the queue and the game share a world, and GameSessionTracker raises its
     * id on the active edge. The queue still marks a new game.
     */
    @Test
    public void sameWorldQueueThenGameWithANewTrackerIdIsANewGame() {
        SessionStats s = new SessionStats();
        s.onGameTick(queue(7, 3));
        s.onGameTick(active(7, 3, true));
        feed(s, "Steve was killed by Self.");
        s.onGameTick(lobby(7, 3));
        s.onGameTick(queue(7, 3));
        assertEquals(GameStart.NEW_AFTER_ABANDONED, s.onGameTick(active(8, 3, true)));
        assertEquals(1, s.losses());
        assertEquals(8, s.gameSessionId());
        assertEquals(0, s.gameKills());
    }

    /**
     * In the open game's world the GameSessionTracker id can move (it lags a queue-opened game by a
     * tick and rises again on an objective flicker). Only a pregame queue starts another game there.
     */
    @Test
    public void aTrackerIdChangeInTheSameWorldIsTheSameGame() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "Steve was killed by Self.");
        assertEquals(GameStart.NONE, s.onGameTick(active(2, 1, true)));
        assertEquals(2, s.gameSessionId());
        assertEquals(1, s.gameKills());
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        s.onGameTick(lobby(2, 1));
        assertEquals(GameStart.NONE, s.onGameTick(active(3, 1, true))); // post-game flicker
        assertEquals(1, s.gameKills());
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(1, s.wins());
        assertEquals(GameStart.NEW, play(s, 4));
        assertEquals(0, s.losses());
    }

    /** Code review round 1 B1: the tracker catches up a tick after a queue-opened game settled. */
    @Test
    public void trackerCatchUpAfterAQueueOpenedGameCountsItOnce() {
        SessionStats s = new SessionStats();
        s.onGameTick(queue(7, 1));
        s.onGameTick(active(7, 1, true));
        feed(s, "Steve was killed by Self.");
        s.onGameTick(lobby(7, 1));
        s.onGameTick(queue(7, 1));
        // The new game's winner row arrives before the tracker's tick, so its sample still says id 7.
        assertEquals(GameStart.NEW_AFTER_ABANDONED,
                s.onEvent(active(7, 1, true), SessionChatLine.parse("     Blue - Self, Alex", SELF)));
        assertEquals(GameStart.NONE, s.onGameTick(active(8, 1, true))); // the tracker catches up
        assertEquals(8, s.gameSessionId());
        s.onEvent(active(8, 1, true), SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(1, s.wins());
        assertEquals(1, s.losses());
        assertEquals(2, s.games());
    }

    /** Code review round 1 I1: leaving Hypixel right after a manual reset still ends the session. */
    @Test
    public void leavingHypixelRightAfterAResetStillEndsTheSession() {
        SessionStats s = new SessionStats();
        s.onTick(true, 1_000L);
        s.seedWinstreak(4);
        play(s, 1);
        s.onGameTick(activeAs(1, 1, "Red"));
        s.onNickChange("Nicky");
        s.reset();
        s.onTick(false, 2_000L); // disconnect before any further tick on Hypixel
        assertEquals(Integer.MIN_VALUE, s.gameSessionId());
        assertNull(s.chatNick());
        assertNull(s.ownTeam());
        assertEquals(0, s.winstreak());
        s.onTick(true, 3_000L);
        s.onGameTick(lobby(2, 2));
        assertEquals(GameStart.NEW, s.onGameTick(active(3, 3, true))); // nothing to rejoin
    }

    /**
     * Code review round 1 I2: the team comes with every sample, so it is known before the first event
     * of a game is applied, even when that event is the player's own final death.
     */
    @Test
    public void theTeamIsKnownBeforeAnEarlyFinalDeath() {
        SessionStats s = new SessionStats();
        s.onGameTick(queue(1, 1));
        s.onEvent(activeAs(1, 1, "Red"), SessionChatLine.parse("Self fell into the void. FINAL KILL!", SELF));
        assertEquals("Red", s.ownTeam());
        s.onGameTick(new Sample(1, 1, true, false, true, "Gray")); // latched once out
        assertEquals("Red", s.ownTeam());
        assertTrue(s.onTeamEliminated(active(1, 1, false), "Red"));
        assertEquals(1, s.losses());

        SessionStats t = new SessionStats();
        t.onGameTick(queue(1, 1));
        assertTrue(t.onTeamEliminated(activeAs(1, 1, "Blue"), "Blue")); // before any periodic sample
        assertEquals(1, t.losses());

        SessionStats u = new SessionStats();
        u.onGameTick(queue(1, 1));
        u.onGameTick(new Sample(1, 1, true, false, false, "Gray")); // unarmoured: a spectator
        assertNull(u.ownTeam());
    }

    /**
     * Plan review round 2 B2: the new game's first line can arrive before any periodic sample. The
     * event's own sample opens the new game first, so the line is not credited to the previous one.
     */
    @Test
    public void aLineFromANewGameOpensItBeforeItCounts() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "Steve was killed by Self.");
        s.onGameTick(queue(2, 2));
        // Every opponent left at once: the winner row is the first thing the client sees.
        GameStart start = s.onEvent(active(2, 2, true), SessionChatLine.parse("     Blue - Self, Alex", SELF));
        assertEquals(GameStart.NEW_AFTER_ABANDONED, start);
        assertEquals(1, s.losses()); // game 1, left early
        assertEquals(1, s.wins());   // game 2
        s.onEvent(active(2, 2, true), SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(1, s.wins());
        assertEquals(2, s.games());

        SessionStats k = new SessionStats();
        play(k, 1);
        k.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        k.onGameTick(queue(2, 2));
        k.onEvent(active(2, 2, true), SessionChatLine.parse("Steve was killed by Self.", SELF));
        assertEquals(1, k.gameKills()); // in the new game's block
        assertEquals(2, k.gameSessionId());
    }

    /** Plan review round 2 B3: a manual reset must never re-open a game that was already decided. */
    @Test
    public void resetAfterADecidedGameNeverReopensIt() {
        SessionStats s = new SessionStats();
        s.seedWinstreak(3);
        play(s, 1);
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(4, s.winstreak());
        s.reset();
        assertEquals(GameStart.NONE, s.onGameTick(active(1, 1, true))); // still there, armoured
        s.onGameTick(lobby(2, 2));
        assertEquals(GameStart.NEW, s.onGameTick(active(3, 3, true))); // no queue, but game 1 is over
        assertEquals(0, s.losses());
        assertEquals(4, s.winstreak());
    }

    @Test
    public void resetMidGameKeepsTheGameOpen() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "Steve was killed by Self.");
        s.reset();
        assertEquals(0, s.kills());
        assertEquals(GameStart.NEW_AFTER_ABANDONED, play(s, 2));
        assertEquals(1, s.losses()); // the abandoned game counts in the new tally
    }

    @Test
    public void lateVictoryAfterAResetNeverMakesLossesNegative() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "You have been eliminated!", "1st Killer - Alex - 9");
        assertEquals(1, s.losses());
        s.reset();
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(0, s.losses());
        assertEquals(0, s.wins());
    }

    @Test
    public void anUnresolvedEndIsReportedOnce() {
        SessionStats s = new SessionStats();
        play(s, 1);
        feed(s, "1st Killer - Alex - 9");
        assertTrue(s.reset());
        assertEquals(GameStart.NEW, play(s, 2));
        assertEquals(0, s.games());
    }

    @Test
    public void ownTeamEliminatedSettlesTheLossAtOnce() {
        SessionStats s = new SessionStats();
        s.seedWinstreak(2);
        play(s, 1);
        s.observeOwnTeam("Red");
        assertFalse(s.onTeamEliminated(active(1, 1, true), "Blue"));
        assertEquals(0, s.losses());
        assertTrue(s.onTeamEliminated(active(1, 1, false), "Red"));
        assertEquals(1, s.losses());
        assertEquals(1, s.games());
        assertEquals(0, s.winstreak());
        s.onEvent(SessionChatLine.parseTitle("GAME OVER!"));
        feed(s, "1st Killer - Alex - 9", "     Blue - Alex, Steve");
        assertEquals(1, s.losses());
        assertEquals(GameStart.NEW, play(s, 2)); // decided, so leaving it adds nothing
        assertEquals(1, s.losses());
    }

    @Test
    public void yourTeamEliminatedNeedsNoColour() {
        SessionStats s = new SessionStats();
        play(s, 1);
        assertTrue(s.onTeamEliminated(active(1, 1, true), "Your"));
        assertEquals(1, s.losses());
        assertFalse(s.onTeamEliminated(active(1, 1, true), null));
    }

    /** Plan review round 1 I2: once the player is out, a spectator team colour must not replace theirs. */
    @Test
    public void theTeamIsLatchedOnceThePlayerIsOut() {
        SessionStats a = new SessionStats();
        play(a, 1);
        a.observeOwnTeam("Red");
        a.onEvent(SessionChatLine.parse("Self fell into the void. FINAL KILL!", SELF));
        a.observeOwnTeam("Gray");
        assertEquals("Red", a.ownTeam());
        assertFalse(a.onTeamEliminated(active(1, 1, false), "Gray"));
        assertTrue(a.onTeamEliminated(active(1, 1, false), "Red"));

        SessionStats b = new SessionStats();
        play(b, 1);
        b.observeOwnTeam("Red");
        b.onEvent(Kind.ELIMINATED);
        b.observeOwnTeam("Gray");
        assertEquals("Red", b.ownTeam());

        SessionStats c = new SessionStats();
        play(c, 1);
        c.observeOwnTeam("Red");
        assertTrue(c.onTeamEliminated(active(1, 1, true), "Red"));
        c.observeOwnTeam("Blue");
        assertEquals("Red", c.ownTeam());

        play(c, 2);
        assertNull(c.ownTeam()); // a new game starts unlatched
        c.observeOwnTeam("Blue");
        assertEquals("Blue", c.ownTeam());
    }

    @Test
    public void ownTeamLossSurvivesASpectatorRejoin() {
        SessionStats s = new SessionStats();
        play(s, 1);
        s.observeOwnTeam("Red");
        s.onTeamEliminated(active(1, 1, false), "Red");
        s.onGameTick(lobby(2, 2));
        assertEquals(GameStart.REJOINED, s.onGameTick(active(3, 3, false)));
        s.onEvent(SessionChatLine.parseTitle("GAME OVER!"));
        assertEquals(1, s.losses());
        assertEquals(GameStart.NEW, play(s, 4));
        assertEquals(1, s.losses());
    }

    @Test
    public void aMisreadTeamLossIsCorrectedByVictory() {
        SessionStats s = new SessionStats();
        play(s, 1);
        s.observeOwnTeam("Red");
        s.onTeamEliminated(active(1, 1, true), "Red");
        assertEquals(1, s.losses());
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(0, s.losses());
        assertEquals(1, s.wins());
    }

    @Test
    public void leavingHypixelDropsTheOpenGame() {
        SessionStats s = new SessionStats();
        s.onTick(true, 1_000L);
        play(s, 1);
        feed(s, "Steve was killed by Self.");
        s.onTick(false, 2_000L);
        assertEquals(Integer.MIN_VALUE, s.gameSessionId());
        s.onTick(true, 3_000L);
        s.onGameTick(lobby(2, 2));
        // A /rejoin after reconnecting opens a fresh block in the new session.
        assertEquals(GameStart.NEW, s.onGameTick(active(3, 3, true)));
        assertEquals(0, s.losses());
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(1, s.wins());
    }
}
