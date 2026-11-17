package com.bedwarsqol.rewind;

import com.bedwarsqol.rewind.RewindGameRules.Command;
import com.bedwarsqol.rewind.RewindGameRules.CommandType;
import com.bedwarsqol.rewind.RewindWriterTest.FileContents;
import com.bedwarsqol.rewind.RewindWriterTest.Fixture;
import com.bedwarsqol.rewind.RewindWriterTest.NetRec;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bedwarsqol.rewind.RewindWriterTest.packet;
import static com.bedwarsqol.rewind.RewindWriterTest.read;
import static com.bedwarsqol.rewind.RewindWriterTest.s01;
import static com.bedwarsqol.rewind.RewindWriterTest.snapshot;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Close protocol (PLAN "Sync to a cutoff", "Close protocol"): the NET producer runs on its own thread
 * and rolls its chunk at the exact windows of the writer's sync, driven by latches through the
 * test-only {@link RewindWriter.CloseHooks}. In every case the file holds every record committed
 * before the cutoff (P, the client thread's MARKER and GAME_END), then INDEX and END, and nothing
 * committed after the cutoff ever changes it.
 */
public class RewindWriterCloseTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ExecutorService netty;
    private Thread nettyThread;

    @Before
    public void startNetty() throws Exception {
        netty = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "fake-netty");
                t.setDaemon(true);
                nettyThread = t;
                return t;
            }
        });
        onNetty(new Runnable() {
            @Override
            public void run() {
            }
        });
    }

    @After
    public void stopNetty() {
        netty.shutdownNow();
    }

    /** Runs {@code r} on the producer thread and waits for it (a latch both ways). */
    private void onNetty(Runnable r) {
        try {
            netty.submit(r).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("producer thread task failed", e);
        }
    }

    private final byte[] pPacket = packet(0x21, 20, 1);
    /** Too large for what is left of C0, so appending it rolls to C1. */
    private final byte[] qPacket = packet(0x22, 100, 2);
    private final byte[] rPacket = packet(0x23, 30, 3);

    /** A single-player game is open with S01 written; P sits in the live chunk C0 with no handoff. */
    private Fixture openGameWithP() {
        final Fixture f = new Fixture(new File(tmp.getRoot(), "rewind"), null, 2_000_000L);
        f.connect(true, 128, 8);
        f.advance(1_000);
        onNetty(new Runnable() {
            @Override
            public void run() {
                f.netBoundary(0, null, s01(42, 0));
            }
        });
        f.clientBoundary(0, snapshot(0));
        f.tick(1);
        f.step();
        assertEquals("game file open", 1, RewindWriterTest.ids(f.dir).size());
        f.advance(5_000);
        onNetty(new Runnable() {
            @Override
            public void run() {
                f.net(pPacket);
                assertEquals("P in C0", 1, f.net.current().seq());
            }
        });
        return f;
    }

    /** Client thread: terminal TRACK records first, then CLOSE through the queue. */
    private void clientCloses(Fixture f) {
        f.advance(1_000);
        f.marker(RewindFormat.MARKER_DEATH);
        f.gameEnd(RewindFormat.GAME_END_LOSS);
        List<Command> close = f.rules.onDisconnect(f.clock.nowUs, false);
        assertEquals(CommandType.CLOSE, close.get(0).type);
        f.submit(close);
    }

    private Runnable append(final Fixture f, final byte[] packet) {
        return new Runnable() {
            @Override
            public void run() {
                f.advance(0);
                f.net(packet);
            }
        };
    }

    private static List<byte[]> netBytes(List<NetRec> recs) {
        List<byte[]> out = new ArrayList<byte[]>();
        for (NetRec n : recs) {
            out.add(n.bytes);
        }
        return out;
    }

    /** P, the client thread's MARKER and GAME_END, then INDEX and END. */
    private static FileContents assertClosedWith(Fixture f, List<byte[]> expectedNet) throws Exception {
        File cbr = RewindWriterTest.onlyCbr(f.dir);
        FileContents fc = read(cbr);
        assertTrue("complete", fc.complete);
        assertEquals(0, fc.blocksAfterEnd);
        List<byte[]> got = netBytes(fc.net);
        assertEquals("net records", expectedNet.size(), got.size());
        for (int i = 0; i < got.size(); i++) {
            assertArrayEquals("net #" + i, expectedNet.get(i), got.get(i));
        }
        RewindWriterTest.assertTrackEquals(f.trackSent, fc.track);
        int n = fc.track.size();
        assertEquals(RewindFormat.TAG_MARKER, fc.track.get(n - 2).tag);
        assertEquals(RewindFormat.TAG_GAME_END, fc.track.get(n - 1).tag);
        int b = fc.blocks.size();
        assertEquals(RewindFormat.BLOCK_INDEX, fc.blocks.get(b - 2).type);
        assertEquals(RewindFormat.BLOCK_END, fc.blocks.get(b - 1).type);
        assertEquals("disconnect", fc.endMeta.endedBy);
        assertEquals("the rules gave no result; the client's GAME_END did",
                RecordingMeta.RESULT_LOSS, fc.endMeta.result);
        return fc;
    }

    /** Anything committed after the cutoff (R, a late handlerRemoved handoff) leaves the file as it is. */
    private void assertLateDataDropped(final Fixture f, long lateBefore) throws Exception {
        File cbr = RewindWriterTest.onlyCbr(f.dir);
        byte[] before = Files.readAllBytes(cbr.toPath());
        byte[] jsonBefore = Files.readAllBytes(new File(f.dir, cbr.getName().replace(".cbr", ".json")).toPath());
        f.advance(1_000);
        onNetty(append(f, rPacket));
        f.trackRecord(RewindFormat.TAG_MARKER, new byte[]{RewindFormat.MARKER_KEY}, 1);
        f.step();
        f.advance(RewindWriter.SYNC_INTERVAL_US);
        f.step();
        onNetty(new Runnable() {
            @Override
            public void run() {
                f.net.handOff(); // handlerRemoved after Record Games off
            }
        });
        f.track.handOff();
        f.step();
        f.advance(RewindWriter.SYNC_INTERVAL_US);
        f.step();
        assertArrayEquals("file byte-identical after END", before, Files.readAllBytes(cbr.toPath()));
        assertArrayEquals(jsonBefore,
                Files.readAllBytes(new File(f.dir, cbr.getName().replace(".cbr", ".json")).toPath()));
        assertTrue("late records counted", f.writer.lateBytes() > lateBefore);
        assertTrue(f.writer.lateRanges() > 0);
    }

    // ------------------------------------------------------------------ (a)

    @Test
    public void delayedRemovalCloseCopiesTheLiveTail() throws Exception {
        Fixture f = openGameWithP();
        clientCloses(f);
        f.step();
        assertClosedWith(f, list(s01(42, 0), pPacket));
        assertLateDataDropped(f, 0);
    }

    // ------------------------------------------------------------------ (b)

    @Test
    public void rolloverAfterTheCutoffReadFindsChunkSAtTheRingHead() throws Exception {
        final Fixture f = openGameWithP();
        clientCloses(f);
        final AtomicInteger fired = new AtomicInteger();
        f.writer.setCloseHooks(new RewindWriter.CloseHooks() {
            @Override
            public void at(RewindWriter.HookPoint point, RecordChunk.Producer producer) {
                if (point == RewindWriter.HookPoint.AFTER_CUTOFF && producer == f.net && fired.getAndIncrement() == 0) {
                    onNetty(append(f, qPacket));
                    onNetty(new Runnable() {
                        @Override
                        public void run() {
                            assertEquals("Q rolled to C1", 2, f.net.current().seq());
                        }
                    });
                }
            }
        });
        f.step();
        f.writer.setCloseHooks(null);
        assertEquals(1, fired.get());
        assertClosedWith(f, list(s01(42, 0), pPacket));
        long late = f.writer.lateBytes();
        f.step();
        f.advance(RewindWriter.SYNC_INTERVAL_US);
        f.step();
        assertTrue("Q is past the cutoff and counted", f.writer.lateBytes() > late);
        assertLateDataDropped(f, f.writer.lateBytes());
    }

    // ------------------------------------------------------------------ (c)

    @Test
    public void rolloverBetweenDrainAndTailReadKeepsP() throws Exception {
        final Fixture f = openGameWithP();
        clientCloses(f);
        final AtomicInteger fired = new AtomicInteger();
        f.writer.setCloseHooks(new RewindWriter.CloseHooks() {
            @Override
            public void at(RewindWriter.HookPoint point, RecordChunk.Producer producer) {
                if (point == RewindWriter.HookPoint.AFTER_DRAIN && producer == f.net && fired.getAndIncrement() == 0) {
                    onNetty(append(f, qPacket));
                    onNetty(new Runnable() {
                        @Override
                        public void run() {
                            assertEquals("Q rolled to C1", 2, f.net.current().seq());
                        }
                    });
                }
            }
        });
        f.step();
        f.writer.setCloseHooks(null);
        assertEquals(1, fired.get());
        assertClosedWith(f, list(s01(42, 0), pPacket));
        long late = f.writer.lateBytes();
        f.advance(RewindWriter.SYNC_INTERVAL_US);
        f.step();
        assertTrue("Q is past the cutoff and counted", f.writer.lateBytes() > late);
        assertLateDataDropped(f, f.writer.lateBytes());
    }

    // ------------------------------------------------------------------ (d)

    @Test
    public void rolloverBeforeCloseIsProcessedDrainsC0ThenTheC1Tail() throws Exception {
        final Fixture f = openGameWithP();
        clientCloses(f);
        onNetty(append(f, qPacket));
        onNetty(new Runnable() {
            @Override
            public void run() {
                assertEquals("C1 published before the cutoff", 2, f.net.current().seq());
            }
        });
        assertNotEquals(null, f.net.peekFull());
        f.step();
        assertClosedWith(f, list(s01(42, 0), pPacket, qPacket));
        assertLateDataDropped(f, 0);
    }

    // ------------------------------------------------------------------ shutdown

    @Test
    public void shutdownClosesTheOpenGameWithEveryCommittedRecordFromLiveChunks() throws Exception {
        final Fixture f = new Fixture(new File(tmp.getRoot(), "rewind"), null, 2_000_000L);
        f.writer.start();
        f.connect(true, 4096, 8);
        f.advance(1_000);
        onNetty(new Runnable() {
            @Override
            public void run() {
                f.netBoundary(0, RewindWriterTest.s47("FakeCarry"), s01(42, 0));
            }
        });
        f.clientBoundary(0, snapshot(0));
        for (int i = 0; i < 20; i++) {
            f.advance(2_000);
            onNetty(append(f, packet(0x30, 10 + i, i)));
            f.tick(i);
        }
        f.marker(RewindFormat.MARKER_KEY);
        // No handoff anywhere: both producers' records sit in live chunks.
        assertTrue("shutdown finished within the deadline", f.writer.shutdown(RewindWriter.SHUTDOWN_DEADLINE_MS));

        File cbr = RewindWriterTest.onlyCbr(f.dir);
        FileContents fc = read(cbr);
        assertTrue(fc.complete);
        RewindWriterTest.assertNetEquals(f.netSent, fc.net);
        RewindWriterTest.assertTrackEquals(f.trackSent, fc.track);
        assertEquals("quit", fc.endMeta.endedBy);
        assertEquals("quit", RewindWriterTest.jsonOf(cbr).endedBy);
        assertTrue(RewindWriterTest.jsonOf(cbr).keyMarked);
        assertTrue(nettyThread.isAlive());
    }

    private static List<byte[]> list(byte[]... b) {
        List<byte[]> out = new ArrayList<byte[]>();
        for (byte[] x : b) {
            out.add(x);
        }
        return out;
    }
}
