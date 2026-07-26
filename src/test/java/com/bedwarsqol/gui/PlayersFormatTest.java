package com.bedwarsqol.gui;

import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.SeraphTag;
import com.bedwarsqol.stats.UrchinTag;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/**
 * Pins the pure Players-page formatting (CR-04/CR-05): a mode with no games renders all dashes (never
 * Overall's numbers), a played mode exposes all six counters + three ratios, and §-color codes map to
 * the standard palette.
 */
public class PlayersFormatTest {

    @Test
    public void noGamesModeIsAllDashes() {
        String[] cells = PlayersFormat.modeCells(BedwarsStats.ModeStats.EMPTY);
        assertEquals(PlayersFormat.COLS.length, cells.length);
        for (String c : cells) assertEquals("-", c);
    }

    @Test
    public void nullModeIsAllDashes() {
        for (String c : PlayersFormat.modeCells(null)) assertEquals("-", c);
    }

    @Test
    public void playedModeExposesEveryCounterAndRatio() {
        // finalKills=10, finalDeaths=5, wins=8, losses=4, kills=20, deaths=10
        BedwarsStats.ModeStats m = new BedwarsStats.ModeStats(10, 5, 8, 4, 20, 10);
        String[] cells = PlayersFormat.modeCells(m);
        // FKDR, WLR, KD, FK, FD, W, L, K, D
        assertArrayEquals(new String[]{"2.00", "2.00", "2.00", "10", "5", "8", "4", "20", "10"}, cells);
    }

    @Test
    public void fmt2AlwaysTwoDecimals() {
        assertEquals("0.00", PlayersFormat.fmt2(0));
        assertEquals("3.14", PlayersFormat.fmt2(3.14159));
        assertEquals("2.50", PlayersFormat.fmt2(2.5));
    }

    @Test
    public void stripSectionRemovesColorCodes() {
        assertEquals("[MVP+]", PlayersFormat.stripSection("§b[MVP§c+§b]"));
        assertEquals("", PlayersFormat.stripSection(null));
        assertEquals("plain", PlayersFormat.stripSection("plain"));
    }

    @Test
    public void sectionColorMapsPalette() {
        assertEquals(0xFFAA00AA, PlayersFormat.sectionColorToRgb("§5")); // dark purple (confirmed_cheater)
        assertEquals(0xFFFFAA00, PlayersFormat.sectionColorToRgb("§6")); // gold
        assertEquals(0xFFAA0000, PlayersFormat.sectionColorToRgb("§4")); // dark red (sniper)
        assertEquals(0xFFFF5555, PlayersFormat.sectionColorToRgb("§c")); // red (legit_sniper)
        assertEquals(0xFFFFFFFF, PlayersFormat.sectionColorToRgb(null)); // fallback white
    }

    // ---- abbrev (raw-counter compaction) ------------------------------------

    @Test
    public void abbrevBelowThousandIsVerbatim() {
        assertEquals("0", PlayersFormat.abbrev(0));
        assertEquals("7", PlayersFormat.abbrev(7));
        assertEquals("999", PlayersFormat.abbrev(999));
    }

    @Test
    public void abbrevThousands() {
        assertEquals("1k", PlayersFormat.abbrev(1000));
        assertEquals("1.2k", PlayersFormat.abbrev(1200));
        assertEquals("12.3k", PlayersFormat.abbrev(12345));
    }

    @Test
    public void abbrevMillions() {
        assertEquals("1M", PlayersFormat.abbrev(1_000_000));
        assertEquals("1.5M", PlayersFormat.abbrev(1_500_000));
    }

    // ---- chipFor (list-row severity chip) -----------------------------------

    private static final long NOW = 1_000L;

    private static UrchinTag urchin(String type) {
        return new UrchinTag(type, "", NOW, null);
    }

    private static SeraphTag seraph(String kind, boolean verified) {
        return new SeraphTag(kind, "", "", NOW, verified);
    }

    @Test
    public void chipNullWhenNoSignals() {
        assertNull(PlayersFormat.chipFor(Collections.<UrchinTag>emptyList(), NOW,
                Collections.<SeraphTag>emptyList()));
        assertNull(PlayersFormat.chipFor(null, NOW, null));
    }

    @Test
    public void chipSafelistAloneIsGreenSL() {
        PlayersFormat.Chip c = PlayersFormat.chipFor(Collections.<UrchinTag>emptyList(), NOW,
                Arrays.asList(seraph("safelist", false)));
        assertEquals("SL", c.code);
        assertEquals(PlayersFormat.sectionColorToRgb("§a"), c.argb);
    }

    @Test
    public void chipUrchinOnly() {
        PlayersFormat.Chip c = PlayersFormat.chipFor(Arrays.asList(urchin("confirmed_cheater")), NOW,
                Collections.<SeraphTag>emptyList());
        assertEquals("CCC", c.code);
        assertEquals(PlayersFormat.sectionColorToRgb("§5"), c.argb);
    }

    @Test
    public void dangerOutranksSafelist() {
        // Urchin sniper (danger) beats a Seraph safelist (positive) → chip is the danger, not "SL".
        PlayersFormat.Chip c = PlayersFormat.chipFor(Arrays.asList(urchin("sniper")), NOW,
                Arrays.asList(seraph("safelist", false)));
        assertEquals("S", c.code);
        assertNotEquals("SL", c.code);
    }

    @Test
    public void higherSeverityDangerWinsAcrossProviders() {
        // Urchin confirmed_cheater (7) vs Seraph verified blacklist (5) → Urchin.
        PlayersFormat.Chip u = PlayersFormat.chipFor(Arrays.asList(urchin("confirmed_cheater")), NOW,
                Arrays.asList(seraph("blacklist", true)));
        assertEquals("CCC", u.code);
        // Urchin caution (1) vs Seraph verified blacklist (5) → Seraph.
        PlayersFormat.Chip s = PlayersFormat.chipFor(Arrays.asList(urchin("caution")), NOW,
                Arrays.asList(seraph("blacklist", true)));
        assertEquals("BL", s.code);
        assertEquals(PlayersFormat.sectionColorToRgb("§4"), s.argb);
    }
}
