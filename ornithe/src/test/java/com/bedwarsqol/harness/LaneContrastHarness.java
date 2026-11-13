package com.bedwarsqol.harness;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.StatsCache;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One arm of the lane contrast. Runs in its own JVM (see {@code LaneContrastTest}) because
 * {@code StatsCache} starts its dispatcher and flusher threads and loads the disk cache in a static
 * initializer with no reset path — arms would otherwise contaminate each other.
 *
 * <p>Replays a real recorded lobby arrival pattern ({@code lobby-arrivals.txt}: inter-arrival gaps
 * and opaque sender tokens taken from the diag log) through the real
 * {@code StatsCache.ensureFetchedByName} — the name-keyed path a lobby chat line actually uses when
 * the sender has no tab UUID ({@code ChatNameTags.java:329}) — against {@link FakeStatsBackend}.
 *
 * <p>Usage: {@code LaneContrastHarness <USER|VISIBLE|TAB|MIXED> <warmFraction> <configDir>}
 *
 * <p>{@code MIXED} replays the arrivals at VISIBLE while a second thread enqueues a background
 * TAB-only set, so {@code mixedRequests} can prove the dispatcher never puts two lanes in one
 * request. The other arms dispatch every name at a single lane, where that count is vacuously 0.
 */
public final class LaneContrastHarness {

    private static final long GATE_SPACING_MS = 620;  // BATCH_CFG.minSpacingMs
    private static final long SCRAPE_MS = 250;        // modeled Hypixel page service time
    private static final long POLL_MS = 5;
    private static final long QUIESCE_TIMEOUT_MS = 180_000;

    /** MIXED arm background load: small enough that it cannot starve the replay inside the quiesce. */
    private static final String BG_PREFIX = "bgtab";
    private static final int BG_WAVES = 2;
    private static final int BG_PER_WAVE = 16;
    private static final long BG_FIRST_WAVE_MS = 2_000;
    private static final long BG_WAVE_GAP_MS = 58_000;

