package com.bedwarsqol.stats;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A1-A7: the single decision point every overlay row is built from. */
public class PlayerCardTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final UUID U = UUID.fromString("11111111-2222-4333-8444-555555555555");

    /** Map-backed source recording name fetches. */
    static final class Stub implements PlayerCard.Source {
        final Map<UUID, BedwarsStats> byUuid = new HashMap<UUID, BedwarsStats>();
        final Map<String, BedwarsStats> byName = new HashMap<String, BedwarsStats>();
        final List<String> fetched = new ArrayList<String>();

        public BedwarsStats byUuid(UUID uuid) { return byUuid.get(uuid); }
        public BedwarsStats byName(String name) { return byName.get(name); }
        public void fetchByName(String name) { fetched.add(name); }
    }

    static BedwarsStats.ModeStats mode(int fk, int fd) {
        return new BedwarsStats.ModeStats(fk, fd, 10, 5, 100, 50);
    }

    static BedwarsStats ok(double fkdr) {
        int fd = 100;
        return BedwarsStats.ok("P", "§b[MVP§c+§b]", "mvp_plus",
                mode((int) Math.round(fkdr * fd), fd), null, null, null, null);
    }

    static UrchinTag urchin(String type) {
        return new UrchinTag(type, "r", NOW - 1000, null);
    }

    static SeraphTag seraph(String kind, boolean verified) {
        return new SeraphTag(kind, "", "r", NOW - 1000, verified);
    }

    static PlayerCard card(Stub s, BedwarsStats st) {
        s.byUuid.put(U, st);
        return PlayerCard.build(s, "P", U, BedwarsMode.UNKNOWN, PlayerCard.Toggles.ALL_ON,
                true, true, null, NOW);
    }

    // ---- A1 -----------------------------------------------------------------

    @Test
    public void cheaterFollowsIsCheaterType() {
        String[] urchinTypes = {"confirmed_cheater", "blatant_cheater", "closet_cheater", "sniper",
                "legit_sniper", "possible_sniper", "caution", "info", "account", "future_type"};
        for (String type : urchinTypes) {
            PlayerCard c = card(new Stub(), ok(1).withUrchinTags(Arrays.asList(urchin(type))));
            boolean expected = type.equals("confirmed_cheater") || type.equals("blatant_cheater")
                    || type.equals("closet_cheater");
            assertEquals(type, expected, c.cheater);
            assertEquals(type, urchin(type).isCheaterType(), c.cheater);
        }
        String[] seraphKinds = {"blacklist", "safelist", "bot", "annoy", "future_kind"};
        for (String kind : seraphKinds) {
            PlayerCard c = card(new Stub(), ok(1).withSeraph(Arrays.asList(seraph(kind, false)), 4, 9));
            assertEquals(kind, kind.equals("blacklist"), c.cheater);
            assertEquals(kind, seraph(kind, false).isCheaterType(), c.cheater);
        }
        // Threat is carried, never acted on.
        PlayerCard threat = card(new Stub(), ok(1).withSeraph(Collections.<SeraphTag>emptyList(), 5, 9));
        assertFalse(threat.cheater);
        assertEquals(5, threat.seraphThreat);
    }

    // ---- A2 -----------------------------------------------------------------

    @Test
    public void fkdrTierAndColourShareOneTable() {
        double[] values = {0, 1.99, 2, 4.99, 5, 9.99, 10, 25};
        int[] tiers = {0, 0, 1, 1, 2, 2, 3, 3};
        String[] colours = {"§f", "§f", "§e", "§e", "§6", "§6", "§c", "§c"};
        for (int i = 0; i < values.length; i++) {
            assertEquals("tier " + values[i], tiers[i], BedwarsStats.fkdrTier(values[i]));
            assertEquals("colour " + values[i], colours[i], BedwarsStats.fkdrColor(values[i]));
            assertEquals("card " + values[i], tiers[i], card(new Stub(), ok(values[i])).fkdrTier);
        }
    }

    // ---- A3 -----------------------------------------------------------------

    @Test
    public void numbersFollowDisplayMode() {
        BedwarsStats withFours = BedwarsStats.ok("P", "", "", mode(300, 100), null, null, null, mode(80, 10));
        Stub s = new Stub();
        s.byUuid.put(U, withFours);
        PlayerCard fours = PlayerCard.build(s, "P", U, BedwarsMode.FOURS, PlayerCard.Toggles.ALL_ON,
                true, true, null, NOW);
        assertEquals(8.0, fours.fkdr, 0.0);
        assertEquals("4s", fours.modeLabel);
        assertEquals(2, fours.fkdrTier);

        BedwarsStats noFours = BedwarsStats.ok("P", "", "", mode(300, 100), null, null, null, null);
        s.byUuid.put(U, noFours);
        PlayerCard fallback = PlayerCard.build(s, "P", U, BedwarsMode.FOURS, PlayerCard.Toggles.ALL_ON,
                true, true, null, NOW);
        assertEquals(3.0, fallback.fkdr, 0.0);
        assertEquals(PlayerCard.OVERALL, fallback.modeLabel);
    }

    // ---- A4 -----------------------------------------------------------------

    @Test
    public void gatesAndTogglesFailClosed() {
        BedwarsStats tagged = ok(3)
                .withUrchinTags(Arrays.asList(urchin("blatant_cheater")))
                .withSeraph(Arrays.asList(seraph("blacklist", true)), 4, 2);

        // Expired Urchin tag: inactive, never shown.
        BedwarsStats expired = ok(3).withUrchinTags(Arrays.asList(
                new UrchinTag("blatant_cheater", "r", NOW - 5000, Long.valueOf(NOW - 1))));
        assertClosed(card(new Stub(), expired), true);

        // Non-displayable Urchin tags and unknown Seraph kinds never reach a surface.
        BedwarsStats hidden = ok(3)
                .withUrchinTags(Arrays.asList(urchin("info"), urchin("account")))
                .withSeraph(Arrays.asList(seraph("future_kind", false)), -1, -1);
        assertClosed(card(new Stub(), hidden), true);

        // Eligibility false for either provider.
        Stub s = new Stub();
        s.byUuid.put(U, tagged);
        assertClosed(PlayerCard.build(s, "P", U, BedwarsMode.UNKNOWN, PlayerCard.Toggles.ALL_ON,
                false, false, null, NOW), false);

        // Master toggles off.
        assertClosed(PlayerCard.build(s, "P", U, BedwarsMode.UNKNOWN,
                new PlayerCard.Toggles(false, false, true, true), true, true, null, NOW), false);

        // One provider gated: only the other's signal survives.
        PlayerCard urchinOnly = PlayerCard.build(s, "P", U, BedwarsMode.UNKNOWN,
                new PlayerCard.Toggles(true, false, true, true), true, true, null, NOW);
        assertEquals(1, urchinOnly.chips.size());
        assertEquals("BC", urchinOnly.badge.code);
        assertEquals(-1, urchinOnly.seraphThreat);
        assertTrue(urchinOnly.cheater);

        // Stats themselves are unaffected by gating.
        assertEquals(3.0, urchinOnly.fkdr, 0.0);
    }

    private static void assertClosed(PlayerCard c, boolean threatAllowed) {
        assertTrue(c.chips.isEmpty());
        assertNull(c.badge);
        assertFalse(c.cheater);
        if (!threatAllowed) assertEquals(-1, c.seraphThreat);
    }

    // ---- A5 -----------------------------------------------------------------

    @Test
    public void safelistIsPositiveAndBadgeRanking() {
        PlayerCard safe = card(new Stub(), ok(1).withSeraph(Arrays.asList(seraph("safelist", false)), -1, -1));
        assertEquals(1, safe.chips.size());
        assertTrue(safe.chips.get(0).positive);
        assertEquals("§a", safe.chips.get(0).color);
        assertEquals("SL", safe.badge.code);
        assertTrue(safe.badge.positive);
        assertFalse(safe.cheater);

        PlayerCard verified = card(new Stub(), ok(1).withSeraph(Arrays.asList(seraph("blacklist", true)), -1, -1));
        assertFalse(verified.chips.get(0).positive);
        assertEquals("§4", verified.chips.get(0).color);
        assertEquals("Blacklisted (verified)", verified.chips.get(0).label);
        assertTrue(verified.cheater);

        // Danger outranks positive: Urchin sniper beats Seraph safelist.
        PlayerCard dangerFirst = card(new Stub(), ok(1)
                .withUrchinTags(Arrays.asList(urchin("sniper")))
                .withSeraph(Arrays.asList(seraph("safelist", false)), -1, -1));
        assertEquals("S", dangerFirst.badge.code);
        assertFalse(dangerFirst.badge.positive);

        // Within the danger band the higher severity wins: bot (3) over caution (1).
        PlayerCard bySeverity = card(new Stub(), ok(1)
                .withUrchinTags(Arrays.asList(urchin("caution")))
                .withSeraph(Arrays.asList(seraph("bot", false)), -1, -1));
        assertEquals("BOT", bySeverity.badge.code);

        // Ties favour Seraph: possible_sniper (2) vs annoy (2).
        PlayerCard tie = card(new Stub(), ok(1)
                .withUrchinTags(Arrays.asList(urchin("possible_sniper")))
                .withSeraph(Arrays.asList(seraph("annoy", false)), -1, -1));
        assertEquals("AL", tie.badge.code);

        // The Players page chip is the same decision.
        assertEquals("AL", com.bedwarsqol.gui.PlayersFormat.chipFor(
                Arrays.asList(urchin("possible_sniper")), NOW, Arrays.asList(seraph("annoy", false))).code);
    }

    // ---- A6 -----------------------------------------------------------------

    @Test
    public void denickUsesRealStatsOnlyWhenBothTogglesOn() {
        Stub s = new Stub();
        s.byUuid.put(U, BedwarsStats.nicked());
        s.byName.put("RealGuy", ok(7));

        PlayerCard revealed = PlayerCard.build(s, "Nick", U, BedwarsMode.UNKNOWN, PlayerCard.Toggles.ALL_ON,
                true, true, "RealGuy", NOW);
        assertEquals(BedwarsStats.State.OK, revealed.state);
        assertEquals("OK", revealed.stateName());
        assertTrue(revealed.nicked);
        assertEquals("RealGuy", revealed.realName);
        assertEquals(7.0, revealed.fkdr, 0.0);
        assertEquals("[MVP+]", revealed.rank);
        assertEquals("§b[MVP§c+§b]", revealed.rankCodes);
        assertTrue(s.fetched.isEmpty());

        for (PlayerCard.Toggles t : new PlayerCard.Toggles[]{
                new PlayerCard.Toggles(true, true, false, true),
                new PlayerCard.Toggles(true, true, true, false)}) {
            PlayerCard hidden = PlayerCard.build(s, "Nick", U, BedwarsMode.UNKNOWN, t, true, true, "RealGuy", NOW);
            assertEquals("NICKED", hidden.stateName());
            assertTrue(hidden.nicked);
            assertNull(hidden.realName);
            assertEquals(0.0, hidden.fkdr, 0.0);
        }

        // Real name not cached yet: loading card that already carries the reveal, and one fetch.
        Stub cold = new Stub();
        cold.byUuid.put(U, BedwarsStats.nicked());
        PlayerCard loading = PlayerCard.build(cold, "Nick", U, BedwarsMode.UNKNOWN, PlayerCard.Toggles.ALL_ON,
                true, true, "RealGuy", NOW);
        assertEquals("LOADING", loading.stateName());
        assertTrue(loading.nicked);
        assertEquals("RealGuy", loading.realName);
        assertEquals(Arrays.asList("RealGuy"), cold.fetched);

        // No verified name: NICKED state alone marks the row nicked, never a reveal.
        PlayerCard plain = PlayerCard.build(cold, "Nick", U, BedwarsMode.UNKNOWN, PlayerCard.Toggles.ALL_ON,
                true, true, null, NOW);
        assertEquals("NICKED", plain.stateName());
        assertTrue(plain.nicked);
        assertNull(plain.realName);
    }

    // ---- A7 -----------------------------------------------------------------

    /** The tab list concatenates formatForTab + priorityUrchinTag().badgeToken() + prioritySeraphTag().badgeToken(). */
    @Test
    public void goldenAgreementWithTabPolicy() {
        BedwarsStats fixture = ok(6.4)
                .withUrchinTags(Arrays.asList(urchin("sniper"), urchin("caution")))
                .withSeraph(Arrays.asList(seraph("safelist", false)), -1, -1);

        String tab = fixture.formatForTab(BedwarsMode.UNKNOWN, true);
        assertNotNull(tab);
        assertTrue(tab, tab.contains("§7FKDR: §66.40"));
        assertEquals(" §8[§4S§8]", fixture.priorityUrchinTag(NOW).badgeToken());
        assertEquals(" §8[§aSL§8]", fixture.prioritySeraphTag().badgeToken());

        PlayerCard c = card(new Stub(), fixture);
        assertEquals(2, c.fkdrTier);
        assertEquals("S", c.badge.code);
        assertEquals("§4", c.badge.color);
        assertFalse(c.badge.positive);
        assertEquals(3, c.chips.size());
        assertChip(c.chips.get(0), "S", "§4", "Sniper", false);
        assertChip(c.chips.get(1), "C", "§6", "Caution", false);
        assertChip(c.chips.get(2), "SL", "§a", "Safelisted", true);
        assertFalse(c.cheater);
        assertEquals(6.4, c.fkdr, 0.0);
    }

    private static void assertChip(PlayerCard.Chip chip, String code, String color, String label, boolean positive) {
        assertEquals(code, chip.code);
        assertEquals(color, chip.color);
        assertEquals(label, chip.label);
        assertEquals(positive, chip.positive);
    }

    // ---- misc ---------------------------------------------------------------

    @Test
    public void loadingAndSpecialStates() {
        Stub s = new Stub();
        PlayerCard none = PlayerCard.build(s, "P", U, BedwarsMode.UNKNOWN, PlayerCard.Toggles.ALL_ON, true, true, null, NOW);
        assertEquals("LOADING", none.stateName());
        assertNull(none.state);
        assertTrue(none.chips.isEmpty());

        s.byUuid.put(U, BedwarsStats.neverPlayed("P"));
        assertEquals("NEVER_PLAYED", card(s, BedwarsStats.neverPlayed("P")).stateName());
        assertEquals("ERROR", card(s, BedwarsStats.error()).stateName());

        // Name-keyed fallback when the uuid has no entry.
        Stub byName = new Stub();
        byName.byName.put("P", ok(2));
        PlayerCard viaName = PlayerCard.build(byName, "P", U, BedwarsMode.UNKNOWN, PlayerCard.Toggles.ALL_ON, true, true, null, NOW);
        assertEquals("OK", viaName.stateName());
        assertEquals(1, viaName.fkdrTier);

        // A null source never throws.
        assertEquals("LOADING", PlayerCard.build(null, "P", null, null, null, false, false, null, NOW).stateName());
    }
}
