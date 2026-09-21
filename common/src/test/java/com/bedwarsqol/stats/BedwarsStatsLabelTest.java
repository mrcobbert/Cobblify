package com.bedwarsqol.stats;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the forced-mode label rule shared by every stats surface: with {@code labelMode} the mode
 * the numbers came from is stamped ({@code 4s …}), a fallback to overall is stamped {@code All …},
 * and in auto mode nothing changes. Also pins the chat placeholder's width contract and that a
 * backend body without a {@code modes} block renders as {@code All} rather than claiming a mode.
 */
public class BedwarsStatsLabelTest {

    private static final BedwarsStats.ModeStats OVERALL = new BedwarsStats.ModeStats(210, 100, 10, 5, 100, 50); // 2.10
    private static final BedwarsStats.ModeStats FOURS = new BedwarsStats.ModeStats(445, 100, 10, 5, 100, 50);   // 4.45

    /** Has 4s games (4.45) and overall 2.10. */
    private static final BedwarsStats WITH_FOURS = BedwarsStats.ok("P", "", "", OVERALL, null, null, null, FOURS);
    /** Overall only (2.10): every specific mode falls back. */
    private static final BedwarsStats OVERALL_ONLY = BedwarsStats.ok("P", "", "", OVERALL, null, null, null, null);

    // ---- chat bracket ------------------------------------------------------

    @Test
    public void forcedModeWithGamesStampsTheModeOnTheChatBracket() {
        assertEquals("§7[4s §e4.45§7]§r ", WITH_FOURS.chatBracket(BedwarsMode.FOURS, true));
    }

    @Test
    public void forcedModeWithoutGamesStampsAllAndShowsOverall() {
        assertEquals("§7[All §e2.10§7]§r ", OVERALL_ONLY.chatBracket(BedwarsMode.FOURS, true));
        assertEquals("§7[All §e2.10§7]§r ", WITH_FOURS.chatBracket(BedwarsMode.SOLO, true));
        // Forcing "all" itself is labelled All too — it is a forced choice, so it is stamped.
        assertEquals("§7[All §e2.10§7]§r ", WITH_FOURS.chatBracket(BedwarsMode.UNKNOWN, true));
    }

    @Test
    public void autoModeChatBracketIsUnlabelledExactlyAsBefore() {
        assertEquals("§7[§e4.45§7]§r ", WITH_FOURS.chatBracket(BedwarsMode.FOURS, false));
        assertEquals("§7[§e2.10§7]§r ", OVERALL_ONLY.chatBracket(BedwarsMode.FOURS, false));
        assertEquals("§7[§e2.10§7]§r ", WITH_FOURS.chatBracket(BedwarsMode.UNKNOWN, false));
    }

    @Test
    public void nonOkStatesRenderNewOrNothing() {
        assertEquals("§7[New]§r ", BedwarsStats.neverPlayed("P").chatBracket(BedwarsMode.FOURS, true));
        assertEquals("§7[New]§r ", BedwarsStats.neverPlayed("P").chatBracket(BedwarsMode.FOURS, false));
        assertEquals("", BedwarsStats.error().chatBracket(BedwarsMode.FOURS, true));
        assertEquals("", BedwarsStats.nicked().chatBracket(BedwarsMode.FOURS, true));
    }

    @Test
    public void pendingBracketSharesItsPrefixWithTheResolvedForcedBracket() {
        assertEquals("§7[-.--]§r ", BedwarsStats.pendingChatBracket(BedwarsMode.FOURS, false));
        assertEquals("§7[4s -.--]§r ", BedwarsStats.pendingChatBracket(BedwarsMode.FOURS, true));
        assertEquals("§7[All -.--]§r ", BedwarsStats.pendingChatBracket(BedwarsMode.UNKNOWN, true));
        // Font-independent: for every forced mode, the placeholder and the resolved bracket for a
        // player who has games there start with the identical "[<label> " prefix, so resolving never
        // moves the line in the common case.
        for (BedwarsMode m : BedwarsMode.values()) {
            BedwarsStats.ModeStats own = new BedwarsStats.ModeStats(445, 100, 10, 5, 100, 50);
            BedwarsStats st = BedwarsStats.ok("P", "", "", OVERALL,
                    m == BedwarsMode.SOLO ? own : null, m == BedwarsMode.DOUBLES ? own : null,
                    m == BedwarsMode.THREES ? own : null, m == BedwarsMode.FOURS ? own : null);
            String prefix = "§7[" + m.shortLabel() + " ";
            assertTrue(m.name(), BedwarsStats.pendingChatBracket(m, true).startsWith(prefix));
            assertTrue(m.name(), st.chatBracket(m, true).startsWith(prefix));
        }
    }

    /**
     * Vanilla 1.8.9 glyph advance widths (glyph + 1px spacing; space = 4) for every character the
     * brackets use, measured from the client jar's {@code assets/minecraft/textures/font/ascii.png}
     * with {@code FontRenderer}'s rule (rightmost opaque column + 1). Custom fonts (Lunar) differ and
     * are outside what this test can pin.
     */
    private static final Map<Character, Integer> VANILLA_WIDTH = new HashMap<Character, Integer>();
    static {
        for (char c = '0'; c <= '9'; c++) VANILLA_WIDTH.put(c, 6);
        VANILLA_WIDTH.put('[', 4); VANILLA_WIDTH.put(']', 4); VANILLA_WIDTH.put(' ', 4);
        VANILLA_WIDTH.put('-', 6); VANILLA_WIDTH.put('.', 2);
        VANILLA_WIDTH.put('s', 6); VANILLA_WIDTH.put('A', 6); VANILLA_WIDTH.put('l', 3);
        VANILLA_WIDTH.put('S', 6); VANILLA_WIDTH.put('o', 6);
        VANILLA_WIDTH.put('N', 6); VANILLA_WIDTH.put('e', 6); VANILLA_WIDTH.put('w', 6);
    }

