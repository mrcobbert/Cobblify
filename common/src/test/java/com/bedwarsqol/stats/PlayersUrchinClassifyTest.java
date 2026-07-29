package com.bedwarsqol.stats;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the Urchin resolution boundary that the Players page's RESOLVED_EMPTY vs RESOLVED_TAGS split
 * relies on (CR-05/CR-07): {@link UrchinTag#activeTags} — the pure core behind
 * {@code StatsCache.classifyUrchin} — drops expired and non-displayable (info/account) tags. (The
 * enum-returning wrapper lives on {@code StatsCache}, which cannot load headless, so the classification
 * substance is pinned here on the pure filter it delegates to.)
 */
public class PlayersUrchinClassifyTest {

    private static final long NOW = 1_000_000L;

    @Test
    public void emptyWhenNoTags() {
        assertTrue(UrchinTag.activeTags(Collections.<UrchinTag>emptyList(), NOW).isEmpty());
        assertTrue(UrchinTag.activeTags(null, NOW).isEmpty());
    }

    @Test
    public void expiredTagsAreDropped() {
        UrchinTag expired = new UrchinTag("blatant_cheater", "x", 0L, NOW - 1); // expiresAt in the past
        assertTrue(UrchinTag.activeTags(Arrays.asList(expired), NOW).isEmpty());
    }

    @Test
    public void nonDisplayableTagsAreDropped() {
        UrchinTag info = new UrchinTag("info", "note", 0L, null);       // displayIcon() == null
        UrchinTag account = new UrchinTag("account", "alt", 0L, null);
        assertTrue(UrchinTag.activeTags(Arrays.asList(info, account), NOW).isEmpty());
    }

    @Test
    public void activeDisplayableTagsSurvive() {
        UrchinTag live = new UrchinTag("sniper", "reason", 0L, NOW + 100000L);
        UrchinTag never = new UrchinTag("caution", "", 0L, null); // null = never expires
        List<UrchinTag> active = UrchinTag.activeTags(Arrays.asList(live, never), NOW);
        assertEquals(2, active.size());
    }
}
