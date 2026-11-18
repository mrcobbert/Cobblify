package com.bedwarsqol.rewind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which recordings to delete (G15, A5). Pure: the store lists the folder, says which files are
 * closed, lockable and open, and deletes what this returns.
 *
 * <p>Candidates are CLOSED recordings (clean or recovered) that this process can lock, that are not
 * open here, not starred and not key-marked. The {@link #KEEP} newest candidates by
 * {@code startedAtMs}, then id, are kept; every other candidate is deleted. Everything that is not a
 * candidate is kept and does not count toward the {@link #KEEP}. Only names matching
 * {@code ^\d{8}-\d{6}-[0-9a-f]{4}\.(cbr|json|stats\.json)$} are ever touched.
 */
public final class PruneRules {

    /** Newest unstarred, unmarked games kept. */
    public static final int KEEP = 10;

    private static final Pattern ID = Pattern.compile("^\\d{8}-\\d{6}-[0-9a-f]{4}$");
    private static final Pattern FILE = Pattern.compile("^(\\d{8}-\\d{6}-[0-9a-f]{4})\\.(?:cbr|json|stats\\.json)$");

    private PruneRules() {
    }

    /** What the store knows about one recording id. */
    public static final class Recording {
        public final String id;
        public final long startedAtMs;
        /** END present and {@code .json} {@code complete:true} (clean or recovered). */
        public final boolean closed;
        /** This process could take the file lock (no other running instance holds it). */
        public final boolean lockable;
        /** Held open by this process's writer. */
        public final boolean open;
        public final boolean starred;
        public final boolean keyMarked;

        public Recording(String id, long startedAtMs, boolean closed, boolean lockable, boolean open,
                         boolean starred, boolean keyMarked) {
            this.id = id;
            this.startedAtMs = startedAtMs;
            this.closed = closed;
            this.lockable = lockable;
            this.open = open;
            this.starred = starred;
            this.keyMarked = keyMarked;
        }

        @Override
        public String toString() {
            return "Recording{" + id + ", start=" + startedAtMs + ", closed=" + closed + ", lockable=" + lockable
                    + ", open=" + open + ", starred=" + starred + ", keyMarked=" + keyMarked + "}";
        }
    }

    /** Whether a recording may be deleted at all (and so counts toward the {@link #KEEP}). */
    public static boolean isCandidate(Recording r) {
        return r != null && isValidId(r.id) && r.closed && r.lockable && !r.open && !r.starred && !r.keyMarked;
    }

    /** Newest first: larger {@code startedAtMs}, then larger id. */
    private static final Comparator<Recording> NEWEST_FIRST = new Comparator<Recording>() {
        @Override
        public int compare(Recording a, Recording b) {
            if (a.startedAtMs != b.startedAtMs) return a.startedAtMs > b.startedAtMs ? -1 : 1;
            return b.id.compareTo(a.id);
        }
    };

    /**
     * Ids whose files should be deleted, newest first. A duplicated id counts once (its first
     * entry is used).
     */
    public static List<String> idsToDelete(List<Recording> recordings) {
        List<Recording> candidates = new ArrayList<Recording>();
        Set<String> seen = new HashSet<String>();
        if (recordings != null) {
            for (Recording r : recordings) {
                if (r == null || r.id == null || !seen.add(r.id)) continue;
                if (isCandidate(r)) candidates.add(r);
            }
        }
        if (candidates.size() <= KEEP) return Collections.emptyList();
        Collections.sort(candidates, NEWEST_FIRST);
        List<String> out = new ArrayList<String>(candidates.size() - KEEP);
        for (int i = KEEP; i < candidates.size(); i++) out.add(candidates.get(i).id);
        return out;
    }

    /** Whether {@code id} has the {@code yyyyMMdd-HHmmss-xxxx} shape (lower-case hex). */
    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** The recording id of a file name this module owns, or {@code null} for any other name. */
    public static String idOf(String fileName) {
        if (fileName == null) return null;
        Matcher m = FILE.matcher(fileName);
        return m.matches() ? m.group(1) : null;
    }

    /** The three file names of one recording: {@code .cbr}, {@code .json}, {@code .stats.json}. */
    public static List<String> fileNames(String id) {
        if (!isValidId(id)) throw new IllegalArgumentException("not a recording id");
        return Collections.unmodifiableList(Arrays.asList(id + ".cbr", id + ".json", id + ".stats.json"));
    }
}
