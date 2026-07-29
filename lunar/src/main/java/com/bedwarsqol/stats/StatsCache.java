package com.bedwarsqol.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.feature.DiagLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * UUID-keyed cache and scheduler for Bedwars stats fetched from the BedwarsQol stats
 * backend (Cloudflare Worker / forum scrape). Requests are paced by a shared
 * {@link RateLimiter}, de-duplicated, served from a priority queue (user-initiated >
 * visible players > tab-only), and persisted to disk so repeat encounters cost zero
 * network calls across sessions.
 */
public final class StatsCache {

    /** User typed /bw: fetch immediately. */
    public static final int PRIORITY_USER = 0;
    /** A player you can physically see in the world. */
    public static final int PRIORITY_VISIBLE = 1;
    /** A player only present in the tab list. */
    public static final int PRIORITY_TAB = 2;

    private static final long OK_TTL_MS = 15L * 60L * 1000L;
    private static final long NEGATIVE_TTL_MS = 30L * 60L * 1000L;
    /** Bounded retry gap for an unresolved Urchin lookup on an eligible warm entry. */
    private static final long URCHIN_REFRESH_MS = 60L * 1000L;
    /** Bounded retry gap for an unresolved Seraph lookup on an eligible warm entry. */
    private static final long SERAPH_REFRESH_MS = 60L * 1000L;
    // Short so a transient fetch failure retries within a few seconds (it renders blank meanwhile,
    // never "[?]") instead of sticking for a full minute on a real, lookupable player. Doubles per
    // consecutive failure (see ERROR_STREAK) so a name that keeps failing — or a backend outage —
    // backs off instead of re-scraping every few seconds for as long as the line stays rendered.
    private static final long ERROR_TTL_MS = 6L * 1000L;
    /** Ceiling for the doubled ERROR retry gap. */
    private static final long ERROR_TTL_MAX_MS = 5L * 60L * 1000L;
    private static final long FLUSH_INTERVAL_MS = 30L * 1000L;
    private static final int MAX_QUEUE = 512;

    public static final RateLimiter LIMITER = new RateLimiter();

    /** Thrown when a synchronous fetch is throttled by local request pacing. */
    public static final class RateLimitedException extends Exception {
        public RateLimitedException(String message) { super(message); }
    }

