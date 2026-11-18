package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bedwarsqol.rewind.watch.PlaybackIsolationScanTest.javaFiles;
import static com.bedwarsqol.rewind.watch.PlaybackIsolationScanTest.line;
import static com.bedwarsqol.rewind.watch.PlaybackIsolationScanTest.mainRoot;
import static com.bedwarsqol.rewind.watch.PlaybackIsolationScanTest.read;
import static com.bedwarsqol.rewind.watch.PlaybackIsolationScanTest.rel;
import static com.bedwarsqol.rewind.watch.PlaybackIsolationScanTest.strip;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The inventory guard of the lifetime rule (PLAN section 5 layer 4; Phase 5b), on the Ornithe and common sources:
 * <ul>
 *   <li>no {@code addScheduledTask(} in {@code feature}, {@code stats}, {@code command} or {@code hud} outside
 *       {@code FeatureTasks}: every client-thread callback carries a ticket;</li>
 *   <li>every {@code FeatureTasks.schedule(} passes a ticket captured earlier (never one taken inline, which would
 *       be the effect-time capture of review round 2 B3), and the callers are exactly the reviewed sites;</li>
 *   <li>no {@code openConnection(} outside the three network clients and {@code Tls}, and each client takes a
 *       permit, attaches its connection and re-checks before a request starts;</li>
 *   <li>no class in those packages starts a thread or an executor unless it is on the reviewed list.</li>
 * </ul>
 * A new site fails here until a reviewer adds it to the rule (residual risk 5).
 */
public class QueuedWorkScanTest {

    private static final String[] PACKAGES = {"feature", "stats", "command", "hud"};

    /** Classes that call {@code FeatureTasks.schedule}: the seven reviewed callback sites (PLAN section 5). */
    private static final Set<String> CALLBACK_SITES = set(
            "feature/QueueAlert.java", "feature/ChatNameTags.java", "feature/HeightLimitWatch.java",
            "feature/TeamUpgradesWatch.java", "command/BedwarsQolCommand.java", "command/BedwarsStatsCommand.java");

    /** The only classes that may open a URL connection. */
    private static final Set<String> NETWORK_CLIENTS = set(
            "stats/ScraperBackendClient.java", "stats/MojangNameResolver.java", "hud/HttpRangeFetcher.java");
    private static final Set<String> OPEN_CONNECTION_ALLOWED = set(
            "stats/ScraperBackendClient.java", "stats/MojangNameResolver.java", "hud/HttpRangeFetcher.java",
            "stats/Tls.java");

    /** Reviewed classes that start a thread or executor in those packages, and why their effects are covered. */
    private static final Map<String, String> THREADS = new TreeMap<String, String>();

    static {
        THREADS.put("feature/QueueAlert.java", "lookups through the gated clients; output via FeatureTasks");
        THREADS.put("stats/StatsCache.java", "fetches through the gated client; refused/aborted work is requeued");
        THREADS.put("feature/LobbyExport.java", "writes only under a permit for the snapshot's ticket");
        THREADS.put("hud/EffectIconTextures.java", "downloads through HttpRangeFetcher, which takes a permit");
        THREADS.put("stats/ProviderKeySubmitter.java", "a settings action; POST through the gated client");
        THREADS.put("command/BedwarsQolCommand.java", "command work; callbacks via FeatureTasks with its ticket");
        THREADS.put("command/BedwarsStatsCommand.java", "command work; callbacks via FeatureTasks with its ticket");
    }

    private static final Pattern THREAD_START = Pattern.compile(
            "\\bnew\\s+Thread\\s*\\(|\\bExecutors\\s*\\.|\\bnew\\s+(?:Scheduled)?ThreadPoolExecutor\\s*\\("
                    + "|\\bnew\\s+Timer\\s*\\(|\\bCompletableFuture\\s*\\.|\\bForkJoinPool\\b");

    private static Set<String> set(String... s) {
        Set<String> out = new TreeSet<String>();
        Collections.addAll(out, s);
        return out;
    }

    /** {@code common/src/main/java}, next to the Ornithe root. */
    static File commonRoot() {
        File ornithe = mainRoot().getAbsoluteFile();
        File common = new File(ornithe.getParentFile().getParentFile().getParentFile().getParentFile(),
                "common/src/main/java");
        if (!new File(common, "com/bedwarsqol/stats/ScraperBackendClient.java").isFile()) {
            throw new AssertionError("common/src/main/java not found at " + common);
        }
        return common;
    }

    private static List<File> roots() {
        List<File> out = new ArrayList<File>();
        out.add(mainRoot());
        out.add(commonRoot());
        return out;
    }

    /** Package-relative path (e.g. {@code feature/QueueAlert.java}) for files under {@code com/bedwarsqol}. */
    private static String pkgPath(File root, File f) {
        return rel(new File(root, "com/bedwarsqol"), f);
    }

    private static boolean inScannedPackage(String pkgPath) {
        for (String p : PACKAGES) {
            if (pkgPath.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void everyClientThreadCallbackGoesThroughFeatureTasksWithASubmissionTicket() throws IOException {
        List<String> raw = new ArrayList<String>();
        List<String> inlineTicket = new ArrayList<String>();
        Map<String, Integer> sites = new TreeMap<String, Integer>();
        Pattern schedule = Pattern.compile("\\bFeatureTasks\\s*\\.\\s*schedule\\s*\\(");
        Pattern inline = Pattern.compile("\\bFeatureTasks\\s*\\.\\s*schedule\\s*\\(\\s*PlaybackGate\\s*\\.");
        for (File r : roots()) {
            for (File f : javaFiles(new File(r, "com/bedwarsqol"))) {
                String p = pkgPath(r, f);
                if (!inScannedPackage(p)) {
                    continue;
                }
                String src = strip(read(f));
                if (!p.equals("feature/FeatureTasks.java")) {
                    Matcher m = Pattern.compile("\\baddScheduledTask\\s*\\(").matcher(src);
                    while (m.find()) {
                        raw.add(p + ":" + line(src, m.start()));
                    }
                }
                Matcher s = schedule.matcher(src);
                while (s.find()) {
                    Integer n = sites.get(p);
                    sites.put(p, n == null ? 1 : n + 1);
                }
                Matcher i = inline.matcher(src);
                while (i.find()) {
                    inlineTicket.add(p + ":" + line(src, i.start()));
                }
            }
        }
        assertEquals("raw addScheduledTask outside FeatureTasks", Collections.<String>emptyList(), raw);
        assertEquals("tickets taken inline at the effect instead of at submission",
                Collections.<String>emptyList(), inlineTicket);
        assertEquals("the reviewed callback sites", CALLBACK_SITES, sites.keySet());
        assertEquals("HeightLimitWatch: payload and placement", Integer.valueOf(2),
                sites.get("feature/HeightLimitWatch.java"));
    }

    @Test
    public void onlyTheThreeGatedClientsOpenConnections() throws IOException {
        List<String> outside = new ArrayList<String>();
        Map<String, String> clientSources = new TreeMap<String, String>();
        for (File r : roots()) {
            for (File f : javaFiles(new File(r, "com/bedwarsqol"))) {
                String p = pkgPath(r, f);
                String src = strip(read(f));
                if (NETWORK_CLIENTS.contains(p)) {
                    clientSources.put(p, src);
                }
                Matcher m = Pattern.compile("\\bopenConnection\\s*\\(").matcher(src);
                while (m.find()) {
                    if (!OPEN_CONNECTION_ALLOWED.contains(p)) {
                        outside.add(rel(r, f) + ":" + line(src, m.start()));
                    }
                }
            }
        }
        assertEquals(Collections.<String>emptyList(), outside);
        assertEquals(NETWORK_CLIENTS, clientSources.keySet());
        for (Map.Entry<String, String> e : clientSources.entrySet()) {
            String src = e.getValue();
            assertTrue(e.getKey() + " takes a permit", src.contains("PlaybackGate.global().admit()"));
            assertTrue(e.getKey() + " attaches its connection", src.contains(".attach("));
            assertTrue(e.getKey() + " re-checks before a request starts", src.contains(".check()"));
            assertTrue(e.getKey() + " ends its permit", src.contains(".end()"));
        }
    }

    @Test
    public void onlyReviewedClassesStartThreadsOrExecutors() throws IOException {
        Set<String> found = new TreeSet<String>();
        List<String> unreviewed = new ArrayList<String>();
        for (File r : roots()) {
            for (File f : javaFiles(new File(r, "com/bedwarsqol"))) {
                String p = pkgPath(r, f);
                if (!inScannedPackage(p)) {
                    continue;
                }
                String src = strip(read(f));
                Matcher m = THREAD_START.matcher(src);
                while (m.find()) {
                    found.add(p);
                    if (!THREADS.containsKey(p)) {
                        unreviewed.add(rel(r, f) + ":" + line(src, m.start()) + " " + m.group().trim());
                    }
                }
            }
        }
        assertEquals("threads or executors not on the reviewed list", Collections.<String>emptyList(), unreviewed);
        assertEquals("stale reviewed entries", THREADS.keySet(), found);
    }

    @Test
    public void theWritersAndTheCacheFollowTheRule() throws IOException {
        String lobby = strip(read(new File(commonRoot(), "com/bedwarsqol/feature/LobbyExport.java")));
        assertTrue("LobbyExport writes under a permit for the snapshot's ticket", lobby.contains(".tryBegin("));
        String cache = strip(read(new File(mainRoot(), "com/bedwarsqol/stats/StatsCache.java")));
        assertTrue("StatsCache requeues refused or aborted work", cache.contains("PlaybackMutedException"));
        assertTrue("StatsCache's dispatcher waits while muted", cache.contains(".awaitUnmuted()"));
    }
}
