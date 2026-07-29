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
 * Pins the fail-closed history scrub for {@code /bw urchinkey} (review finding B1): the key must never
 * survive in up-arrow history, and the scrub must report a verified verdict so the command handler can
 * abort the submission when clearing fails. The helper only ever sees raw history lines - the key is
 * never passed to it - so every path here returns a plain boolean.
 */
public class UrchinKeyScrubTest {

    @Test
    public void removesAllAliasesCaseInsensitively() {
        List<String> h = new ArrayList<String>(Arrays.asList(
                "/bw urchinkey secret-lower",
                "/BedwarsQol UrchinKey Secret-Mixed",
                "/HYPIXELCLIENT URCHINKEY SECRET-UPPER",
                "/Cobblify urchinkey secret-primary",
                "/bw stats Notch"));
        assertTrue(UrchinKeyScrub.scrubKeyEntries(h));
        assertEquals(Collections.singletonList("/bw stats Notch"), h);
        for (String s : h) assertFalse(s.toLowerCase().contains("secret"));
    }

    @Test
    public void noMatchingEntriesIsSuccess() {
        List<String> h = new ArrayList<String>(Arrays.asList("/bw stats Notch", "hello world"));
        assertTrue(UrchinKeyScrub.scrubKeyEntries(h));
        assertEquals(2, h.size());
    }

    @Test
    public void nullHistoryFailsClosed() {
        assertFalse(UrchinKeyScrub.scrubKeyEntries(null));
    }

    @Test
    public void unmodifiableListWithMatchFailsClosed() {
        // Removal throws and the raw line survives -> verifying re-scan fails closed.
        List<String> h = Collections.unmodifiableList(Arrays.asList("/bw urchinkey secret"));
        assertFalse(UrchinKeyScrub.scrubKeyEntries(h));
    }

    @Test
    public void unmodifiableListWithoutMatchSucceeds() {
        // Nothing matches -> iterator.remove is never called -> no throw -> verified clear.
        List<String> h = Collections.unmodifiableList(Arrays.asList("/bw stats Notch"));
        assertTrue(UrchinKeyScrub.scrubKeyEntries(h));
    }

    @Test
    public void throwingListFailsClosed() {
        List<String> h = new ArrayList<String>(Arrays.asList("/bw urchinkey secret")) {
            @Override public Iterator<String> iterator() {
                throw new UnsupportedOperationException("boom");
            }
        };
        assertFalse(UrchinKeyScrub.scrubKeyEntries(h));
    }
}
