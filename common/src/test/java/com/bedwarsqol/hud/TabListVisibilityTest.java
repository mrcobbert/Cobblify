package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TabListVisibilityTest {

    @Test
    public void keyUpNeverShows() {
        for (int players = 0; players <= 2; players++) {
            assertFalse(TabListVisibility.showing(false, false, players, false));
            assertFalse(TabListVisibility.showing(false, false, players, true));
            assertFalse(TabListVisibility.showing(false, true, players, false));
            assertFalse(TabListVisibility.showing(false, true, players, true));
        }
    }

    @Test
    public void multiplayerAlwaysShowsWhileHeld() {
        for (int players = 0; players <= 2; players++) {
            assertTrue(TabListVisibility.showing(true, false, players, false));
            assertTrue(TabListVisibility.showing(true, false, players, true));
        }
    }

    @Test
    public void singleplayerNeedsAnotherPlayerOrAListObjective() {
        assertFalse(TabListVisibility.showing(true, true, 0, false));
        assertFalse(TabListVisibility.showing(true, true, 1, false));
        assertTrue(TabListVisibility.showing(true, true, 2, false));
        assertTrue(TabListVisibility.showing(true, true, 0, true));
        assertTrue(TabListVisibility.showing(true, true, 1, true));
        assertTrue(TabListVisibility.showing(true, true, 2, true));
    }

    @Test
    public void fullTruthTableMatchesVanillasCondition() {
        boolean[] bools = {false, true};
        for (boolean key : bools) {
            for (boolean integrated : bools) {
                for (int players = 0; players <= 2; players++) {
                    for (boolean objective : bools) {
                        // vanilla: !key || integrated && size <= 1 && objective == null -> not drawn
                        boolean hiddenByVanilla = !key || (integrated && players <= 1 && !objective);
                        assertEquals(!hiddenByVanilla,
                                TabListVisibility.showing(key, integrated, players, objective));
                    }
                }
            }
        }
    }

    @Test
    public void agreesOnlyWhenPredictionMatchesVanilla() {
        assertTrue(TabListVisibility.agrees(true, true));
        assertTrue(TabListVisibility.agrees(false, false));
        assertFalse(TabListVisibility.agrees(true, false));
        assertFalse(TabListVisibility.agrees(false, true));
    }
}
