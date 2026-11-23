package com.bedwarsqol.rewind.export;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One running {@code cobblify-encode encode} (Rewind Export PLAN Solution 4; TASK X2, X3, U6). The program is started
 * from an argument list, never a shell. A writer thread feeds its input from a queue; frames travel in two reused
 * buffers, so the game thread copies a frame and returns at once unless both are still waiting to be written (then it
 * waits, which paces the export to the encoder). A reader thread follows the status lines: the ready handshake (a
 * helper that speaks another protocol fails), progress, {@code done} or an error code. The input stays open until the
 * {@code done} line (closing it earlier cancels the helper, which then deletes its file). A watchdog fails the export
 * and kills the helper when one write has not finished for {@code stallMs}; a helper that exits early fails with its
 * error line's code, else {@code exit-<status>}. Failures: {@code start}, {@code protocol}, {@code stall},
 * {@code write}, {@code exit-N}, the helper's own codes, {@code cancelled}.
 */
public final class HelperProcess implements ExportJob.Encoder {

    /** Starts the program. */
    public interface Launcher {
        Process start(List<String> command) throws IOException;
    }

    /** The default stall limit (PLAN Solution 4: 30 s). */
    public static final long STALL_MS = 30_000;
    /** Frame buffers in flight. */
    static final int FRAME_BUFFERS = 2;

    private static final int FRAME = HelperProtocol.KIND_FRAME;
    private static final int SOUND = HelperProtocol.KIND_SOUND;
    private static final int FINISH = HelperProtocol.KIND_FINISH;
    private static final int STOP = -1;

    /** One packet waiting to be written. */
    private static final class Packet {
        final int kind;
        final ByteBuffer frame;
        final byte[] payload;

        Packet(int kind, ByteBuffer frame, byte[] payload) {
            this.kind = kind;
            this.frame = frame;
            this.payload = payload;
        }
    }

    private final List<String> command;
    private final int frameBytes;
    private final Launcher launcher;
    private final long stallMs;
    private final BlockingQueue<Packet> queue = new ArrayBlockingQueue<Packet>(256);
    private final BlockingQueue<ByteBuffer> free = new ArrayBlockingQueue<ByteBuffer>(FRAME_BUFFERS);
    private final Object lock = new Object();

    private volatile Status status = Status.STARTING;
    private volatile String failure;
    private volatile String errorLine;
    private volatile long framesReported;
    private volatile long writingSince;
    private volatile Process process;
    private volatile OutputStream stdin;
    private volatile String encoderKind;

    /**
     * @param command    the helper and its arguments ({@link HelperProtocol#encodeArgs})
     * @param frameBytes one frame's size ({@code width x height x 4})
     * @param launcher   {@link #processBuilder()} in the game
     * @param stallMs    the watchdog's limit for one write
     */
    public HelperProcess(List<String> command, int frameBytes, Launcher launcher, long stallMs) {
        this.command = command;
        this.frameBytes = frameBytes;
        this.launcher = launcher;
        this.stallMs = stallMs;
    }

    /** One probe run: the probe's line, or why there is none ({@code start}, {@code timeout}, {@code no-line-exit-N}). */
    public static final class ProbeRun {
        public HelperProtocol.Probe probe;
        public String failure;
    }

