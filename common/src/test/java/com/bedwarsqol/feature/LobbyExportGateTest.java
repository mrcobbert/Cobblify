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
            public void write(LobbyExport.Lobby lobby) {
                written.add(lobby.context);
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
            public void write(LobbyExport.Lobby lobby) throws Exception {
                inWrite.countDown();
                release.await();
                order.add("write finished");
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
}
