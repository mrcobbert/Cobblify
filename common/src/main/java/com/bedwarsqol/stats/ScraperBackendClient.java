package com.bedwarsqol.stats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.google.gson.JsonArray;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fetches Bedwars stats from the BedwarsQol Cloudflare Worker (forum scrape backend).
 * No Hypixel API key; Hypixel only sees Cloudflare egress, not the player's home IP.
 *
 * <p>Two shapes: {@link #fetch} for a single player (the immediate USER path), and
 * {@link #fetchBatch} for many players in one request (the background-lobby path). The Worker
 * paces its own cache-miss scrapes, so the client no longer has to serialize requests itself.
 */
public final class ScraperBackendClient {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    // A batch may scrape several cold pages server-side; give it more room than a single lookup.
    private static final int BATCH_READ_TIMEOUT_MS = 30000;

    /** The opt-in header the eligible owner client sends so the Worker enriches Urchin tags. */
    private static final String URCHIN_OPT_IN_HEADER = "X-BWQOL-Urchin";

    /** The opt-in header the eligible owner client sends so the Worker enriches Seraph tags. */
    private static final String SERAPH_OPT_IN_HEADER = "X-BWQOL-Seraph";

    private ScraperBackendClient() {}

    /**
     * Seam for opening the {@link #postSecret} connection so redirect/non-replay behavior can be
     * exercised headlessly without a live TLS endpoint. The default opener is the real one; tests
     * swap in a recording factory. Package-private on purpose — not part of the public API.
     */
    interface SecretConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    static SecretConnectionFactory secretConnectionFactory =
            new SecretConnectionFactory() {
                @Override
                public HttpURLConnection open(URL url) throws IOException {
                    return (HttpURLConnection) url.openConnection();
                }
            };

    public static final class BackendException extends IOException {
        public BackendException(String message) { super(message); }
    }

    public static BedwarsStats fetch(String playerName, String baseUrl, String token, boolean fresh) throws IOException {
        return fetch(playerName, baseUrl, token, fresh, null, null);
    }

    /**
     * Single fetch, optionally Urchin-enriched: when {@code urchinUuid} is non-null the request carries
     * the {@code X-BWQOL-Urchin} opt-in header and {@code ?uuid=<canonical>}, and any Urchin resolution
     * in the response is delivered to {@code onUrchin}. Ordinary callers pass null for both — zero
     * Urchin traffic, byte-identical to the legacy request.
     */
    public static BedwarsStats fetch(String playerName, String baseUrl, String token, boolean fresh,
            String urchinUuid, Consumer<UrchinResult> onUrchin) throws IOException {
        return fetch(playerName, baseUrl, token, fresh, urchinUuid, onUrchin, null, null, false);
    }

    public static BedwarsStats fetch(String playerName, String baseUrl, String token, boolean fresh,
            String urchinUuid, Consumer<UrchinResult> onUrchin,
            String seraphUuid, Consumer<SeraphResult> onSeraph) throws IOException {
        return fetch(playerName, baseUrl, token, fresh, urchinUuid, onUrchin, seraphUuid, onSeraph, false);
    }

    /**
     * Single fetch enriched by any subset of providers. Each provider's non-null uuid adds its opt-in
     * header and (the shared) {@code ?uuid=}, and its resolution is delivered to its callback. All-null
     * provider args = pure legacy request, zero provider traffic.
     *
     * <p>{@code highPriority} sends {@code &prio=1}, which only REORDERS work at the backend's origin
     * pacer - a high-lane request is granted ahead of queued background work. It does not raise the
     * origin request rate, so it makes a waited-on lookup land sooner without adding scrape load.
     */
    public static BedwarsStats fetch(String playerName, String baseUrl, String token, boolean fresh,
            String urchinUuid, Consumer<UrchinResult> onUrchin,
            String seraphUuid, Consumer<SeraphResult> onSeraph, boolean highPriority) throws IOException {
        if (playerName == null || playerName.trim().isEmpty()) {
            throw new BackendException("Empty player name");
        }
        String base = normalizeBase(baseUrl);
        if (base == null) throw new BackendException("No stats backend URL configured");

        boolean wantUrchin = urchinUuid != null && !urchinUuid.isEmpty();
        boolean wantSeraph = seraphUuid != null && !seraphUuid.isEmpty();
        // Both providers key off the same canonical uuid; send it if either wants enrichment.
        String uuid = wantUrchin ? urchinUuid : (wantSeraph ? seraphUuid : null);

        String encoded = java.net.URLEncoder.encode(playerName.trim(), "UTF-8");
        StringBuilder query = new StringBuilder();
        if (fresh) query.append(query.length() == 0 ? '?' : '&').append("fresh=1");
        if (uuid != null) {
            query.append(query.length() == 0 ? '?' : '&').append("uuid=").append(uuid);
        }
        if (highPriority) query.append(query.length() == 0 ? '?' : '&').append("prio=1");
        // fresh=1 tells the Worker to bypass (and refresh) its edge cache for an up-to-date scrape.
        String url = base + "/bedwars/" + encoded + query;
        HttpURLConnection conn = open(url, token, READ_TIMEOUT_MS);
        if (wantUrchin) conn.setRequestProperty(URCHIN_OPT_IN_HEADER, "1");
        if (wantSeraph) conn.setRequestProperty(SERAPH_OPT_IN_HEADER, "1");

        String body = readBody(conn);
        JsonObject po = parseJsonObject(body);
        if (onUrchin != null) {
            UrchinResult r = parseUrchin(po);
            if (r != null) onUrchin.accept(r);
        }
        if (onSeraph != null) {
            SeraphResult r = parseSeraph(po);
            if (r != null) onSeraph.accept(r);
        }
        return statsFromPlayerObject(po, playerName.trim());
    }

    /**
     * Resolve many players in one streaming request. The backend returns NDJSON — one player object
     * per line (each carrying a {@code "name"} field), cache hits first then cold scrapes as they land
     * — and this invokes {@code onResult} for each line the moment it arrives, so callers can render
     * players progressively instead of waiting for the slowest (rate-limited) scrape in the lobby.
     */
    public static void fetchBatchStreaming(List<String> names, String baseUrl, String token,
            BiConsumer<String, BedwarsStats> onResult) throws IOException {
        fetchBatchStreaming(names, baseUrl, token, onResult, null, null);
    }

    /**
     * As above, plus Urchin: when {@code uuidsCsv} is non-null it is sent as {@code &uuids=<csv>}
     * (index-aligned with {@code names}, {@code -} placeholder for ineligible members) and the
     * {@code X-BWQOL-Urchin} opt-in header is sent. Urchin resolution (inline on base lines and via
     * {@code urchinUpdate} follow-ups) is delivered to {@code onUrchin}. Null {@code uuidsCsv} = pure
     * legacy stream, zero Urchin traffic.
     */
    public static void fetchBatchStreaming(List<String> names, String baseUrl, String token,
            BiConsumer<String, BedwarsStats> onResult,
            String uuidsCsv, BiConsumer<String, UrchinResult> onUrchin) throws IOException {
        fetchBatchStreaming(names, baseUrl, token, onResult, uuidsCsv, onUrchin, null, false);
    }

    public static void fetchBatchStreaming(List<String> names, String baseUrl, String token,
            BiConsumer<String, BedwarsStats> onResult,
            String uuidsCsv, BiConsumer<String, UrchinResult> onUrchin,
            BiConsumer<String, SeraphResult> onSeraph) throws IOException {
        fetchBatchStreaming(names, baseUrl, token, onResult, uuidsCsv, onUrchin, onSeraph, false);
    }

    /**
     * As above, plus Seraph: the same aligned {@code uuidsCsv} feeds both providers. Each provider's
     * opt-in header is sent when its callback is non-null; resolutions arrive on the matching callback
     * (inline on base lines and via {@code urchinUpdate}/{@code seraphUpdate} follow-ups). A provider
     * with a null callback causes zero traffic.
     *
     * <p>{@code highPriority} sends {@code &prio=1}, which only REORDERS work at the backend's origin
     * pacer - a high-lane request is granted ahead of queued background work. It does not raise the
     * origin request rate, so it never speeds the origin up, it only changes who goes first.
     */
    public static void fetchBatchStreaming(List<String> names, String baseUrl, String token,
            BiConsumer<String, BedwarsStats> onResult,
            String uuidsCsv, BiConsumer<String, UrchinResult> onUrchin,
            BiConsumer<String, SeraphResult> onSeraph, boolean highPriority) throws IOException {
        if (names == null || names.isEmpty()) return;
        String base = normalizeBase(baseUrl);
        if (base == null) throw new BackendException("No stats backend URL configured");

        StringBuilder param = new StringBuilder();
        for (String n : names) {
            if (n == null) continue;
            String t = n.trim();
            if (t.isEmpty()) continue;
            if (param.length() > 0) param.append(',');
            param.append(java.net.URLEncoder.encode(t, "UTF-8"));
        }
        if (param.length() == 0) return;

        boolean haveUuids = uuidsCsv != null && !uuidsCsv.isEmpty();
        boolean wantUrchin = haveUuids && onUrchin != null;
        boolean wantSeraph = haveUuids && onSeraph != null;

        String url = base + "/bedwars/batch?names=" + param;
        if (haveUuids && (wantUrchin || wantSeraph)) url += "&uuids=" + uuidsCsv;
        if (highPriority) url += "&prio=1";
        HttpURLConnection conn = open(url, token, BATCH_READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/x-ndjson");
        conn.setRequestProperty("Accept-Encoding", "identity"); // keep NDJSON lines unbuffered
        if (wantUrchin) conn.setRequestProperty(URCHIN_OPT_IN_HEADER, "1");
        if (wantSeraph) conn.setRequestProperty(SERAPH_OPT_IN_HEADER, "1");

        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (code < 200 || code >= 300) {
            readAll(in);
            throw new BackendException("Stats backend returned " + code);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                JsonObject po;
                try {
                    po = parseJsonObject(line);
                } catch (IOException e) {
                    continue; // skip a malformed line, keep streaming
                }
                String name = string(po, "name");
                if (name == null || name.isEmpty()) continue;
                if (bool(po, "urchinUpdate", false)) {
                    if (onUrchin != null) {
                        UrchinResult ur = parseUrchin(po);
                        if (ur != null) onUrchin.accept(name, ur);
                    }
                    continue;
                }
                if (bool(po, "seraphUpdate", false)) {
                    if (onSeraph != null) {
                        SeraphResult sr = parseSeraph(po);
                        if (sr != null) onSeraph.accept(name, sr);
                    }
                    continue;
                }
                // Every base line carries a state; follow-up lines never do. An unrecognized
                // follow-up (a legacy starUpdate from a backend that still scrapes the star page,
                // or any kind added later) would otherwise parse as a base line with no counters
                // and overwrite an already-resolved player with FKDR 0.00. Skipping it costs
                // nothing: the player keeps the stats its base line already delivered.
                if (string(po, "state") == null) continue;
                onResult.accept(name, statsFromPlayerObject(po, name));
                if (onUrchin != null) {
                    UrchinResult ur = parseUrchin(po); // inline resolution on a base line
                    if (ur != null) onUrchin.accept(name, ur);
                }
                if (onSeraph != null) {
                    SeraphResult sr = parseSeraph(po); // inline resolution on a base line
                    if (sr != null) onSeraph.accept(name, sr);
                }
            }
        }
    }

    private static HttpURLConnection open(String url, String token, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "BedwarsQol/0.1 ScraperBackendClient");
        if (token != null && !token.trim().isEmpty()) {
            conn.setRequestProperty("X-BedwarsQol-Token", token.trim());
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(readTimeoutMs);
        Tls.apply(conn);
        return conn;
    }

    /** Reads the full body (draining error streams too, so the keep-alive socket can be reused). */
    private static String readBody(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(in);
        if (code < 200 || code >= 300) {
            throw new BackendException("Stats backend returned " + code);
        }
        return body;
    }

    private static String normalizeBase(String baseUrl) {
        if (baseUrl == null) return null;
        String s = baseUrl.trim();
        if (s.isEmpty()) return null;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static JsonObject parseJsonObject(String body) throws IOException {
        try {
            JsonElement el = new JsonParser().parse(body == null ? "" : body);
            if (el == null || !el.isJsonObject()) throw new IOException("bad json");
            return el.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid backend response", e);
        }
    }

    /** Build a {@link BedwarsStats} from one player object (the single body, or one entry of a batch). */
    private static BedwarsStats statsFromPlayerObject(JsonObject root, String requestedName) {
        if (!bool(root, "success", true)) {
            String state = string(root, "state");
            if ("NICKED".equals(state)) return BedwarsStats.nicked();
            return BedwarsStats.error();
        }

        String state = string(root, "state");
        String displayName = string(root, "displayName");
        if (displayName == null || displayName.isEmpty()) displayName = requestedName;

        if ("NEVER_PLAYED".equals(state)) {
            return BedwarsStats.neverPlayed(displayName);
        }
        if ("NICKED".equals(state)) {
            return BedwarsStats.nicked();
        }
        if (state != null && !"OK".equals(state)) {
            return BedwarsStats.error();
        }

        String rankCode = string(root, "rank");
        String rankPrefix = HypixelRanks.prefix(rankCode);
        // Coerce null → "" so a successfully-fetched rankless (Default) account is KNOWN non-elevated,
        // distinct from a null code (old cache / unknown) which forces a denick refetch.
        JsonObject modes = obj(root, "modes");
        return BedwarsStats.ok(
                displayName,
                rankPrefix,
                rankCode == null ? "" : rankCode,
                readOverall(root),
                readMode(modes, "solo"),
                readMode(modes, "doubles"),
                readMode(modes, "threes"),
                readMode(modes, "fours"));
    }

    private static JsonObject obj(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key)) return null;
        JsonElement el = root.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static BedwarsStats.ModeStats readMode(JsonObject modes, String key) {
        JsonObject o = obj(modes, key);
        return o == null ? BedwarsStats.ModeStats.EMPTY : modeFrom(o);
    }

    /** Overall stats from the {@code overall} object, falling back to flat top-level fields. */
    private static BedwarsStats.ModeStats readOverall(JsonObject root) {
        JsonObject o = obj(root, "overall");
        return modeFrom(o != null ? o : root);
    }

    private static BedwarsStats.ModeStats modeFrom(JsonObject o) {
        return new BedwarsStats.ModeStats(
                number(o, "finalKills"),
                number(o, "finalDeaths"),
                number(o, "wins"),
                number(o, "losses"),
                number(o, "kills"),
                number(o, "deaths"));
    }

    private static boolean bool(JsonObject root, String key, boolean fallback) {
        if (root == null || key == null || !root.has(key)) return fallback;
        try {
            return root.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String string(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key)) return null;
        JsonElement el = root.get(key);
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int number(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key)) return 0;
        JsonElement el = root.get(key);
        if (el == null || el.isJsonNull()) return 0;
        try {
            return el.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double doubleNum(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key)) return 0.0;
        JsonElement el = root.get(key);
        if (el == null || el.isJsonNull()) return 0.0;
        try {
            return el.getAsDouble();
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    // ---- Urchin -------------------------------------------------------------

    /**
     * Parse the Urchin resolution fields from one player object (base line, single body, or
     * {@code urchinUpdate} follow-up). Returns null when the line carries NO resolution fields at all
     * (a lookup failure/timeout the client may retry). Static + pure for unit testing.
     */
    public static UrchinResult parseUrchin(JsonObject po) {
        if (po == null) return null;
        boolean checked = bool(po, "urchinChecked", false);
        boolean unavailable = bool(po, "urchinUnavailable", false);
        boolean notFound = bool(po, "urchinNotFound", false);
        JsonObject urchin = obj(po, "urchin");
        boolean hasUrchinField = urchin != null;
        if (!checked && !unavailable && !notFound && !hasUrchinField) return null;
        return new UrchinResult(parseTags(urchin), checked, unavailable, notFound, canonicalUuid(string(po, "urchinUuid")));
    }

    /**
     * Parse the Seraph resolution fields from one player object (base line, single body, or
     * {@code seraphUpdate} follow-up). Returns null when the line carries NO resolution fields at all
     * (a lookup failure/timeout the client may retry). Static + pure for unit testing.
     */
    public static SeraphResult parseSeraph(JsonObject po) {
        if (po == null) return null;
        boolean checked = bool(po, "seraphChecked", false);
        boolean unavailable = bool(po, "seraphUnavailable", false);
        boolean notFound = bool(po, "seraphNotFound", false);
        JsonObject seraph = obj(po, "seraph");
        if (!checked && !unavailable && !notFound && seraph == null) return null;
        int threat = -1, encounters = -1;
        if (seraph != null) {
            if (seraph.has("threatLevel") && !seraph.get("threatLevel").isJsonNull()) threat = number(seraph, "threatLevel");
            if (seraph.has("encounters") && !seraph.get("encounters").isJsonNull()) encounters = number(seraph, "encounters");
        }
        return new SeraphResult(parseSeraphTags(seraph), checked, unavailable, notFound,
                canonicalUuid(string(po, "seraphUuid")), threat, encounters);
    }

    /** Parse the {@code tags} array of a {@code seraph} object into {@link SeraphTag}s. */
    public static List<SeraphTag> parseSeraphTags(JsonObject seraph) {
        List<SeraphTag> out = new ArrayList<SeraphTag>();
        if (seraph == null || !seraph.has("tags")) return out;
        JsonElement el = seraph.get("tags");
        if (el == null || !el.isJsonArray()) return out;
        JsonArray arr = el.getAsJsonArray();
        for (JsonElement e : arr) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            String kind = string(t, "kind");
            if (kind == null || kind.isEmpty()) continue;
            out.add(new SeraphTag(kind, string(t, "subtype"), string(t, "reason"),
                    longNum(t, "addedOn"), bool(t, "verified", false)));
        }
        return out;
    }

    /** Canonical (lowercase, undashed) UUID form, or null when absent/blank. Defends the B1 merge key. */
    private static String canonicalUuid(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("-", "").toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    /** Parse the {@code tags} array of an {@code urchin} object into {@link UrchinTag}s. */
    public static List<UrchinTag> parseTags(JsonObject urchin) {
        List<UrchinTag> out = new ArrayList<UrchinTag>();
        if (urchin == null || !urchin.has("tags")) return out;
        JsonElement el = urchin.get("tags");
        if (el == null || !el.isJsonArray()) return out;
        JsonArray arr = el.getAsJsonArray();
        for (JsonElement e : arr) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            String type = string(t, "type");
            if (type == null || type.isEmpty()) continue;
            String reason = string(t, "reason");
            long addedOn = longNum(t, "addedOn");
            Long expires = null;
            if (t.has("expiresAtMs") && !t.get("expiresAtMs").isJsonNull()) {
                // Present expiry must be valid: a non-numeric or non-positive value is treated as
                // corrupt metadata and DROPS the tag defensively (never "never expires").
                Long parsed = longObj(t, "expiresAtMs");
                if (parsed == null || parsed <= 0L) continue;
                expires = parsed;
            }
            out.add(new UrchinTag(type, reason, addedOn, expires));
        }
        return out;
    }

    /** Result of {@code GET /urchin/<name>} (manual command path). */
    public static final class UrchinLookup {
        public final boolean success;
        public final String player;
        public final String uuid;
        public final List<UrchinTag> tags;
        public final boolean stale;
        public final boolean notFound;
        public final boolean unavailable;

        UrchinLookup(boolean success, String player, String uuid, List<UrchinTag> tags,
                     boolean stale, boolean notFound, boolean unavailable) {
            this.success = success;
            this.player = player;
            this.uuid = uuid;
            this.tags = tags == null ? new ArrayList<UrchinTag>() : tags;
            this.stale = stale;
            this.notFound = notFound;
            this.unavailable = unavailable;
        }
    }

    /** Manual Urchin lookup by name; requires the opt-in header (owner-gated on the Worker). */
    public static UrchinLookup getUrchin(String baseUrl, String token, String name) throws IOException {
        String base = normalizeBase(baseUrl);
        if (base == null) throw new BackendException("No stats backend URL configured");
        String url = base + "/urchin/" + java.net.URLEncoder.encode(name.trim(), "UTF-8");
        HttpURLConnection conn = open(url, token, READ_TIMEOUT_MS);
        conn.setRequestProperty(URCHIN_OPT_IN_HEADER, "1");
        String body = readBody(conn);
        JsonObject po = parseJsonObject(body);
        List<UrchinTag> tags = new ArrayList<UrchinTag>();
        if (po.has("tags") && po.get("tags").isJsonArray()) {
            JsonObject wrap = new JsonObject();
            wrap.add("tags", po.get("tags"));
            tags = parseTags(wrap);
        }
        return new UrchinLookup(bool(po, "success", false), string(po, "player"), string(po, "uuid"),
                tags, bool(po, "stale", false), bool(po, "notFound", false), bool(po, "unavailable", false));
    }

    /** Result of a manual Seraph lookup by name (the {@code /cobblify seraph} command path). */
    public static final class SeraphLookup {
        public final boolean success;
        public final String player;
        public final String uuid;
        public final List<SeraphTag> tags;
        public final boolean notFound;
        public final boolean unavailable;

        SeraphLookup(boolean success, String player, String uuid, List<SeraphTag> tags,
                     boolean notFound, boolean unavailable) {
            this.success = success;
            this.player = player;
            this.uuid = uuid;
            this.tags = tags == null ? new ArrayList<SeraphTag>() : tags;
            this.notFound = notFound;
            this.unavailable = unavailable;
        }
    }

    /**
     * Manual Seraph lookup by name. Seraph is UUID-only, so the name is resolved to a UUID
     * client-side (via {@link MojangNameResolver}) before the Worker's uuid-keyed Seraph route is
     * called with the opt-in header (owner-gated on the Worker). An unresolvable name -> notFound.
     */
    public static SeraphLookup getSeraph(String baseUrl, String token, String name) throws IOException {
        String base = normalizeBase(baseUrl);
        if (base == null) throw new BackendException("No stats backend URL configured");
        java.util.UUID resolved = MojangNameResolver.resolve(name.trim());
        if (resolved == null) {
            return new SeraphLookup(true, name.trim(), null, new ArrayList<SeraphTag>(), true, false);
        }
        String uuid = resolved.toString().replace("-", "");
        String url = base + "/seraph/" + uuid;
        HttpURLConnection conn = open(url, token, READ_TIMEOUT_MS);
        conn.setRequestProperty(SERAPH_OPT_IN_HEADER, "1");
        String body = readBody(conn);
        JsonObject po = parseJsonObject(body);
        List<SeraphTag> tags = new ArrayList<SeraphTag>();
        if (po.has("tags") && po.get("tags").isJsonArray()) {
            JsonObject wrap = new JsonObject();
            wrap.add("tags", po.get("tags"));
            tags = parseSeraphTags(wrap);
        }
        return new SeraphLookup(bool(po, "success", false), name.trim(), string(po, "uuid"),
                tags, bool(po, "notFound", false), bool(po, "unavailable", false));
    }

    /** Result of a fail-closed secret POST. */
    public static final class SecretPostResult {
        public final boolean success;
        public final int status;   // HTTP status, or 0 when no request was made (validation/transport error)
        public final String error; // machine error token (e.g. "invalid_url", "key_managed_by_secret") or null

        SecretPostResult(boolean success, int status, String error) {
            this.success = success;
            this.status = status;
            this.error = error;
        }
    }

    /**
     * Validate a base URL for secret submission BEFORE any connection is opened or the secret touched.
     * Requires an absolute URL, scheme exactly {@code https} (case-insensitive), and no userinfo. Pure
     * and static so it is unit-testable. Returns an error token, or null when the URL is acceptable.
     */
    public static String validateSecretUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) return "invalid_url";
        URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (Exception e) {
            return "invalid_url";
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) return "invalid_url";
        if (!uri.getScheme().toLowerCase(Locale.ROOT).equals("https")) return "insecure_scheme";
        if (uri.getUserInfo() != null) return "userinfo_present";
        if (uri.getHost() == null) return "invalid_url";
        return null;
    }

    /**
     * POST {@code bodyJson} to {@code baseUrl + path} over HTTPS with the shared token header. Validates
     * the URL first (see {@link #validateSecretUrl}); opens the HTTPS connection with redirects
     * disabled and treats every 3xx as failure — the secret body is never replayed to a redirect target.
     */
    public static SecretPostResult postSecret(String baseUrl, String path, String token, String bodyJson) {
        String base = normalizeBase(baseUrl);
        String err = validateSecretUrl(base);
        if (err != null) return new SecretPostResult(false, 0, err);
        try {
            HttpURLConnection conn = secretConnectionFactory.open(new URL(base + path));
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "BedwarsQol/0.1 ScraperBackendClient");
            if (token != null && !token.trim().isEmpty()) {
                conn.setRequestProperty("X-BedwarsQol-Token", token.trim());
            }
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            Tls.apply(conn);
            byte[] payload = (bodyJson == null ? "" : bodyJson).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) return new SecretPostResult(false, code, "redirect_rejected");
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String respBody = readAll(in);
            String errToken = null;
            try {
                JsonObject o = parseJsonObject(respBody);
                errToken = string(o, "error");
            } catch (IOException ignored) { }
            return new SecretPostResult(code >= 200 && code < 300, code, errToken);
        } catch (Exception e) {
            // Never surface the exception text (it can embed the URL, never the secret) — a generic token.
            return new SecretPostResult(false, 0, "transport_error");
        }
    }

    private static long longNum(JsonObject root, String key) {
        Long v = longObj(root, key);
        return v == null ? 0L : v;
    }

    private static Long longObj(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key)) return null;
        JsonElement el = root.get(key);
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(4096);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
