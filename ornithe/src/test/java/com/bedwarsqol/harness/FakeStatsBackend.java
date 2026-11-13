package com.bedwarsqol.harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-in for the stats Worker, used by the lane-contrast harness.
 *
 * <p>Models the one property that decides this experiment: the Worker serializes every cold Hypixel
 * scrape through a single origin gate ({@code claimOrigin}, {@code SPACING_MS = 620} in
 * {@code server/stats-worker/src/scrape.js:143-166}). Warm names return immediately; cold names wait
 * their turn on a shared timeline exactly as the real gate does, so a single lookup cannot jump a
 * running batch's queue except through the priority lane.
 *
 * <p>The gate is two-lane, mirroring {@code pickNext}/{@code oldest}
 * ({@code server/stats-worker/src/scrape.js:117-166}): {@code &prio=1} puts a request in the HIGH
 * lane, nothing is ever reserved ({@code lastStart} is a past fact, so a late HIGH arrival can get
 * ahead of already-queued LOW work), and {@code HIGH_STREAK_MAX = 3} is the low lane's fairness
 * floor — {@code highStreak} counts consecutive HIGH grants taken WHILE A LOW WAITER WAS PRESENT,
 * so an uncontended HIGH run never causes a spurious later deferral. Grants are still one spacing
 * apart for every lane mix: priority chooses WHICH waiter is served, never HOW MANY.
 *
 * <p>Not modeled (no analogue in a single in-process test run): 429 backoff/elevation and abandoned
 * ({@code STALE_WAITER_MS}) waiters.
 *
 * <p>Request and origin-start counts are ground truth here — they are counted where the work
 * actually happens, which is why the harness does not need the production telemetry the earlier
 * plan revisions kept failing to design.
 */
public final class FakeStatsBackend {

    /** Mirrors BATCH_CFG.concurrency in the real Worker. */
    private static final int BATCH_CONCURRENCY = 2;

    private static final int LANE_HIGH = 0;
    private static final int LANE_LOW = 1;
    /** Mirrors HIGH_STREAK_MAX in the real Worker: the low lane gets >= 1 slot in 4. */
    private static final int HIGH_STREAK_MAX = 3;
    /** Mirrors PICK_QUANTUM_MS: how often a waiter re-checks whether it is its turn. */
    private static final long PICK_QUANTUM_MS = 5;

    /** Mirrors BATCH_CFG.minSpacingMs in the real Worker. */
    private final long gateSpacingMs;
    /** Simulated Hypixel page service time once a slot is claimed. */
    private final long scrapeMs;
    /** Names treated as already cached at the Worker (instant, no origin start). */
    private final Set<String> warm = ConcurrentHashMap.newKeySet();

