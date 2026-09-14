package com.bedwarsqol.feature;

import org.junit.Test;

import static com.bedwarsqol.feature.SessionChatLine.Kind;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Chat shapes come from upstream sources, cited per fixture: AxolotlClient {@code BedwarsMessages}
 * (BM:n = template line in the copy saved in the cycle folder), {@code 1mshy/bedwars
 * HypixelMessages} (HM), {@code crow-1.8.9 AutoGG} (CR). None were observed on live Hypixel here.
 */
public class SessionChatLineTest {

    private static final String SELF = "Self";

    private static Kind parse(String line) {
        return SessionChatLine.parse(line, SELF);
    }

    // ---- own kills ----

    @Test
    public void plainKill() {
        assertEquals(Kind.KILL, parse("Steve was killed by Self."));                      // BM:70
        assertEquals(Kind.KILL, parse("Steve was shot by Self."));                        // BM:128
        assertEquals(Kind.KILL, parse("Steve was struck down by Self."));                 // BM:38
    }

    @Test
    public void finalKillSuffix() {
        assertEquals(Kind.FINAL_KILL, parse("Steve was knocked into the void by Self. FINAL KILL!")); // BM:101
        assertEquals(Kind.FINAL_KILL, parse("Steve was killed by Self. FINAL KILL!"));
    }

    @Test
    public void killShapesWithNonByPrepositions() {
        assertEquals(Kind.KILL, parse("Steve hit the ground too hard whilst trying to escape Self."));
        assertEquals(Kind.KILL, parse("Steve had a small brain moment while fighting Self."));   // BM:67
        assertEquals(Kind.KILL, parse("Steve was not able to block clutch against Self."));      // BM:102
        assertEquals(Kind.KILL, parse("Steve howled into the void for Self."));                   // BM:81
        assertEquals(Kind.KILL, parse("Steve was hit with a snowball from Self."));               // BM:125
        assertEquals(Kind.KILL, parse("Steve took the L to Self."));                              // BM:89
        assertEquals(Kind.KILL, parse("Steve fought to the edge with Self."));                    // BM:78
        assertEquals(Kind.KILL, parse("Steve hit the hard-wood floor because of Self."));         // BM:82
        assertEquals(Kind.KILL, parse("Steve was too shy to meet Self."));                        // BM:68
    }

    @Test
    public void envelopeVariations() {
        assertEquals(Kind.KILL, parse("Steve's heart was pierced by Self."));                    // BM:130 possessive victim
        assertEquals(Kind.FINAL_KILL, parse("Steve's heart was pierced by Self. FINAL KILL!"));
        assertEquals(Kind.KILL, parse("Steve was shot into limbo by Self"));                      // BM:141 no period
        assertEquals(Kind.FINAL_KILL, parse("Steve was shot into limbo by Self FINAL KILL!"));
        assertEquals(Kind.DEATH, SessionChatLine.parse("Self's heart was pierced by Steve.", SELF));
    }

    @Test
    public void possessiveKillShapes() {
        assertEquals(Kind.KILL, parse("Steve was fried by Self's Golem."));                       // BM:181
        assertEquals(Kind.KILL, parse("Steve was pushed by Self's holiday spirit."));             // BM:169
        assertEquals(Kind.KILL, parse("Steve was Self's final #12."));                            // BM:60
        assertEquals(Kind.KILL, parse("Steve was Self's final #12"));                             // BM:56 (no period)
        assertEquals(Kind.FINAL_KILL, parse("Steve was banished into the ether by Self's holiday spirit. FINAL KILL!")); // BM:99
    }

    // ---- own deaths ----

    @Test
    public void ownFinalDeath() {
        assertEquals(Kind.FINAL_DEATH, parse("Self fell into the void. FINAL KILL!"));            // BM SELF_VOID
        assertEquals(Kind.FINAL_DEATH, parse("Self was killed by Steve. FINAL KILL!"));
        assertEquals(Kind.FINAL_DEATH, parse("Self was fried by Steve's Golem. FINAL KILL!"));
    }

