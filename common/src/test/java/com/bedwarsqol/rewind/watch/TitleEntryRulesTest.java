package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** TASK A1: the Realms slot reads "Rewind" and opens the gallery while the Rewind module is on; vanilla when off. */
public class TitleEntryRulesTest {

    @Test
    public void realmsButtonIsIdFourteen() {
        assertEquals(14, TitleEntryRules.REALMS_BUTTON_ID);
    }

    @Test
    public void labelOnlyWithTheModuleOn() {
        assertEquals("Rewind", TitleEntryRules.label(true));
        assertNull(TitleEntryRules.label(false));
    }

    @Test
    public void onlyTheRealmsButtonOpensTheGallery() {
        assertTrue(TitleEntryRules.opensGallery(true, 14));
        assertFalse(TitleEntryRules.opensGallery(false, 14));
        for (int id : new int[]{0, 1, 2, 4, 5, 13, 15, 200}) {
            assertFalse("id " + id, TitleEntryRules.opensGallery(true, id));
        }
    }
}
