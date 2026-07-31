package com.bedwarsqol.stats;

import com.bedwarsqol.stats.BedwarsStats.ModeStats;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * The optional {@code bedwarsLevel} wire field (sent only on fallback-served bodies) and the star
 * it renders. The load-bearing contract is absence: a body without the field must build stats that
 * render BYTE-IDENTICALLY to before the field existed, because the hypixel path never sends it.
 */
public class StarFieldTest {

    /** finalKills 1000 / finalDeaths 250 -> FKDR 4.00 (yellow); wins 100 / losses 50 -> WLR 2.00. */
    private static final ModeStats OVERALL = new ModeStats(1000, 250, 100, 50, 400, 200);

    private static BedwarsStats ok() {
        return BedwarsStats.ok("Notch", "§b[MVP§c+§b]", "mvp_plus",
                OVERALL, ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY, ModeStats.EMPTY);
    }

    // ---- absent star = today's exact output (pinned strings) -----------------

    @Test
    public void absentStarRendersByteIdenticalToToday() {
        BedwarsStats s = ok();
        assertEquals("§eFKDR 4.00§r", s.formatForNametag(BedwarsMode.UNKNOWN, false));
        assertEquals("§b[MVP§c+§b]§r §eFKDR 4.00§r", s.formatForNametag(BedwarsMode.UNKNOWN, true));
        assertEquals("§7FKDR: §e4.00 §7WLR: §f2.00", s.formatForTab(BedwarsMode.UNKNOWN, false));
        assertEquals("§6§lBedWars", s.formatForHoverCard(BedwarsMode.UNKNOWN, false).get(0));
        assertEquals("§6§lBedWars §r§b[MVP§c+§b]", s.formatForHoverCard(BedwarsMode.UNKNOWN, true).get(0));
    }

    // ---- star rendering ------------------------------------------------------

    @Test
    public void starRendersBeforeTheRankInEveryPrefixPath() {
        BedwarsStats s = ok().withLevel(29);
        assertEquals("§7[29✫]§r§r §eFKDR 4.00§r", s.formatForNametag(BedwarsMode.UNKNOWN, false));
        assertEquals("§7[29✫]§r§r §b[MVP§c+§b]§r §eFKDR 4.00§r",
                s.formatForNametag(BedwarsMode.UNKNOWN, true));
        assertEquals("§7[29✫]§r§r §7FKDR: §e4.00 §7WLR: §f2.00",
                s.formatForTab(BedwarsMode.UNKNOWN, false));
        assertEquals("§6§lBedWars §r§7[29✫]§r", s.formatForHoverCard(BedwarsMode.UNKNOWN, false).get(0));
        assertEquals("§6§lBedWars §r§7[29✫]§r §r§b[MVP§c+§b]",
                s.formatForHoverCard(BedwarsMode.UNKNOWN, true).get(0));
    }

    @Test
    public void starColorFollowsThePrestigeTier() {
        assertEquals("§7[29✫]§r", BedwarsStats.starTag(29));    // Stone
        assertEquals("§f[129✫]§r", BedwarsStats.starTag(129));  // Iron
        assertEquals("§5[999✫]§r", BedwarsStats.starTag(999));  // Amethyst
        assertEquals("§c[1000✫]§r", BedwarsStats.starTag(1000)); // Rainbow stand-in
    }

    // ---- withLevel rules -----------------------------------------------------

    @Test
    public void withLevelIsANoOpForNonOkNonPositiveAndUnchanged() {
        BedwarsStats nicked = BedwarsStats.nicked();
        assertSame(nicked, nicked.withLevel(29));
        BedwarsStats error = BedwarsStats.error();
        assertSame(error, error.withLevel(29));
        BedwarsStats s = ok();
        assertSame(s, s.withLevel(0));
        assertSame(s, s.withLevel(-5));
        BedwarsStats starred = s.withLevel(29);
        assertSame(starred, starred.withLevel(29));
        assertEquals(29, starred.bedwarsLevel);
        assertEquals(0, s.bedwarsLevel);
    }

    @Test
    public void starSurvivesProviderMerges() {
        BedwarsStats s = ok().withLevel(29)
                .withUrchinTags(Collections.<UrchinTag>emptyList())
                .withSeraph(Collections.<SeraphTag>emptyList(), 2, 7);
        assertEquals(29, s.bedwarsLevel);
        assertEquals(4.0, s.fkdr, 0.001);
    }

    // ---- wire parse ----------------------------------------------------------

    private static final String LINE_WITH_STAR =
            "{\"name\":\"Notch\",\"success\":true,\"state\":\"OK\",\"displayName\":\"Notch\","
                    + "\"rank\":null,\"bedwarsLevel\":29,"
                    + "\"overall\":{\"finalKills\":1000,\"finalDeaths\":250,\"kills\":400,"
                    + "\"deaths\":200,\"wins\":100,\"losses\":50}}";

    private static final String LINE_WITHOUT_STAR =
            "{\"name\":\"Steve\",\"success\":true,\"state\":\"OK\",\"displayName\":\"Steve\","
                    + "\"overall\":{\"finalKills\":1000,\"finalDeaths\":250,\"kills\":400,"
                    + "\"deaths\":200,\"wins\":100,\"losses\":50}}";

    @Test
    public void batchLineWithBedwarsLevelParsesIntoTheStarField() throws Exception {
        List<BedwarsStats> got = stream(Arrays.asList("Notch", "Steve"), LINE_WITH_STAR, LINE_WITHOUT_STAR);
        assertEquals(2, got.size());
        assertEquals(29, got.get(0).bedwarsLevel);
        assertEquals("§7[29✫]§r§r §eFKDR 4.00§r", got.get(0).formatForNametag(BedwarsMode.UNKNOWN, false));
        // Absent field -> exactly today's object state and render.
        assertEquals(0, got.get(1).bedwarsLevel);
        assertEquals("§eFKDR 4.00§r", got.get(1).formatForNametag(BedwarsMode.UNKNOWN, false));
    }

    /** Serves the given NDJSON lines once and returns every stats result the client accepted. */
    private static List<BedwarsStats> stream(List<String> names, String... lines) throws Exception {
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
                    names,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "",
                    (name, stats) -> out.add(stats));
            return out;
        } finally {
            server.stop(0);
        }
    }
}
