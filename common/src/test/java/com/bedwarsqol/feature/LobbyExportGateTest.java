package com.bedwarsqol.feature;

import com.bedwarsqol.rewind.watch.PlaybackGate;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The lobby-export writer under Rewind's lifetime rule (PLAN section 5 layer 4; review round 2 I2): a snapshot is
 * submitted with the ticket of its moment and written only under a permit for that ticket, so a snapshot from
 * before playback is never written during or after it, and entry waits for a write already in progress. The
 * writer is driven step by step with a recording sink: nothing here touches the real {@code ~/.cobblify}.
 */
public class LobbyExportGateTest {

    private final PlaybackGate gate = new PlaybackGate(60_000);
    private final List<String> written = Collections.synchronizedList(new ArrayList<String>());

    private LobbyExport.Exporter exporter() {
        return new LobbyExport.Exporter(gate, new LobbyExport.Sink() {
            @Override
            public void write(final LobbyExport.Lobby lobby, PlaybackGate.Permit permit) throws Exception {
                permit.commit(new PlaybackGate.Commit() {
                    @Override
                    public void run() {
                        written.add(lobby.context);
                    }
                });
            }
        });
    }

    private static LobbyExport.Lobby lobby(String context) {
        LobbyExport.Lobby l = new LobbyExport.Lobby();
        l.context = context;
        return l;
    }

    @Test
    public void outsidePlaybackTheLatestSnapshotIsWritten() {
        LobbyExport.Exporter e = exporter();
        e.submit(lobby("LOBBY"));
        e.submit(lobby("QUEUE"));
        e.step();
        assertEquals(Collections.singletonList("QUEUE"), written);
        e.step();
        assertEquals("nothing pending", 1, written.size());
        e.markDirty();
        e.step();
        assertEquals("a dirty mark re-evaluates the latest snapshot", 2, written.size());
    }

    @Test
    public void aSnapshotSubmittedBeforeEntryIsNotWrittenDuringOrAfterPlayback() {
        LobbyExport.Exporter e = exporter();
        e.submit(lobby("GAME"));
        gate.enter();
        e.step();
        assertTrue("not during playback", written.isEmpty());
        e.markDirty(); // a stat resolving in the background re-arms the writer
        e.step();
        assertTrue(written.isEmpty());
        gate.exit();
        e.step();
        e.markDirty();
        e.step();
        assertTrue("its ticket is stale after exit too", written.isEmpty());
    }

    @Test
    public void aSnapshotSubmittedWhileEntryDrainsIsNotWritten() {
        LobbyExport.Exporter e = exporter();
        gate.beginEntry();
        e.submit(lobby("GAME"));
        e.step();
        assertTrue(written.isEmpty());
    }

    @Test
    public void aSnapshotSubmittedAfterExitIsWritten() {
        LobbyExport.Exporter e = exporter();
        e.submit(lobby("GAME"));
        gate.enter();
        e.step();
        gate.exit();
        e.submit(lobby("LOBBY"));
        e.step();
        assertEquals(Collections.singletonList("LOBBY"), written);
    }

