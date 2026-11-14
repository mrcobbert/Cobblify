package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Edit HUD shows live data; an item uses sample data only when it has nothing live to show (D11). */
public class PreviewDataTest {

    private static PreviewData.Facts facts(boolean player, int potions, boolean legs, int diamond, int emerald,
                                           boolean map) {
        return new PreviewData.Facts(player, potions, legs, diamond, emerald, map);
    }

    private static final PreviewData.Facts LIVE = facts(true, 2, true, 23, 47, true);
    private static final PreviewData.Facts NOTHING = facts(true, 0, false, -1, -1, false);
    private static final PreviewData.Facts NO_PLAYER = facts(false, 0, false, -1, -1, false);

    @Test
    public void everythingIsLiveWhenThereIsSomethingToShow() {
        for (String id : new String[]{"potion", "armor", "inventory", "diamondtimer", "emeraldtimer", "heightlimit",
                "keystrokes", "sessionstats"}) {
            assertFalse(id, PreviewData.sample(id, LIVE));
        }
    }

    @Test
    public void potionSamplesWithNoActivePotion() {
        assertTrue(PreviewData.sample("potion", NOTHING));
        assertFalse(PreviewData.sample("potion", facts(true, 1, false, -1, -1, false)));
        assertTrue(PreviewData.sample("potion", NO_PLAYER));
    }

    @Test
    public void armorSamplesWithoutKnownLeggings() {
        assertTrue(PreviewData.sample("armor", NOTHING));
        assertFalse(PreviewData.sample("armor", facts(true, 0, true, -1, -1, false)));
        assertTrue(PreviewData.sample("armor", facts(false, 0, true, -1, -1, false)));
    }

    @Test
    public void inventoryIsLiveWithAPlayer() {
        assertFalse(PreviewData.sample("inventory", NOTHING));
        assertTrue(PreviewData.sample("inventory", NO_PLAYER));
    }

    @Test
    public void timersSampleWhileUnknown() {
        assertTrue(PreviewData.sample("diamondtimer", NOTHING));
        assertTrue(PreviewData.sample("emeraldtimer", NOTHING));
        assertFalse(PreviewData.sample("diamondtimer", facts(true, 0, false, 0, -1, false)));
        assertTrue(PreviewData.sample("emeraldtimer", facts(true, 0, false, 0, -1, false)));
        assertFalse(PreviewData.sample("emeraldtimer", facts(true, 0, false, -1, 5, false)));
    }

    @Test
    public void mapInfoSamplesWhileTheMapIsUnknown() {
        assertTrue(PreviewData.sample("heightlimit", NOTHING));
        assertFalse(PreviewData.sample("heightlimit", facts(true, 0, false, -1, -1, true)));
    }

    @Test
    public void fpsCpsAndPingAreAlwaysLive() {
        for (PreviewData.Facts f : new PreviewData.Facts[]{NOTHING, NO_PLAYER, LIVE}) {
            assertFalse(PreviewData.sample("fps", f));
            assertFalse(PreviewData.sample("cps", f));
            assertFalse(PreviewData.sample("ping", f));
        }
    }

    @Test
    public void theScoreboardChatAndTabListAreLive() {
        // D11: chat with lines and a scoreboard with a sidebar are live; with nothing to show they have no box
        for (PreviewData.Facts f : new PreviewData.Facts[]{NOTHING, NO_PLAYER, LIVE}) {
            assertFalse(PreviewData.sample("scoreboard", f));
            assertFalse(PreviewData.sample("chat", f));
            assertFalse(PreviewData.sample("tablist", f));
        }
    }

    @Test
    public void keystrokesSessionStatsAndUnknownIdsAreAlwaysLive() {
        for (PreviewData.Facts f : new PreviewData.Facts[]{NOTHING, NO_PLAYER, LIVE}) {
            assertFalse(PreviewData.sample("keystrokes", f));
            assertFalse(PreviewData.sample("sessionstats", f));
            assertFalse(PreviewData.sample("teamupgrades", f));
            assertFalse(PreviewData.sample(null, f));
        }
    }
}
