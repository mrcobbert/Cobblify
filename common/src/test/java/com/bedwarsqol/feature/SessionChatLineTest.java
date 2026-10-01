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

    /**
     * The current end-of-game block names the winning team as {@code <Team> - <names>}: a 2026 log
     * capture {@code "     Blue - FakeSlime, [VIP] Studying"} (emi-ran Bedwars-Overlay
     * ParserTests.cs:203), Lunar's WIN/LOSS winner-row patterns (BedwarsUpdater), and the
     * HypixelRecreation GameEndListener. Copies are in the cycle's evidence folder.
     */
    @Test
    public void winnerRowCountsAWinWhenItNamesYou() {
        assertEquals(Kind.WIN, parse("     Blue - FakeSlime, [VIP] Self"));
        assertEquals(Kind.WIN, parse("     Blue - Self, [VIP] Studying"));
        assertEquals(Kind.WIN, parse("Red - [MVP+] Self"));                    // solo: one name
        assertEquals(Kind.WIN, parse("Green - [MVP++] Alex,Self"));            // recreation joins with ","
        assertEquals(Kind.WIN, parse("Gray - self"));
    }

    @Test
    public void winnerRowWithoutYouIsALoss() {
        assertEquals(Kind.LOSS, parse("     Blue - FakeSlime, [VIP] Studying"));
        assertEquals(Kind.LOSS, parse("Red - [MVP+] Selfish"));
        assertEquals(Kind.LOSS, parse("Yellow - [VIP] Self2, Alex"));
        assertEquals(Kind.LOSS, parse("Pink - [VIP] Alex, Self_"));
    }

    @Test
    public void winnerRowNeedsAKnownSelf() {
        assertNull(SessionChatLine.parse("Blue - Alex", null));
        assertNull(SessionChatLine.parse("Blue - Alex", "", null));
    }

    @Test
    public void rowsThatAreNotTheWinnerRow() {
        assertEquals(Kind.GAME_END, parse("1st Killer - Self - 5"));
        assertNull(parse("Self: Red - Self"));
        assertNull(parse("[MVP+] Alex: Blue - Self"));
        assertNull(parse("Red - not a name list here"));
        assertNull(parse("Red - Self - 5"));
        assertNull(parse("Purple - Self"));
        assertNull(parse("Blue - "));
        assertNull(parse("Bed Wars"));
    }

    // ---- nick ----

    private static final String NICK = "Nicky";

    private static Kind parseNicked(String line) {
        return SessionChatLine.parse(line, SELF, NICK);
    }

    /** Hypixel prints a nicked player's nick in every game line, never the account name. */
    @Test
    public void nickIsCreditedLikeTheRealName() {
        assertEquals(Kind.KILL, parseNicked("Steve was killed by Nicky."));
        assertEquals(Kind.KILL, parseNicked("Steve was fried by Nicky's Golem."));
        assertEquals(Kind.FINAL_KILL, parseNicked("Steve was killed by Nicky. FINAL KILL!"));
        assertEquals(Kind.DEATH, parseNicked("Nicky was shot by Steve."));
        assertEquals(Kind.DEATH, parseNicked("Nicky fell into the void."));
        assertEquals(Kind.FINAL_DEATH, parseNicked("Nicky fell into the void. FINAL KILL!"));
        assertEquals(Kind.BED_BREAK, parseNicked("BED DESTRUCTION > Red Bed was destroyed by Nicky!"));
        assertEquals(Kind.BED_BREAK, parseNicked("BED DESTRUCTION > Blue Bed was melted by nicky's holiday spirit!"));
        assertEquals(Kind.WIN, parseNicked("Winners: Alex, Nicky"));
        assertEquals(Kind.WIN, parseNicked("     Blue - Alex, [VIP] Nicky"));
        assertEquals(Kind.LOSS, parseNicked("     Blue - Alex, [VIP] Steve"));
        assertEquals(Kind.KILL, parseNicked("Steve was killed by Self."));        // the account name still counts
        assertEquals(Kind.KILL, SessionChatLine.parse("Steve was killed by Nicky.", null, NICK));
    }

    @Test
    public void nickDoesNotCreditOtherNames() {
        assertNull(parseNicked("Steve was killed by Nickyy."));
        assertNull(parseNicked("Steve was killed by Alex."));
        assertNull(parseNicked("Nickyy was shot by Steve."));
        assertNull(parseNicked("BED DESTRUCTION > Red Bed was destroyed by Nick!"));
        assertNull(parseNicked("Winners: Alex, Nickyy"));
        assertNull(SessionChatLine.parse("Steve was fried by Alex's Golem.", SELF, "Golem"));
        assertEquals(Kind.KILL, SessionChatLine.parse("Steve was killed by Self.", SELF, ""));
    }

    @Test
    public void nickChangeLines() {
        assertEquals("AmazingNick", SessionChatLine.parseNickChange("You are now nicked as AmazingNick!"));
        assertEquals("AmazingNick", SessionChatLine.parseNickChange("  You are now nicked as AmazingNick!  "));
        assertEquals("", SessionChatLine.parseNickChange("Your nick has been reset!"));
        assertNull(SessionChatLine.parseNickChange("[MVP+] Steve: You are now nicked as Alex!"));
        assertNull(SessionChatLine.parseNickChange("You are now nicked as !"));
        assertNull(SessionChatLine.parseNickChange("Steve was killed by Self."));
        assertNull(SessionChatLine.parseNickChange(null));
    }

    /** Same table as LobbySnapshot.teamName; the words TEAM ELIMINATED prints. */
    @Test
    public void teamColourWords() {
        assertEquals("Red", SessionChatLine.teamForColourCode('c'));
        assertEquals("Red", SessionChatLine.teamForColourCode('C'));
        assertEquals("Blue", SessionChatLine.teamForColourCode('9'));
        assertEquals("Green", SessionChatLine.teamForColourCode('a'));
        assertEquals("Yellow", SessionChatLine.teamForColourCode('e'));
        assertEquals("Aqua", SessionChatLine.teamForColourCode('b'));
        assertEquals("White", SessionChatLine.teamForColourCode('f'));
        assertEquals("Pink", SessionChatLine.teamForColourCode('d'));
        assertEquals("Gray", SessionChatLine.teamForColourCode('8'));
        assertEquals("Gray", SessionChatLine.teamForColourCode('7'));
        assertNull(SessionChatLine.teamForColourCode('0'));
        assertNull(SessionChatLine.teamForColourCode((char) 0));
    }

    @Test
    public void maskSelfForTheDiagnosticLog() {
        assertEquals("     Blue - <self>, [VIP] <nick>",
                SessionChatLine.maskSelf("     Blue - Self, [VIP] Nicky", SELF, NICK));
        assertEquals("Selfish - <self>", SessionChatLine.maskSelf("Selfish - self", SELF, null));
        assertEquals("1st Killer - Alex - 5", SessionChatLine.maskSelf("1st Killer - Alex - 5", SELF, NICK));
        assertNull(SessionChatLine.maskSelf(null, SELF, NICK));
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