    @Test
    public void ownRegularDeath() {
        assertEquals(Kind.DEATH, parse("Self was shot by Steve."));
        assertEquals(Kind.DEATH, parse("Self died."));                                            // BM SELF_UNKNOWN
        assertEquals(Kind.DEATH, parse("Self fell into the void."));
        assertEquals(Kind.DEATH, parse("Self hit the ground too hard."));
        assertEquals(Kind.DEATH, parse("Self was killed by Self."));                               // documents: victim wins
    }

    @Test
    public void ownPresenceLinesAreNotDeaths() {
        assertNull(parse("Self disconnected."));
        assertNull(parse("Self reconnected."));
    }

    // ---- beds ----

    @Test
    public void ownBedBreaks() {
        assertEquals(Kind.BED_BREAK, parse("BED DESTRUCTION > Red Bed was destroyed by Self!"));           // BM:242
        assertEquals(Kind.BED_BREAK, parse("BED DESTRUCTION > Blue Bed was melted by Self's holiday spirit!")); // BM:236
        assertEquals(Kind.BED_BREAK, parse("BED DESTRUCTION > Green Bed was bed #4 destroyed by Self!"));  // BM:230
        assertEquals(Kind.BED_BREAK, parse("BED DESTRUCTION > Aqua Bed has left the game after seeing Self!")); // BM:238
    }

    /**
     * The losing team sees its own colour word replaced by "Your" (Open-Meowtils BedTracker.java:110;
     * Raven-Scripts session.java:304-308, copy in the cycle folder). No self name is needed.
     */
    @Test
    public void ownTeamBedLost() {
        assertEquals(Kind.BED_LOST, parse("BED DESTRUCTION > Your Bed was destroyed by Steve!"));
        assertEquals(Kind.BED_LOST, parse("BED DESTRUCTION > Your Bed was melted by Steve's holiday spirit!"));
        assertEquals(Kind.BED_LOST, parse("BED DESTRUCTION > Your Bed has left the game after seeing Steve!"));
        assertEquals(Kind.BED_LOST, parse("BED DESTRUCTION > Your Bed was bed #4 destroyed by Steve!"));
        assertEquals(Kind.BED_LOST, SessionChatLine.parse("BED DESTRUCTION > Your Bed was destroyed by Steve!", null));
        assertNull(parse("[MVP+] Steve: BED DESTRUCTION > Your Bed was destroyed by Steve!"));
        assertNull(parse("Party > Alex: BED DESTRUCTION > Your Bed was destroyed by Steve!"));
        assertNull(parse("Your bed has been destroyed!")); // not the BED DESTRUCTION shape
    }

    @Test
    public void winstreakHologram() {
        assertEquals(1204, SessionChatLine.parseWinstreakHologram("Current Winstreak: 1,204"));
        assertEquals(3, SessionChatLine.parseWinstreakHologram("Current Winstreak: 3"));
        assertEquals(0, SessionChatLine.parseWinstreakHologram("  Current Winstreak: 0  "));
        assertEquals(-1, SessionChatLine.parseWinstreakHologram("Total Wins: 1,204"));
        assertEquals(-1, SessionChatLine.parseWinstreakHologram("Winstreak"));
        assertEquals(-1, SessionChatLine.parseWinstreakHologram("Current Winstreak: 1,2"));
        assertEquals(-1, SessionChatLine.parseWinstreakHologram("Current Winstreak: 9,999,999,999")); // I1: overflow, no throw
        assertEquals(-1, SessionChatLine.parseWinstreakHologram("Current Winstreak: 1234567890"));
        assertEquals(-1, SessionChatLine.parseWinstreakHologram(""));
        assertEquals(-1, SessionChatLine.parseWinstreakHologram(null));
    }

    @Test
    public void otherPlayersBedsAreIgnored() {
        assertNull(parse("BED DESTRUCTION > Red Bed was destroyed by Steve!"));
        assertNull(parse("BED DESTRUCTION > Red Bed was destroyed by Selfish!"));
        // Breaker position, not incidental words in the cosmetic.
        assertNull(SessionChatLine.parse("BED DESTRUCTION > Blue Bed was melted by Alex's holiday spirit!", "holiday"));
        assertNull(SessionChatLine.parse("BED DESTRUCTION > Aqua Bed has left the game after seeing Alex!", "game"));
        assertNull(SessionChatLine.parse("BED DESTRUCTION > Red Bed was destroyed by Alex!", "Bed"));
    }

