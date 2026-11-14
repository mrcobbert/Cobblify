package com.bedwarsqol.hud;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/** The per-item Background store: only Off items are kept, so an item that was never switched reads On. */
public class HudBackgroundTest {

    @Test
    public void absentOrNullMapIsOn() {
        assertTrue(HudBackground.on(null, "potion"));
        assertTrue(HudBackground.on(new HashMap<String, Boolean>(), "potion"));
        Map<String, Boolean> offs = new HashMap<String, Boolean>();
        offs.put("armor", false);
        assertTrue("another item's Off does not reach this one", HudBackground.on(offs, "potion"));
        offs.put("potion", null);
        assertTrue("a null value is not Off", HudBackground.on(offs, "potion"));
        offs.put("potion", true);
        assertTrue(HudBackground.on(offs, "potion"));
        assertTrue(HudBackground.on(offs, null));
    }

    @Test
    public void offIsStoredAndOnRemovesTheEntry() {
        Map<String, Boolean> offs = new LinkedHashMap<String, Boolean>();
        HudBackground.set(offs, "potion", false);
        assertFalse(HudBackground.on(offs, "potion"));
        assertEquals(Boolean.FALSE, offs.get("potion"));
        assertEquals(1, offs.size());

        HudBackground.set(offs, "potion", true);
        assertTrue(HudBackground.on(offs, "potion"));
        assertFalse("On is the absence of an entry", offs.containsKey("potion"));
        assertTrue(offs.isEmpty());

        HudBackground.set(offs, "armor", true);
        assertTrue("switching On an item that is On stores nothing", offs.isEmpty());
        HudBackground.set(offs, null, false);
        HudBackground.set(null, "potion", false);
        assertTrue(offs.isEmpty());
    }

    @Test
    public void sanitizedKeepsOnlyOffEntriesAndUnknownIds() {
        Map<String, Boolean> raw = new HashMap<String, Boolean>();
        raw.put("potion", false);
        raw.put("armor", true);
        raw.put("inventory", null);
        raw.put("someFutureItem", false);
        raw.put(null, false);
        Map<String, Boolean> clean = HudBackground.sanitized(raw);
        assertNotSame(raw, clean);
        assertTrue(clean instanceof LinkedHashMap);
        assertEquals(2, clean.size());
        assertEquals(Boolean.FALSE, clean.get("potion"));
        assertEquals("an id this build does not know is kept for the build that does",
                Boolean.FALSE, clean.get("someFutureItem"));

        Map<String, Boolean> fromNull = HudBackground.sanitized(null);
        assertTrue(fromNull instanceof LinkedHashMap);
        assertTrue(fromNull.isEmpty());
    }

    @Test
    public void fillIsLunarSeeThroughBlack() {
        assertEquals(0x6F000000, HudBackground.FILL);
        assertEquals(0x6F, HudBackground.FILL >>> 24);
        assertEquals(0, HudBackground.FILL & 0xFFFFFF);
    }

    @Test
    public void padIsThreeAtMediumAndScales() {
        assertEquals(3, HudBackground.pad(1f));
        assertEquals(2, HudBackground.pad(0.75f));
        assertEquals(2, HudBackground.pad(0.5f));
        assertEquals("never under 2", 2, HudBackground.pad(0.3f));
        assertEquals(5, HudBackground.pad(1.5f));
        assertEquals(6, HudBackground.pad(2f));
    }

    @Test
    public void overlayIdsAreTheScoreboardChatAndTabList() {
        assertEquals(Arrays.asList("scoreboard", "chat", "tablist"), HudBackground.OVERLAY_IDS);
        try {
            HudBackground.OVERLAY_IDS.add("x");
            throw new AssertionError("OVERLAY_IDS must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // fine
        }
    }
}
