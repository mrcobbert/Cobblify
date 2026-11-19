package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.PruneRules;
import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RecordingStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The recordings R2 can show and change (G39, G46; TASK A3, A16; PLAN section 6 "Library").
 *
 * <p><b>Listing.</b> {@link #list()} returns the closed recordings ({@code .json complete}, a regular {@code .cbr} in
 * this build's format, a valid id), never a Hypixel replay-viewer visit (coordinator amendment 2). Each call reads
 * fresh copies; the id is the file's.
 *
 * <p><b>Changes.</b> {@link #setStarred} rewrites the {@code .json} atomically and {@link #delete} removes the three
 * files, both only for a closed recording this process can lock (not being written here or by another instance),
 * whose id matches R1's pattern, reached without following a link, and both under
 * {@link RecordingStore#mutationLock()}, which R1's prune also holds.
 *
 * <p><b>Quick check.</b> {@link LoadedRecording#quickCheck} results are cached per (id, {@code .cbr} size, modified
 * time). {@link #checkState} only looks at the cache (one {@code stat}, fine on the client thread); {@link #check}
 * and {@link #refresh} read files and belong off the client thread, e.g. when the writer closes a game. Only closed
 * recordings are ever read: a file still being written is never opened here (closing a second descriptor on it would
 * drop the writer's lock on POSIX systems).
 *
 * <p>The newest kept game ({@link #newest()}) is what Watch Last Game opens; replay-viewer visits never count.
 */
public final class RecordingLibrary {

    /** Runs the quick check; a seam for tests. */
    public interface Checker {
        /** Null when the recording can be played, else why not. */
        LoadedRecording.Problem check(File cbr, File json);
    }

    public static final Checker QUICK_CHECK = new Checker() {
        @Override
        public LoadedRecording.Problem check(File cbr, File json) {
            return LoadedRecording.quickCheck(cbr, json);
        }
    };

    public enum Check { UNCHECKED, PASSED, FAILED }

    /** The newest kept recording. */
    public static final class NewestGame {
        public final String id;
        public final RecordingMeta meta;
        /** Its {@code .json} is not complete yet: the game is still being written. */
        public final boolean writing;

        NewestGame(String id, RecordingMeta meta) {
            this.id = id;
            this.meta = meta;
            this.writing = !meta.complete;
        }
    }

    private static final class Cached {
        final long size, modified;
        final LoadedRecording.Problem problem;

        Cached(long size, long modified, LoadedRecording.Problem problem) {
            this.size = size;
            this.modified = modified;
            this.problem = problem;
        }
    }

    private final RecordingStore store;
    private final Checker checker;
    private final Map<String, Cached> cache = new HashMap<String, Cached>();

    public RecordingLibrary(RecordingStore store) {
        this(store, QUICK_CHECK);
    }

    public RecordingLibrary(RecordingStore store, Checker checker) {
        this.store = store;
        this.checker = checker;
    }

    public RecordingStore store() {
        return store;
    }

    // ------------------------------------------------------------------ listing

    /** The closed recordings, in id order (the gallery sorts them newest first). */
    public List<RecordingMeta> list() {
        List<RecordingMeta> out = new ArrayList<RecordingMeta>();
        for (String id : store.listIds()) {
            RecordingMeta m = closed(id);
            if (m != null && !ModeLabels.isReplay(m) && store.supportedCbr(id)) {
                m.id = id;
                out.add(m);
            }
        }
        return out;
    }

    /** The newest recording with a {@code .cbr} and readable metadata, closed or still being written; or null. */
    public NewestGame newest() {
        NewestGame best = null;
        for (String id : store.listIds()) {
            if (!store.hasCbr(id)) continue;
            RecordingMeta m = store.readMeta(id);
            if (m == null || ModeLabels.isReplay(m)) continue;
            if (best == null || m.startedAtMs > best.meta.startedAtMs
                    || (m.startedAtMs == best.meta.startedAtMs && id.compareTo(best.id) > 0)) {
                best = new NewestGame(id, m);
            }
        }
        return best;
    }

    /** Watch Last Game's view of the newest game ({@link WatchEntryRules}). */
    public WatchEntryRules.Newest newestAvailability() {
        return availability(newest(), this);
    }

    public static WatchEntryRules.Newest availability(NewestGame n, RecordingLibrary lib) {
        if (n == null) return WatchEntryRules.Newest.NONE;
        if (n.writing) return WatchEntryRules.Newest.WRITING;
        switch (lib.checkState(n.id)) {
            case PASSED:
                return WatchEntryRules.Newest.PASSED;
            case FAILED:
                return WatchEntryRules.Newest.FAILED;
            default:
                return WatchEntryRules.Newest.UNCHECKED;
        }
    }

    /** {@code id}'s metadata when it is a closed recording that may be read or changed here, else null. */
    private RecordingMeta closed(String id) {
        if (!PruneRules.isValidId(id) || store.isOpenHere(id) || !store.hasCbr(id)) return null;
        RecordingMeta m = store.readMeta(id);
        return m != null && m.complete ? m : null;
    }

    // ------------------------------------------------------------------ star and delete

    /** Stars (keep forever) or unstars a closed, lockable recording; false when refused or the write failed. */
    public boolean setStarred(String id, boolean on) {
        synchronized (RecordingStore.mutationLock()) {
            RecordingMeta m = closed(id);
            if (m == null || !store.supportedCbr(id)) return false;
            RecordingStore.Handle h = store.openExisting(id);
            if (h == null) return false;
            try {
                m.starred = on;
                store.writeMeta(id, m);
                return true;
            } catch (IOException e) {
                return false;
            } catch (RuntimeException e) {
                return false;
            } finally {
                store.release(id, h);
            }
        }
    }

    /** Deletes a closed, lockable recording's {@code .cbr}, {@code .json} and {@code .stats.json}. */
    public boolean delete(String id) {
        synchronized (RecordingStore.mutationLock()) {
            RecordingMeta m = closed(id);
            if (m == null || !store.supportedCbr(id)) return false;
            RecordingStore.Handle h = store.openExisting(id);
            if (h == null) return false;
            store.release(id, h); // a file cannot be deleted on Windows while it is open
            boolean ok = store.delete(id);
            if (ok) {
                synchronized (cache) {
                    cache.remove(id);
                }
            }
            return ok;
        }
    }

    // ------------------------------------------------------------------ quick check

    /** {size, modified} of {@code id.cbr} without following a link, or null. */
    private long[] stat(String id) {
        try {
            BasicFileAttributes a = Files.readAttributes(store.cbrFile(id).toPath(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile()) return null;
            return new long[]{a.size(), a.lastModifiedTime().toMillis()};
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The cached quick-check result for the file as it is now; no file is read. */
    public Check checkState(String id) {
        if (!PruneRules.isValidId(id)) return Check.UNCHECKED;
        long[] s = stat(id);
        if (s == null) return Check.UNCHECKED;
        Cached c;
        synchronized (cache) {
            c = cache.get(id);
        }
        if (c == null || c.size != s[0] || c.modified != s[1]) return Check.UNCHECKED;
        return c.problem == null ? Check.PASSED : Check.FAILED;
    }

    /**
     * Runs the quick check on a closed recording (or answers from the cache) and caches it. UNCHECKED when the
     * recording is not closed or not there: it is not read. Off the client thread.
     */
    public Check check(String id) {
        Check now = checkState(id);
        if (now != Check.UNCHECKED) return now;
        if (closed(id) == null) return Check.UNCHECKED;
        long[] s = stat(id);
        if (s == null) return Check.UNCHECKED;
        LoadedRecording.Problem p;
        try {
            p = checker.check(store.cbrFile(id), store.jsonFile(id));
        } catch (Throwable e) {
            // review code-2 O11: whatever one file's check throws (an Error included), that file is unreadable and
            // the library goes on; the loader's size caps keep a corrupt file from exhausting memory first
            p = LoadedRecording.Problem.UNREADABLE;
        }
        synchronized (cache) {
            cache.put(id, new Cached(s[0], s[1], p));
        }
        return p == null ? Check.PASSED : Check.FAILED;
    }

    /**
     * Checks every closed recording (replay-viewer visits aside) whose result is not cached for its current size and
     * modified time; returns how many were read. Off the client thread, e.g. after the writer closes a game.
     */
    public int refresh() {
        int n = 0;
        List<String> ids = store.listIds();
        Collections.sort(ids, Collections.reverseOrder()); // newest ids first: Watch Last Game's game is ready soonest
        for (String id : ids) {
            RecordingMeta m = closed(id);
            if (m == null || ModeLabels.isReplay(m) || checkState(id) != Check.UNCHECKED) continue;
            check(id);
            n++;
        }
        return n;
    }
}
