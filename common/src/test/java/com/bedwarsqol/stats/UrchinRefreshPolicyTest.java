package com.bedwarsqol.stats;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the two pure Urchin scheduling/clear decisions that StatsCache delegates to. These are the
 * headless regression seams for review findings B1 and B2 (StatsCache itself pulls in Minecraft at
 * class-init and can't load here). See StatsCache#putBase / #putResolved / #mergeUrchin.
 */
public class UrchinRefreshPolicyTest {

    // ---- B1: a batch base line with no Urchin metadata still bounds the retry -----------------

    @Test
    public void emittedUuidStampsAttemptSoRetryIsBounded() {
        long now = 10_000L;
        // UUID was actually sent: stamp now so needsUrchinRefresh's 60s gap holds even with no
        // resolution line. Before the fix this stayed 0 and re-enqueued every coalesce cycle.
        assertEquals(now, UrchinRefreshPolicy.attemptForBaseMerge(0L, true, now));
    }

    @Test
    public void nonEmittedUuidPreservesPriorAttempt() {
        // No UUID sent (ineligible member): the prior attempt is untouched.
        assertEquals(500L, UrchinRefreshPolicy.attemptForBaseMerge(500L, false, 10_000L));
        assertEquals(0L, UrchinRefreshPolicy.attemptForBaseMerge(0L, false, 10_000L));
    }

    // ---- B2: a pre-clear response must not reattach stripped tags -----------------------------

    @Test
    public void sameGenerationKeepsUrchinData() {
        assertFalse(UrchinRefreshPolicy.dropStaleUrchin(4, 4));
    }

    @Test
    public void olderGenerationIsDroppedAfterClear() {
        // Request dispatched at gen 4; stripUrchinTags() bumped to 5 before the response merged.
        assertTrue(UrchinRefreshPolicy.dropStaleUrchin(4, 5));
    }
    // ---- I6: a batch result merges only into the emitted UUID's cache key ---------------------

    @Test
    public void onlyEmittedKeyIsUrchinMergeTarget() {
        // Two cache keys share one displayed name; only "uuid-eligible" passed send-time eligibility
        // and had its UUID emitted. The batch result (and the base-line attempt stamp) may land ONLY
        // there - never on the stale/unconfirmed sibling that shares the name.
        assertTrue(UrchinRefreshPolicy.isUrchinMergeTarget("uuid-eligible", "uuid-eligible"));
        assertFalse(UrchinRefreshPolicy.isUrchinMergeTarget("uuid-stale", "uuid-eligible"));
        // No eligible member emitted for this name -> nothing merges.
        assertFalse(UrchinRefreshPolicy.isUrchinMergeTarget("uuid-eligible", null));
    }

    // ---- B1: a resolution merges by emitted UUID identity, never by (case-varied) name -----------

    @Test
    public void resolutionMergesOnlyIntoItsEmittedUuidKey() {
        // Two tasks with case-varied names ("Alice"/"alice") backing DISTINCT UUIDs both emit. The
        // resolution for uuid2 must land ONLY on uuid2's cache key, never uuid1's, even though the
        // Worker's stream could carry either spelling of the name.
        java.util.Map<String, String> emitted = new java.util.HashMap<>();
        emitted.put("uuid1", "key-Alice");
        emitted.put("uuid2", "key-alice");
        assertEquals("key-alice", UrchinRefreshPolicy.urchinMergeTarget("uuid2", emitted));
        assertEquals("key-Alice", UrchinRefreshPolicy.urchinMergeTarget("uuid1", emitted));
    }

    @Test
    public void resolutionWithNullOrUnknownUuidIsDropped() {
        java.util.Map<String, String> emitted = new java.util.HashMap<>();
        emitted.put("uuid1", "key-Alice");
        assertNull(UrchinRefreshPolicy.urchinMergeTarget(null, emitted));
        assertNull(UrchinRefreshPolicy.urchinMergeTarget("uuid-never-sent", emitted));
        assertNull(UrchinRefreshPolicy.urchinMergeTarget("uuid1", null));
    }

    @Test
    public void setAlsoAdvancesGenerationSoPreSetUnavailableIsDropped() {
        // A request dispatched at gen N (key missing -> unavailable result) must be dropped
        // once a successful SET has advanced the generation to N+1, or the just-invalidated
        // entry would be pinned resolved-empty. Mirrors StatsCache.invalidateUrchinResolution.
        int genAtDispatch = 4;
        int genAfterSet = 5;
        assertTrue(UrchinRefreshPolicy.dropStaleUrchin(genAtDispatch, genAfterSet));
        assertFalse(UrchinRefreshPolicy.dropStaleUrchin(genAfterSet, genAfterSet));
    }

}
