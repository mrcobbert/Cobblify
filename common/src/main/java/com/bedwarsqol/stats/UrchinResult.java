package com.bedwarsqol.stats;

import java.util.Collections;
import java.util.List;

/**
 * The parsed Urchin resolution for one player from a base or follow-up backend line. {@code checked}
 * or {@code unavailable} marks the entry resolved (no client retry); a bare transient failure leaves
 * both false so the bounded refresh predicate may retry later.
 */
public final class UrchinResult {

    public final List<UrchinTag> tags;   // never null; already sanitized, may include expired tags
    public final boolean checked;        // urchinChecked: a definitive resolution (cache hit / success / 404)
    public final boolean unavailable;    // urchinUnavailable: no fresh data now (may carry stale tags)
    public final boolean notFound;       // urchinNotFound: Coral 404 (manual name route only)
    public final String uuid;            // canonical lowercase undashed UUID this resolution is FOR; nullable (B1)

    public UrchinResult(List<UrchinTag> tags, boolean checked, boolean unavailable, boolean notFound, String uuid) {
        this.tags = tags == null ? Collections.<UrchinTag>emptyList() : tags;
        this.checked = checked;
        this.unavailable = unavailable;
        this.notFound = notFound;
        this.uuid = uuid;
    }

    /** Whether this result concludes the entry (no further Urchin retry). */
    public boolean resolved() {
        return checked || unavailable;
    }
}
