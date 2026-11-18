package com.bedwarsqol.stats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.bedwarsqol.rewind.watch.PlaybackGate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves a player name to a UUID. Only needed by {@code /bw <name>} for players
 * who are not currently in the world/tab list (in-game players already carry their
 * UUID via their GameProfile). UUIDs are stable, so we cache them aggressively.
 *
 * <p>The network part runs under one Rewind gate permit ({@link PlaybackGate}, PLAN "Isolation", layer 4) with each
 * connection attached and the permit re-checked before each request starts (the Ashcon fallback included). While a
 * recording plays, or once entry aborts the lookup, it fails with {@link PlaybackGate.PlaybackMutedException}.
 */
public final class MojangNameResolver {

    private static final String MOJANG = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String ASHCON = "https://api.ashcon.app/mojang/v2/user/";
    /** Base-URL seam for tests (a local server); production never changes these. */
    static volatile String mojangBase = MOJANG;
    static volatile String ashconBase = ASHCON;
    private static final long TTL_MS = 6L * 60L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;

    private static final ConcurrentMap<String, Entry> CACHE = new ConcurrentHashMap<>();

    private MojangNameResolver() {}

    /** Returns the resolved UUID, or null if the name does not exist. */
    public static UUID resolve(String name) throws IOException {
        if (name == null || name.trim().isEmpty()) return null;
        String key = name.trim().toLowerCase(Locale.ROOT);

        Entry cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.timestamp <= TTL_MS) {
            return cached.uuid;
        }

        UUID uuid;
        PlaybackGate.Permit permit = PlaybackGate.global().admit();
        try {
            try {
                uuid = resolveMojang(name.trim(), permit);
            } catch (PlaybackGate.PlaybackMutedException muted) {
                throw muted;
            } catch (IOException primary) {
                permit.check(); // an aborted lookup never falls back to a second request
                uuid = resolveAshcon(name.trim(), permit);
            }
        } catch (IOException e) {
            throw permit.failure(e);
        } finally {
            permit.end();
        }
        CACHE.put(key, new Entry(uuid, System.currentTimeMillis()));
        return uuid;
    }

    private static UUID resolveMojang(String name, PlaybackGate.Permit permit) throws IOException {
        String encoded = URLEncoder.encode(name, "UTF-8");
        HttpURLConnection conn = open(mojangBase + encoded, permit);
        int code = conn.getResponseCode();
        if (code == 204 || code == 404) return null;
        if (code < 200 || code >= 300) throw new IOException("Mojang returned " + code);
        JsonObject root = parse(readAll(conn.getInputStream()));
        return fromUndashed(string(root, "id"));
    }

    private static UUID resolveAshcon(String name, PlaybackGate.Permit permit) throws IOException {
        String encoded = URLEncoder.encode(name, "UTF-8");
        HttpURLConnection conn = open(ashconBase + encoded, permit);
        int code = conn.getResponseCode();
        if (code == 404) return null;
        if (code < 200 || code >= 300) throw new IOException("Ashcon returned " + code);
        JsonObject root = parse(readAll(conn.getInputStream()));
        String uuid = string(root, "uuid");
        if (uuid != null && uuid.contains("-")) {
            try {
                return UUID.fromString(uuid);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return fromUndashed(uuid);
    }

    /** Opens and attaches the connection, then re-checks the permit: the request starts in the caller's next call. */
    private static HttpURLConnection open(String url, PlaybackGate.Permit permit) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        permit.attach(conn);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "BedwarsQol/0.1 MojangNameResolver");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        Tls.apply(conn);
        permit.check();
        return conn;
    }

    private static UUID fromUndashed(String id) {
        if (id == null || id.length() != 32) return null;
        String dashed = id.substring(0, 8) + "-" + id.substring(8, 12) + "-"
                + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20);
        try {
            return UUID.fromString(dashed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static JsonObject parse(String body) throws IOException {
        try {
            JsonElement el = new JsonParser().parse(body == null ? "" : body);
            if (el != null && el.isJsonObject()) return el.getAsJsonObject();
        } catch (RuntimeException ignored) {
            // fall through
        }
        throw new IOException("Invalid name-resolution response");
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

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(2048);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[2048];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static final class Entry {
        final UUID uuid;
        final long timestamp;
        Entry(UUID uuid, long timestamp) {
            this.uuid = uuid;
            this.timestamp = timestamp;
        }
    }
}
