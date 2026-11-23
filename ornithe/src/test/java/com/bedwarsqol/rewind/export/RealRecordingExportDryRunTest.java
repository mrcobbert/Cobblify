package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.watch.ClientOps;
import com.bedwarsqol.rewind.watch.LoadedRecording;
import com.bedwarsqol.rewind.watch.PacketSink;
import com.bedwarsqol.rewind.watch.PlaybackGate;
import com.bedwarsqol.rewind.watch.PlaybackSession;
import com.bedwarsqol.rewind.watch.TickTable;
import com.bedwarsqol.rewind.watch.Timeline;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Rewind Export PLAN Phase 1 (TASK P2), on every real recording in the folder named by
 * {@code COBBLIFY_REWIND_SAMPLES} (skipped when unset): a 20 s export at 60 fps in the middle of the game, driven by
 * the production {@link PlaybackSession} with the real tick feed into a sink that only counts. The ticks run grow
 * frame by frame, and the last frame has run exactly the ticks recorded at or before its time. Recordings are player
 * data: one counts-only line per file, numbered by position.
 */
public class RealRecordingExportDryRunTest {

    static File[] samples() {
        String dir = System.getenv("COBBLIFY_REWIND_SAMPLES");
        Assume.assumeTrue("COBBLIFY_REWIND_SAMPLES unset", dir != null && !dir.trim().isEmpty());
        File[] files = new File(dir.trim()).listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.endsWith(".cbr");
            }
        });
        Assume.assumeTrue("no .cbr files", files != null && files.length > 0);
        Arrays.sort(files);
        return files;
    }

    /** A client that plays into nothing: every packet taken, the world present after the first boundary. */
    static final class Ops implements ClientOps, PacketSink {
        final File cbr;
        boolean world;
        boolean paused;
        int elapsed;
        long nanos = 1_000_000_000L;
        float timerSpeed = 1f;
        int thirdPerson;
        boolean hideGui;
        boolean showDebug;
        boolean smoothCamera;
        int difficulty;
        int counter;

        Ops(File cbr) {
            this.cbr = cbr;
        }

        @Override public RecordingMeta readMeta(String id) { return null; }
        @Override public boolean connected() { return false; }
        @Override public void disconnect() { }
        @Override public void settleRecorder() { }
        @Override public void showLoading() { }
        @Override public void showViewer() { }

        @Override
        public void startLoad(String id, PlaybackSession.Load load) {
            String name = cbr.getName();
            File json = new File(cbr.getParentFile(), name.substring(0, name.length() - 4) + ".json");
            LoadedRecording r = LoadedRecording.load(cbr, json.isFile() ? json : null);
            if (r.problem() != null) {
                load.fail(PlaybackSession.Failure.UNPLAYABLE, "problem");
            } else {
                load.succeed(r);
            }
        }

        @Override public void resetGui() { }
        @Override public void newConnection() { world = false; }
        @Override public PacketSink sink() { return this; }
        @Override public boolean worldPresent() { return world; }
        @Override public boolean gamePaused() { return paused; }
        @Override public int elapsedTicks() { return elapsed; }
        @Override public void setElapsedTicks(int ticks) { elapsed = ticks; }
        @Override public void setPartialTicks(float partial) { }
        @Override public long nanoTime() { return nanos; }
        @Override public void teardown() { world = false; }
        @Override public void clearTitlesAndBossBar() { }
        @Override public void openTitle() { }
        @Override public void openRejoin() { }
        @Override public void openGallery() { }
        @Override public void log(String line) { }
        @Override public float timerSpeed() { return timerSpeed; }
        @Override public void setTimerSpeed(float speed) { timerSpeed = speed; }
        @Override public int thirdPersonView() { return thirdPerson; }
        @Override public void setThirdPersonView(int view) { thirdPerson = view; }
        @Override public boolean hideGui() { return hideGui; }
        @Override public void setHideGui(boolean hide) { hideGui = hide; }
        @Override public boolean showDebugInfo() { return showDebug; }
        @Override public void setShowDebugInfo(boolean show) { showDebug = show; }
        @Override public boolean smoothCamera() { return smoothCamera; }
        @Override public void setSmoothCamera(boolean smooth) { smoothCamera = smooth; }
        @Override public int difficulty() { return difficulty; }
        @Override public void setDifficulty(int id) { difficulty = id; }
        @Override public void setGamePaused(boolean p) { paused = p; }
        @Override public Object copyChat() { return "chat"; }
        @Override public void restoreChat(Object saved) { }
        @Override public int updateCounter() { return counter; }
        @Override public void setUpdateCounter(int c) { counter = c; }

        // PacketSink: nothing decoded
        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            world = true;
            return true;
        }

        @Override public void boundary(int packetId, byte[] buf, int off, int len) { }
        @Override public boolean sessionHook(int packetId, byte[] buf, int off, int len) { return true; }
        @Override public void sample(long tick, long timeUs) { }
    }

    static void frame(Ops ops, PlaybackSession session, long wallNs) {
        ops.nanos += wallNs;
        session.frameStart();
        for (int j = 0; j < ops.elapsed; j++) {
            session.tickHead();
            session.tickEnd();
        }
        ops.paused = false;
        session.afterPauseWrite();
    }

    @Test
    public void aTwentySecondExportInTheMiddleOfEachRealGameRunsTheTicksAtOrBeforeEachFrame() {
        File[] files = samples();
        for (int n = 0; n < files.length; n++) {
            String label = "file #" + (n + 1);
            Ops ops = new Ops(files[n]);
            PlaybackSession session = new PlaybackSession(ops, new PlaybackGate(3000));
            session.open("sample");
            for (int i = 0; i < 1_000 && session.state() != PlaybackSession.State.WATCHING; i++) {
                frame(ops, session, 0L);
            }
            assertEquals(label + " opens", PlaybackSession.State.WATCHING, session.state());
            Timeline t = session.timeline();
            TickTable table = t.ticks();
            long first = t.timeOfTick(t.startTick());
            long last = t.timeOfTick(t.endTick());
            long mid = first + (last - first) / 2;
            long start = Math.max(first, mid - 10_000_000L);
            long end = Math.min(last, start + 20_000_000L);
            ExportPlan plan = new ExportPlan(start, end, 60);
            long t0 = System.nanoTime();
            assertTrue(label + " begins", session.beginExport(plan));

            long prev = -1;
            long frames = 0;
            long lastTicks = -1;
            for (int guard = 0; guard < 10_000_000 && frames < plan.frames(); guard++) {
                frame(ops, session, 16_666_667L);
                long f = session.exportFrame();
                if (f < 0) {
                    continue;
                }
                assertEquals(label + " frame order", frames, f);
                assertEquals(label + " frame time", plan.timeOfFrame(f), session.renderTimeUs());
                long ticks = session.ticks().ticksRun();
                assertTrue(label + " ticks run are monotonic at frame " + f, ticks >= prev);
                prev = ticks;
                lastTicks = ticks;
                frames++;
                session.advanceExportFrame();
            }
            assertEquals(label + " every frame", plan.frames(), frames);
            long want = Math.min(table.tickOf(plan.timeOfFrame(plan.frames() - 1) + 1), session.ticks().endTick());
            assertEquals(label + " the last frame ran exactly the ticks at or before its time", want, lastTicks);
            session.endExport();
            System.out.println("rewind-export dry run " + label + ": frames=" + frames + " ticksRun=" + lastTicks
                    + " ms=" + (System.nanoTime() - t0) / 1_000_000L);
        }
    }
}
