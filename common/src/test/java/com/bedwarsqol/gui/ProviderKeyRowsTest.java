package com.bedwarsqol.gui;

import com.bedwarsqol.stats.ProviderKeySubmitter;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Thin dispatch test for the settings GUI's key rows, compiled into BOTH suites: the Urchin row's
 * kind must construct URCHIN submissions and the Seraph row's kind SERAPH - a swapped enum in
 * {@link ProviderKeyRows} (the only mapping SettingsGui submits through) fails here.
 */
public class ProviderKeyRowsTest {

    @Test
    public void urchinRowDispatchesToUrchin() {
        assertEquals(ProviderKeySubmitter.Provider.URCHIN,
                ProviderKeyRows.providerFor(ProviderKeyRows.URCHIN_KEY_KIND));
    }

    @Test
    public void seraphRowDispatchesToSeraph() {
        assertEquals(ProviderKeySubmitter.Provider.SERAPH,
                ProviderKeyRows.providerFor(ProviderKeyRows.SERAPH_KEY_KIND));
    }

    @Test
    public void kindsAreDistinctAndNonKeyKindsMapToNothing() {
        assertTrue(ProviderKeyRows.URCHIN_KEY_KIND != ProviderKeyRows.SERAPH_KEY_KIND);
        assertNull(ProviderKeyRows.providerFor(0));
        assertNull(ProviderKeyRows.providerFor(104)); // the Urchin Tags toggle kind is not a key row
        assertNull(ProviderKeyRows.providerFor(110)); // the Seraph Tags toggle kind is not a key row
    }
}
