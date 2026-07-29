package com.bedwarsqol.stats;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, session-stamped set of confirmed player identities published by
 * {@link com.bedwarsqol.feature.NickUtils} at the end of every tab scan. A confirmed row is a v4
 * (real-account) tab row whose signed-skin/name consistency check passed in the latest clean scan;
 * only such rows may drive automatic Urchin transport or display. v1 (nick) rows never appear here
 * (no automatic denick participation — amendment R11).
 */
public final class IdentitySnapshot {

    /** name(lowercase) + "|" + canonical undashed lowercase uuid, for each confirmed v4 row. */
    public final Set<String> confirmedRows;
    public final long scanGen;
    public final int sessionId;

    public static final IdentitySnapshot EMPTY =
            new IdentitySnapshot(Collections.<String>emptySet(), 0L, -1);

    public IdentitySnapshot(Set<String> confirmedRows, long scanGen, int sessionId) {
        this.confirmedRows = confirmedRows == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(confirmedRows);
        this.scanGen = scanGen;
        this.sessionId = sessionId;
    }

    /** The confirmed-row key for a (displayed name, canonical uuid) pair. */
    public static String rowKey(String name, UUID uuid) {
        if (name == null || uuid == null) return null;
        return name.toLowerCase(Locale.ROOT) + "|" + uuid.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    public boolean contains(String name, UUID uuid) {
        String key = rowKey(name, uuid);
        return key != null && confirmedRows.contains(key);
    }
}
