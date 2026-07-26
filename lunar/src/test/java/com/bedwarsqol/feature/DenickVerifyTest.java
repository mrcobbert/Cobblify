package com.bedwarsqol.feature;

import com.bedwarsqol.feature.Denicks.DenickDecision;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.BedwarsStats.ModeStats;
import com.bedwarsqol.stats.HypixelRanks;
import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Layer 2 publish gate + verified-map behavior: a denick reaches the verified (chat/tab-visible) map
 * only after its real account resolves OK + elevated; the map is rebuilt each scan so departure,
 * owner change, and rank downgrade prune automatically and no stale/unverified name is exposed.
 */
public class DenickVerifyTest {

    @After
    public void reset() {
        Denicks.clear();
    }

    private static BedwarsStats ok(String code) {
        return BedwarsStats.ok("P", 1, 1, HypixelRanks.prefix(code), code,
                ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY);
    }

    @Test
    public void decideGatesOnElevatedRank() {
        assertEquals(DenickDecision.PENDING, Denicks.decide(null));          // uncached → fetch
        assertEquals(DenickDecision.VERIFY, Denicks.decide(ok("superstar"))); // MVP++
        assertEquals(DenickDecision.VERIFY, Denicks.decide(ok("youtube")));
        assertEquals(DenickDecision.DROP, Denicks.decide(ok("mvp_plus")));    // cannot /nick
        assertEquals(DenickDecision.DROP, Denicks.decide(ok("")));            // fetched Default
        assertEquals(DenickDecision.PENDING, Denicks.decide(BedwarsStats.error())); // UNKNOWN → refetch
    }

    @Test
    public void warmOldCacheEntryStaysPendingNotDropped() {
        // A pre-field cache entry: rankPrefix present but rankCode null → UNKNOWN → refetch, never a
        // false DROP of a genuine (warm) MVP++.
        BedwarsStats warmOld = BedwarsStats.ok("P", 1, 1, "§6[MVP§c++§6]", null,
                ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY);
        assertEquals(DenickDecision.PENDING, Denicks.decide(warmOld));
    }

    @Test
    public void onlyVerifiedNamesAreReadable() {
        Denicks.publishVerified(Collections.singletonMap("nick", "RealDude"));
        assertEquals("RealDude", Denicks.realNameForNick("NICK")); // case-insensitive read
        assertNull(Denicks.realNameForNick("someoneElse"));
    }

    @Test
    public void republishPrunesDepartedAndSwapsOwner() {
        Denicks.publishVerified(Collections.singletonMap("nick", "AccountA"));
        assertEquals("AccountA", Denicks.realNameForNick("nick"));

        // Owner change A→B for the same nick: new snapshot must expose B, never the stale A.
        Denicks.publishVerified(Collections.singletonMap("nick", "AccountB"));
        assertEquals("AccountB", Denicks.realNameForNick("nick"));

        // Nick departs (empty snapshot): pruned immediately.
        Denicks.publishVerified(Collections.<String, String>emptyMap());
        assertNull(Denicks.realNameForNick("nick"));
    }

    @Test
    public void clearAndNullSnapshotDropEverything() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("a", "AA");
        m.put("b", "BB");
        Denicks.publishVerified(m);
        assertEquals("AA", Denicks.realNameForNick("a"));
        Denicks.publishVerified(null); // untrustworthy identities → drop all
        assertNull(Denicks.realNameForNick("a"));
        assertNull(Denicks.realNameForNick("b"));
    }
}
