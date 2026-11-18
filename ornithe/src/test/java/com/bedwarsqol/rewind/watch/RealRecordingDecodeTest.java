package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RecordingReader;
import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.init.Bootstrap;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Opt-in check on real recordings (PLAN Phase 3, TASK A6): every NET and HEADER packet of every {@code .cbr} in the
 * folder named by {@code COBBLIFY_REWIND_SAMPLES} decodes with vanilla 1.8.9's readers, every byte consumed.
 * Skipped when the variable is unset. Recordings are player data, so the output is one counts-only line per file,
 * numbered by position: never a file name, player name, chat line or map.
 *
 * <p>Run: {@code cd ornithe && COBBLIFY_REWIND_SAMPLES=<folder> ./gradlew test --no-daemon
 * --tests '*RealRecordingDecodeTest'}.
 */
public class RealRecordingDecodeTest {

    @Test
    public void everyStoredPacketDecodesWithVanillaReaders() throws IOException {
        String dir = System.getenv("COBBLIFY_REWIND_SAMPLES");
        Assume.assumeTrue("set COBBLIFY_REWIND_SAMPLES to a folder of .cbr files to run", dir != null
                && !dir.isEmpty());
        File folder = new File(dir);
        File[] files = folder.listFiles((d, name) -> name.endsWith(".cbr"));
        Assume.assumeTrue("no .cbr files in COBBLIFY_REWIND_SAMPLES", files != null && files.length > 0);
        Arrays.sort(files);

        PrintStream out = System.out; // before Bootstrap, which may redirect System.out to its log
        Bootstrap.register();

        long totalNet = 0;
        long totalHeader = 0;
        long totalFailures = 0;
        for (int i = 0; i < files.length; i++) {
            Counts c = decodeAll(files[i]);
            out.println("rewind decode file " + (i + 1) + ": net=" + c.net + " header=" + c.header
                    + " failures=" + c.failures + (c.firstFailure == null ? "" : " first=" + c.firstFailure)
                    + " readerStop=" + c.stop);
            totalNet += c.net;
            totalHeader += c.header;
            totalFailures += c.failures;
            assertTrue("file " + (i + 1) + " holds packets", c.net > 0 && c.header > 0);
        }
        out.println("rewind decode total: files=" + files.length + " net=" + totalNet + " header=" + totalHeader
                + " failures=" + totalFailures);
        assertEquals("packets that vanilla could not decode to their last byte", 0, totalFailures);
    }

    private static final class Counts {
        long net;
        long header;
        long failures;
        /** Packet id and reason only, never content. */
        String firstFailure;
        RecordingReader.Stop stop;

        void decode(byte[] buf, int off, int len) {
            try {
                VanillaPacketDecoder.decode(buf, off, len);
            } catch (IOException e) {
                failures++;
                if (firstFailure == null) {
                    // The decoder's own messages carry no packet content; a wrapped vanilla error might, so only
                    // its class is printed.
                    firstFailure = "0x" + Integer.toHexString(RewindFormat.packetId(buf, off, len)) + "/"
                            + (e.getCause() == null ? e.getMessage() : e.getCause().getClass().getSimpleName());
                }
            }
        }
    }

    private static Counts decodeAll(File file) throws IOException {
        final Counts c = new Counts();
        try (RecordingReader r = RecordingReader.open(file)) {
            RecordingReader.Block b;
            while ((b = r.next()) != null) {
                if (b.type == RewindFormat.BLOCK_NET) {
                    RecordingReader.walkNet(b.payload, 0, b.payload.length, b.firstTimeUs,
                            (timeUs, buf, off, len) -> {
                                c.net++;
                                c.decode(buf, off, len);
                            });
                } else if (b.type == RewindFormat.BLOCK_HEADER) {
                    for (byte[] p : RecordingReader.headerPayload(b).packets) {
                        c.header++;
                        c.decode(p, 0, p.length);
                    }
                }
            }
            c.stop = r.stop();
        }
        return c;
    }
}
