package com.bedwarsqol.feature;

import com.bedwarsqol.stats.SeraphTag;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pins Seraph ordinary chat option-A strings; safelist stays silent. */
public class SeraphAlertFormatTest {

    private static SeraphTag tag(String kind, boolean verified) {
        return new SeraphTag(kind, "cheater", "reason", 0L, verified);
    }

    @Test
    public void ordinaryKindsMatchOptionA() {
        assertEquals("§8[§6Seraph§8] §cDream §7is blacklisted",
                SeraphAlertFormat.formatOrdinary("§c", "Dream", tag("blacklist", false)));
        assertEquals("§8[§6Seraph§8] §eDream §7is blacklisted (verified)",
                SeraphAlertFormat.formatOrdinary("§e", "Dream", tag("blacklist", true)));
        assertEquals("§8[§6Seraph§8] §9Botty §7is a bot",
                SeraphAlertFormat.formatOrdinary("§9", "Botty", tag("bot", false)));
        assertEquals("§8[§6Seraph§8] §aAnnoy §7is on the annoylist",
                SeraphAlertFormat.formatOrdinary("§a", "Annoy", tag("annoy", false)));
    }

    @Test
    public void safelistAndUnknownProduceNoChat() {
        assertNull(SeraphAlertFormat.formatOrdinary("§c", "Dream", tag("safelist", false)));
        assertNull(SeraphAlertFormat.formatOrdinary("§c", "Dream", tag("unknown_kind", false)));
        assertNull(SeraphAlertFormat.formatOrdinary("§c", "Dream", null));
    }

    @Test
    public void linesIgnoreSubtypeAndLegacyPrefix() {
        // Constructor stores subtype "cheater" / reason — must not appear in chat.
        SeraphTag bl = new SeraphTag("blacklist", "sniper", "threat 9", 0L, false);
        String line = SeraphAlertFormat.formatOrdinary("§e", "Dream", bl);
        assertEquals("§8[§6Seraph§8] §eDream §7is blacklisted", line);
        assertTrue(line.contains("§8[§6Seraph§8]"));
        assertFalse(line.contains("Cobblify"));
        assertFalse(line.contains("sniper"));
        assertFalse(line.contains("threat"));
        assertFalse(line.contains("[BL]"));
    }

    @Test
    public void emptyNameColorFallsBackToYellow() {
        assertEquals("§8[§6Seraph§8] §eDream §7is a bot",
                SeraphAlertFormat.formatOrdinary(null, "Dream", tag("bot", false)));
    }
}
