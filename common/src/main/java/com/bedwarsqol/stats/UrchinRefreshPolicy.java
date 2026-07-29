package com.bedwarsqol.stats;

import java.util.Map;

/**
 * Pure decision helpers for Urchin refresh scheduling and clear-generation safety, extracted from
 * {@link StatsCache} so they can be unit-tested headlessly (StatsCache pulls in Minecraft/Forge at
 * class-init). {@link StatsCache} calls these from its batch base-line merge and from
 * putResolved/mergeUrchin; keep the two in sync.
 */
final class UrchinRefreshPolicy {

    private UrchinRefreshPolicy() {}

    /**
     * The {@code urchinAttemptMs} to store when a batch base (non-Urchin) stats line is merged.
     * When this member's UUID was actually emitted in the request, stamp {@code now} so the 60 s
     * refresh bound holds even if no Urchin resolution line ever returns (B1: a Coral timeout / 5xx
     * must be one retry per minute, not one per coalesce cycle); otherwise preserve the prior
     * attempt. Never marks the entry resolved.
     */
    static long attemptForBaseMerge(long priorAttemptMs, boolean uuidEmitted, long now) {
        return uuidEmitted ? now : priorAttemptMs;
    }

    /**
     * Whether an Urchin resolution arriving from {@code requestGeneration} must be DROPPED because
     * the key was cleared (generation bumped by {@code stripUrchinTags()}) after the request was
     * dispatched (B2: a pre-clear response must not reattach stripped tags). Stats still merge
     * normally; only the Urchin tags/resolution are discarded.
     */
    static boolean dropStaleUrchin(int requestGeneration, int currentGeneration) {
        return requestGeneration != currentGeneration;
    }

    /**
     * Whether a batch Urchin resolution (or base-line attempt stamp) for a name may land on
     * {@code taskKey}. Only the single cache key whose UUID was actually emitted in the request
     * ({@code emittedKey}) may receive the tags/resolution; other tasks sharing the same displayed name
     * must not, or a stale/unconfirmed pair could inherit the eligible pair's accusation (I6: the exact
     * {@code (displayedName, uuid)} provenance boundary). A null {@code emittedKey} means no eligible
     * member was sent for this name, so nothing merges. Ordinary (non-Urchin) stats fan-out stays
     * name-based and does not consult this.
     */
    static boolean isUrchinMergeTarget(String taskKey, String emittedKey) {
        return emittedKey != null && emittedKey.equals(taskKey);
    }

    /**
     * The cache key an Urchin resolution may merge into, chosen by UUID identity rather than name
     * (B1: case-varied duplicate names could otherwise cross-attach one UUID's accusation to another).
     * The target is the cache key whose canonical UUID was actually EMITTED for this request and equals
     * {@code resultUuid}. Returns null when {@code resultUuid} is null or no emitted task carried it, so
     * the resolution is DROPPED everywhere instead of landing on a name-matched sibling. A name-based
     * lookup may locate candidates, but this UUID comparison is decisive. {@code emittedKeyByUuid} keys
     * are canonical lowercase undashed UUIDs.
     */
    static String urchinMergeTarget(String resultUuid, Map<String, String> emittedKeyByUuid) {
        if (resultUuid == null || emittedKeyByUuid == null) return null;
        return emittedKeyByUuid.get(resultUuid);
    }
}
