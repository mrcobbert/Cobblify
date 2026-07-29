package com.bedwarsqol.stats;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * A backend line that is NOT a base stats line must never reach the stats parser.
 *
 * <p>Regression: the client used to have an explicit {@code starUpdate} branch. When the star was
 * removed that branch went away, but a backend still scraping the star page keeps streaming those
 * lines - and with no branch to catch them they fell through to the base-line parser, which read a
 * line carrying no counters as a legitimate result and overwrote an already-resolved player with
 * FKDR 0.00. In game that looked like the FKDR appearing correctly and then flipping to 0.00.
 *
 * <p>The guard keys off {@code state}, which every base line carries and no follow-up line does, so
 * it also covers follow-up kinds added to the backend later.
 */
public class FollowUpLineTest {

    private static final String BASE_LINE =
            "{\"name\":\"Notch\",\"success\":true,\"state\":\"OK\",\"displayName\":\"Notch\","
                    + "\"overall\":{\"finalKills\":1000,\"finalDeaths\":250,\"kills\":400,"
                    + "\"deaths\":200,\"wins\":100,\"losses\":50}}";

    /** Exactly what a backend that still scrapes the star page streams after the counters. */
    private static final String LEGACY_STAR_UPDATE =
            "{\"name\":\"Notch\",\"success\":true,\"starUpdate\":true,\"bedwarsLevel\":312}";

    /** A follow-up kind this client has never heard of; must be ignored just as safely. */
    private static final String UNKNOWN_FOLLOW_UP =
            "{\"name\":\"Notch\",\"success\":true,\"somethingNewUpdate\":true,\"value\":7}";

    @Test
    public void legacyStarUpdateDoesNotOverwriteResolvedStats() throws Exception {
        List<BedwarsStats> got = stream(BASE_LINE, LEGACY_STAR_UPDATE);
        assertEquals("follow-up line must not produce a second result", 1, got.size());
        assertEquals(4.0, got.get(0).statsFor(BedwarsMode.UNKNOWN).fkdr, 0.001);
    }

    @Test
    public void unknownFollowUpKindIsIgnoredToo() throws Exception {
        List<BedwarsStats> got = stream(BASE_LINE, UNKNOWN_FOLLOW_UP);
        assertEquals(1, got.size());
        assertEquals(4.0, got.get(0).statsFor(BedwarsMode.UNKNOWN).fkdr, 0.001);
    }

    @Test
    public void aFollowUpArrivingWithNoBaseLineResolvesNothing() throws Exception {
        // The player stays unresolved rather than being cached as a bogus FKDR 0.00.
        assertEquals(0, stream(LEGACY_STAR_UPDATE).size());
    }

    /** Serves the given NDJSON lines once and returns every stats result the client accepted. */
    private static List<BedwarsStats> stream(String... lines) throws Exception {
        final byte[] body = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bedwars/batch", ex -> {
            ex.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            final List<BedwarsStats> out = new ArrayList<BedwarsStats>();
            ScraperBackendClient.fetchBatchStreaming(
                    Arrays.asList("Notch"),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "",
                    (name, stats) -> out.add(stats));
            return out;
        } finally {
            server.stop(0);
        }
    }
}