    private static final ConcurrentMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    /** Consecutive ERROR results per key, cleared by any non-ERROR result; paces the ERROR TTL. */
    private static final ConcurrentMap<String, Integer> ERROR_STREAK = new ConcurrentHashMap<>();
    private static final java.util.Set<String> QUEUED = ConcurrentHashMap.newKeySet();
    private static final PriorityBlockingQueue<Task> QUEUE = new PriorityBlockingQueue<>();
    private static final AtomicLong SEQ = new AtomicLong();
    /** Bumped by {@link #stripUrchinTags()} (key clear); requests capture it at dispatch so a
     *  pre-clear response is dropped at merge instead of reattaching stripped tags (B2). */
    private static final java.util.concurrent.atomic.AtomicInteger URCHIN_GEN =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Seraph counterpart of {@link #URCHIN_GEN}: bumped on Seraph key set/clear so a pre-change
     *  response is dropped at merge instead of reattaching stripped/stale tags. */
    private static final java.util.concurrent.atomic.AtomicInteger SERAPH_GEN =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final Gson GSON = new GsonBuilder().create();

    /** HTTP worker pool. The Worker backend paces its own hypixel scrapes (and backs off on 429),
     *  so the client only needs a few threads to overlap in-flight batches/lookups — NOT the old
     *  single thread + 1300ms global gap that serialized a whole lobby into ~20s. */
    private static final int FETCH_THREADS = 3;
    /** Max players per batch request (a Bedwars lobby is <=16). */
    private static final int MAX_BATCH = 16;
    /** Brief window to let a whole lobby's tab/nametag fetches coalesce into one batch. */
    private static final long COALESCE_WINDOW_MS = 80L;
    /** Max per-player lookups a partially-failed batch may fall back to (the rest retry via ERROR TTL). */
    private static final int FALLBACK_SINGLES_MAX = 5;
    /** Wall-clock budget for one batch's fallback singles; no further fetch starts past it. */
    private static final long FALLBACK_BUDGET_MS = 15_000L;
    private static final ExecutorService FETCHERS = Executors.newFixedThreadPool(FETCH_THREADS, r -> {
        Thread t = new Thread(r, "BedwarsQol-StatsFetch");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean dirty = false;

    static {
        load();
        Thread dispatcher = new Thread(StatsCache::dispatcherLoop, "BedwarsQol-StatsDispatch");
        dispatcher.setDaemon(true);
        dispatcher.start();

        Thread flusher = new Thread(StatsCache::flushLoop, "BedwarsQol-StatsFlush");
        flusher.setDaemon(true);
        flusher.start();

        Runtime.getRuntime().addShutdownHook(new Thread(StatsCache::flush, "BedwarsQol-StatsSave"));
    }

    private StatsCache() {}

    private static boolean useBackend() {
        return statsBackendUrl() != null;
    }

    private static String statsBackendUrl() {
        if (BedwarsQol.config == null) return null;
        String u = BedwarsQol.config.statsBackendUrl;
        if (u == null) return null;
        u = u.trim();
        return u.isEmpty() ? null : u;
    }

    private static String statsBackendToken() {
        return BedwarsQol.config == null ? null : BedwarsQol.config.statsBackendToken;
    }

    public static BedwarsStats getCached(UUID uuid) {
        if (uuid == null) return null;
        return getCachedByKey(uuid.toString());
    }

    /** Stats cached under a bare player name, for players with no resolvable tab-list UUID. */
    public static BedwarsStats getCachedByName(String name) {
        if (name == null || name.isEmpty()) return null;
        return getCachedByKey(nameKey(name));
    }

    private static BedwarsStats getCachedByKey(String k) {
        Entry e = liveEntry(k);
        return e == null ? null : e.stats;
    }

    /** Coarse Urchin lookup state for a UUID, derived purely from the immutable cache entry.
     *  Policy-free: callers apply the master toggle + {@link UrchinTag#badgeAllowed} themselves
     *  before displaying anything. Used by the Players page to show checking / no-tags / tags. */
    public enum UrchinResolution { ABSENT, PENDING, RESOLVED_EMPTY, RESOLVED_TAGS }

    /** The Urchin resolution state for {@code uuid} from immutable cache reads only — no fetch, no
     *  name lookup, no eligibility policy. {@code ABSENT} = no live cache entry (or not yet fetched). */
    public static UrchinResolution urchinResolution(UUID uuid) {
        if (uuid == null) return UrchinResolution.ABSENT;
        Entry e = liveEntry(uuid.toString());
        if (e == null) return UrchinResolution.ABSENT;
        return classifyUrchin(e.urchinResolved, e.stats.urchinTags, System.currentTimeMillis());
    }

    /** Pure classification of a cache entry's Urchin state (no cache access) — the testable core of
     *  {@link #urchinResolution}. {@code resolved} = the lookup concluded; otherwise it is still pending. */
    public static UrchinResolution classifyUrchin(boolean resolved, List<UrchinTag> tags, long nowMs) {
        if (!resolved) return UrchinResolution.PENDING;
        return UrchinTag.activeTags(tags, nowMs).isEmpty()
                ? UrchinResolution.RESOLVED_EMPTY : UrchinResolution.RESOLVED_TAGS;
    }

    /** The Seraph resolution state for {@code uuid} from immutable cache reads only (no fetch/policy). */
    public static UrchinResolution seraphResolution(UUID uuid) {
        if (uuid == null) return UrchinResolution.ABSENT;
        Entry e = liveEntry(uuid.toString());
        if (e == null) return UrchinResolution.ABSENT;
        return classifySeraph(e.seraphResolved, e.stats.seraphTags);
    }

    /** Pure classification of a cache entry's Seraph state (no cache access, no expiry). */
    public static UrchinResolution classifySeraph(boolean resolved, List<SeraphTag> tags) {
        if (!resolved) return UrchinResolution.PENDING;
        return SeraphTag.activeTags(tags).isEmpty()
                ? UrchinResolution.RESOLVED_EMPTY : UrchinResolution.RESOLVED_TAGS;
    }

    /** Whether a fetch for {@code uuid} is currently queued or in flight. The {@link #QUEUED} key is the
     *  dashed {@code uuid.toString()} used by {@link #ensureFetched(UUID, int, boolean, boolean)}. */
    public static boolean isFetching(UUID uuid) {
        return uuid != null && QUEUED.contains(uuid.toString());
    }

    /** Whether a name-only lookup for {@code name} is currently queued or in flight. Uses the same
     *  namespaced key as {@link #ensureFetchedByName(String, int, boolean)} so a remote lookup can show
     *  an honest fetching state. */
    public static boolean isFetchingName(String name) {
        return name != null && !name.isEmpty() && QUEUED.contains(nameKey(name));
    }

    /** The non-expired cache entry for a key, evicting it if the TTL has passed. */
    private static Entry liveEntry(String k) {
        Entry e = CACHE.get(k);
        if (e == null) return null;
        long ttl = e.stats.state == BedwarsStats.State.ERROR ? errorTtl(k) : ttlFor(e.stats.state);
        if (System.currentTimeMillis() - e.timestamp > ttl) {
            CACHE.remove(k, e);
            return null;
        }
        return e;
    }

    /** The ERROR retry gap for a key: base TTL doubled per consecutive failure, capped. */
    private static long errorTtl(String key) {
        Integer streak = ERROR_STREAK.get(key);
        int doublings = streak == null ? 0 : Math.min(Math.max(streak - 1, 0), 6);
        return Math.min(ERROR_TTL_MS << doublings, ERROR_TTL_MAX_MS);
    }

    /** Track consecutive ERROR results per key so {@link #errorTtl} can back off retries. */
    private static void noteErrorStreak(String key, BedwarsStats.State state) {
        if (state == BedwarsStats.State.ERROR) ERROR_STREAK.merge(key, 1, Integer::sum);
        else ERROR_STREAK.remove(key);
    }

    /** Whether an eligible pair still needs an Urchin lookup: eligible && unresolved && attempt aged out. */
    private static boolean needsUrchinRefresh(boolean pairEligible, Entry e, long now) {
        if (!pairEligible) return false;
        if (e == null) return true;
        return !e.urchinResolved && now - e.urchinAttemptMs > URCHIN_REFRESH_MS;
    }

    /** Whether an eligible pair still needs a Seraph lookup: eligible && unresolved && attempt aged out. */
    private static boolean needsSeraphRefresh(boolean pairEligible, Entry e, long now) {
        if (!pairEligible) return false;
        if (e == null) return true;
        return !e.seraphResolved && now - e.seraphAttemptMs > SERAPH_REFRESH_MS;
    }

    /** Whether a task still needs ANY provider fetch (base stats OR an unresolved eligible provider). */
    private static boolean needsAnyRefresh(Task t, Entry e, long now) {
        if (e == null) return true;
        // A force task refetches past a live entry once it has aged out of the refresh window —
        // the dispatch-side mirror of the ensureFetchedByName force gate (an entry refreshed
        // since enqueue is still skipped, bounding a failing name to one refetch per window).
        if (t.force && now - e.timestamp > URCHIN_REFRESH_MS) return true;
        return needsUrchinRefresh(t.urchinEligible, e, now) || needsSeraphRefresh(t.seraphEligible, e, now);
    }

    /** Undashed lowercase canonical UUID to send, or null when the send-time recheck fails (fail-closed). */
    private static String urchinSendUuid(Task t) {
        if (!t.urchinEligible || t.uuid == null) return null;
        if (!EligibilitySnapshot.current().eligible(t.playerName, t.uuid)) return null;
        return t.uuid.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** Seraph counterpart of {@link #urchinSendUuid}: rechecks {@code eligibleSeraph} at send time. */
    private static String seraphSendUuid(Task t) {
        if (!t.seraphEligible || t.uuid == null) return null;
        if (!EligibilitySnapshot.current().eligibleSeraph(t.playerName, t.uuid)) return null;
        return t.uuid.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** Reset Urchin resolution on all entries so warm players re-enqueue (used on a successful key set). */
    public static void invalidateUrchinResolution() {
        // Advance the generation on SET too: an unavailable result from a request sent
        // while the key was missing/rejected must not re-resolve the entry after the new
        // key succeeds (it would pin the player resolved-empty for the entry lifetime).
        URCHIN_GEN.incrementAndGet();
        // replaceAll remaps each key atomically, so a concurrent per-key merge (compute) sees the
        // reset entry rather than losing this update to a stale get-then-put.
        CACHE.replaceAll((k, cur) -> new Entry(cur.stats, cur.timestamp, false, 0L,
                cur.seraphResolved, cur.seraphAttemptMs));
    }

    /** Immediately drop every entry's tags and mark it resolved-empty (used on a successful key clear). */
    public static void stripUrchinTags() {
        // Bump first: any request already in flight now carries an older generation and its Urchin
        // resolution is dropped at merge (mergeUrchin/putResolved), so it cannot reattach tags here.
        URCHIN_GEN.incrementAndGet();
        long now = System.currentTimeMillis();
        CACHE.replaceAll((k, cur) -> new Entry(cur.stats.withUrchinTags(null), cur.timestamp, true, now,
                cur.seraphResolved, cur.seraphAttemptMs));
    }

    /** Reset Seraph resolution on all entries so warm players re-enqueue (used on a successful key set). */
    public static void invalidateSeraphResolution() {
        SERAPH_GEN.incrementAndGet();
        CACHE.replaceAll((k, cur) -> new Entry(cur.stats, cur.timestamp,
                cur.urchinResolved, cur.urchinAttemptMs, false, 0L));
    }

    /** Immediately drop every entry's Seraph tags and mark it resolved-empty (on a successful key clear). */
    public static void stripSeraphTags() {
        SERAPH_GEN.incrementAndGet();
        long now = System.currentTimeMillis();
        CACHE.replaceAll((k, cur) -> new Entry(cur.stats.withSeraph(null, -1, -1), cur.timestamp,
                cur.urchinResolved, cur.urchinAttemptMs, true, now));
    }

    /** Cache key for a name-only lookup, namespaced so it can't collide with a UUID key. */
    private static String nameKey(String name) {
        return "name:" + name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static void ensureFetched(UUID uuid, int priority) {
        ensureFetched(uuid, priority, false, false);
    }

    public static void ensureFetched(UUID uuid, int priority, boolean urchinEligible) {
        ensureFetched(uuid, priority, urchinEligible, false);
    }

    /**
     * As {@link #ensureFetched(UUID, int)} but marks the task eligible per provider when its flag is
     * true (only ever set from a current-session confirmed-row pair). An eligible task re-enqueues even
     * on a cache hit while ANY provider lookup is unresolved and its 60 s retry gap has passed.
     */
    public static void ensureFetched(UUID uuid, int priority, boolean urchinEligible, boolean seraphEligible) {
        if (uuid == null) return;
        if (!useBackend()) return;
        String k = uuid.toString();
        Entry e = liveEntry(k);
        long now = System.currentTimeMillis();
        if (e != null && !needsUrchinRefresh(urchinEligible, e, now)
                && !needsSeraphRefresh(seraphEligible, e, now)) return;
        String playerName = PlayerNames.nameForUuid(uuid);
        if (playerName == null || playerName.isEmpty()) return;
        if (!QUEUED.add(k)) return;
        if (QUEUE.size() >= MAX_QUEUE) {
            QUEUED.remove(k);
            return;
        }
        QUEUE.add(new Task(k, uuid, playerName, priority, SEQ.incrementAndGet(),
                urchinEligible, seraphEligible, false));
    }

    /**
     * Queue a fetch keyed by player name, for players whose UUID isn't in your tab list (nicks, and
     * party/guild members in another lobby). The result is cached under the name, not a UUID.
     */
    public static void ensureFetchedByName(String name, int priority) {
        ensureFetchedByName(name, priority, false);
    }

    /**
     * As {@link #ensureFetchedByName(String, int)}, but when {@code force} is set a live cached entry
     * no longer short-circuits the enqueue once it has aged past {@link #URCHIN_REFRESH_MS}. The denick
     * backstop uses this to upgrade a stale pre-rankCode entry (whose provenance reads UNKNOWN) with a
     * fresh lookup instead of waiting out the full TTL; the age gate bounds a persistently-failing name
     * to one refetch per refresh window so it can never hammer the backend.
     */
    public static void ensureFetchedByName(String name, int priority, boolean force) {
        if (name == null || name.isEmpty()) return;
        if (!useBackend()) return;
        String key = nameKey(name);
        Entry cached = liveEntry(key);
        if (cached != null
                && !(force && System.currentTimeMillis() - cached.timestamp > URCHIN_REFRESH_MS)) return;
        if (!QUEUED.add(key)) return;
        if (QUEUE.size() >= MAX_QUEUE) {
            QUEUED.remove(key);
            return;
        }
        QUEUE.add(new Task(key, null, name.trim(), priority, SEQ.incrementAndGet(), false, false, force));
    }

    /**
     * Synchronous fetch for the /bw command. May throw on throttle/backend error.
     *
     * @param force when true, bypass both the local cache and the backend's edge cache so the
     *              returned stats are freshly scraped. /bw is invoked rarely enough that the
     *              extra scrape load is negligible; the fresh result is still written back to
     *              the local cache (and refreshes the shared edge entry) for later reuse.
     */
    public static BedwarsStats fetchNow(UUID uuid, String playerName, boolean force) throws Exception {
        if (!force) {
            BedwarsStats cached = getCached(uuid);
            if (cached != null) return cached;
        }

        if (!LIMITER.canRequest()) {
            throw new RateLimitedException(
                    "Rate limited; try again in " + (LIMITER.pauseRemainingMs() / 1000L + 1) + "s");
        }
        LIMITER.awaitSlot();

        String backend = statsBackendUrl();
        if (backend == null) {
            throw new ScraperBackendClient.BackendException("Stats backend not configured");
        }
        String name = playerName != null && !playerName.isEmpty()
                ? playerName
                : PlayerNames.nameForUuid(uuid);
        if (name == null || name.isEmpty()) {
            return BedwarsStats.nicked();
        }
        // /bw is the player waiting on a lookup they typed: high lane, same as PRIORITY_USER queue work.
        BedwarsStats stats = ScraperBackendClient.fetch(name, backend, statsBackendToken(), force,
                null, null, null, null, true);
        put(uuid.toString(), stats);
        return stats;
    }

    /**
     * Drains the priority queue and dispatches work to {@link #FETCHERS}. A USER-priority task (a /bw
     * or chat-hover lookup the player is waiting on) is fetched immediately as a single request; lower
     * priority tasks (visible players, tab-only) are coalesced over a short window into one batch
     * request, so a whole 16-player lobby resolves in one round trip instead of 16 serialized ones.
     */
    /**
     * Drains the priority queue and dispatches work to {@link #FETCHERS}. A USER-priority task (a /bw
     * or chat-hover lookup the player is waiting on) is fetched immediately as a single request; lower
     * priority tasks (visible players, tab-only) are coalesced over a short window into one streaming
     * batch request, so a whole lobby resolves over one round trip and renders progressively.
     *
     * <p><b>De-dup invariant:</b> a key stays in {@link #QUEUED} from enqueue until its result lands
     * (cleared by {@link #submitSingle}/{@link #submitBatch}, NOT here at dequeue). Otherwise a player
     * being scraped — not yet in CACHE — would be re-enqueued every render frame, firing redundant
     * scrapes that trip hypixel's ~1.7/s per-IP rate limit and slow the whole lobby down.
     */
    private static void dispatcherLoop() {
        while (true) {
            Task first;
            try {
                first = QUEUE.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (first.priority == PRIORITY_USER) {
                submitSingle(first);
                continue;
            }

            // Background: let the rest of the lobby enqueue, then grab the whole batch at once.
            List<Task> drained = new ArrayList<>();
            drained.add(first);
            try {
                Thread.sleep(COALESCE_WINDOW_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            QUEUE.drainTo(drained, MAX_BATCH - 1);

            // Keep the priority lane crisp: any USER tasks swept in still go out immediately.
            // The rest split by lane so an on-screen player is never queued behind the tab-only
            // players it was coalesced with - a mixed batch would give the visible lookup the
            // latency of its position in a 16-player scrape.
            List<Task> high = new ArrayList<>();
            List<Task> low = new ArrayList<>();
            for (Task t : drained) {
                if (t.priority == PRIORITY_USER) submitSingle(t);
                else if (t.priority <= PRIORITY_VISIBLE) high.add(t);
                else low.add(t);
            }
            if (!high.isEmpty()) submitBatch(high);
            if (!low.isEmpty()) submitBatch(low);
        }
    }

    private static void submitSingle(Task t) {
        final int uGen = URCHIN_GEN.get(); // capture at dispatch; a later clear drops this result's tags
        final int sGen = SERAPH_GEN.get();
        FETCHERS.submit(() -> {
            long now = System.currentTimeMillis();
            try {
                Entry e = liveEntry(t.key);
                if (e != null && !needsAnyRefresh(t, e, now)) return;
                String backend = statsBackendUrl();
                if (backend == null || t.playerName == null || t.playerName.isEmpty()) return;
                String urchinUuid = urchinSendUuid(t); // null unless send-time recheck passes
                String seraphUuid = seraphSendUuid(t);
                UrchinResult[] uHolder = {null};
                SeraphResult[] sHolder = {null};
                BedwarsStats s = ScraperBackendClient.fetch(t.playerName, backend, statsBackendToken(),
                        false, urchinUuid, r -> uHolder[0] = r, seraphUuid, r -> sHolder[0] = r,
                        t.priority <= PRIORITY_VISIBLE);
                putResolved(t.key, s, uHolder[0], urchinUuid != null, uGen,
                        sHolder[0], seraphUuid != null, sGen, now);
            } catch (Throwable other) {
                // Never clobber a warm entry with a failure marker (mirrors the batch-fallback guard).
                if (liveEntry(t.key) == null) put(t.key, BedwarsStats.error());
            } finally {
                QUEUED.remove(t.key); // done (or skipped) -> allow a future re-fetch
            }
        });
    }

    /**
     * Streams a batch: each player is cached and un-marked from {@link #QUEUED} the moment its NDJSON
     * line arrives, so the HUD fills in progressively rather than waiting for the slowest scrape.
     * Names that never resolve (network error) are marked ERROR (short TTL) so they retry soon.
     */
    private static void submitBatch(List<Task> batch) {
        final int uGen = URCHIN_GEN.get(); // capture at dispatch; a later clear drops these results' tags
        final int sGen = SERAPH_GEN.get();
        FETCHERS.submit(() -> {
            String backend = statsBackendUrl();
            long now = System.currentTimeMillis();
            // Map requested name -> task(s) (a name may back multiple keys); track who resolves.
            Map<String, List<Task>> byName = new HashMap<>();
            Set<String> names = new LinkedHashSet<>();
            for (Task t : batch) {
                Entry e = liveEntry(t.key);
                boolean keep = e == null || needsAnyRefresh(t, e, now);
                if (t.playerName == null || t.playerName.isEmpty() || !keep) {
                    QUEUED.remove(t.key);
                    continue;
                }
                byName.computeIfAbsent(t.playerName, k -> new ArrayList<>()).add(t);
                names.add(t.playerName);
            }
            if (backend == null || names.isEmpty()) {
                for (List<Task> ts : byName.values()) for (Task t : ts) QUEUED.remove(t.key);
                return;
            }
            // Index-aligned uuids param: a member's canonical UUID is emitted when EITHER provider is
            // eligible (send-time rechecked), "-" otherwise. The uuid identity is shared, but each
            // provider's opt-in header + attempt-stamp target is tracked separately so a member eligible
            // for only one provider is never looked up by the other.
            List<String> nameList = new ArrayList<>(names);
            StringBuilder uuidsSb = new StringBuilder();
            boolean anyUrchin = false, anySeraph = false;
            // Per-name attempt-stamp target key, split per provider (only the provider that actually
            // emitted the uuid stamps its attempt on the base line, B1/I6).
            Map<String, String> urchinKeyByName = new HashMap<>();
            Map<String, String> seraphKeyByName = new HashMap<>();
            // Canonical emitted UUID -> cache key (shared): resolutions merge by UUID identity, never by
            // name (B1). A case-varied duplicate name can't cross-attach one UUID's tags to another.
            Map<String, String> emittedKeyByUuid = new HashMap<>();
            for (String nm : nameList) {
                String send = "-";
                for (Task t : byName.get(nm)) {
                    String u = urchinSendUuid(t);
                    String su = seraphSendUuid(t);
                    if (u != null || su != null) {
                        String canon = u != null ? u : su;
                        send = canon;
                        emittedKeyByUuid.put(canon, t.key);
                        String lk = nm.toLowerCase(java.util.Locale.ROOT);
                        if (u != null) { anyUrchin = true; urchinKeyByName.put(lk, t.key); }
                        if (su != null) { anySeraph = true; seraphKeyByName.put(lk, t.key); }
                        break;
                    }
                }
                if (uuidsSb.length() > 0) uuidsSb.append(',');
                uuidsSb.append(send);
            }
            String uuidsCsv = (anyUrchin || anySeraph) ? uuidsSb.toString() : null;
            BiConsumer<String, UrchinResult> onUrchin = anyUrchin
                    ? (name, result) -> {
                        String emittedKey = UrchinRefreshPolicy.urchinMergeTarget(
                                result == null ? null : result.uuid, emittedKeyByUuid);
                        if (emittedKey == null) return;
                        mergeUrchin(emittedKey, result, System.currentTimeMillis(), uGen);
                    } : null;
            BiConsumer<String, SeraphResult> onSeraph = anySeraph
                    ? (name, result) -> {
                        String emittedKey = UrchinRefreshPolicy.urchinMergeTarget(
                                result == null ? null : result.uuid, emittedKeyByUuid);
                        if (emittedKey == null) return;
                        mergeSeraph(emittedKey, result, System.currentTimeMillis(), sGen);
                    } : null;
            Set<String> resolved = new HashSet<>();
            boolean threw = false;
            // Homogeneous by construction: the dispatcher partitions each drain by lane before
            // calling here, so the first task's lane is the whole batch's lane.
            boolean high = batch.get(0).priority <= PRIORITY_VISIBLE;
            try {
                DiagLog.log("BATCH n=" + nameList.size());
                ScraperBackendClient.fetchBatchStreaming(nameList, backend, statsBackendToken(),
                        (name, stats) -> {
                            List<Task> ts = byName.get(name);
                            if (ts == null) return;
                            resolved.add(name);
                            String lk = name.toLowerCase(java.util.Locale.ROOT);
                            String uKey = urchinKeyByName.get(lk);
                            String sKey = seraphKeyByName.get(lk);
                            for (Task t : ts) {
                                // Stamp each provider's attempt only for the key whose UUID it emitted (B1);
                                // other keys sharing the name are not the target (I6).
                                putBase(t.key, stats,
                                        UrchinRefreshPolicy.isUrchinMergeTarget(t.key, uKey),
                                        UrchinRefreshPolicy.isUrchinMergeTarget(t.key, sKey), now);
                                QUEUED.remove(t.key); // clear in-flight as each player lands
                            }
                        },
                        uuidsCsv, onUrchin, onSeraph, high);
            } catch (Throwable other) {
                threw = true; // whether singles make sense is decided below
            } finally {
                // Any player the batch didn't resolve falls back to a per-player lookup — the
                // long-standing single path — instead of leaving the tab/nametag blank. This also
                // covers a deployed Worker that predates the /bedwars/batch route: its single-route
                // fallback returns one non-NDJSON object the stream can't map, so nothing resolves and
                // everyone lands here. Once the batch Worker is deployed this branch goes quiet.
                //
                // Exception: the batch route erroring before ANY line resolved (429/5xx/transport)
                // means the backend is refusing or unreachable, so up to 16 immediate singles would
                // only amplify the failure. Mark cold keys ERROR instead — the short TTL plus streak
                // backoff paces the retry. A clean-but-unresolved stream (legacy single-route Worker,
                // server case-dedupe) and a partially-resolved stream still fall back as before.
                //
                // The singles are capped ({@link #FALLBACK_SINGLES_MAX}), paced through the shared
                // LIMITER and bounded by a wall-clock budget ({@link #FALLBACK_BUDGET_MS}), so a
                // partially-failed batch can never fan out unpaced or occupy a fetcher thread
                // indefinitely. Anything beyond the cap/budget gets the backendDown treatment.
                boolean backendDown = threw && resolved.isEmpty();
                List<Task> pending = new ArrayList<>();
                // Every key handled below enters `remaining` first and leaves it as its cleanup site
                // runs; the outermost finally sweeps whatever is left, so the enqueue-to-result
                // invariant (a key stays in QUEUED until its result lands) holds on every exit path.
                Set<String> remaining = new HashSet<>();
                for (Map.Entry<String, List<Task>> e : byName.entrySet()) {
                    if (resolved.contains(e.getKey())) continue;
                    for (Task t : e.getValue()) {
                        pending.add(t);
                        remaining.add(t.key);
                    }
                }
                try {
                    int singles = 0;
                    boolean stop = backendDown; // a refusing/unreachable backend gets no singles at all
                    long fallbackStart = System.currentTimeMillis();
                    for (Task t : pending) {
                        Entry cur = liveEntry(t.key);
                        boolean needsFetch = cur == null || needsAnyRefresh(t, cur, now);
                        if (needsFetch && !stop) {
                            long elapsed = System.currentTimeMillis() - fallbackStart;
                            if (singles >= FALLBACK_SINGLES_MAX || elapsed >= FALLBACK_BUDGET_MS) {
                                stop = true;
                            } else {
                                boolean slot = false;
                                try {
                                    // Bounded by the remaining budget: a fetch can never start past
                                    // the deadline, and the pacing wait itself can never exceed it.
                                    slot = LIMITER.awaitSlot(FALLBACK_BUDGET_MS - elapsed);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                if (!slot) {
                                    stop = true; // deadline or interrupt: start no further fetches
                                } else if (System.currentTimeMillis() - fallbackStart >= FALLBACK_BUDGET_MS) {
                                    stop = true; // budget ran out while waiting for the slot: no fetch
                                } else {
                                    singles++;
                                    needsFetch = false;
                                    try {
                                        String urchinUuid = urchinSendUuid(t); // fallback single: recheck too
                                        String seraphUuid = seraphSendUuid(t);
                                        UrchinResult[] uHolder = {null};
                                        SeraphResult[] sHolder = {null};
                                        BedwarsStats s = ScraperBackendClient.fetch(t.playerName, backend,
                                                statsBackendToken(), false, urchinUuid, r -> uHolder[0] = r,
                                                seraphUuid, r -> sHolder[0] = r,
                                                t.priority <= PRIORITY_VISIBLE);
                                        putResolved(t.key, s, uHolder[0], urchinUuid != null, uGen,
                                                sHolder[0], seraphUuid != null, sGen,
                                                System.currentTimeMillis());
                                    } catch (Throwable single) {
                                        // Never clobber a warm entry with a failure marker.
                                        if (cur == null) put(t.key, BedwarsStats.error());
                                    }
                                }
                            }
                        }
                        // Not fetched (backend down, past the cap/budget, or interrupted): mark cold
                        // keys ERROR so the short TTL + streak backoff re-paces them.
                        if (needsFetch && stop && cur == null) put(t.key, BedwarsStats.error());
                        QUEUED.remove(t.key);
                        remaining.remove(t.key);
                    }
                } finally {
                    for (String k : remaining) QUEUED.remove(k);
                }
            }
        });
    }

    private static void put(String key, BedwarsStats stats) {
        putBase(key, stats, false, false, 0L);
    }

    /**
     * Base (non-tag) stats merge. When a provider's {@code *Emitted} flag is true its canonical UUID
     * was actually sent in the request, so its attempt is stamped to {@code now} even if no resolution
     * line follows (B1) - without marking the entry resolved. Each provider's prior tags/resolution are
     * preserved across a plain stats refresh. See {@link UrchinRefreshPolicy#attemptForBaseMerge}.
     */
    private static void putBase(String key, BedwarsStats stats, boolean urchinEmitted,
                               boolean seraphEmitted, long now) {
        // compute remaps the key atomically so a concurrent strip/merge on the other provider can't be
        // lost to a stale get-then-put; the merged entry is always built from the current value.
        CACHE.compute(key, (k, prior) -> {
            // A transient failure (a streamed batch line included) must never clobber good data: keep
            // the prior entry and let the retry happen after it expires on its own TTL.
            if (prior != null && stats.state == BedwarsStats.State.ERROR
                    && prior.stats.state != BedwarsStats.State.ERROR) {
                return prior;
            }
            BedwarsStats merged = stats;
            boolean uResolved = false, sResolved = false;
            long uAttempt = 0L, sAttempt = 0L;
            if (prior != null) {
                uResolved = prior.urchinResolved;
                uAttempt = prior.urchinAttemptMs;
                sResolved = prior.seraphResolved;
                sAttempt = prior.seraphAttemptMs;
                // A plain stats refresh (re-fetch) keeps any provider data already resolved.
                if (stats.urchinTags.isEmpty() && !prior.stats.urchinTags.isEmpty()) {
                    merged = merged.withUrchinTags(prior.stats.urchinTags);
                }
                if (stats.seraphTags.isEmpty() && (!prior.stats.seraphTags.isEmpty()
                        || prior.stats.seraphThreat >= 0 || prior.stats.seraphEncounters >= 0)) {
                    merged = merged.withSeraph(prior.stats.seraphTags, prior.stats.seraphThreat,
                            prior.stats.seraphEncounters);
                }
            }
            uAttempt = UrchinRefreshPolicy.attemptForBaseMerge(uAttempt, urchinEmitted, now);
            sAttempt = UrchinRefreshPolicy.attemptForBaseMerge(sAttempt, seraphEmitted, now);
            return new Entry(merged, System.currentTimeMillis(), uResolved, uAttempt, sResolved, sAttempt);
        });
        noteErrorStreak(key, stats.state);
        if (stats.state != BedwarsStats.State.ERROR) dirty = true;
    }

    /**
     * Put fresh stats plus ALL providers' resolutions from the same fetch (single / batch-fallback
     * paths). Each provider is independently gated: a key cleared mid-flight (its generation advanced
     * since dispatch) drops that provider's resolution so a pre-clear response can't reattach stripped
     * data (B2), while the other providers and the base stats still merge.
     */
    private static void putResolved(String key, BedwarsStats stats,
                                    UrchinResult uResult, boolean uAttempted, int uReqGen,
                                    SeraphResult sResult, boolean sAttempted, int sReqGen, long now) {
        // Drop each stale provider result up front (gen advanced since dispatch), then remap atomically
        // so the surviving provider and base stats merge onto the current entry, never a stale snapshot.
        final UrchinResult uR = UrchinRefreshPolicy.dropStaleUrchin(uReqGen, URCHIN_GEN.get()) ? null : uResult;
        final SeraphResult sR = UrchinRefreshPolicy.dropStaleUrchin(sReqGen, SERAPH_GEN.get()) ? null : sResult;
        CACHE.compute(key, (k, prior) -> {
            // Same guard as putBase: a failed refetch must not clobber a warm entry.
            if (prior != null && stats.state == BedwarsStats.State.ERROR
                    && prior.stats.state != BedwarsStats.State.ERROR) {
                return prior;
            }
            BedwarsStats merged = stats;
            boolean uResolved = false;
            long uAttempt = uAttempted ? now : (prior != null ? prior.urchinAttemptMs : 0L);
            if (uR != null) {
                merged = merged.withUrchinTags(uR.tags);
                uResolved = uR.resolved();
            } else if (prior != null) {
                uResolved = prior.urchinResolved;
                if (!prior.stats.urchinTags.isEmpty()) merged = merged.withUrchinTags(prior.stats.urchinTags);
            }
            boolean sResolved = false;
            long sAttempt = sAttempted ? now : (prior != null ? prior.seraphAttemptMs : 0L);
            if (sR != null) {
                merged = merged.withSeraph(sR.tags, sR.threatLevel, sR.encounters);
                sResolved = sR.resolved();
            } else if (prior != null) {
                sResolved = prior.seraphResolved;
                if (!prior.stats.seraphTags.isEmpty() || prior.stats.seraphThreat >= 0
                        || prior.stats.seraphEncounters >= 0)
                    merged = merged.withSeraph(prior.stats.seraphTags,
                            prior.stats.seraphThreat, prior.stats.seraphEncounters);
            }
            return new Entry(merged, now, uResolved, uAttempt, sResolved, sAttempt);
        });
        noteErrorStreak(key, stats.state);
        if (stats.state != BedwarsStats.State.ERROR) dirty = true;
    }

    /** Merge a follow-up / inline Urchin resolution into an existing entry (tags are not persisted). */
    private static void mergeUrchin(String key, UrchinResult result, long now, int reqGen) {
        if (result == null) return;
        // compute reads the current entry under the key lock, so a Seraph strip/merge racing this
        // update can neither be lost nor have its cleared data resurrected from a stale snapshot.
        CACHE.compute(key, (k, prior) -> {
            if (prior == null) return null; // documented drop: next fetch attaches inline
            // Key cleared after this request dispatched: drop the tags rather than reattach them (B2).
            if (UrchinRefreshPolicy.dropStaleUrchin(reqGen, URCHIN_GEN.get())) return prior;
            BedwarsStats s = prior.stats.withUrchinTags(result.tags);
            boolean resolved = result.resolved() || prior.urchinResolved;
            return new Entry(s, prior.timestamp, resolved, now, prior.seraphResolved, prior.seraphAttemptMs);
        });
    }

    /** Merge a follow-up / inline Seraph resolution into an existing entry (tags are not persisted). */
    private static void mergeSeraph(String key, SeraphResult result, long now, int reqGen) {
        if (result == null) return;
        CACHE.compute(key, (k, prior) -> {
            if (prior == null) return null;
            if (UrchinRefreshPolicy.dropStaleUrchin(reqGen, SERAPH_GEN.get())) return prior;
            BedwarsStats s = prior.stats.withSeraph(result.tags, result.threatLevel, result.encounters);
            boolean resolved = result.resolved() || prior.seraphResolved;
            return new Entry(s, prior.timestamp, prior.urchinResolved, prior.urchinAttemptMs, resolved, now);
        });
    }

    private static long ttlFor(BedwarsStats.State state) {
        switch (state) {
            case OK: return OK_TTL_MS;
            case ERROR: return ERROR_TTL_MS;
            default: return NEGATIVE_TTL_MS;
        }
    }

    // ---- Disk persistence ------------------------------------------------

    private static File cacheFile() {
        // v2: richer entries (rank, per-mode). Old v1 files are abandoned.
        return new File(configDir(), "cobblify-stats-cache-v2.json");
    }

    /** Mod data dir (~/.cobblify), created on demand. Forge's config dir is unavailable under Weave. */
    private static File configDir() {
        File dir = new File(System.getProperty("user.home"), ".cobblify");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    private static void flushLoop() {
        while (true) {
            try {
                Thread.sleep(FLUSH_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            sweep();
            if (dirty) flush();
        }
    }

    /**
     * Evict expired entries and orphaned error streaks so a long session can't retain players that
     * never appear again (they are otherwise only evicted when looked up). Streaks are dropped first
     * and only for keys with no entry at all, so a just-expired ERROR entry keeps its streak until
     * the next cycle and an actively-retried player's backoff isn't reset.
     */
    private static void sweep() {
        ERROR_STREAK.keySet().removeIf(k -> !CACHE.containsKey(k));
        for (String k : CACHE.keySet()) liveEntry(k);
    }

    private static synchronized void flush() {
        if (!dirty) return;
        dirty = false;
        List<PersistentEntry> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, Entry> e : CACHE.entrySet()) {
            BedwarsStats s = e.getValue().stats;
            if (s.state == BedwarsStats.State.ERROR) continue;
            if (now - e.getValue().timestamp > ttlFor(s.state)) continue;
            out.add(PersistentEntry.from(e.getKey(), e.getValue()));
        }

        File file = cacheFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            GSON.toJson(out, w);
        } catch (Exception ignored) {
            dirty = true; // retry on next flush
        }
    }

    private static void load() {
        File file = cacheFile();
        if (!file.isFile()) return;
        Type type = new TypeToken<List<PersistentEntry>>() {}.getType();
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            List<PersistentEntry> in = GSON.fromJson(r, type);
            if (in == null) return;
            long now = System.currentTimeMillis();
            for (PersistentEntry pe : in) {
                if (pe == null || pe.uuid == null) continue;
                BedwarsStats stats = pe.toStats();
                if (stats == null) continue;
                if (now - pe.timestamp > ttlFor(stats.state)) continue;
                CACHE.put(pe.uuid, new Entry(stats, pe.timestamp));
            }
        } catch (Exception ignored) {
            // Corrupt cache file is non-fatal; start empty.
        }
    }

    private static final class Entry {
        final BedwarsStats stats;
        final long timestamp;
        /** Urchin lookup concluded (checked/unavailable) for this entry — no further Urchin retry. */
        final boolean urchinResolved;
        /** When the last Urchin lookup was attempted (bounds the 60 s refresh predicate). */
        final long urchinAttemptMs;
        /** Seraph lookup concluded for this entry — no further Seraph retry. */
        final boolean seraphResolved;
        /** When the last Seraph lookup was attempted. */
        final long seraphAttemptMs;
        Entry(BedwarsStats stats, long timestamp) {
            this(stats, timestamp, false, 0L, false, 0L);
        }
        Entry(BedwarsStats stats, long timestamp, boolean urchinResolved, long urchinAttemptMs,
              boolean seraphResolved, long seraphAttemptMs) {
            this.stats = stats;
            this.timestamp = timestamp;
            this.urchinResolved = urchinResolved;
            this.urchinAttemptMs = urchinAttemptMs;
            this.seraphResolved = seraphResolved;
            this.seraphAttemptMs = seraphAttemptMs;
        }
    }

    private static final class Task implements Comparable<Task> {
        final String key;
        final UUID uuid;
        final String playerName;
        final int priority;
        final long seq;
        /** Provenance: the task was created from a current-session confirmed-row pair (send-time rechecked). */
        final boolean urchinEligible;
        /** Seraph counterpart of {@link #urchinEligible} (independent per-provider opt-in). */
        final boolean seraphEligible;
        /** Refetch past a live entry once aged out of the refresh window (denick upgrade path). */
        final boolean force;
        Task(String key, UUID uuid, String playerName, int priority, long seq,
             boolean urchinEligible, boolean seraphEligible, boolean force) {
            this.key = key;
            this.uuid = uuid;
            this.playerName = playerName;
            this.priority = priority;
            this.seq = seq;
            this.urchinEligible = urchinEligible;
            this.seraphEligible = seraphEligible;
            this.force = force;
        }
        @Override
        public int compareTo(Task o) {
            if (priority != o.priority) return Integer.compare(priority, o.priority);
            return Long.compare(seq, o.seq);
        }
    }

    /** Plain DTO for Gson serialization (avoids reflecting over BedwarsStats' final fields). */
    private static final class PersistentEntry {
        String uuid;
        String state;
        String displayName;
        String rankPrefix;
        String rankCode; // null on entries persisted before this field → provenance UNKNOWN → refetch
        ModeDTO overall;
        ModeDTO solo;
        ModeDTO doubles;
        ModeDTO threes;
        ModeDTO fours;
        long timestamp;

        static PersistentEntry from(String uuid, Entry e) {
            BedwarsStats s = e.stats;
            PersistentEntry pe = new PersistentEntry();
            pe.uuid = uuid;
            pe.state = s.state.name();
            pe.displayName = s.displayName;
            pe.rankPrefix = s.rankPrefix;
            pe.rankCode = s.rankCode;
            pe.overall = ModeDTO.from(s.overall);
            pe.solo = ModeDTO.from(s.solo);
            pe.doubles = ModeDTO.from(s.doubles);
            pe.threes = ModeDTO.from(s.threes);
            pe.fours = ModeDTO.from(s.fours);
            pe.timestamp = e.timestamp;
            return pe;
        }

        BedwarsStats toStats() {
            if (state == null) return null;
            switch (state) {
                case "OK":
                    return BedwarsStats.ok(displayName, rankPrefix, rankCode,
                            ModeDTO.toOrEmpty(overall), ModeDTO.toOrEmpty(solo), ModeDTO.toOrEmpty(doubles),
                            ModeDTO.toOrEmpty(threes), ModeDTO.toOrEmpty(fours));
                case "NEVER_PLAYED":
                    return BedwarsStats.neverPlayed(displayName);
                case "NICKED":
                    return BedwarsStats.nicked();
                default:
                    return null;
            }
        }
    }

    /** Per-mode counters for serialization. */
    private static final class ModeDTO {
        int finalKills;
        int finalDeaths;
        int wins;
        int losses;
        int kills;
        int deaths;

        static ModeDTO from(BedwarsStats.ModeStats m) {
            ModeDTO d = new ModeDTO();
            d.finalKills = m.finalKills;
            d.finalDeaths = m.finalDeaths;
            d.wins = m.wins;
            d.losses = m.losses;
            d.kills = m.kills;
            d.deaths = m.deaths;
            return d;
        }

        static BedwarsStats.ModeStats toOrEmpty(ModeDTO d) {
            return d == null ? BedwarsStats.ModeStats.EMPTY
                    : new BedwarsStats.ModeStats(d.finalKills, d.finalDeaths, d.wins, d.losses, d.kills, d.deaths);
        }
    }
}
