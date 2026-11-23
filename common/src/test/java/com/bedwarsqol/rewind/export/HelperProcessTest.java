package com.bedwarsqol.rewind.export;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link HelperProcess} against {@link FakeHelper} (a real child process: the test JVM's own java and class path).
 * TASK X2, U6; PLAN Solution 4: the ready handshake; a wrong protocol fails; frames and sound arrive in order and
 * byte-exact; a helper that stops reading is failed by the stall watchdog and killed; one that exits early fails with
 * its code; the output path is one argument even with spaces and quotes; stdin stays open until the done line.
 */
public class HelperProcessTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<HelperProcess> started = new ArrayList<HelperProcess>();

    @After
    public void killAll() {
        for (HelperProcess p : started) {
            p.cancel();
        }
    }

    private static List<String> fake(String mode, File out, String... more) {
        List<String> c = new ArrayList<String>();
        c.add(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath());
        c.add("-cp");
        c.add(System.getProperty("java.class.path"));
        c.add(FakeHelper.class.getName());
        c.add(mode);
        c.add(out.getAbsolutePath());
        c.addAll(Arrays.asList(more));
        return c;
    }

    private HelperProcess start(List<String> command, int frameBytes, long stallMs) {
        HelperProcess p = new HelperProcess(command, frameBytes, HelperProcess.processBuilder(), stallMs);
        started.add(p);
        p.start();
        return p;
    }

    private static void await(HelperProcess p, ExportJob.Encoder.Status want, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (p.status() != want && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertEquals("status (failure " + p.failure() + ")", want, p.status());
    }

    private static ByteBuffer frame(int bytes, int seed) {
        ByteBuffer b = ByteBuffer.allocateDirect(bytes);
        for (int i = 0; i < bytes; i++) {
            b.put((byte) (i * 31 + seed));
        }
        b.flip();
        return b;
    }

    private static void packet(ByteArrayOutputStream want, int kind, byte[] payload) {
        want.write(HelperProtocol.header(kind, payload.length), 0, 5);
        want.write(payload, 0, payload.length);
    }

    private static byte[] bytes(ByteBuffer b) {
        byte[] a = new byte[b.remaining()];
        b.duplicate().get(a);
        return a;
    }

    @Test
    public void framesAndSoundArriveInOrderAndByteExact() throws Exception {
        File out = tmp.newFile("received");
        int n = 64 * 32 * 4;
        HelperProcess p = start(fake("ok", out), n, 10_000);
        await(p, ExportJob.Encoder.Status.READY, 20_000);
        ByteBuffer f0 = frame(n, 0), f1 = frame(n, 7), f2 = frame(n, 99);
        short[] s = {1, -1, 32767, -32768, 300, -300};
        p.frame(f0);
        p.frame(f1);
        p.sound(s, 2); // two stereo samples of the six
        p.frame(f2);
        f2.put(0, (byte) 0x55); // the caller's buffer may be reused at once: the copy was taken
        p.finish();
        await(p, ExportJob.Encoder.Status.DONE, 20_000);
        assertEquals(3, p.framesReported());

        ByteArrayOutputStream want = new ByteArrayOutputStream();
        packet(want, 1, bytes(f0));
        packet(want, 1, bytes(f1));
        packet(want, 2, new byte[]{1, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F, 0, (byte) 0x80});
        byte[] b2 = bytes(f2);
        b2[0] = (byte) (0 * 31 + 99);
        packet(want, 1, b2);
        packet(want, 3, new byte[0]);
        assertArrayEquals(want.toByteArray(), Files.readAllBytes(out.toPath()));
        long end = System.currentTimeMillis() + 10_000;
        while (p.alive() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertFalse("after the done line the input is closed and the helper exits", p.alive());
    }

    @Test
    public void aWrongProtocolFails() throws Exception {
        HelperProcess p = start(fake("protocol2", tmp.newFile("x")), 16, 10_000);
        await(p, ExportJob.Encoder.Status.FAILED, 20_000);
        assertEquals("protocol", p.failure());
    }

    @Test
    public void aHelperThatStopsReadingIsFailedByTheWatchdogAndKilled() throws Exception {
        int n = 1024 * 1024; // larger than any pipe buffer
        HelperProcess p = start(fake("stall", tmp.newFile("x")), n, 1_000);
        await(p, ExportJob.Encoder.Status.READY, 20_000);
        long t0 = System.currentTimeMillis();
        ByteBuffer f = frame(n, 1);
        for (int i = 0; i < 6 && p.status() == ExportJob.Encoder.Status.READY; i++) {
            p.frame(f); // the queue fills, then the game thread would block: the watchdog ends it
        }
        await(p, ExportJob.Encoder.Status.FAILED, 15_000);
        assertEquals("stall", p.failure());
        assertTrue("within a few watchdog periods", System.currentTimeMillis() - t0 < 10_000);
        long end = System.currentTimeMillis() + 5_000;
        while (p.alive() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertFalse("killed", p.alive());
    }

    @Test
    public void aHelperThatExitsEarlyFailsWithItsCode() throws Exception {
        HelperProcess p = start(fake("exit5", tmp.newFile("x")), 16, 10_000);
        await(p, ExportJob.Encoder.Status.FAILED, 20_000);
        assertEquals("exit-5", p.failure());
        HelperProcess e = start(fake("error", tmp.newFile("y")), 16, 10_000);
        await(e, ExportJob.Encoder.Status.FAILED, 20_000);
        assertEquals("its error line wins over the exit code", "encoder_refused", e.failure());
    }

    @Test
    public void aMissingProgramFailsToStart() throws Exception {
        HelperProcess p = start(Arrays.asList(new File(tmp.getRoot(), "no such helper").getAbsolutePath(), "encode"),
                16, 10_000);
        await(p, ExportJob.Encoder.Status.FAILED, 5_000);
        assertEquals("start", p.failure());
    }

    @Test
    public void theOutputPathIsOneArgumentEvenWithSpacesAndQuotes() throws Exception {
        File dump = tmp.newFile("args");
        File movie = new File(tmp.getRoot(), "Solo \"x\" - 'Map' $(y) & z.partial.mp4");
        List<String> encode = HelperProtocol.encodeArgs("/ignored", movie, 1920, 1080, 60, "h264", 16_000_000L);
        List<String> tail = encode.subList(1, encode.size());
        HelperProcess p = start(fake("args", dump, tail.toArray(new String[0])), 16, 10_000);
        await(p, ExportJob.Encoder.Status.READY, 20_000);
        List<String> got = Arrays.asList(new String(Files.readAllBytes(dump.toPath()), StandardCharsets.UTF_8)
                .split("\n"));
        assertEquals(tail, got);
        assertEquals(movie.getAbsolutePath(), got.get(got.indexOf("--out") + 1));
    }

    @Test
    public void cancelClosesTheInputAndTheHelperGoes() throws Exception {
        HelperProcess p = start(fake("ok", tmp.newFile("x")), 16, 10_000);
        await(p, ExportJob.Encoder.Status.READY, 20_000);
        p.cancel();
        long end = System.currentTimeMillis() + 5_000;
        while (p.alive() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertFalse(p.alive());
        assertEquals(ExportJob.Encoder.Status.FAILED, p.status());
        assertEquals("cancelled", p.failure());
    }

    /**
     * Code review 1 I2 (TASK F2, X3): a helper that does not go when its input closes (a native finish that blocks) is
     * killed after 2 s, and the unfinished file it kept in place is deleted once it is gone, even though the game's own
     * delete right after the cancel came too early.
     */
    @Test
    public void aCancelledHelperThatHoldsItsFileIsKilledAndTheFileDeletedAfterwards() throws Exception {
        File partial = new File(tmp.getRoot(), "Solo - Map.partial.mp4");
        HelperProcess p = start(fake("hold", tmp.newFile("unused"), "--out", partial.getAbsolutePath()), 16, 10_000);
        await(p, ExportJob.Encoder.Status.READY, 20_000);
        assertTrue(partial.exists());
        long t0 = System.currentTimeMillis();
        p.cancel();
        partial.delete(); // ExportJob's delete right after the cancel: the helper still holds the file
        long end = t0 + 6_000;
        while ((p.alive() || partial.exists()) && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertFalse("killed", p.alive());
        assertFalse("the partial file is deleted after the helper is gone ("
                + (System.currentTimeMillis() - t0) + " ms)", partial.exists());
        Thread.sleep(200);
        assertFalse("and stays deleted", partial.exists());
    }

    /**
     * Rewind Export Phase 7: the probe ({@code helper probe [--size WxH]}) runs to its end from an argument list, its
     * standard error drained and kept out of the result; the probe line is found among other output; no line gives a
     * code with the exit status; a helper that never ends is killed after the limit.
     */
    @Test
    public void theProbeRunsToItsEndAndAHungOneIsKilled() throws Exception {
        File args = tmp.newFile("args");
        HelperProcess.ProbeRun ok = HelperProcess.probe(fake("probe", args, "probe", "--size", "1366x768"), 20_000,
                HelperProcess.processBuilder());
        assertEquals(null, ok.failure);
        assertEquals("/Users/x/Movies", ok.probe.videosDir);
        assertArrayEquals(new int[]{1920, 1080, 60}, ok.probe.h264.get(0));
        assertEquals("probe\n--size\n1366x768\n", new String(Files.readAllBytes(args.toPath()), StandardCharsets.UTF_8));

        HelperProcess.ProbeRun none = HelperProcess.probe(fake("probe-none", tmp.newFile("y")), 20_000,
                HelperProcess.processBuilder());
        assertEquals(null, none.probe);
        assertEquals("no-line-exit-4", none.failure);

        long t0 = System.currentTimeMillis();
        HelperProcess.ProbeRun hung = HelperProcess.probe(fake("stall", tmp.newFile("z")), 1_000,
                HelperProcess.processBuilder());
        long took = System.currentTimeMillis() - t0;
        assertEquals(null, hung.probe);
        assertEquals("timeout", hung.failure);
        assertTrue("killed after the limit, not the helper's 60 s: " + took, took < 15_000);

        HelperProcess.ProbeRun cannot = HelperProcess.probe(Arrays.asList(new File(tmp.getRoot(), "none").getPath(),
                "probe"), 1_000, HelperProcess.processBuilder());
        assertEquals("start", cannot.failure);
    }
}
