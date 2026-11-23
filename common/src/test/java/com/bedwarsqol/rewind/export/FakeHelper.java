package com.bedwarsqol.rewind.export;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for {@code cobblify-encode encode} in {@link HelperProcessTest}, started as its own JVM with the test's
 * class path: {@code FakeHelper MODE OUT [args...]}. Modes: {@code ok} (ready, copies every packet byte to OUT, a
 * frames line per frame, done on kind 3, then waits for its input to close), {@code protocol2} (a ready line with the
 * wrong protocol), {@code stall} (ready, then never reads), {@code exit5} (ready, then exits 5), {@code error} (ready,
 * then an error line and exit 2), {@code args} (writes its arguments after OUT to OUT, one per line, then ready and
 * waits), {@code probe} (writes its arguments after OUT to OUT, then noise on both streams and one probe line, exit
 * 0), {@code probe-none} (noise only, exit 4), {@code hold} (ready, then keeps the
 * file after {@code --out} in place until it is killed), {@code stall-out} (makes the file after {@code --out}, ready,
 * then never reads), {@code slow-cancel} (makes the file after {@code --out}, ready, and 1 s after its input closed
 * deletes that path and exits 3, as the real helper's slow cancel does).
 */
public final class FakeHelper {

    private FakeHelper() {
    }

    public static void main(String[] a) throws Exception {
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        String mode = a[0];
        switch (mode) {
            case "protocol2":
                out.println("{\"ready\":true,\"protocol\":2}");
                drain(System.in);
                return;
            case "stall":
                out.println("{\"ready\":true,\"protocol\":1}");
                Thread.sleep(60_000);
                return;
            case "exit5":
                out.println("{\"ready\":true,\"protocol\":1}");
                out.flush();
                System.exit(5);
                return;
            case "error":
                out.println("{\"ready\":true,\"protocol\":1}");
                out.println("{\"error\":\"encoder_refused\",\"status\":-3}");
                System.exit(2);
                return;
            case "args":
                try (OutputStream f = new FileOutputStream(a[1])) {
                    for (int i = 2; i < a.length; i++) {
                        f.write((a[i] + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
                out.println("{\"ready\":true,\"protocol\":1}");
                drain(System.in);
                return;
            case "probe":
                try (OutputStream f = new FileOutputStream(a[1])) {
                    for (int i = 2; i < a.length; i++) {
                        f.write((a[i] + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
                for (int i = 0; i < 2000; i++) {
                    System.err.println("diagnostic line " + i + " that the game never logs");
                }
                out.println("probing...");
                out.println("{\"protocol\":1,\"videosDir\":\"/Users/x/Movies\",\"h264\":[[1920,1080,60]],\"hevc\":[]}");
                System.exit(0);
                return;
            case "probe-none":
                out.println("probing...");
                System.exit(4);
                return;
            case "hold":
                holdOutput(out, a);
                return;
            case "stall-out":
                // ready with its file made, then never reads and never goes on its own (killed after 2 s)
                new FileOutputStream(outArg(a)).close();
                out.println("{\"ready\":true,\"protocol\":1}");
                Thread.sleep(60_000);
                return;
            case "slow-cancel": {
                // ready with its file made; when its input closes it takes 1 s, then deletes that path and exits 3
                java.io.File f = outArg(a);
                new FileOutputStream(f).close();
                out.println("{\"ready\":true,\"protocol\":1}");
                drain(System.in);
                Thread.sleep(1_000);
                f.delete();
                System.exit(3);
                return;
            }
            default:
                break;
        }
        out.println("{\"ready\":true,\"protocol\":1,\"encoder\":\"fake\"}");
        DataInputStream in = new DataInputStream(System.in);
        long frames = 0;
        try (OutputStream f = new FileOutputStream(a[1])) {
            while (true) {
                int kind;
                try {
                    kind = in.readUnsignedByte();
                } catch (EOFException e) {
                    System.exit(3); // input closed without kind 3: cancelled
                    return;
                }
                byte[] len = new byte[4];
                in.readFully(len);
                int n = (len[0] & 0xFF) | (len[1] & 0xFF) << 8 | (len[2] & 0xFF) << 16 | (len[3] & 0xFF) << 24;
                byte[] payload = new byte[n];
                in.readFully(payload);
                f.write(kind);
                f.write(len);
                f.write(payload);
                f.flush();
                if (kind == 1) {
                    frames++;
                    out.println("{\"frames\":" + frames + "}");
                } else if (kind == 3) {
                    out.println("{\"done\":true,\"frames\":" + frames + "}");
                    break;
                }
            }
        }
        drain(System.in); // stdin stays open until the done line was read
        System.exit(0);
    }

    /**
     * Ready, then the file after {@code --out} stays there whatever happens to the input, as a Windows writer that
     * still holds it open would keep it (a delete fails there; here the file is made again at once), until killed.
     */
    private static void holdOutput(PrintStream out, String[] a) throws Exception {
        java.io.File file = null;
        for (int i = 2; i + 1 < a.length; i++) {
            if ("--out".equals(a[i])) {
                file = new java.io.File(a[i + 1]);
            }
        }
        new FileOutputStream(file).close();
        out.println("{\"ready\":true,\"protocol\":1}");
        Thread reader = new Thread(() -> {
            try {
                drain(System.in);
            } catch (IOException ignored) {
                // closed
            }
        });
        reader.setDaemon(true);
        reader.start();
        while (true) {
            if (!file.exists()) {
                new FileOutputStream(file).close();
            }
            Thread.sleep(5);
        }
    }

    /** The file after {@code --out}. */
    private static java.io.File outArg(String[] a) {
        for (int i = 2; i + 1 < a.length; i++) {
            if ("--out".equals(a[i])) {
                return new java.io.File(a[i + 1]);
            }
        }
        throw new IllegalArgumentException("no --out");
    }

    private static void drain(InputStream in) throws IOException {
        byte[] b = new byte[4096];
        while (in.read(b) >= 0) {
            // until the game closes our input
        }
    }
}