    @Test(timeout = 10_000)
    public void aWriteInProgressAtEntryFinishesBeforeEntryCompletes() throws Exception {
        final CountDownLatch inWrite = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> order = Collections.synchronizedList(new ArrayList<String>());
        final LobbyExport.Exporter e = new LobbyExport.Exporter(gate, new LobbyExport.Sink() {
            @Override
            public void write(LobbyExport.Lobby lobby, PlaybackGate.Permit permit) throws Exception {
                inWrite.countDown();
                release.await();
                permit.commit(new PlaybackGate.Commit() {
                    @Override
                    public void run() {
                        order.add("write finished");
                    }
                });
            }
        });
        e.submit(lobby("GAME"));
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                e.step();
            }
        }, "lobby-writer");
        writer.start();
        assertTrue(inWrite.await(5, TimeUnit.SECONDS));
        gate.beginEntry();
        assertFalse("the write holds a permit", gate.tryCompleteEntry());
        Thread.sleep(50);
        assertFalse(gate.tryCompleteEntry());
        release.countDown();
        while (!gate.tryCompleteEntry()) {
            Thread.sleep(1);
        }
        order.add("entry complete");
        writer.join(5_000);
        assertEquals(java.util.Arrays.asList("write finished", "entry complete"), order);
    }

    /**
     * Code review 1, I3 (plan round 2 I2 recurring): a writer stalled before its rename past the drain bound. Entry
     * completes without waiting for it (the zero drain takes the production deadline branch at once), and when the
     * writer resumes its rename is refused: nothing lands while playback is muted.
     */
    @Test(timeout = 10_000)
    public void aWriterStalledPastTheDrainBoundNeverCommitsAfterMute() throws Exception {
        final PlaybackGate quick = new PlaybackGate(0);
        final CountDownLatch inWrite = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> committed = Collections.synchronizedList(new ArrayList<String>());
        final List<Boolean> mutedAtCommit = Collections.synchronizedList(new ArrayList<Boolean>());
        final LobbyExport.Exporter e = new LobbyExport.Exporter(quick, new LobbyExport.Sink() {
            @Override
            public void write(final LobbyExport.Lobby lobby, PlaybackGate.Permit permit) throws Exception {
                inWrite.countDown();
                release.await(); // a stalled disk before the rename
                permit.commit(new PlaybackGate.Commit() {
                    @Override
                    public void run() {
                        mutedAtCommit.add(quick.isMuted());
                        committed.add(lobby.context);
                    }
                });
            }
        });
        e.submit(lobby("GAME"));
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                e.step();
            }
        }, "lobby-writer");
        writer.start();
        assertTrue(inWrite.await(5, TimeUnit.SECONDS));
        quick.beginEntry();
        assertTrue("entry completes with the writer still blocked", quick.tryCompleteEntry());
        assertTrue(quick.isMuted());
        release.countDown();
        writer.join(5_000);
        assertFalse(writer.isAlive());
        assertTrue("no lobby write after mute: " + mutedAtCommit, committed.isEmpty());
    }

    /**
     * The other side of the barrier: a rename already under way when the drain bound passes finishes first, and
     * entry completes only after it (never in the middle of the commit section).
     */
    @Test(timeout = 10_000)
    public void aRenameUnderWayAtTheDrainBoundFinishesBeforeEntryCompletes() throws Exception {
        final PlaybackGate quick = new PlaybackGate(0);
        final CountDownLatch inCommit = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> order = Collections.synchronizedList(new ArrayList<String>());
        final LobbyExport.Exporter e = new LobbyExport.Exporter(quick, new LobbyExport.Sink() {
            @Override
            public void write(LobbyExport.Lobby lobby, PlaybackGate.Permit permit) throws Exception {
                permit.commit(new PlaybackGate.Commit() {
                    @Override
                    public void run() throws Exception {
                        inCommit.countDown();
                        release.await();
                        order.add(quick.isMuted() ? "renamed while muted" : "renamed");
                    }
                });
            }
        });
        e.submit(lobby("GAME"));
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                e.step();
            }
        }, "lobby-writer");
        writer.start();
        assertTrue(inCommit.await(5, TimeUnit.SECONDS));
        quick.beginEntry();
        assertFalse("the commit section began before the abort", quick.tryCompleteEntry());
        assertFalse(quick.isMuted());
        release.countDown();
        while (!quick.tryCompleteEntry()) {
            Thread.sleep(1);
        }
        order.add("entry complete");
        writer.join(5_000);
        assertEquals(java.util.Arrays.asList("renamed", "entry complete"), order);
    }

    /** The real file step: an aborted permit leaves {@code lobby.json} as it was and no temp file behind. */
    @Test
    public void anAbortedPermitLeavesTheFileAndNoTempFile() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("lobby-export-gate").toFile();
        try {
            java.io.File file = new java.io.File(dir, "lobby.json");
            java.io.File tmp = new java.io.File(dir, "lobby.json.test.tmp");
            java.nio.file.Files.write(file.toPath(), "old".getBytes("UTF-8"));
            PlaybackGate quick = new PlaybackGate(0);
            PlaybackGate.Permit permit = quick.tryBegin(null);
            quick.beginEntry();
            assertTrue(quick.tryCompleteEntry());
            try {
                LobbyExport.writeAtomically(dir, tmp, "new", permit);
                org.junit.Assert.fail("an aborted permit's write is refused");
            } catch (PlaybackGate.PlaybackMutedException expected) {
                // ok
            }
            assertEquals("old", new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8"));
            assertFalse("temp file cleaned", tmp.exists());

            PlaybackGate open = new PlaybackGate(0);
            PlaybackGate.Permit ok = open.tryBegin(null);
            LobbyExport.writeAtomically(dir, tmp, "new", ok);
            ok.end();
            assertEquals("new", new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8"));
            assertFalse(tmp.exists());
        } finally {
            java.io.File[] left = dir.listFiles();
            if (left != null) for (java.io.File f : left) f.delete();
            dir.delete();
        }
    }
}