    private final HttpServer server;
    /** Held so {@link #stop()} can shut it down: {@code HttpServer.stop} leaves a custom executor
     *  running, and non-daemon pool threads would keep the harness JVM alive forever. */
    private final java.util.concurrent.ExecutorService httpPool;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger highRequests = new AtomicInteger();
    private final AtomicInteger lowRequests = new AtomicInteger();
    private final AtomicInteger originStarts = new AtomicInteger();
    private final AtomicInteger highGrants = new AtomicInteger();
    private final AtomicInteger lowGrants = new AtomicInteger();
    private final List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<Integer>());
    /** Names carried by each request, in arrival order (a single route contributes one name). */
    private final List<List<String>> requestNames =
            Collections.synchronizedList(new ArrayList<List<String>>());

    /** Shared origin timeline and its waiter lanes; guarded by {@link #gateLock}. */
    private final Object gateLock = new Object();
    private long lastStart;
    private long gateSeq;
    private int highStreak;
    private final List<Waiter> waiters = new ArrayList<Waiter>();

    private static final class Waiter {
        final int lane;
        final long seq;

        Waiter(int lane, long seq) {
            this.lane = lane;
            this.seq = seq;
        }
    }

    public FakeStatsBackend(long gateSpacingMs, long scrapeMs) throws IOException {
        this.gateSpacingMs = gateSpacingMs;
        this.scrapeMs = scrapeMs;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Generous pool: concurrency limits must come from the modeled gate, not from the stub.
        // Daemon threads so a leaked pool can never outlive the harness run.
        this.httpPool = Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "FakeStatsBackend");
            t.setDaemon(true);
            return t;
        });
        this.server.setExecutor(httpPool);
        this.server.createContext("/bedwars/", this::handle);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        httpPool.shutdownNow();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void markWarm(String name) {
        warm.add(name.toLowerCase(Locale.ROOT));
    }

    public int requestCount() {
        return requests.get();
    }

    /** Requests that carried {@code &prio=1}. */
    public int highRequestCount() {
        return highRequests.get();
    }

    /** Requests that did not carry {@code &prio=1}. */
    public int lowRequestCount() {
        return lowRequests.get();
    }

    public int originStartCount() {
        return originStarts.get();
    }

    /** Origin slots granted to the HIGH lane. */
    public int highGrantCount() {
        return highGrants.get();
    }

    /** Origin slots granted to the LOW lane. */
    public int lowGrantCount() {
        return lowGrants.get();
    }

    public List<Integer> batchSizes() {
        synchronized (batchSizes) {
            return new ArrayList<Integer>(batchSizes);
        }
    }

    /** Names per request, in arrival order — lets a caller check that no request mixed two lanes. */
    public List<List<String>> requestNames() {
        synchronized (requestNames) {
            return new ArrayList<List<String>>(requestNames);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        boolean high = isHighPriority(ex.getRequestURI().getRawQuery());
        if (high) highRequests.incrementAndGet(); else lowRequests.incrementAndGet();
        try {
            if (path.equals("/bedwars/batch")) {
                handleBatch(ex, high);
            } else {
                handleSingle(ex, path.substring("/bedwars/".length()), high);
            }
        } catch (Exception e) {
            ex.sendResponseHeaders(500, -1);
        } finally {
            ex.close();
        }
    }

    private void handleBatch(HttpExchange ex, boolean high) throws Exception {
        List<String> names = namesFromQuery(ex.getRequestURI().getRawQuery());
        batchSizes.add(names.size());
        requestNames.add(new ArrayList<String>(names));
        ex.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            // Cache hits stream first, exactly like streamBedwarsBatch.
            List<String> misses = new ArrayList<String>();
            for (String n : names) {
                if (warm.contains(n.toLowerCase(Locale.ROOT))) writeLine(out, n, true);
                else misses.add(n);
            }
            // Production scrapePool runs cfg.concurrency workers, each claiming a gate slot BEFORE
            // awaiting its own scrape (scrape.js:242-263), so two future slots are reserved up front.
            // Serial handling would let another request take a slot this batch should already own.
            final java.util.concurrent.atomic.AtomicInteger idx =
                    new java.util.concurrent.atomic.AtomicInteger();
            final Object writeLock = new Object();
            int workers = Math.min(BATCH_CONCURRENCY, misses.size());
            Thread[] pool = new Thread[workers];
            for (int w = 0; w < workers; w++) {
                pool[w] = new Thread(() -> {
                    int i;
                    while ((i = idx.getAndIncrement()) < misses.size()) {
                        String n = misses.get(i);
                        try {
                            awaitOriginSlot(high);
                            Thread.sleep(scrapeMs);
                            synchronized (writeLock) { writeLine(out, n, false); }
                        } catch (Exception e) {
                            return;
                        }
                    }
                });
                pool[w].setDaemon(true);
                pool[w].start();
            }
            for (Thread t : pool) t.join();
        }
    }

    private void handleSingle(HttpExchange ex, String rawName, boolean high) throws Exception {
        String name = URLDecoder.decode(rawName, "UTF-8");
        requestNames.add(Collections.singletonList(name));
        boolean isWarm = warm.contains(name.toLowerCase(Locale.ROOT));
        if (!isWarm) {
            awaitOriginSlot(high);
            Thread.sleep(scrapeMs);
        }
        byte[] body = playerJson(name, isWarm).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Wait for this lane's turn on the shared timeline (the real {@code claimOrigin}'s shape): join
     * the waiter list, then poll until enough time has passed AND {@code pickNext} names me. Nothing
     * is reserved, so a HIGH waiter that arrives after a queued LOW one still goes first.
     */
    private void awaitOriginSlot(boolean high) throws InterruptedException {
        int lane = high ? LANE_HIGH : LANE_LOW;
        Waiter w;
        synchronized (gateLock) {
            w = new Waiter(lane, ++gateSeq);
            waiters.add(w);
        }
        try {
            while (true) {
                long sleepMs;
                synchronized (gateLock) {
                    long now = System.currentTimeMillis();
                    long earliest = lastStart + gateSpacingMs;
                    if (now >= earliest && pickNext() == w) {
                        lastStart = now; // the only assignment: the rate invariant lives here
                        if (lane == LANE_HIGH) {
                            // Counts consecutive HIGH grants taken while a LOW waiter was present.
                            highStreak = oldest(LANE_LOW) != null ? highStreak + 1 : 0;
                            highGrants.incrementAndGet();
                        } else {
                            highStreak = 0;
                            lowGrants.incrementAndGet();
                        }
                        originStarts.incrementAndGet();
                        return;
                    }
                    sleepMs = now >= earliest ? PICK_QUANTUM_MS : earliest - now;
                }
                Thread.sleep(sleepMs);
            }
        } finally {
            synchronized (gateLock) {
                waiters.remove(w);
            }
        }
    }

    /** Oldest waiter in {@code lane} (FIFO by seq). Caller holds {@link #gateLock}. */
    private Waiter oldest(int lane) {
        Waiter best = null;
        for (Waiter w : waiters) {
            if (w.lane != lane) continue;
            if (best == null || w.seq < best.seq) best = w;
        }
        return best;
    }

    /** Who gets the next slot: strict priority, bounded by the low lane's fairness floor. */
    private Waiter pickNext() {
        Waiter hi = oldest(LANE_HIGH);
        Waiter lo = oldest(LANE_LOW);
        if (lo != null && (hi == null || highStreak >= HIGH_STREAK_MAX)) return lo;
        return hi != null ? hi : lo;
    }

    private void writeLine(OutputStream out, String name, boolean cached) throws IOException {
        out.write((playerJson(name, cached) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** FKDR the fake reports for every player; arms assert on it, so a parse regression cannot pass. */
    public static final double EXPECTED_FKDR = 4.0;

    /**
     * Body in the production schema. {@code readOverall} reads a TOP-LEVEL {@code overall} object and
     * only falls back to flat root fields ({@code ScraperBackendClient.java:348-351}) — nesting it
     * under {@code modes} (the harness's first attempt) parsed as FKDR 0.00 while still reporting
     * state OK, which is exactly the silent-success failure this schema avoids.
     *
     * <p>{@code networkLevel}/{@code bedwarsLevel} stay here on purpose even though the client no
     * longer reads them: they make every arm a free check that a client talking to an older Worker
     * tolerates the extra keys instead of failing to parse.
     */
    private static String playerJson(String name, boolean cached) {
        return "{\"name\":\"" + name + "\",\"success\":true,\"state\":\"OK\",\"displayName\":\"" + name
                + "\",\"networkLevel\":100,\"bedwarsLevel\":100,\"cached\":" + cached
                + ",\"overall\":{\"finalKills\":1000,\"finalDeaths\":250,"
                + "\"kills\":400,\"deaths\":200,\"wins\":100,\"losses\":50}"
                + ",\"modes\":{\"solo\":{\"finalKills\":1000,\"finalDeaths\":250,"
                + "\"kills\":400,\"deaths\":200,\"wins\":100,\"losses\":50}}}";
    }

    /** {@code &prio=1} on either route = HIGH lane, exactly like {@code laneFor} in worker.js. */
    private static boolean isHighPriority(String rawQuery) {
        if (rawQuery == null) return false;
        for (String part : rawQuery.split("&")) {
            if ("prio=1".equals(part)) return true;
        }
        return false;
    }

    private static List<String> namesFromQuery(String rawQuery) throws Exception {
        List<String> out = new ArrayList<String>();
        if (rawQuery == null) return out;
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0 || !"names".equals(part.substring(0, eq))) continue;
            for (String n : URLDecoder.decode(part.substring(eq + 1), "UTF-8").split(",")) {
                if (!n.trim().isEmpty()) out.add(n.trim());
            }
        }
        return out;
    }
}
