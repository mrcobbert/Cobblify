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
 * Pins the fail-closed history scrub for {@code /bw statstoken} (F5): the token must never survive in
 * up-arrow history, and the scrub must report a verified verdict so the command handler can abort the
 * save when clearing fails. Mirrors {@link UrchinKeyScrubTest}; the token is never passed to the
 * helper, so every path here returns a plain boolean.
 */
public class StatsTokenScrubTest {

    @Test
    public void removesAllAliasesCaseInsensitively() {
        List<String> h = new ArrayList<String>(Arrays.asList(
                "/bw statstoken secret-lower",
                "/BedwarsQol StatsToken Secret-Mixed",
                "/HYPIXELCLIENT STATSTOKEN SECRET-UPPER",
                "/Cobblify statstoken secret-primary",
                "/bw stats Notch"));
        assertTrue(StatsTokenScrub.scrubKeyEntries(h));
        assertEquals(Collections.singletonList("/bw stats Notch"), h);
        for (String s : h) assertFalse(s.toLowerCase().contains("secret"));
    }

    @Test
    public void leadingSpacesStillMatch() {
        List<String> h = new ArrayList<String>(Arrays.asList("   /cobblify statstoken secret"));
        assertTrue(StatsTokenScrub.scrubKeyEntries(h));
        assertTrue(h.isEmpty());
    }

    @Test
    public void noMatchingEntriesIsSuccess() {
        List<String> h = new ArrayList<String>(Arrays.asList("/bw stats Notch", "hello world"));
        assertTrue(StatsTokenScrub.scrubKeyEntries(h));
        assertEquals(2, h.size());
    }

    @Test
    public void statsUrlIsLeftAlone() {
        // A sibling subcommand that carries no secret keeps its history entry.
        List<String> h = new ArrayList<String>(Arrays.asList("/cobblify statsurl https://x"));
        assertTrue(StatsTokenScrub.scrubKeyEntries(h));
        assertEquals(Collections.singletonList("/cobblify statsurl https://x"), h);
        assertFalse(StatsTokenScrub.matches("/cobblify statsurl https://x"));
    }

    @Test
    public void thePhraseInsideAChatLineIsLeftAlone() {
        // The pattern is anchored at the start of the line, so prose mentioning the command survives.
        String chat = "tell them to run /cobblify statstoken after they get the key";
        List<String> h = new ArrayList<String>(Arrays.asList(chat));
        assertTrue(StatsTokenScrub.scrubKeyEntries(h));
        assertEquals(Collections.singletonList(chat), h);
        assertFalse(StatsTokenScrub.matches(chat));
    }

    @Test
    public void nullHistoryFailsClosed() {
        assertFalse(StatsTokenScrub.scrubKeyEntries(null));
    }

    @Test
    public void unmodifiableListWithMatchFailsClosed() {
        // Removal throws and the raw line survives -> verifying re-scan fails closed.
        List<String> h = Collections.unmodifiableList(Arrays.asList("/bw statstoken secret"));
        assertFalse(StatsTokenScrub.scrubKeyEntries(h));
    }

    @Test
    public void unmodifiableListWithoutMatchSucceeds() {
        // Nothing matches -> iterator.remove is never called -> no throw -> verified clear.
        List<String> h = Collections.unmodifiableList(Arrays.asList("/bw stats Notch"));
        assertTrue(StatsTokenScrub.scrubKeyEntries(h));
    }

    @Test
    public void throwingListFailsClosed() {
        List<String> h = new ArrayList<String>(Arrays.asList("/bw statstoken secret")) {
            @Override public Iterator<String> iterator() {
                throw new UnsupportedOperationException("boom");
            }
        };
        assertFalse(StatsTokenScrub.scrubKeyEntries(h));
    }
}
