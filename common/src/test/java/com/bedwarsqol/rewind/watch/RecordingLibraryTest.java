package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RecordingStore;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * TASK A3, A16, coordinator amendment 2: the library lists closed recordings (never REPLAY visits), stars and deletes
 * only closed, lockable recordings with a valid id and no links, under the mutation lock that pruning also takes, and
 * caches the quick check per (id, size, modified time).
 */
public class RecordingLibraryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final String A = "20261114-200000-aaaa";
    static final String B = "20261114-210000-bbbb";
    static final String C = "20261114-220000-cccc";

    private File dir;

    private RecordingMeta closed(String id, long startedAtMs) throws IOException {
        RecordingMeta m = RecordingFixture.realShapedMeta();
        m.id = id;
        m.startedAtMs = startedAtMs;
        RecordingFixture.realShaped().write(dir, id);
        RecordingFixture.writeMeta(dir, id, m);
        write(new File(dir, id + ".stats.json"), "{}");
        return m;
    }

    private static void write(File f, String s) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        } finally {
            o.close();
        }
    }

    private RecordingLibrary library() {
        return new RecordingLibrary(new RecordingStore(dir));
    }

    private void setUp() throws IOException {
        dir = tmp.newFolder("rewind");
    }

    private static List<String> ids(List<RecordingMeta> l) {
        List<String> out = new ArrayList<String>();
        for (RecordingMeta m : l) out.add(m.id);
        return out;
    }

    // ------------------------------------------------------------------ listing

    @Test
    public void listsClosedRecordingsButNotReplayVisitsOrOpenFiles() throws IOException {
        setUp();
        closed(A, 1000);
        RecordingMeta replay = closed(B, 2000);
        replay.serverType = "REPLAY";
        RecordingFixture.writeMeta(dir, B, replay);
        RecordingMeta open = closed(C, 3000);
        open.complete = false;
        RecordingFixture.writeMeta(dir, C, open);
        write(new File(dir, "notes.json"), "{}");
        List<RecordingMeta> l = library().list();
        assertEquals(1, l.size());
        assertEquals(A, l.get(0).id);
    }

    @Test
    public void theIdComesFromTheFileName() throws IOException {
        setUp();
        RecordingMeta m = closed(A, 1000);
        m.id = "something-else";
        RecordingFixture.writeMeta(dir, A, m);
        assertEquals(A, library().list().get(0).id);
    }

    // ------------------------------------------------------------------ star

    @Test
    public void starRewritesTheJsonAndSurvivesARestart() throws IOException {
        setUp();
        closed(A, 1000);
        assertTrue(library().setStarred(A, true));
        RecordingMeta disk = new RecordingStore(dir).readMeta(A);
        assertTrue(disk.starred);
        assertTrue(disk.complete);
        assertTrue("a fresh library sees it", library().list().get(0).starred);
        assertTrue(library().setStarred(A, false));
        assertFalse(new RecordingStore(dir).readMeta(A).starred);
    }

    @Test
    public void aStarredGameIsNeverPruned() throws IOException {
        setUp();
        for (int i = 0; i < 12; i++) closed(String.format("20261114-2000%02d-aaaa", i), 1000 + i);
        RecordingLibrary lib = library();
        assertTrue(lib.setStarred("20261114-200000-aaaa", true)); // the oldest
        assertEquals(1, new RecordingStore(dir).prune());
        List<String> left = ids(lib.list());
        assertEquals(11, left.size());
        assertTrue(left.contains("20261114-200000-aaaa"));
        assertFalse(left.contains("20261114-200001-aaaa"));
    }

    @Test
    public void starAndDeleteRefuseAFileThatIsStillBeingWritten() throws IOException {
        setUp();
        RecordingMeta open = closed(A, 1000);
        open.complete = false;
        RecordingFixture.writeMeta(dir, A, open);
        RecordingLibrary lib = library();
        assertFalse(lib.setStarred(A, true));
        assertFalse(lib.delete(A));
        assertTrue(new File(dir, A + ".cbr").isFile());
        assertFalse(new RecordingStore(dir).readMeta(A).starred);
    }

    @Test
    public void starAndDeleteRefuseALockedFile() throws IOException {
        setUp();
        closed(A, 1000);
        RecordingStore locked = new RecordingStore(dir, new RecordingStore.Locker() {
            @Override
            public RecordingStore.Handle open(File cbr, boolean create) {
                return null; // another instance holds it
            }
        });
        RecordingLibrary lib = new RecordingLibrary(locked);
        assertFalse(lib.setStarred(A, true));
        assertFalse(lib.delete(A));
        assertTrue(new File(dir, A + ".cbr").isFile());
    }

    @Test
    public void starAndDeleteRefuseAFileOpenHere() throws IOException {
        setUp();
        closed(A, 1000);
        RecordingStore store = new RecordingStore(dir);
        RecordingStore.Handle h = store.openExisting(A);
        assertNotNull(h);
        try {
            RecordingLibrary lib = new RecordingLibrary(store);
            assertFalse(lib.setStarred(A, true));
            assertFalse(lib.delete(A));
        } finally {
            store.release(A, h);
        }
        assertTrue(new File(dir, A + ".cbr").isFile());
    }

    @Test
    public void badIdsAreRefused() throws IOException {
        setUp();
        RecordingLibrary lib = library();
        for (String id : new String[]{null, "", "../x", "20261114-200000-AAAA", "20261114-200000-aaaa/..",
                "notes"}) {
            assertFalse(String.valueOf(id), lib.setStarred(id, true));
            assertFalse(String.valueOf(id), lib.delete(id));
        }
    }

    // ------------------------------------------------------------------ delete

    @Test
    public void deleteRemovesTheThreeFiles() throws IOException {
        setUp();
        closed(A, 1000);
        closed(B, 2000);
        assertTrue(library().delete(A));
        assertFalse(new File(dir, A + ".cbr").exists());
        assertFalse(new File(dir, A + ".json").exists());
        assertFalse(new File(dir, A + ".stats.json").exists());
        assertTrue(new File(dir, B + ".cbr").exists());
        assertEquals(1, library().list().size());
    }

    @Test
    public void neverFollowsLinks() throws IOException {
        setUp();
        File outside = tmp.newFolder("outside");
        File dirKeep = dir;
        dir = outside;
        closed(A, 1000);
        dir = dirKeep;
        // the .cbr is a link to a real recording elsewhere; the .json is ours
        try {
            Files.createSymbolicLink(new File(dir, A + ".cbr").toPath(), new File(outside, A + ".cbr").toPath());
        } catch (UnsupportedOperationException e) {
            Assume.assumeTrue("no symbolic links here", false);
        } catch (IOException e) {
            Assume.assumeTrue("no symbolic links here", false);
        }
        RecordingFixture.writeMeta(dir, A, new RecordingStore(outside).readMeta(A));
        // and the .json of another id links outside
        closed(B, 2000);
        assertTrue(new File(dir, B + ".json").delete());
        Files.createSymbolicLink(new File(dir, B + ".json").toPath(), new File(outside, A + ".json").toPath());

        RecordingLibrary lib = library();
        assertTrue(lib.list().isEmpty());
        assertFalse(lib.setStarred(A, true));
        assertFalse(lib.delete(A));
        assertFalse(lib.setStarred(B, true));
        assertFalse(lib.delete(B));
        assertTrue(new File(outside, A + ".cbr").isFile());
        assertFalse(new RecordingStore(outside).readMeta(A).starred);
        assertTrue(Files.isSymbolicLink(new File(dir, A + ".cbr").toPath()));
    }

    // ------------------------------------------------------------------ the mutation lock

    @Test
    public void pruneWaitsForTheLibrarysLock() throws Exception {
        setUp();
        for (int i = 0; i < 11; i++) closed(String.format("20261114-2000%02d-aaaa", i), 1000 + i);
        final RecordingStore store = new RecordingStore(dir);
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicInteger pruned = new AtomicInteger(-1);
        Thread t;
        synchronized (RecordingStore.mutationLock()) {
            t = new Thread(new Runnable() {
                @Override
                public void run() {
                    started.countDown();
                    pruned.set(store.prune());
                }
            });
            t.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            t.join(300);
            assertTrue("prune must wait for the lock", t.isAlive());
            assertEquals(-1, pruned.get());
            assertEquals(11, store.listIds().size());
        }
        t.join(5000);
        assertFalse(t.isAlive());
        assertEquals(1, pruned.get());
    }

    @Test
    public void starAndDeleteWaitForTheSameLock() throws Exception {
        setUp();
        closed(A, 1000);
        closed(B, 2000);
        final RecordingLibrary lib = library();
        final AtomicBoolean starred = new AtomicBoolean(), deleted = new AtomicBoolean();
        Thread s, d;
        synchronized (RecordingStore.mutationLock()) {
            s = new Thread(new Runnable() {
                @Override
                public void run() {
                    starred.set(lib.setStarred(A, true));
                }
            });
            d = new Thread(new Runnable() {
                @Override
                public void run() {
                    deleted.set(lib.delete(B));
                }
            });
            s.start();
            d.start();
            s.join(300);
            d.join(50);
            assertTrue(s.isAlive());
            assertTrue(d.isAlive());
            assertFalse(new RecordingStore(dir).readMeta(A).starred);
            assertTrue(new File(dir, B + ".cbr").exists());
        }
        s.join(5000);
        d.join(5000);
        assertTrue(starred.get());
        assertTrue(deleted.get());
    }

    @Test
    public void theMutationLockIsOneObjectForEveryStore() {
        assertSame(RecordingStore.mutationLock(), RecordingStore.mutationLock());
        assertNotNull(RecordingStore.mutationLock());
    }

    // ------------------------------------------------------------------ quick check and the newest game

    @Test
    public void quickCheckResultsAreCachedPerSizeAndModifiedTime() throws IOException {
        setUp();
        closed(A, 1000);
        final AtomicInteger calls = new AtomicInteger();
        RecordingLibrary lib = new RecordingLibrary(new RecordingStore(dir), new RecordingLibrary.Checker() {
            @Override
            public LoadedRecording.Problem check(File cbr, File json) {
                calls.incrementAndGet();
                return LoadedRecording.quickCheck(cbr, json);
            }
        });
        assertSame(RecordingLibrary.Check.UNCHECKED, lib.checkState(A));
        assertSame(RecordingLibrary.Check.PASSED, lib.check(A));
        assertSame(RecordingLibrary.Check.PASSED, lib.checkState(A));
        assertSame(RecordingLibrary.Check.PASSED, lib.check(A));
        assertEquals("cached", 1, calls.get());
        // the file changes on disk: checked again, and now it fails
        File cbr = new File(dir, A + ".cbr");
        byte[] head = new byte[28];
        System.arraycopy(Files.readAllBytes(cbr.toPath()), 0, head, 0, 28);
        head[8] = 99; // the version field (format v1 at offset 8)
        FileOutputStream o = new FileOutputStream(cbr);
        try {
            o.write(head);
        } finally {
            o.close();
        }
        assertTrue(cbr.setLastModified(cbr.lastModified() + 5000));
        assertSame(RecordingLibrary.Check.UNCHECKED, lib.checkState(A));
        assertSame(RecordingLibrary.Check.FAILED, lib.check(A));
        assertSame(RecordingLibrary.Check.FAILED, lib.checkState(A));
        assertEquals(2, calls.get());
    }

    @Test
    public void aFileStillBeingWrittenIsNeverRead() throws IOException {
        setUp();
        RecordingMeta open = closed(A, 1000);
        open.complete = false;
        RecordingFixture.writeMeta(dir, A, open);
        final AtomicInteger calls = new AtomicInteger();
        RecordingLibrary lib = new RecordingLibrary(new RecordingStore(dir), new RecordingLibrary.Checker() {
            @Override
            public LoadedRecording.Problem check(File cbr, File json) {
                calls.incrementAndGet();
                return null;
            }
        });
        assertSame(RecordingLibrary.Check.UNCHECKED, lib.check(A));
        assertEquals(0, lib.refresh());
        assertEquals(0, calls.get());
    }

    @Test
    public void refreshChecksEveryListedRecordingOnce() throws IOException {
        setUp();
        closed(A, 1000);
        closed(B, 2000);
        final AtomicInteger calls = new AtomicInteger();
        RecordingLibrary lib = new RecordingLibrary(new RecordingStore(dir), new RecordingLibrary.Checker() {
            @Override
            public LoadedRecording.Problem check(File cbr, File json) {
                calls.incrementAndGet();
                return null;
            }
        });
        assertEquals(2, lib.refresh());
        assertEquals(0, lib.refresh());
        assertEquals(2, calls.get());
        assertSame(RecordingLibrary.Check.PASSED, lib.checkState(B));
    }

    /**
     * Review code-2 O11: one bad file never takes the library down. Whatever a single file's check throws (an
     * Error included), that file is cached as an unreadable recording and the others are still checked.
     */
    @Test
    public void anyFailureOfOneFilesCheckMarksItUnreadable() throws IOException {
        setUp();
        closed(A, 1000);
        closed(B, 2000);
        final AtomicInteger calls = new AtomicInteger();
        RecordingLibrary lib = new RecordingLibrary(new RecordingStore(dir), new RecordingLibrary.Checker() {
            @Override
            public LoadedRecording.Problem check(File cbr, File json) {
                calls.incrementAndGet();
                if (cbr.getName().startsWith(A)) {
                    throw new StackOverflowError("crafted nesting");
                }
                throw new LinkageError("crafted");
            }
        });
        assertSame(RecordingLibrary.Check.FAILED, lib.check(A));
        assertSame(RecordingLibrary.Check.FAILED, lib.check(B));
        assertSame("cached", RecordingLibrary.Check.FAILED, lib.checkState(A));
        assertEquals(0, lib.refresh());
        assertEquals(2, calls.get());
    }

    @Test
    public void theNewestKeptGame() throws IOException {
        setUp();
        RecordingLibrary lib = library();
        assertNull(lib.newest());
        assertSame(WatchEntryRules.Newest.NONE, RecordingLibrary.availability(lib.newest(), lib));
        closed(A, 1000);
        closed(B, 2000);
        RecordingLibrary.NewestGame n = lib.newest();
        assertEquals(B, n.id);
        assertFalse(n.writing);
        assertSame(WatchEntryRules.Newest.UNCHECKED, RecordingLibrary.availability(n, lib));
        lib.check(B);
        assertSame(WatchEntryRules.Newest.PASSED, RecordingLibrary.availability(lib.newest(), lib));
        // a game being written is newer still
        RecordingMeta open = closed(C, 3000);
        open.complete = false;
        RecordingFixture.writeMeta(dir, C, open);
        n = lib.newest();
        assertEquals(C, n.id);
        assertTrue(n.writing);
        assertSame(WatchEntryRules.Newest.WRITING, RecordingLibrary.availability(n, lib));
    }

    @Test
    public void theNewestGameIgnoresReplayVisits() throws IOException {
        setUp();
        closed(A, 1000);
        RecordingMeta replay = closed(B, 2000);
        replay.serverType = "REPLAY";
        RecordingFixture.writeMeta(dir, B, replay);
        assertEquals(A, library().newest().id);
    }

    @Test
    public void aNewestGameThatFailsTheCheckHidesTheRow() throws IOException {
        setUp();
        closed(A, 1000);
        File cbr = new File(dir, A + ".cbr");
        write(cbr, "not a recording at all, just text that is long enough");
        RecordingLibrary lib = library();
        assertSame(RecordingLibrary.Check.FAILED, lib.check(A));
        assertSame(WatchEntryRules.Newest.FAILED, RecordingLibrary.availability(lib.newest(), lib));
        assertFalse(WatchEntryRules.show(true, true, RecordingLibrary.availability(lib.newest(), lib), false, false,
                true));
    }

    @Test
    public void listReturnsCopiesSoStarringDoesNotChangeOldLists() throws IOException {
        setUp();
        closed(A, 1000);
        RecordingLibrary lib = library();
        List<RecordingMeta> before = lib.list();
        lib.setStarred(A, true);
        assertFalse(before.get(0).starred);
        assertArrayEquals(new Object[]{A}, ids(lib.list()).toArray());
    }
}
