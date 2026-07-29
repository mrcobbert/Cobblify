package com.bedwarsqol.stats;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the pure {@link GameSessionTracker} state machine: world-identity changes, the debounced
 * active-game rising edge, and the world-change-swallows-the-following-rising-edge rule.
 */
public class GameSessionTrackerTest {

    private static final long S = GameSessionTracker.ACTIVE_DEBOUNCE_MS;

    @Test
    public void initialJoinStraightIntoActiveGameIsOneSession() {
        GameSessionTracker t = new GameSessionTracker();
        Object menu = new Object();
        assertEquals(0, t.tick(menu, false, 0));       // main menu, no world
        Object world = new Object();
        assertEquals(1, t.tick(world, false, 100));    // world loads -> increment
        // active rises after >5s but the world change owns it -> swallowed, still session 1.
        assertEquals(1, t.tick(world, true, 100 + S + 10));
    }

    @Test
    public void flickerUnderFiveSecondsDoesNotIncrement() {
        GameSessionTracker t = new GameSessionTracker();
        Object w = new Object();
        t.tick(w, true, 0);
        int base = t.sessionId();
        t.tick(w, false, 1000);              // brief drop
        assertEquals(base, t.tick(w, true, 3000)); // back within 5s -> no new session
    }

    @Test
    public void sameWorldGameLobbyGameIncrements() {
        GameSessionTracker t = new GameSessionTracker();
        Object w = new Object();
        t.tick(w, true, 0);                  // in game
        int base = t.sessionId();
        t.tick(w, false, 1000);              // to lobby
        // Back to active after >5s in the SAME world (no world change) -> new session.
        assertEquals(base + 1, t.tick(w, true, 1000 + S + 1));
    }

    @Test
    public void worldChangeSwallowsFollowingRisingEdge() {
        GameSessionTracker t = new GameSessionTracker();
        Object w1 = new Object();
        t.tick(w1, true, 0);                 // game in world 1
        Object w2 = new Object();
        int afterChange = t.tick(w2, false, 100); // world transfer -> increment
        // Scoreboard comes up later; rising edge must be swallowed by the world change.
        assertEquals(afterChange, t.tick(w2, true, 100 + S + 50));
        // A subsequent same-world game->lobby->game still increments.
        t.tick(w2, false, 100 + S + 60);
        assertEquals(afterChange + 1, t.tick(w2, true, 100 + 2 * S + 200));
    }

    @Test
    public void directWorldChangeIntoActiveDoesNotPoisonNextBoundary() {
        // Regression for I1: a world change straight into an already-active game has no following
        // rising edge to swallow, so it must NOT leave the swallow flag set for the next same-world
        // game->lobby->game boundary.
        GameSessionTracker t = new GameSessionTracker();
        Object w1 = new Object();
        t.tick(w1, true, 0);                     // game in world 1
        Object w2 = new Object();
        int afterChange = t.tick(w2, true, 100); // world change straight into active -> increment
        t.tick(w2, false, 200);                  // to lobby
        // Back to active after >5s in the SAME world must still increment (edge not swallowed).
        assertEquals(afterChange + 1, t.tick(w2, true, 200 + S + 1));
    }

    @Test
    public void disconnectToNullIncrements() {
        GameSessionTracker t = new GameSessionTracker();
        Object w = new Object();
        t.tick(w, true, 0);
        int base = t.sessionId();
        assertEquals(base + 1, t.tick(null, false, 100)); // world -> null on disconnect
    }
}
