package com.bedwarsqol.rewind.export;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExportFileName} (TASK F1): {@code <mode> - <map> - <yyyy-MM-dd HH.mm.ss>.mp4} in local time at the start; a
 * missing mode or map is left out; characters a file name cannot hold are replaced; a taken name gets {@code (2)}; the
 * unfinished file is {@code <name>.<token>.partial.mp4}, the token one export's own (F2).
 */
public class ExportFileNameTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    /** 2025-11-19 14:32:05 UTC. */
    private static final long T = 1_763_562_725_000L;

    @Test
    public void modeMapAndLocalTime() {
        assertEquals("Solo - Lighthouse - 2025-11-19 14.32.05", ExportFileName.base("Solo", "Lighthouse", T, UTC));
        assertEquals("in the zone given", "Solo - Lighthouse - 2025-11-19 15.32.05",
                ExportFileName.base("Solo", "Lighthouse", T, TimeZone.getTimeZone("Europe/Berlin")));
    }

    @Test
    public void aMissingModeOrMapIsLeftOut() {
        assertEquals("Lighthouse - 2025-11-19 14.32.05", ExportFileName.base(null, "Lighthouse", T, UTC));
        assertEquals("Solo - 2025-11-19 14.32.05", ExportFileName.base("Solo", "  ", T, UTC));
        assertEquals("2025-11-19 14.32.05", ExportFileName.base("", null, T, UTC));
    }

    @Test
    public void charactersAFileNameCannotHoldAreReplaced() {
        String b = ExportFileName.base("4v4/Duels", "Map: \"A\" <b>|c?*\\d", T, UTC);
        for (char c : "/\\:*?\"<>|".toCharArray()) {
            assertFalse(b + " holds " + c, b.indexOf(c) >= 0);
        }
        assertEquals("4v4_Duels - Map_ _A_ _b__c___d - 2025-11-19 14.32.05", b);
        assertEquals("control characters and trailing dots go", "Map", ExportFileName.clean("Map\u0007. "));
    }

    @Test
    public void aTakenNameGetsANumber() {
        File dir = new File("/x/Cobblify");
        final Set<String> taken = new HashSet<String>(Arrays.asList("A.mp4", "A (2).partial.mp4"));
        File f = ExportFileName.pick(dir, "A", file -> taken.contains(file.getName()));
        assertEquals(new File(dir, "A (3).mp4"), f);
        assertEquals(new File(dir, "B.mp4"), ExportFileName.pick(dir, "B", file -> false));
    }

    @Test
    public void thePartialFileSitsNextToTheFinalOne() {
        assertEquals(new File("/x/A (2).partial.mp4"), ExportFileName.partial(new File("/x/A (2).mp4")));
        assertEquals(new File("/x/A (2).k3.partial.mp4"), ExportFileName.partial(new File("/x/A (2).mp4"), "k3"));
        String t1 = ExportFileName.newToken(), t2 = ExportFileName.newToken();
        assertFalse("each export its own", t1.equals(t2));
        assertTrue(t1, t1.matches("[0-9a-z]+"));
    }
}
