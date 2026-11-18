package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Admission permits and the staged gate entry (PLAN section 5 layer 4; Phase 5b; review round 2 B4). A permit is
 * decided under the gate's lock; entry refuses new permits, waits for running ones up to the drain bound, then
 * aborts the rest (flag, then {@code disconnect()} on the attached connection), mutes and advances the epoch.
 */
public class PlaybackGatePermitTest {

    /** Records {@code disconnect()}; never connects. */
    static final class FakeConnection extends HttpURLConnection {
        final AtomicInteger disconnects = new AtomicInteger();

        FakeConnection() throws IOException {
            super(new URL("http://127.0.0.1:9/"));
        }

        @Override
        public void disconnect() {
            disconnects.incrementAndGet();
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
            throw new AssertionError("never connects");
        }
    }

    @Test
    public void theProductionDrainBoundIsThreeSeconds() {
        assertEquals(3000L, PlaybackGate.DRAIN_MS);
        assertEquals(3000L, PlaybackGate.global().drainMs());
    }

    @Test
    public void aMutedRefusalIsAnIOException() {
        assertTrue(new PlaybackGate.PlaybackMutedException("x") instanceof IOException);
    }

    @Test
    public void permitsAreGivenWhileUnmutedAndRefusedWhileMutedOrEntering() {
        PlaybackGate gate = new PlaybackGate(200);
        PlaybackGate.Permit p = gate.tryBegin(null);
        assertNotNull(p);
        p.end();
        gate.beginEntry();
        assertTrue(gate.isEntering());
        assertFalse("events are not muted until entry completes", gate.isMuted());
        assertNull("refused from the first step of entry", gate.tryBegin(null));
        assertTrue("nothing running: entry completes at once", gate.tryCompleteEntry());
        assertFalse(gate.isEntering());
        assertTrue(gate.isMuted());
        assertEquals(1L, gate.epoch());
        assertNull(gate.tryBegin(null));
        gate.exit();
        assertNotNull(gate.tryBegin(null));
    }

    @Test
    public void aTicketDecidesByEpochANullTicketByStateAlone() {
        PlaybackGate gate = new PlaybackGate(200);
        PlaybackGate.Ticket before = gate.currentTicket();
        assertNotNull(gate.tryBegin(before));
        gate.enter();
        gate.exit();
        assertNull("stale ticket", gate.tryBegin(before));
        assertNotNull("null ticket: any session", gate.tryBegin(null));
        assertNotNull(gate.tryBegin(gate.currentTicket()));
    }

    @Test
    public void ticketsAreNotCurrentWhileEntering() {
        PlaybackGate gate = new PlaybackGate(200);
        PlaybackGate.Ticket t = gate.currentTicket();
        gate.beginEntry();
        assertFalse("entry refuses new effects", gate.isCurrent(t));
    }

    @Test
    public void entryWaitsForARunningPermitAndCompletesWhenItEnds() {
        PlaybackGate gate = new PlaybackGate(60_000);
        PlaybackGate.Permit p = gate.tryBegin(null);
        gate.beginEntry();
        assertFalse(gate.tryCompleteEntry());
        assertFalse(gate.tryCompleteEntry());
        assertFalse(gate.isMuted());
        p.end();
        assertTrue(gate.tryCompleteEntry());
        assertTrue(gate.isMuted());
        assertFalse("ended normally, never aborted", p.aborted());
    }

    @Test
    public void afterTheDrainBoundTheRestAreAbortedAndTheirConnectionsDisconnected() throws Exception {
        PlaybackGate gate = new PlaybackGate(150);
        PlaybackGate.Permit held = gate.tryBegin(null);
        FakeConnection conn = new FakeConnection();
        held.attach(conn);
        long t0 = System.nanoTime();
        gate.beginEntry();
        assertFalse(gate.tryCompleteEntry());
        while (!gate.tryCompleteEntry()) {
            Thread.sleep(5);
        }
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue("waited the drain bound: " + waitedMs, waitedMs >= 150);
        assertTrue(held.aborted());
        assertEquals(1, conn.disconnects.get());
        assertTrue(gate.isMuted());
        try {
            held.check();
            fail("an aborted permit refuses every start point");
        } catch (PlaybackGate.PlaybackMutedException expected) {
            // ok
        }
        held.end(); // late end from the worker: harmless
        held.end();
        assertTrue(gate.isMuted());
    }

    @Test
    public void attachingAfterTheAbortDisconnectsAtOnce() throws Exception {
        PlaybackGate gate = new PlaybackGate(0);
        PlaybackGate.Permit held = gate.tryBegin(null);
        gate.beginEntry();
        assertTrue(gate.tryCompleteEntry());
        assertTrue(held.aborted());
        FakeConnection late = new FakeConnection();
        held.attach(late);
        assertEquals(1, late.disconnects.get());
    }

