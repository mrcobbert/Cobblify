package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Pins the four in-game broadcasts that change a player's standing, and everything that must not. */
public class GameChatLineTest {

    @Test
    public void disconnect() {
        GameChatLine l = GameChatLine.parse("Prot_IV_Diamond disconnected.");
        assertNotNull(l);
        assertEquals(GameChatLine.Kind.DISCONNECT, l.kind);
        assertEquals("Prot_IV_Diamond", l.name);
        assertNull(l.team);
    }

    @Test
    public void reconnect() {
        GameChatLine l = GameChatLine.parse("Prot_IV_Diamond reconnected.");
        assertNotNull(l);
        assertEquals(GameChatLine.Kind.RECONNECT, l.kind);
        assertEquals("Prot_IV_Diamond", l.name);
    }

    @Test
    public void finalKillVictimIsFirstTokenAcrossDeathShapes() {
        String[] lines = {
                "coolkid2013 was killed by Tenko. FINAL KILL!",
                "coolkid2013 fell into the void. FINAL KILL!",
                "coolkid2013 was knocked into the void by Tenko. FINAL KILL!",
                "coolkid2013 hit the ground too hard whilst trying to escape Tenko. FINAL KILL!",
                "coolkid2013 died. FINAL KILL!",
                "coolkid2013 was struck down by Tenko. FINAL KILL!",
        };
        for (String line : lines) {
            GameChatLine l = GameChatLine.parse(line);
            assertNotNull(line, l);
            assertEquals(line, GameChatLine.Kind.FINAL_KILL, l.kind);
            assertEquals(line, "coolkid2013", l.name);
        }
    }

    @Test
    public void nonFinalDeathIsNotAStandingChange() {
        assertNull(GameChatLine.parse("coolkid2013 was killed by Tenko."));
        assertNull(GameChatLine.parse("coolkid2013 fell into the void."));
    }

    @Test
    public void teamEliminated() {
        GameChatLine l = GameChatLine.parse("TEAM ELIMINATED > Red Team has been eliminated!");
        assertNotNull(l);
        assertEquals(GameChatLine.Kind.TEAM_ELIMINATED, l.kind);
        assertEquals("Red", l.team);
        assertNull(l.name);
    }

    @Test
    public void leadingWhitespaceIsTolerated() {
        assertNotNull(GameChatLine.parse("   TEAM ELIMINATED > Blue Team has been eliminated!  "));
        assertNotNull(GameChatLine.parse(" Tenko disconnected. "));
    }

    @Test
    public void playerChatCannotForgeAnyShape() {
        // Rankless, ranked, team-prefixed and channel-prefixed chat all carry a first token that is
        // not a bare name followed by the broadcast text.
        assertNull(GameChatLine.parse("Tenko: coolkid2013 disconnected."));
        assertNull(GameChatLine.parse("[MVP+] Tenko: coolkid2013 disconnected."));
        assertNull(GameChatLine.parse("[RED] Tenko: coolkid2013 fell into the void. FINAL KILL!"));
        assertNull(GameChatLine.parse("Tenko: coolkid2013 fell into the void. FINAL KILL!"));
        assertNull(GameChatLine.parse("Party > Tenko: coolkid2013 died. FINAL KILL!"));
        assertNull(GameChatLine.parse("Guild > Tenko: TEAM ELIMINATED > Red Team has been eliminated!"));
        assertNull(GameChatLine.parse("[SHOUT] [MVP+] Tenko: FINAL KILL!"));
    }

    @Test
    public void pregameBroadcastsAreNotStandingChanges() {
        // The anonymised queue lobby lines: join/quit are not recognised at all, and a queue-lobby
        // "disconnected." does parse — callers gate on the active game and the roster only ever
        // matches names it has already seen in tab, so a junk name changes nothing.
        assertNull(GameChatLine.parse("vj3x1s4w18 has joined (5/8)!"));
        assertNull(GameChatLine.parse("vj3x1s4w18 has quit!"));
        assertNull(GameChatLine.parse("Sending you to mini12345!"));
    }

    @Test
    public void nearMissesDoNotMatch() {
        assertNull(GameChatLine.parse("Tenko disconnected"));              // no period
        assertNull(GameChatLine.parse("Tenko has disconnected."));         // wrong verb
        assertNull(GameChatLine.parse("Tenko FINAL KILL!"));               // no death verb
        assertNull(GameChatLine.parse("Tenko > died. FINAL KILL!"));       // channel-shaped
        assertNull(GameChatLine.parse("TEAM ELIMINATED > Red Team"));      // truncated
        assertNull(GameChatLine.parse("BED DESTRUCTION > Red Bed was destroyed by Tenko!"));
        assertNull(GameChatLine.parse(""));
        assertNull(GameChatLine.parse(null));
    }
}
