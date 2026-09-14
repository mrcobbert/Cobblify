package com.bedwarsqol.feature;

import org.junit.Test;

import static com.bedwarsqol.feature.SessionChatLine.Kind;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionStatsTest {

    private static final String SELF = "Self";

    private static void feed(SessionStats s, String... lines) {
        for (String l : lines) s.onEvent(SessionChatLine.parse(l, SELF));
    }

    @Test
    public void countersAndRatios() {
        SessionStats s = new SessionStats();
        s.onGameStart(1);
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
        s.onGameStart(1);
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
        s.onGameStart(2);
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
        s.onGameStart(1);
        feed(s, "VICTORY!");
        assertEquals(1, s.winstreak());
        s.onGameStart(2);
        feed(s, "VICTORY!", "1st Killer - Self - 2");
        assertEquals(2, s.winstreak()); // the killer row after a settled title adds nothing
        s.onGameStart(3);
        feed(s, "You have been eliminated!");
        assertEquals(2, s.winstreak()); // provisional loss does not settle
        feed(s, "GAME OVER!");
        assertEquals(0, s.winstreak());
        s.onGameStart(4);
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
        s.onGameStart(1);
        feed(s, "VICTORY!");
        assertEquals(8, s.winstreak());
        // Loss settled by the killer row, then the team's comeback title: the streak is restored.
        s.onGameStart(2);
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
        s.onGameStart(1);
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
        s.onGameStart(1);
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
        s.onGameStart(1);
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
        s.onGameStart(1);
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
        s.onGameStart(1);
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
        s.onGameStart(1);
        s.onEvent(SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(1, s.wins());
        assertFalse(s.onGameStart(2));
        s.onEvent(SessionChatLine.parseTitle("GAME OVER!"));
        assertEquals(1, s.losses());
        assertFalse(s.onGameStart(3));
        // A mid-game elimination alone never settles: the game is still running.
        feed(s, "You have been eliminated!");
        assertEquals(1, s.losses());
        assertEquals(SessionStats.Outcome.LOSS, s.outcome());
        assertFalse(s.onGameStart(4)); // provisional outcome without an end is not "unresolved"
    }

    @Test
    public void lossCountsOnce() {
        SessionStats s = new SessionStats();
        s.onGameStart(1);
        feed(s, "You have been eliminated!");
        s.onEvent(SessionChatLine.parseTitle("GAME OVER!"));
        feed(s, "1st Killer - Alex - 9");
        assertEquals(1, s.losses());
        assertEquals(0, s.wins());
    }

    @Test
    public void eliminatedThenTeamComebackIsAWin() {
        SessionStats s = new SessionStats();
        s.onGameStart(1);
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
        s.onGameStart(1);
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
        s.onGameStart(1);
        feed(s, "1st Killer - Alex - 9");
        assertEquals(0, s.wins() + s.losses());
        assertTrue(s.onGameStart(2));
        assertFalse(s.onGameStart(3));
        assertEquals(0, s.wins() + s.losses());
    }

    // A4: game block resets per game, session block accumulates.

    @Test
    public void gameBlockResetsPerGame() {
        SessionStats s = new SessionStats();
        s.onGameStart(1);
        feed(s, "Steve was killed by Self.", "Steve was killed by Self. FINAL KILL!",
                "BED DESTRUCTION > Red Bed was destroyed by Self!", "VICTORY!", "1st Killer - Self - 2");
        s.onGameStart(2);
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
        s.onGameStart(1);
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
        s.onGameStart(1);
        feed(s, "Steve was killed by Self.", "1st Killer - Alex - 4");
        assertTrue(s.reset()); // that game ended unresolved
        assertEquals(0, s.gameKills());
        assertEquals(0, s.kills());
        assertEquals(-1L, s.sessionStartMs());
        s.onTick(true, 20_000L);
        assertEquals(20_000L, s.sessionStartMs());
        assertFalse(s.reset());
    }
}
