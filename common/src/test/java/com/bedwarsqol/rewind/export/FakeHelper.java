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
 * waits).
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

    private static void drain(InputStream in) throws IOException {
        byte[] b = new byte[4096];
        while (in.read(b) >= 0) {
            // until the game closes our input
        }
    }
}
