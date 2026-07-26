package com.bedwarsqol.stats;

import java.util.Collections;
import java.util.List;

/**
 * The parsed Seraph resolution for one player from a base or follow-up backend line. {@code checked}
 * or {@code unavailable} marks the entry resolved (no client retry); a bare transient failure leaves
 * both false so the bounded refresh predicate may retry later. Carries the optional
 * {@code threatLevel}/{@code encounters} statistics for the detail panel (-1 = absent).
 */
public final class SeraphResult {

    public final List<SeraphTag> tags;   // never null; already sanitized
    public final boolean checked;        // seraphChecked: a definitive resolution (success / 404)
    public final boolean unavailable;    // seraphUnavailable: no fresh data now
    public final boolean notFound;       // seraphNotFound: upstream 404
    public final String uuid;            // canonical lowercase undashed UUID this resolution is FOR; nullable
    public final int threatLevel;        // statistics.threat_level; -1 when absent
    public final int encounters;         // statistics.encounters; -1 when absent

    public SeraphResult(List<SeraphTag> tags, boolean checked, boolean unavailable, boolean notFound,
                        String uuid, int threatLevel, int encounters) {
        this.tags = tags == null ? Collections.<SeraphTag>emptyList() : tags;
        this.checked = checked;
        this.unavailable = unavailable;
        this.notFound = notFound;
        this.uuid = uuid;
        this.threatLevel = threatLevel;
        this.encounters = encounters;
    }

    /** Whether this result concludes the entry (no further Seraph retry). */
    public boolean resolved() {
        return checked || unavailable;
    }
}