    private static int vanillaWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') { i++; continue; }
            Integer cw = VANILLA_WIDTH.get(c);
            if (cw == null) throw new AssertionError("no width for '" + c + "' in " + s);
            w += cw;
        }
        return w;
    }

    @Test
    public void fallbackToAllIsWidthIdenticalForTeamModesInTheVanillaFont() {
        String fallback = OVERALL_ONLY.chatBracket(BedwarsMode.FOURS, true); // [All 2.10]
        for (BedwarsMode m : Arrays.asList(BedwarsMode.DOUBLES, BedwarsMode.THREES, BedwarsMode.FOURS)) {
            String pending = BedwarsStats.pendingChatBracket(m, true);
            assertEquals(m.name() + " pending vs fallback", vanillaWidth(pending), vanillaWidth(fallback));
        }
        assertEquals(48, vanillaWidth(fallback)); // "[All 2.10]" 44px + the trailing space
        // Solo is the one label wider than "All": a Solo-forced lookup that falls back shifts 9px,
        // the bound the plan accepts (today's "[-.--]" → "[New]" already shifts 2px).
        assertEquals(9, vanillaWidth(BedwarsStats.pendingChatBracket(BedwarsMode.SOLO, true)) - vanillaWidth(fallback));
        assertEquals(2, vanillaWidth(BedwarsStats.pendingChatBracket(BedwarsMode.FOURS, false))
                - vanillaWidth(BedwarsStats.neverPlayed("P").chatBracket(BedwarsMode.FOURS, false)));
    }

    // ---- tab / nametag / hover ----------------------------------------------

    @Test
    public void tabAndNametagStampTheModeUnderForcedModeOnly() {
        assertEquals("§74s FKDR: §e4.45 §7WLR: §f2.00", WITH_FOURS.formatForTab(BedwarsMode.FOURS, false, true));
        assertEquals("§7All FKDR: §e2.10 §7WLR: §f2.00", OVERALL_ONLY.formatForTab(BedwarsMode.FOURS, false, true));
        assertEquals("§7FKDR: §e4.45 §7WLR: §f2.00", WITH_FOURS.formatForTab(BedwarsMode.FOURS, false, false));
        assertEquals("§7FKDR: §e4.45 §7WLR: §f2.00", WITH_FOURS.formatForTab(BedwarsMode.FOURS, false));

        assertEquals("§e4s FKDR 4.45§r", WITH_FOURS.formatForNametag(BedwarsMode.FOURS, false, true));
        assertEquals("§eAll FKDR 2.10§r", OVERALL_ONLY.formatForNametag(BedwarsMode.FOURS, false, true));
        assertEquals("§eFKDR 4.45§r", WITH_FOURS.formatForNametag(BedwarsMode.FOURS, false, false));
        assertEquals("§eFKDR 4.45§r", WITH_FOURS.formatForNametag(BedwarsMode.FOURS, false));
    }

    @Test
    public void hoverHeaderNamesTheModeAndOnlyUnderForcedModeNamesAll() {
        assertEquals("§6§lBedWars §r§7(4s§7)", WITH_FOURS.formatForHoverCard(BedwarsMode.FOURS, false, true).get(0));
        assertEquals("§6§lBedWars §r§7(All§7)", OVERALL_ONLY.formatForHoverCard(BedwarsMode.FOURS, false, true).get(0));
        // Auto: a specific mode with games is still named (as before); a fallback stays bare (as before).
        assertEquals("§6§lBedWars §r§7(4s§7)", WITH_FOURS.formatForHoverCard(BedwarsMode.FOURS, false, false).get(0));
        assertEquals("§6§lBedWars", OVERALL_ONLY.formatForHoverCard(BedwarsMode.FOURS, false, false).get(0));
        assertEquals("§6§lBedWars", OVERALL_ONLY.formatForHoverCard(BedwarsMode.FOURS, false).get(0));
    }

    @Test
    public void modeLabelContractIsUnchangedForTheOverlay() {
        assertEquals("4s", WITH_FOURS.modeLabel(BedwarsMode.FOURS));
        assertNull(WITH_FOURS.modeLabel(BedwarsMode.SOLO));
        assertNull(WITH_FOURS.modeLabel(BedwarsMode.UNKNOWN)); // PlayerCard maps null → "Overall"
        assertNull(OVERALL_ONLY.modeLabel(BedwarsMode.FOURS));
    }

    // ---- a body without `modes` (fallback source, or the Worker's per-mode parse guard) -----------

    private static final String NO_MODES_LINE =
            "{\"name\":\"Steve\",\"success\":true,\"state\":\"OK\",\"displayName\":\"Steve\","
                    + "\"overall\":{\"finalKills\":210,\"finalDeaths\":100,\"kills\":100,"
                    + "\"deaths\":50,\"wins\":10,\"losses\":5},\"modesError\":\"parse_failed\"}";

    @Test
    public void bodyWithoutModesRendersAsAllNotAsTheForcedMode() throws Exception {
        List<BedwarsStats> got = stream(Arrays.asList("Steve"), NO_MODES_LINE);
        assertEquals(1, got.size());
        BedwarsStats st = got.get(0);
        assertEquals(2.10, st.statsFor(BedwarsMode.FOURS).fkdr, 0.001);
        assertEquals("§7[All §e2.10§7]§r ", st.chatBracket(BedwarsMode.FOURS, true));
        assertEquals("§7All FKDR: §e2.10 §7WLR: §f2.00", st.formatForTab(BedwarsMode.FOURS, false, true));
        assertNull(st.modeLabel(BedwarsMode.FOURS));
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
