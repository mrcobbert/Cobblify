package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.hud.HttpRangeFetcher;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.MojangNameResolver;
import com.bedwarsqol.stats.ScraperBackendClient;
import com.bedwarsqol.stats.StatsCache;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Network admission under Rewind's gate (PLAN section 5 layer 4; Phase 5b; review round 2 B4), against a local
 * {@code com.sun.net.httpserver} server that logs when each request starts and can hold a response on a latch:
 * <ul>
 *   <li>with the gate muted, {@link ScraperBackendClient}, {@link MojangNameResolver} (through its base-URL seam)
 *       and {@link HttpRangeFetcher} make 0 requests and throw {@link PlaybackGate.PlaybackMutedException};</li>
 *   <li>a request admitted before entry and released during the drain completes before entry finishes, and the
 *       server sees no request start after entry completed;</li>
 *   <li>a request admitted before entry and held past the 3 s drain bound is aborted and never reaches the server
 *       again (the JDK's one silent GET retry is suppressed by {@code disconnect()});</li>
 *   <li>{@link StatsCache} (in its own JVM, because its static state has no reset path, as in
 *       {@code LaneContrastTest}): aborted lookups (a single and a batch) and one refused after it passed the
 *       dispatcher are requeued, not marked ERROR, and fetched exactly once after exit.</li>
 * </ul>
 * These tests use the production gate ({@link PlaybackGate#global()}) and leave it unmuted.
 */
public class PlaybackNetworkGateTest {

    // ------------------------------------------------------------------ local server

    /** One request as the server saw it start. */
    static final class Req {
        final String path;
        final String name;
        final long startNs;

        Req(String path, String name, long startNs) {
            this.path = path;
            this.name = name;
            this.startNs = startNs;
        }
    }

    /** A stats Worker, Mojang and a range host in one; names in {@link #holds} wait for their latch once. */
    static final class LocalServer {
        final HttpServer server;
        final ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "local-http");
            t.setDaemon(true);
            return t;
        });
        final List<Req> requests = new java.util.concurrent.CopyOnWriteArrayList<Req>();
        final Map<String, CountDownLatch> holds = new ConcurrentHashMap<String, CountDownLatch>();

        LocalServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(pool);
            server.createContext("/", new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    handleRequest(ex);
                }
            });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void hold(String name) {
            holds.put(name, new CountDownLatch(1));
        }

        void release(String name) {
            CountDownLatch l = holds.remove(name);
            if (l != null) {
                l.countDown();
            }
        }

        void releaseAll() {
            for (String n : new ArrayList<String>(holds.keySet())) {
                release(n);
            }
        }

        /** Requests naming {@code name} (alone, or as one of a batch's comma-separated names). */
        int count(String name) {
            int n = 0;
            for (Req r : requests) {
                if (Arrays.asList(r.name.split(",")).contains(name)) {
                    n++;
                }
            }
            return n;
        }

        int startedAfter(long ns) {
            int n = 0;
            for (Req r : requests) {
                if (r.startNs > ns) {
                    n++;
                }
            }
            return n;
        }

        void awaitCount(String name, int n, long timeoutMs) throws InterruptedException {
            long end = System.currentTimeMillis() + timeoutMs;
            while (count(name) < n) {
                if (System.currentTimeMillis() > end) {
                    throw new AssertionError("server never saw " + n + " request(s) for " + name);
                }
                Thread.sleep(2);
            }
        }

        void stop() {
            releaseAll();
            server.stop(0);
            pool.shutdownNow();
        }

        private void handleRequest(HttpExchange ex) throws IOException {
            long start = System.nanoTime();
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (path.equals("/bedwars/batch") && query != null && query.startsWith("names=")) {
                name = query.substring(6).split("&")[0];
            }
            requests.add(new Req(path, name, start));
            for (String n : name.split(",")) {
                CountDownLatch hold = holds.get(n);
                if (hold != null) {
                    try {
                        hold.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            try {
                if (path.startsWith("/users/profiles/minecraft/")) {
                    send(ex, 200, "{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"" + name + "\"}");
                } else if (path.startsWith("/ashcon/")) {
                    send(ex, 404, "{}");
                } else if (path.equals("/range")) {
                    ex.getResponseHeaders().set("Content-Range", "bytes 0-9/100");
                    byte[] body = new byte[10];
                    ex.sendResponseHeaders(206, body.length);
                    OutputStream os = ex.getResponseBody();
                    os.write(body);
                    os.close();
                } else if (path.startsWith("/urchin/")) {
                    send(ex, 200, "{\"success\":true,\"player\":\"" + name + "\",\"uuid\":null,\"tags\":[]}");
                } else if (path.equals("/bedwars/batch")) {
                    StringBuilder b = new StringBuilder();
                    for (String n : name.split(",")) {
                        b.append(statsJson(n)).append('\n');
                    }
                    send(ex, 200, b.toString());
                } else {
                    send(ex, 200, statsJson(name));
                }
            } catch (IOException clientGone) {
                // an aborted client closed its socket: nothing to answer
            }
        }

        private static String statsJson(String name) {
            return "{\"name\":\"" + name + "\",\"success\":true,\"state\":\"OK\",\"displayName\":\"" + name
                    + "\",\"overall\":{\"finalKills\":10,\"finalDeaths\":5}}";
        }

        private static void send(HttpExchange ex, int code, String body) throws IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            OutputStream os = ex.getResponseBody();
            os.write(b);
            os.close();
        }
    }

    private final PlaybackGate gate = PlaybackGate.global();
    private LocalServer server;

    @Before
    public void start() throws IOException {
        leaveGateOpen(gate);
        server = new LocalServer();
    }

    @After
    public void stop() {
        leaveGateOpen(gate);
        if (server != null) {
            server.stop();
        }
    }

    static void leaveGateOpen(PlaybackGate g) {
        if (g.isMuted() || g.isEntering()) {
            g.exit();
        }
    }

    private static void expectMuted(String what, Runnable call) {
        try {
            call.run();
            fail(what + " ran while muted");
        } catch (MutedUnchecked expected) {
            // ok
        }
    }

    /** Lets a lambda carry the checked muted refusal to {@link #expectMuted}; anything else fails the test. */
    static final class MutedUnchecked extends RuntimeException {
        MutedUnchecked(Throwable cause) {
            super(cause);
        }
    }

    interface IoCall {
        void run() throws Exception;
    }

    private static Runnable io(final IoCall c) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    c.run();
                } catch (PlaybackGate.PlaybackMutedException m) {
                    throw new MutedUnchecked(m);
                } catch (Exception e) {
                    throw new AssertionError("expected a muted refusal, got " + e, e);
                }
            }
        };
    }

    /** Points {@link MojangNameResolver} at the local server (its package-private base-URL seam). */
    private static String[] setMojangBases(String mojang, String ashcon) throws Exception {
        Field m = MojangNameResolver.class.getDeclaredField("mojangBase");
        Field a = MojangNameResolver.class.getDeclaredField("ashconBase");
        m.setAccessible(true);
        a.setAccessible(true);
        String[] old = {(String) m.get(null), (String) a.get(null)};
        m.set(null, mojang);
        a.set(null, ashcon);
        return old;
    }

    // ------------------------------------------------------------------ muted: no request at all

    @Test(timeout = 20_000)
    public void withTheGateMutedTheThreeClientsMakeNoRequest() throws Exception {
        final String base = server.base();
        String[] old = setMojangBases(base + "/users/profiles/minecraft/", base + "/ashcon/");
        try {
            gate.enter();
            expectMuted("fetch", io(() -> ScraperBackendClient.fetch("mutedOne", base, "t", false)));
            expectMuted("batch", io(() -> ScraperBackendClient.fetchBatchStreaming(
                    Arrays.asList("mutedTwo", "mutedThree"), base, "t", (n, s) -> { })));
            expectMuted("urchin", io(() -> ScraperBackendClient.getUrchin(base, "t", "mutedFour")));
            expectMuted("mojang", io(() -> MojangNameResolver.resolve("mutedFive")));
            final String range = base + "/range";
            expectMuted("range", io(() -> new HttpRangeFetcher(range).range(range, 0, 9)));
            Thread.sleep(100);
            assertEquals("requests the server saw while muted", 0, server.requests.size());

            gate.exit();
            assertEquals("mutedSix", ScraperBackendClient.fetch("mutedSix", base, "t", false).displayName);
            assertNotNull(MojangNameResolver.resolve("mutedSeven"));
            assertEquals(10, new HttpRangeFetcher(range).range(range, 0, 9).length);
            assertEquals("after exit the clients work again", 3, server.requests.size());
        } finally {
            setMojangBases(old[0], old[1]);
        }
    }

    // ------------------------------------------------------------------ drain: released in time

    @Test(timeout = 20_000)
    public void aRequestReleasedDuringTheDrainCompletesBeforeEntryFinishes() throws Exception {
        final String base = server.base();
        server.hold("drainOne");
        final AtomicReference<Object> result = new AtomicReference<Object>();
        final AtomicLong finishedNs = new AtomicLong();
        Thread fetcher = new Thread(() -> {
            try {
                result.set(ScraperBackendClient.fetch("drainOne", base, "t", false));
            } catch (Throwable t) {
                result.set(t);
            }
            finishedNs.set(System.nanoTime());
        }, "drain-fetcher");
        fetcher.start();
        server.awaitCount("drainOne", 1, 5_000);

        gate.beginEntry();
        assertFalse("entry waits for the running request", gate.tryCompleteEntry());
        Thread.sleep(100);
        assertFalse(gate.tryCompleteEntry());
        server.release("drainOne");
        while (!gate.tryCompleteEntry()) {
            Thread.sleep(1);
        }
        long entryDoneNs = System.nanoTime();
        fetcher.join(5_000);
        assertTrue("the request completed: " + result.get(), result.get() instanceof BedwarsStats);
        assertTrue("before entry completed", finishedNs.get() <= entryDoneNs);

        expectMuted("a new request after entry", io(() -> ScraperBackendClient.fetch("drainTwo", base, "t", false)));
        Thread.sleep(200);
        assertEquals("no request started after entry completed", 0, server.startedAfter(entryDoneNs));
    }

    // ------------------------------------------------------------------ drain bound: aborted

    @Test(timeout = 30_000)
    public void aRequestHeldPastTheDrainBoundIsAbortedAndNeverReachesTheServerAgain() throws Exception {
        final String base = server.base();
        server.hold("heldOne");
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread fetcher = new Thread(() -> {
            try {
                ScraperBackendClient.fetch("heldOne", base, "t", false);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "held-fetcher");
        fetcher.start();
        server.awaitCount("heldOne", 1, 5_000);

        long t0 = System.nanoTime();
        gate.beginEntry();
        while (!gate.tryCompleteEntry()) {
            Thread.sleep(5);
        }
        long entryDoneNs = System.nanoTime();
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(entryDoneNs - t0);
        assertTrue("waited the 3 s drain: " + waitedMs, waitedMs >= PlaybackGate.DRAIN_MS);
        fetcher.join(5_000);
        assertFalse("the aborted request returned", fetcher.isAlive());
        assertTrue("aborted -> PlaybackMutedException: " + failure.get(),
                failure.get() instanceof PlaybackGate.PlaybackMutedException);
        Thread.sleep(300);
        assertEquals("the request reached the server once, before entry", 1, server.count("heldOne"));
        assertEquals("nothing started after entry completed", 0, server.startedAfter(entryDoneNs));
    }

    // ------------------------------------------------------------------ StatsCache, in its own JVM

    @Test(timeout = 90_000)
    public void statsCacheRequeuesRefusedAndAbortedLookupsAndFetchesThemOnceAfterExit() throws Exception {
        File configDir = Files.createTempDirectory("cobblify-gate-test").toFile();
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("-Dcobblify.test.configDir=" + configDir.getAbsolutePath());
        cmd.add(StatsCacheScenario.class.getName());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder all = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                all.append(line).append('\n');
            }
        }
        int exit = p.waitFor();
        assertEquals("StatsCache scenario (exit " + exit + "):\n" + all, 0, exit);
        assertTrue(all.toString(), all.toString().contains("SCENARIO OK"));
    }

    /**
     * The StatsCache scenario, run as {@code main} in a fresh JVM with a temp config dir:
     * <ol>
     *   <li>two USER lookups ({@code held1}, {@code held2}, single requests) and one tab-only batch
     *       ({@code batchA}, {@code batchB}) occupy all three fetcher threads on held responses; a fourth lookup
     *       ({@code late}) passes the dispatcher and waits in the fetcher pool;</li>
     *   <li>entry begins; {@code held1} is released and completes during the drain; its thread then runs
     *       {@code late}, which asks for a permit after entry began and is refused; {@code held2} and the batch
     *       pass the 3 s bound and are aborted;</li>
     *   <li>during playback: no request starts; none of them is cached as ERROR, all stay queued;</li>
     *   <li>after exit each is fetched exactly once more and resolves.</li>
     * </ol>
     */
    public static final class StatsCacheScenario {
        public static void main(String[] args) throws Exception {
            LocalServer server = new LocalServer();
            PlaybackGate gate = PlaybackGate.global();
            try {
                ClientSettings cfg = new ClientSettings();
                cfg.statsBackendUrl = server.base();
                cfg.statsBackendToken = "scenario";
                BedwarsQol.config = cfg;
                for (String n : new String[]{"held1", "held2", "batchA"}) {
                    server.hold(n);
                }
                StatsCache.ensureFetchedByName("held1", StatsCache.PRIORITY_USER);
                StatsCache.ensureFetchedByName("held2", StatsCache.PRIORITY_USER);
                StatsCache.ensureFetchedByName("batchA", StatsCache.PRIORITY_TAB); // coalesced into one batch
                StatsCache.ensureFetchedByName("batchB", StatsCache.PRIORITY_TAB);
                for (String n : new String[]{"held1", "held2", "batchA", "batchB"}) {
                    server.awaitCount(n, 1, 10_000);
                }
                check(server.requests.size() == 3, "two singles and one batch: " + server.requests.size());
                StatsCache.ensureFetchedByName("late", StatsCache.PRIORITY_USER);
                Collection<?> queue = (Collection<?>) staticField("QUEUE");
                long until = System.currentTimeMillis() + 5_000;
                while (!queue.isEmpty()) {
                    check(System.currentTimeMillis() < until, "the dispatcher never took 'late'");
                    Thread.sleep(2);
                }
                Thread.sleep(200); // past awaitUnmuted and into the (busy) fetcher pool
                check(server.count("late") == 0, "'late' must not have reached the server yet");

                gate.beginEntry();
                server.release("held1");
                long t0 = System.nanoTime();
                while (!gate.tryCompleteEntry()) {
                    Thread.sleep(5);
                }
                long entryDoneNs = System.nanoTime();
                check(TimeUnit.NANOSECONDS.toMillis(entryDoneNs - t0) >= PlaybackGate.DRAIN_MS - 50,
                        "held2 and the batch kept entry waiting for the drain bound");
                BedwarsStats one = StatsCache.getCachedByName("held1");
                check(one != null && one.state == BedwarsStats.State.OK, "held1 completed during the drain: " + one);

                Thread.sleep(800);
                check(server.startedAfter(entryDoneNs) == 0, "no request started during playback");
                @SuppressWarnings("unchecked")
                Collection<String> queued = (Collection<String>) staticField("QUEUED");
                for (String n : new String[]{"held2", "batchA", "batchB", "late"}) {
                    check(StatsCache.getCachedByName(n) == null, n + " must not be marked ERROR (or cached)");
                    check(queued.contains("name:" + n.toLowerCase(java.util.Locale.ROOT)),
                            n + " stays queued for after playback; queued=" + queued);
                }
                check(server.count("held1") == 1 && server.count("held2") == 1 && server.count("batchA") == 1
                        && server.count("batchB") == 1 && server.count("late") == 0,
                        "requests before exit: " + counts(server));

                server.releaseAll(); // the aborted requests' server threads may finish now
                gate.exit();
                until = System.currentTimeMillis() + 10_000;
                while (!(resolved("held2") && resolved("batchA") && resolved("batchB") && resolved("late"))) {
                    check(System.currentTimeMillis() < until, "lookups did not run after exit: " + counts(server));
                    Thread.sleep(5);
                }
                Thread.sleep(300);
                check(server.count("held1") == 1 && server.count("held2") == 2 && server.count("batchA") == 2
                        && server.count("batchB") == 2 && server.count("late") == 1,
                        "each requeued lookup fetched once after exit: " + counts(server));
                System.out.println("SCENARIO OK " + counts(server));
                System.exit(0);
            } catch (Throwable t) {
                System.out.println("SCENARIO FAILED " + t);
                t.printStackTrace(System.out);
                System.exit(1);
            } finally {
                server.stop();
            }
        }

        private static boolean resolved(String name) {
            BedwarsStats s = StatsCache.getCachedByName(name);
            return s != null && s.state == BedwarsStats.State.OK;
        }

        private static String counts(LocalServer s) {
            return "held1=" + s.count("held1") + " held2=" + s.count("held2") + " batchA=" + s.count("batchA")
                    + " batchB=" + s.count("batchB") + " late=" + s.count("late") + " requests=" + s.requests.size();
        }

        private static Object staticField(String name) throws Exception {
            Field f = StatsCache.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        }

        private static void check(boolean ok, String what) {
            if (!ok) {
                throw new AssertionError(what);
            }
        }
    }

}
