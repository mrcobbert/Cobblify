package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RecordingMeta;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Gallery and loading-card labels: mode, title (mode · map) and result (PLAN section 6 "Labels"). */
public class ModeLabelsTest {

    private static RecordingMeta meta(String server, String serverType, String mode, String map, String result) {
        RecordingMeta m = new RecordingMeta();
        m.server = server;
        m.serverType = serverType;
        m.mode = mode;
        m.map = map;
        if (result != null) m.result = result;
        return m;
    }

    private static RecordingMeta bedwars(String mode) {
        return meta(RecordingMeta.SERVER_HYPIXEL, "BEDWARS", mode, null, null);
    }

    @Test
    public void bedWarsModesUseTheTable() {
        assertEquals("Solo", ModeLabels.mode(bedwars("BEDWARS_EIGHT_ONE")));
        assertEquals("Doubles", ModeLabels.mode(bedwars("BEDWARS_EIGHT_TWO")));
        assertEquals("Threes", ModeLabels.mode(bedwars("BEDWARS_FOUR_THREE")));
        assertEquals("Fours", ModeLabels.mode(bedwars("BEDWARS_FOUR_FOUR")));
        assertEquals("4v4", ModeLabels.mode(bedwars("BEDWARS_TWO_FOUR")));
    }

    @Test
    public void otherBedWarsModesReadBedWars() {
        assertEquals("Bed Wars", ModeLabels.mode(bedwars("BEDWARS_EIGHT_TWO_RUSH")));
        assertEquals("Bed Wars", ModeLabels.mode(bedwars(null)));
        // the mode id alone is enough when the server type is missing
        assertEquals("Solo", ModeLabels.mode(meta(RecordingMeta.SERVER_HYPIXEL, null, "BEDWARS_EIGHT_ONE", null, null)));
    }

    @Test
    public void otherGamesUseTheServerTypeInTitleCase() {
        assertEquals("Duels", ModeLabels.mode(meta(RecordingMeta.SERVER_HYPIXEL, "DUELS", "DUELS_UHC_DUEL", null, null)));
        assertEquals("Build Battle", ModeLabels.mode(meta(RecordingMeta.SERVER_HYPIXEL, "BUILD_BATTLE", "X", null, null)));
        assertEquals("Skywars", ModeLabels.mode(meta(RecordingMeta.SERVER_HYPIXEL, "skywars", null, null, null)));
    }

    @Test
    public void singleplayerAndUnknown() {
        assertEquals("Singleplayer", ModeLabels.mode(meta(RecordingMeta.SERVER_SINGLEPLAYER, null,
                RecordingMeta.MODE_SINGLEPLAYER, null, null)));
        assertEquals("Singleplayer", ModeLabels.mode(meta(null, null, RecordingMeta.MODE_SINGLEPLAYER, null, null)));
        assertEquals("Hypixel", ModeLabels.mode(meta(RecordingMeta.SERVER_HYPIXEL, null, null, null, null)));
        assertEquals("Game", ModeLabels.mode(meta(null, null, null, null, null)));
        assertEquals("Game", ModeLabels.mode(null));
    }

    @Test
    public void titleAddsTheMapWhenKnown() {
        RecordingMeta m = bedwars("BEDWARS_FOUR_FOUR");
        assertEquals("Fours", ModeLabels.title(m));
        m.map = "  ";
        assertEquals("Fours", ModeLabels.title(m));
        m.map = "Lighthouse";
        assertEquals("Fours · Lighthouse", ModeLabels.title(m));
    }

    @Test
    public void resultIsWinLossOrBlank() {
        assertEquals("WIN", ModeLabels.result(meta(null, "BEDWARS", null, null, "WIN")));
        assertEquals("LOSS", ModeLabels.result(meta(null, "BEDWARS", null, null, "LOSS")));
        assertNull(ModeLabels.result(meta(null, "BEDWARS", null, null, "UNKNOWN")));
        assertNull(ModeLabels.result(meta(null, "BEDWARS", null, null, "garbage")));
        assertNull(ModeLabels.result(null));
        assertEquals(0xFF55FF55, ModeLabels.resultColour("WIN"));
        assertEquals(0xFFFF5555, ModeLabels.resultColour("LOSS"));
    }

    @Test
    public void replayVisitsAreRecognised() {
        assertTrue(ModeLabels.isReplay(meta(RecordingMeta.SERVER_HYPIXEL, "REPLAY", null, null, null)));
        assertTrue(ModeLabels.isReplay(meta(RecordingMeta.SERVER_HYPIXEL, "replay", null, null, null)));
        assertFalse(ModeLabels.isReplay(bedwars("BEDWARS_EIGHT_ONE")));
        assertFalse(ModeLabels.isReplay(null));
    }
}
