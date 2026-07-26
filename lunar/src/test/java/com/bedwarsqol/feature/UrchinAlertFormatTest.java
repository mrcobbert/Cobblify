package com.bedwarsqol.feature;

import com.bedwarsqol.stats.UrchinTag;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pins Urchin ordinary chat option-A strings and cheater-only chat gating. */
public class UrchinAlertFormatTest {

    private static UrchinTag tag(String type) {
        return new UrchinTag(type, "", 0L, null);
    }

    @Test
    public void ordinaryCheaterTypesMatchOptionA() {
        assertEquals("§8[§6Urchin§8] §cDream §7is a confirmed cheater",
                UrchinAlertFormat.formatOrdinary("§c", "Dream", tag("confirmed_cheater")));
        assertEquals("§8[§6Urchin§8] §eSteve §7is a blatant cheater",
                UrchinAlertFormat.formatOrdinary("§e", "Steve", tag("blatant_cheater")));
        assertEquals("§8[§6Urchin§8] §9Alex §7is a closet cheater",
                UrchinAlertFormat.formatOrdinary("§9", "Alex", tag("closet_cheater")));
    }

    @Test
    public void ordinaryOmitsNonCheaterTypes() {
        assertNull(UrchinAlertFormat.formatOrdinary("§c", "Dream", tag("sniper")));
        assertNull(UrchinAlertFormat.formatOrdinary("§c", "Dream", tag("legit_sniper")));
        assertNull(UrchinAlertFormat.formatOrdinary("§c", "Dream", tag("possible_sniper")));
        assertNull(UrchinAlertFormat.formatOrdinary("§c", "Dream", tag("caution")));
        assertNull(UrchinAlertFormat.formatOrdinary("§c", "Dream", tag("unknown_future")));
        assertNull(UrchinAlertFormat.formatOrdinary("§c", "Dream", null));
    }

    @Test
    public void ordinaryLinesHaveNoLegacyPhrasesOrBadgeGlyphs() {
        String line = UrchinAlertFormat.formatOrdinary("§e", "Dream", tag("confirmed_cheater"));
        assertTrue(line.contains("§8[§6Urchin§8]"));
        assertFalse(line.contains("Cobblify"));
        assertFalse(line.contains("community-reported"));
        assertFalse(line.contains("[CCC]"));
        assertFalse(line.contains("[BC]"));
        assertFalse(line.contains("[CC]"));
    }

    @Test
    public void emptyNameColorFallsBackToYellow() {
        assertEquals("§8[§6Urchin§8] §eDream §7is a confirmed cheater",
                UrchinAlertFormat.formatOrdinary("", "Dream", tag("confirmed_cheater")));
        assertEquals("§8[§6Urchin§8] §eDream §7is a confirmed cheater",
                UrchinAlertFormat.formatOrdinary(null, "Dream", tag("confirmed_cheater")));
    }
}
