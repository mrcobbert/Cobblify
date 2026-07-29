package com.bedwarsqol.stats;

import com.bedwarsqol.stats.BedwarsStats.ModeStats;
import com.bedwarsqol.stats.BedwarsStats.RankProvenance;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Layer 2 rank backstop: only an elevated rank can /nick, so a denick's resolved real account must be
 * elevated. Classification is on the RAW rank code (fail closed on unknown), and a null code (old
 * cache / non-OK) is UNKNOWN so the caller refetches rather than falsely rejecting a warm elevated
 * entry.
 */
public class RankProvenanceTest {

    @Test
    public void isElevatedCoversNickCapableRanks() {
        for (String c : new String[]{"superstar", "youtube", "youtuber", "mojang",
                "admin", "owner", "staff", "game_master", "moderator", "helper", "SUPERSTAR"}) {
            assertTrue(c, HypixelRanks.isElevated(c));
        }
    }

    @Test
    public void isElevatedRejectsLowerAndUnknown() {
        for (String c : new String[]{"mvp_plus", "mvp", "vip_plus", "vip", "", "default", "bogus"}) {
            assertFalse(c, HypixelRanks.isElevated(c));
        }
        assertFalse(HypixelRanks.isElevated(null));
    }

    private static BedwarsStats ok(String code) {
        return BedwarsStats.ok("P", HypixelRanks.prefix(code), code,
                ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY);
    }

    @Test
    public void provenanceElevatedForSuperstar() {
        assertEquals(RankProvenance.ELEVATED, ok("superstar").rankProvenance());
        assertEquals(RankProvenance.ELEVATED, ok("youtube").rankProvenance());
    }

    @Test
    public void provenanceNonElevatedForMvpPlusAndKnownDefault() {
        assertEquals(RankProvenance.NON_ELEVATED, ok("mvp_plus").rankProvenance());
        assertEquals(RankProvenance.NON_ELEVATED, ok("").rankProvenance()); // fetched, rankless
    }

    @Test
    public void provenanceUnknownForNullCodeAndNonOk() {
        // null rankCode = old cache entry (pre-field) → must refetch, not reject.
        BedwarsStats warmOld = BedwarsStats.ok("P", "§6[MVP§c++§6]", null,
                ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY);
        assertEquals(RankProvenance.UNKNOWN, warmOld.rankProvenance());
        assertEquals(RankProvenance.UNKNOWN, BedwarsStats.error().rankProvenance());
        assertEquals(RankProvenance.UNKNOWN, BedwarsStats.nicked().rankProvenance());
    }
}
