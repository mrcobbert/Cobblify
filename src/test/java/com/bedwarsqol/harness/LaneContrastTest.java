package com.bedwarsqol.harness;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Lane contrast: does dispatching lobby chat lookups at {@code PRIORITY_USER} (immediate single),
 * {@code PRIORITY_VISIBLE} (80 ms coalesce, high origin lane) or {@code PRIORITY_TAB} (80 ms
 * coalesce, background lane) change how fast an FKDR resolves?
 *
 * <p>Each arm runs in a fresh JVM: {@code StatsCache}'s static initializer starts its dispatcher and
 * flusher threads and loads the disk cache, with no reset path, so two arms in one JVM would
 * contaminate each other.
 *
 * <p>Latency is characterization, not an expectation. The hard assertions are that every arm
 * resolved every name, and that each arm's requests carried the {@code &prio=1} flag its lane
 * implies — that part is a real regression test for the priority plumbing.
 *
 * <p>Runtime: every arm replays the same 166 s recorded workload, so arms are added sparingly. The
 * warmth sweep stays on the original {USER, TAB} pair; VISIBLE and MIXED are single arms at cold
 * cache, where lane contention is at its worst and the contrast is visible at all.
 */
public class LaneContrastTest {

    /** Cache-warmth sweep: the input we cannot know for real lobbies, so it is varied, not assumed. */
    private static final double[] WARM_FRACTIONS = {0.0, 0.5, 0.9};

    @Test
    public void printLaneContrastAcrossCacheWarmth() throws Exception {
        List<String> results = new ArrayList<String>();
        for (double warm : WARM_FRACTIONS) {
            for (String lane : new String[] {"USER", "TAB"}) {
                results.add(runArm(lane, warm));
            }
        }
        results.add(runArm("VISIBLE", 0.0));
        results.add(runArm("MIXED", 0.0));

        System.out.println("\n=== LANE CONTRAST (real recorded arrivals, modeled 620ms origin gate) ===");
        for (String r : results) System.out.println(r);
        System.out.println("=== end ===\n");

        // Latencies mean nothing unless every name reached a genuine OK with the expected FKDR:
        // ERROR entries are cached too, and a wrong response schema parses as OK with FKDR 0.00.
        for (String r : results) {
            if (!r.contains("unresolved=0") || !r.contains("nonOk=0") || !r.contains("badFkdr=0")) {
                throw new AssertionError("arm did not cleanly resolve every name: " + r);
            }
        }

        // The wire flag, end to end: USER and VISIBLE must reach the backend as high-lane requests
        // and TAB must not. A regression in either the client lane split or the &prio=1 plumbing
        // shows up here, where a latency table alone would just look like noise.
        for (String r : results) {
            boolean high = r.contains("lane=USER") || r.contains("lane=VISIBLE");
            if (high && (!r.contains(" lowRequests=0") || r.contains(" highRequests=0"))) {
                throw new AssertionError("high-lane arm did not send &prio=1 on every request: " + r);
            }
            if (r.contains("lane=TAB") && (!r.contains(" highRequests=0") || r.contains(" lowRequests=0"))) {
                throw new AssertionError("background arm sent &prio=1: " + r);
            }
        }

        // MIXED runs both lanes at once: both lanes must appear on the wire, and no single request
        // may carry names from both, which is what the dispatcher's per-lane partition guarantees.
        for (String r : results) {
            if (!r.contains("lane=MIXED")) continue;
            if (r.contains(" highRequests=0") || r.contains(" lowRequests=0")) {
                throw new AssertionError("mixed arm did not exercise both lanes: " + r);
            }
            if (!r.contains(" mixedRequests=0")) {
                throw new AssertionError("a request mixed visible and tab-only names: " + r);
            }
        }
    }

    private static String runArm(String lane, double warmFraction) throws Exception {
        File configDir = Files.createTempDirectory("cobblify-harness").toFile();
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("-Dcobblify.test.configDir=" + configDir.getAbsolutePath());
        cmd.add(LaneContrastHarness.class.getName());
        cmd.add(lane);
        cmd.add(Double.toString(warmFraction));

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        String result = null;
        StringBuilder all = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                all.append(line).append('\n');
                if (line.startsWith("RESULT ")) result = line;
            }
        }
        int exit = p.waitFor();
        if (result == null) {
            throw new AssertionError("harness arm " + lane + " produced no RESULT (exit " + exit + "):\n" + all);
        }
        return result;
    }
}