    /**
     * Runs {@code helper probe [--size WxH]} ({@link HelperProtocol#probeArgs}) to its end (Rewind Export Phase 7):
     * started from the argument list, its input closed, its standard error drained and dropped, its output read line by
     * line for the probe line (the last one that parses). A run still going after {@code timeoutMs} is killed. Blocks:
     * call it off the client thread.
     */
    public static ProbeRun probe(List<String> command, long timeoutMs, Launcher launcher) {
        ProbeRun out = new ProbeRun();
        final Process p;
        try {
            p = launcher.start(command);
        } catch (IOException | RuntimeException e) {
            out.failure = "start";
            return out;
        }
        final boolean[] killed = new boolean[1];
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // the helper reads nothing in probe mode
        }
        thread("cobblify-encode-probe-stderr", () -> drain(p.getErrorStream()));
        thread("cobblify-encode-probe-watchdog", () -> {
            try {
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    synchronized (killed) {
                        killed[0] = true;
                    }
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
            }
        });
        HelperProtocol.Probe got = null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                HelperProtocol.Probe q = HelperProtocol.parseProbe(line);
                if (q != null) {
                    got = q;
                }
            }
        } catch (IOException ignored) {
            // killed or closed: judged below
        }
        int exit;
        try {
            exit = p.waitFor();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            out.failure = "interrupted";
            return out;
        }
        synchronized (killed) {
            if (killed[0]) {
                out.failure = "timeout";
                return out;
            }
        }
        if (got == null) {
            out.failure = "no-line-exit-" + exit;
        } else {
            out.probe = got;
        }
        return out;
    }

    /** {@code new ProcessBuilder(command).start()}: an argument list, no shell. */
    public static Launcher processBuilder() {
        return command -> new ProcessBuilder(command).start();
    }

    /** Starts the program and the three threads; a failure shows in {@link #status()}. */
    public void start() {
        Process p;
        try {
            p = launcher.start(command);
        } catch (IOException | RuntimeException e) {
            fail("start");
            return;
        }
        process = p;
        stdin = p.getOutputStream();
        for (int i = 0; i < FRAME_BUFFERS; i++) {
            free.add(ByteBuffer.allocate(frameBytes));
        }
        thread("cobblify-encode-writer", this::write);
        thread("cobblify-encode-reader", this::read);
        thread("cobblify-encode-stderr", () -> drain(p.getErrorStream()));
        thread("cobblify-encode-watchdog", this::watch);
    }

    @Override
    public Status status() {
        return status;
    }

    @Override
    public String failure() {
        return failure;
    }

    /** Frames the helper says it has encoded. */
    public long framesReported() {
        return framesReported;
    }

    /** {@code hardware}, {@code software} or what the ready line said; null before it. */
    public String encoderKind() {
        return encoderKind;
    }

    /** The helper process is still running. */
    public boolean alive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** Copies one frame ({@code frameBytes} from its position) for writing; waits while both buffers are in use. */
    @Override
    public void frame(ByteBuffer bgra) {
        ByteBuffer b = null;
        while (b == null) {
            if (status == Status.FAILED || status == Status.DONE) {
                return;
            }
            try {
                b = free.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        b.clear();
        ByteBuffer src = bgra.duplicate();
        src.limit(Math.min(src.limit(), src.position() + frameBytes));
        b.put(src);
        b.flip();
        enqueue(new Packet(FRAME, b, null));
    }

    /** One stretch of interleaved stereo samples ({@code frames} of them), little-endian. */
    @Override
    public void sound(short[] interleaved, int frames) {
        if (frames <= 0 || status == Status.FAILED || status == Status.DONE) {
            return;
        }
        byte[] out = new byte[frames * 4];
        for (int i = 0; i < frames * 2; i++) {
            out[2 * i] = (byte) interleaved[i];
            out[2 * i + 1] = (byte) (interleaved[i] >> 8);
        }
        enqueue(new Packet(SOUND, null, out));
    }

    /** No more input: the helper writes the file's index and says done. */
    @Override
    public void finish() {
        enqueue(new Packet(FINISH, null, new byte[0]));
    }

    /** Closes the helper's input (it cancels and deletes its file) and kills it if it is still there after 2 s. */
    @Override
    public void cancel() {
        synchronized (lock) {
            if (status != Status.DONE && status != Status.FAILED) {
                status = Status.FAILED;
                failure = "cancelled";
            }
        }
        queue.clear();
        queue.offer(new Packet(STOP, null, null));
        // closing may wait for a write the helper does not read: never on the caller's (the game's) thread
        thread("cobblify-encode-close", this::closeInput);
        final Process p = process;
        if (p != null && p.isAlive()) {
            thread("cobblify-encode-reaper", () -> {
                try {
                    if (!p.waitFor(2, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    p.destroyForcibly();
                }
            });
        }
    }

    // ------------------------------------------------------------------ threads

    private void enqueue(Packet p) {
        while (status != Status.FAILED && status != Status.DONE) {
            try {
                if (queue.offer(p, 50, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void write() {
        try {
            while (true) {
                Packet p = queue.take();
                if (p.kind == STOP) {
                    return;
                }
                OutputStream out = stdin;
                if (out == null) {
                    return;
                }
                int len = p.frame != null ? p.frame.remaining() : p.payload.length;
                writingSince = System.nanoTime();
                out.write(HelperProtocol.header(p.kind, len));
                if (p.frame != null) {
                    out.write(p.frame.array(), p.frame.arrayOffset() + p.frame.position(), len);
                } else if (len > 0) {
                    out.write(p.payload);
                }
                out.flush();
                writingSince = 0;
                if (p.frame != null) {
                    free.offer(p.frame);
                }
            }
        } catch (IOException e) {
            writingSince = 0;
            if (status != Status.FAILED && status != Status.DONE) {
                awaitExit(2_000);
                fail(errorLine != null ? errorLine : exitCode() >= 0 ? "exit-" + exitCode() : "write");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void read() {
        Process p = process;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                HelperProtocol.Status s = HelperProtocol.parse(line);
                if (s == null) {
                    continue;
                }
                switch (s.kind) {
                    case READY:
                        if (s.protocol != HelperProtocol.PROTOCOL) {
                            fail("protocol");
                            kill();
                        } else {
                            encoderKind = s.encoder;
                            synchronized (lock) {
                                if (status == Status.STARTING) {
                                    status = Status.READY;
                                }
                            }
                        }
                        break;
                    case PROGRESS:
                        framesReported = s.frames;
                        break;
                    case DONE:
                        framesReported = s.frames;
                        synchronized (lock) {
                            if (status == Status.READY) {
                                status = Status.DONE;
                            }
                        }
                        queue.offer(new Packet(STOP, null, null));
                        closeInput(); // the file is complete: now the helper may go
                        break;
                    case ERROR:
                        errorLine = s.error;
                        fail(s.error);
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException ignored) {
            // the stream closed under us: the exit code below says why
        }
        awaitExit(5_000);
        if (status != Status.DONE && status != Status.FAILED) {
            fail(errorLine != null ? errorLine : "exit-" + exitCode());
        }
    }

    private void watch() {
        long step = Math.max(10, Math.min(250, stallMs / 4));
        while (status != Status.DONE && status != Status.FAILED || alive()) {
            long since = writingSince;
            if (since != 0 && System.nanoTime() - since > stallMs * 1_000_000L
                    && status != Status.DONE && status != Status.FAILED) {
                fail("stall");
                kill();
                return;
            }
            if (status == Status.FAILED && alive()) {
                // a failure seen elsewhere: the helper must not outlive it
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    return;
                }
                kill();
                return;
            }
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void fail(String code) {
        synchronized (lock) {
            if (status == Status.FAILED || status == Status.DONE) {
                return;
            }
            failure = code == null ? "failed" : code;
            status = Status.FAILED;
        }
    }

    private void kill() {
        Process p = process;
        if (p != null) {
            p.destroyForcibly(); // first: closing the input waits for a blocked write until the pipe breaks
        }
        closeInput();
    }

    private void closeInput() {
        OutputStream out = stdin;
        stdin = null;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
    }

    private void awaitExit(long ms) {
        Process p = process;
        if (p == null) {
            return;
        }
        try {
            p.waitFor(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int exitCode() {
        Process p = process;
        if (p == null || p.isAlive()) {
            return -1;
        }
        return p.exitValue();
    }

    private static void drain(InputStream in) {
        byte[] b = new byte[4096];
        try {
            while (in.read(b) >= 0) {
                // the helper's own diagnostics stay out of the game log
            }
        } catch (IOException ignored) {
            // closed
        }
    }

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