    // ---- outcome ----

    @Test
    public void winLinesSoloAndTeam() {
        // Solo: the same VICTORY! header a solo winner sees (HM WIN_VICTORY); Lunar's solo-win miss must not recur.
        assertEquals(Kind.WIN, parse("VICTORY!"));
        assertEquals(Kind.WIN, parse("You won!"));                                                // HM WIN_YOU_WON
        // Team roll-call (HM WIN_WINNERS_PREFIX / CR "Winners -")
        assertEquals(Kind.WIN, parse("Winners: Self, Alex"));
        assertEquals(Kind.WIN, parse("Winners - [MVP+] Alex, Self"));
        assertEquals(Kind.WIN, parse("Winner: Self"));
        assertNull(parse("Winners: Alex, Selfish"));
    }

    @Test
    public void lossLines() {
        assertEquals(Kind.LOSS, parse("GAME OVER!"));                                             // HM LOSS_GAME_OVER
        assertEquals(Kind.ELIMINATED, parse("You have been eliminated!"));                        // HM LOSS_ELIMINATED (mid-game)
    }

    @Test
    public void titles() {
        assertEquals(Kind.WIN, SessionChatLine.parseTitle("VICTORY!"));
        assertEquals(Kind.LOSS, SessionChatLine.parseTitle("GAME OVER!"));
        assertEquals(Kind.WIN, SessionChatLine.parseTitle("  VICTORY!  "));
        assertNull(SessionChatLine.parseTitle("Bed Wars"));
        assertNull(SessionChatLine.parseTitle(null));
    }

    @Test
    public void gameEndRow() {
        assertEquals(Kind.GAME_END, parse("                        1st Killer - [MVP+] Steve - 7")); // BM GAME_END
        assertEquals(Kind.GAME_END, parse("1st Killer - Steve - 3 Kills"));
        assertNull(parse("2nd Killer - Steve - 3"));
    }

    // ---- negatives ----

    @Test
    public void playerTypedLinesNeverMatch() {
        assertNull(parse("[MVP+] Steve: Self was killed by Steve."));
        assertNull(parse("Party > Self: VICTORY!"));
        assertNull(parse("[SHOUT] [RED] Steve: GAME OVER!"));
        assertNull(parse("Guild > Self: BED DESTRUCTION > Red Bed was destroyed by Self!"));
    }

    @Test
    public void otherPlayersKillsAreIgnored() {
        assertNull(parse("Steve was killed by Alex."));
        assertNull(parse("Steve was killed by Alex. FINAL KILL!"));
        assertNull(parse("Steve was killed by Selfish."));           // token, not prefix
        assertNull(parse("Steve was killed by Self2."));
    }

    @Test
    public void killerPositionProtectsUnluckyNames() {
        assertNull(SessionChatLine.parse("Steve was fried by Alex's Golem.", "Golem"));
        assertNull(SessionChatLine.parse("Steve was pushed by Alex's holiday spirit.", "holiday"));
        assertNull(SessionChatLine.parse("Steve was buzzed to death by Alex.", "death"));
        assertNull(SessionChatLine.parse("Steve howled into the void for Alex.", "void"));
    }

    @Test
    public void systemLinesThatAreNotEvents() {
        assertNull(parse("TEAM ELIMINATED > Red Team has been eliminated!"));
        assertNull(parse("Steve has joined (1/8)!"));
        assertNull(parse("Protect your bed and destroy the enemy beds."));
        assertNull(parse("+25 coins! (Win)"));
        assertNull(parse(""));
        assertNull(parse(null));
        assertNull(SessionChatLine.parse("Steve was killed by Self.", null));
    }

    @Test
    public void killerExtraction() {
        assertEquals("Self", SessionChatLine.killerOf("was killed by Self"));
        assertEquals("Self", SessionChatLine.killerOf("was fried by Self's Golem"));
        assertEquals("Self", SessionChatLine.killerOf("was Self's final "));
        assertEquals("Alex", SessionChatLine.killerOf("was buzzed to death by Alex"));
        assertNull(SessionChatLine.killerOf("fell into the void"));
        assertNull(SessionChatLine.killerOf("disconnected"));
    }
}