    @Test
    public void failureMapsToMutedOnlyForAnAbortedPermit() throws Exception {
        PlaybackGate gate = new PlaybackGate(0);
        PlaybackGate.Permit p = gate.tryBegin(null);
        IOException io = new IOException("socket closed");
        assertSame(io, p.failure(io));
        p.check(); // not aborted: no throw
        gate.beginEntry();
        gate.tryCompleteEntry();
        IOException mapped = p.failure(io);
        assertTrue(mapped instanceof PlaybackGate.PlaybackMutedException);
        assertSame(io, mapped.getCause());
        PlaybackGate.PlaybackMutedException already = new PlaybackGate.PlaybackMutedException("x");
        assertSame(already, p.failure(already));
    }

    @Test
    public void admitThrowsWhenRefused() throws Exception {
        PlaybackGate gate = new PlaybackGate(0);
        gate.admit().end();
        gate.enter();
        try {
            gate.admit();
            fail();
        } catch (PlaybackGate.PlaybackMutedException expected) {
            // ok
        }
    }

    @Test(timeout = 10_000)
    public void aWorkerReleasedDuringTheDrainFinishesBeforeEntryCompletes() throws Exception {
        final PlaybackGate gate = new PlaybackGate(60_000);
        final CountDownLatch admitted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean workDone = new AtomicBoolean();
        final AtomicBoolean doneBeforeMuted = new AtomicBoolean();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                PlaybackGate.Permit p = gate.tryBegin(null);
                admitted.countDown();
                try {
                    release.await();
                    workDone.set(true);
                    doneBeforeMuted.set(!gate.isMuted());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    p.end();
                }
            }
        }, "permit-worker");
        worker.start();
        assertTrue(admitted.await(5, TimeUnit.SECONDS));
        gate.beginEntry();
        assertFalse(gate.tryCompleteEntry());
        release.countDown();
        while (!gate.tryCompleteEntry()) {
            Thread.sleep(1);
        }
        worker.join(5_000);
        assertTrue(workDone.get());
        assertTrue("the work ran while the gate was still unmuted", doneBeforeMuted.get());
    }

    @Test(timeout = 10_000)
    public void aWorkerAskingAfterEntryBeganIsRefusedEvenBeforeEntryCompletes() throws Exception {
        final PlaybackGate gate = new PlaybackGate(60_000);
        PlaybackGate.Permit running = gate.tryBegin(null);
        gate.beginEntry();
        final AtomicReference<PlaybackGate.Permit> late = new AtomicReference<PlaybackGate.Permit>();
        final AtomicBoolean asked = new AtomicBoolean();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                late.set(gate.tryBegin(null));
                asked.set(true);
            }
        });
        worker.start();
        worker.join(5_000);
        assertTrue(asked.get());
        assertNull(late.get());
        running.end();
        assertTrue(gate.tryCompleteEntry());
    }

    @Test(timeout = 10_000)
    public void blockingEnterReturnsWhenTheLastPermitEnds() throws Exception {
        final PlaybackGate gate = new PlaybackGate(60_000);
        final PlaybackGate.Permit p = gate.tryBegin(null);
        Thread ender = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    // end anyway
                }
                p.end();
            }
        });
        ender.start();
        gate.enter();
        assertTrue(gate.isMuted());
        assertFalse(p.aborted());
    }

    @Test(timeout = 10_000)
    public void blockingEnterAbortsAtTheDrainBound() {
        PlaybackGate gate = new PlaybackGate(100);
        PlaybackGate.Permit p = gate.tryBegin(null);
        long t0 = System.nanoTime();
        gate.enter();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) >= 100);
        assertTrue(p.aborted());
        assertTrue(gate.isMuted());
    }

    @Test(timeout = 10_000)
    public void awaitUnmutedBlocksWhileEnteringOrMutedAndReturnsAfterExit() throws Exception {
        final PlaybackGate gate = new PlaybackGate(60_000);
        gate.awaitUnmuted(); // unmuted: returns at once
        PlaybackGate.Permit p = gate.tryBegin(null);
        gate.beginEntry();
        final CountDownLatch waiting = new CountDownLatch(1);
        final AtomicBoolean passed = new AtomicBoolean();
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                waiting.countDown();
                try {
                    gate.awaitUnmuted();
                    passed.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        waiter.start();
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertFalse("entering", passed.get());
        p.end();
        assertTrue(gate.tryCompleteEntry());
        Thread.sleep(50);
        assertFalse("muted", passed.get());
        gate.exit();
        waiter.join(5_000);
        assertTrue(passed.get());
    }

    @Test
    public void exitWhileEnteringCancelsTheEntry() {
        PlaybackGate gate = new PlaybackGate(60_000);
        PlaybackGate.Permit p = gate.tryBegin(null);
        PlaybackGate.Ticket t = gate.currentTicket();
        gate.beginEntry();
        gate.exit();
        assertFalse(gate.isEntering());
        assertFalse(gate.isMuted());
        assertFalse("epoch moved on", gate.isCurrent(t));
        assertNotNull(gate.tryBegin(null));
        p.end();
        assertFalse("not begun: nothing to complete", gate.tryCompleteEntry());
    }
}
