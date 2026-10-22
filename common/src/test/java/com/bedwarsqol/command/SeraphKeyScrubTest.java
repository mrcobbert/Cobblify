package com.bedwarsqol.command;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the fail-closed history scrub for the retired {@code /bw seraphkey}: a pasted key must never
 * survive in up-arrow history, and the scrub must report a verified verdict so the command handler can
 * abort the submission when clearing fails. The helper only ever sees raw history lines - the key is
 * never passed to it - so every path here returns a plain boolean.
 */
public class SeraphKeyScrubTest {

    @Test
    public void removesAllAliasesCaseInsensitively() {
        List<String> h = new ArrayList<String>(Arrays.asList(
                "/bw seraphkey secret-lower",
                "/BedwarsQol SeraphKey Secret-Mixed",
                "/HYPIXELCLIENT SERAPHKEY SECRET-UPPER",
                "/Cobblify seraphkey secret-primary",
                "/bw stats Notch"));
        assertTrue(SeraphKeyScrub.scrubKeyEntries(h));
        assertEquals(Collections.singletonList("/bw stats Notch"), h);
        for (String s : h) assertFalse(s.toLowerCase().contains("secret"));
    }

    @Test
    public void noMatchingEntriesIsSuccess() {
        List<String> h = new ArrayList<String>(Arrays.asList("/bw stats Notch", "hello world"));
        assertTrue(SeraphKeyScrub.scrubKeyEntries(h));
        assertEquals(2, h.size());
    }

    @Test
    public void nullHistoryFailsClosed() {
        assertFalse(SeraphKeyScrub.scrubKeyEntries(null));
    }

    @Test
    public void unmodifiableListWithMatchFailsClosed() {
        // Removal throws and the raw line survives -> verifying re-scan fails closed.
        List<String> h = Collections.unmodifiableList(Arrays.asList("/bw seraphkey secret"));
        assertFalse(SeraphKeyScrub.scrubKeyEntries(h));
    }

    @Test
    public void unmodifiableListWithoutMatchSucceeds() {
        // Nothing matches -> iterator.remove is never called -> no throw -> verified clear.
        List<String> h = Collections.unmodifiableList(Arrays.asList("/bw stats Notch"));
        assertTrue(SeraphKeyScrub.scrubKeyEntries(h));
    }

    @Test
    public void throwingListFailsClosed() {
        List<String> h = new ArrayList<String>(Arrays.asList("/bw seraphkey secret")) {
            @Override public Iterator<String> iterator() {
                throw new UnsupportedOperationException("boom");
            }
        };
        assertFalse(SeraphKeyScrub.scrubKeyEntries(h));
    }
}