    public static void main(String[] args) throws Exception {
        String lane = args[0];
        double warmFraction = Double.parseDouble(args[1]);
        boolean mixed = "MIXED".equals(lane);
        final int priority;
        if ("USER".equals(lane)) priority = StatsCache.PRIORITY_USER;
        else if ("VISIBLE".equals(lane) || mixed) priority = StatsCache.PRIORITY_VISIBLE;
        else priority = StatsCache.PRIORITY_TAB;

        List<Arrival> arrivals = loadArrivals();
        FakeStatsBackend backend = new FakeStatsBackend(GATE_SPACING_MS, SCRAPE_MS);

        // Warm a deterministic prefix of the distinct senders (the swept cache-warmth input).
        List<String> distinct = new ArrayList<String>();
        for (Arrival a : arrivals) if (!distinct.contains(a.token)) distinct.add(a.token);
        int warmCount = (int) Math.round(distinct.size() * warmFraction);
        for (int i = 0; i < warmCount; i++) backend.markWarm(distinct.get(i));

        backend.start();
        try {
            ClientSettings cfg = new ClientSettings();
            cfg.statsBackendUrl = backend.baseUrl();
            cfg.statsBackendToken = "harness";
            BedwarsQol.config = cfg;

            // Concurrent: resolution must be sampled WHILE the replay runs. Sampling only after the
            // replay measures "time until the drain loop looked", not time to resolve.
            final java.util.Map<String, Long> firstRequested =
                    new java.util.concurrent.ConcurrentHashMap<String, Long>();
            final java.util.Map<String, Long> resolvedAt =
                    new java.util.concurrent.ConcurrentHashMap<String, Long>();
            final java.util.concurrent.atomic.AtomicInteger badFkdr =
                    new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger nonOk =
                    new java.util.concurrent.atomic.AtomicInteger();
            Thread poller = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    for (String token : firstRequested.keySet()) {
                        if (resolvedAt.containsKey(token)) continue;
                        BedwarsStats s = StatsCache.getCachedByName(token);
                        if (s == null) continue;
                        // A non-null entry is NOT success: ERROR is cached too. Only a genuine OK
                        // with the expected FKDR counts, so a 500/malformed body/parser regression
                        // shows up as unresolved instead of being timed as a fast resolution.
                        if (s.state == BedwarsStats.State.OK) {
                            resolvedAt.put(token, System.currentTimeMillis());
                            double fkdr = s.statsFor(com.bedwarsqol.stats.BedwarsMode.UNKNOWN).fkdr;
                            if (Math.abs(fkdr - FakeStatsBackend.EXPECTED_FKDR) > 0.001) {
                                badFkdr.incrementAndGet();
                            }
                        } else {
                            nonOk.incrementAndGet();
                        }
                    }
                    try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
                }
            }, "harness-poller");
            poller.setDaemon(true);
            poller.start();

            // MIXED only: background tab-only work running alongside the visible replay. Two waves so
            // the overlap is not a single lucky instant. Distinct prefix, so a request carrying both a
            // BG_PREFIX name and an arrival token is a batch that mixed two lanes.
            Thread background = null;
            if (mixed) {
                background = new Thread(() -> {
                    try {
                        for (int wave = 0; wave < BG_WAVES; wave++) {
                            Thread.sleep(wave == 0 ? BG_FIRST_WAVE_MS : BG_WAVE_GAP_MS);
                            for (int i = 0; i < BG_PER_WAVE; i++) {
                                StatsCache.ensureFetchedByName(
                                        BG_PREFIX + (wave * BG_PER_WAVE + i), StatsCache.PRIORITY_TAB);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "harness-background");
                background.setDaemon(true);
                background.start();
            }

            long t0 = System.currentTimeMillis();
            long virtual = 0;
            for (Arrival a : arrivals) {
                virtual += a.gapMs;
                long due = t0 + virtual;
                long sleep = due - System.currentTimeMillis();
                if (sleep > 0) Thread.sleep(sleep);
                if (!firstRequested.containsKey(a.token)) {
                    firstRequested.put(a.token, System.currentTimeMillis());
                }
                // Exactly what a lobby chat line does, at this arm's lane.
                StatsCache.ensureFetchedByName(a.token, priority);
            }

            // Let the poller finish off anything still in flight after the last arrival.
            long deadline = System.currentTimeMillis() + QUIESCE_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline
                    && resolvedAt.size() < firstRequested.size()) {
                Thread.sleep(POLL_MS);
            }
            poller.interrupt();
            if (background != null) background.interrupt();

            List<Long> latencies = new ArrayList<Long>();
            int unresolved = 0;
            for (Map.Entry<String, Long> e : firstRequested.entrySet()) {
                Long done = resolvedAt.get(e.getKey());
                if (done == null) unresolved++;
                else latencies.add(done - e.getValue());
            }
            java.util.Collections.sort(latencies);

            System.out.println("RESULT lane=" + lane
                    + " warmFraction=" + warmFraction
                    + " names=" + firstRequested.size()
                    + " unresolved=" + unresolved
                    + " nonOk=" + nonOk.get()
                    + " badFkdr=" + badFkdr.get()
                    + " median=" + pct(latencies, 50)
                    + " p90=" + pct(latencies, 90)
                    + " max=" + (latencies.isEmpty() ? -1 : latencies.get(latencies.size() - 1))
                    + " httpRequests=" + backend.requestCount()
                    + " highRequests=" + backend.highRequestCount()
                    + " lowRequests=" + backend.lowRequestCount()
                    + " mixedRequests=" + mixedLaneRequests(backend)
                    + " originStarts=" + backend.originStartCount()
                    + " highGrants=" + backend.highGrantCount()
                    + " lowGrants=" + backend.lowGrantCount()
                    + " batchSizes=" + backend.batchSizes());
        } finally {
            backend.stop();
        }
        // StatsCache's dispatcher/flusher are daemon threads, but be explicit: this process exists
        // only to print one RESULT line, and the parent blocks on our exit.
        System.exit(0);
    }

    /** Requests carrying both a background (TAB) name and a replayed (VISIBLE) one. */
    private static int mixedLaneRequests(FakeStatsBackend backend) {
        int mixed = 0;
        for (List<String> names : backend.requestNames()) {
            boolean bg = false, fg = false;
            for (String n : names) {
                if (n.startsWith(BG_PREFIX)) bg = true; else fg = true;
            }
            if (bg && fg) mixed++;
        }
        return mixed;
    }

    private static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return -1;
        int idx = (int) Math.min(sorted.size() - 1L, Math.round((p / 100.0) * (sorted.size() - 1)));
        return sorted.get(idx);
    }

    private static List<Arrival> loadArrivals() throws Exception {
        List<Arrival> out = new ArrayList<Arrival>();
        InputStream in = LaneContrastHarness.class.getResourceAsStream("/lobby-arrivals.txt");
        if (in == null) throw new IllegalStateException("lobby-arrivals.txt missing from test resources");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                out.add(new Arrival(Long.parseLong(parts[0]), parts[1].toLowerCase(Locale.ROOT)));
            }
        }
        return out;
    }

    /** Temp config dir so persistence never touches the player's real cache file. */
    static File tempConfigDir() throws Exception {
        return Files.createTempDirectory("cobblify-harness").toFile();
    }

    private static final class Arrival {
        final long gapMs;
        final String token;

        Arrival(long gapMs, String token) {
            this.gapMs = gapMs;
            this.token = token;
        }
    }
}
