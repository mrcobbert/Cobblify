package com.bedwarsqol.stats;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the shared Urchin tag policy: type→icon/color mapping (incl. possible_sniper and the unknown
 * fallback), sanitization, the time-injectable active-tag filter with its {@code expiresAtMs <= now}
 * boundary, and severity-driven priority selection.
 */
public class UrchinTagTest {

    private static UrchinTag tag(String type) {
        return new UrchinTag(type, "reason", 0L, null);
    }

    @Test
    public void mapsKnownTypes() {
        assertEquals("CCC", tag("confirmed_cheater").displayIcon());
        assertEquals("§5", tag("confirmed_cheater").color());
        assertEquals("BC", tag("blatant_cheater").displayIcon());
        assertEquals("CC", tag("closet_cheater").displayIcon());
        assertEquals("S", tag("sniper").displayIcon());
        assertEquals("LS", tag("legit_sniper").displayIcon());
        assertEquals("PS", tag("possible_sniper").displayIcon());
        assertEquals("C", tag("caution").displayIcon());
    }

    @Test
    public void unknownTypeFallsBackToGenericBadge() {
        UrchinTag t = tag("some_future_type");
        assertEquals("?", t.displayIcon());
        assertEquals("§7", t.color());
        assertEquals("some_future_type", t.displayName());
        assertTrue(t.isDisplayable());
    }

    @Test
    public void infoAndAccountAreNonDisplayable() {
        assertNull(tag("info").displayIcon());
        assertNull(tag("account").displayIcon());
        assertFalse(tag("info").isDisplayable());
    }

    @Test
    public void cheaterTypeGating() {
        assertTrue(tag("confirmed_cheater").isCheaterType());
        assertTrue(tag("blatant_cheater").isCheaterType());
        assertTrue(tag("closet_cheater").isCheaterType());
        assertFalse(tag("sniper").isCheaterType());
        assertFalse(tag("caution").isCheaterType());
    }

    @Test
    public void nonCheaterTagsAreStillDisplayable() {
        // Badges render for ANY displayable tag; *chat* is cheater-only (see UrchinAlertFormatTest).
        assertTrue(tag("sniper").isDisplayable());
        assertTrue(tag("legit_sniper").isDisplayable());
        assertTrue(tag("possible_sniper").isDisplayable());
        assertTrue(tag("caution").isDisplayable());
        assertTrue(tag("some_future_type").isDisplayable());
        assertFalse(tag("sniper").isCheaterType());
        assertFalse(tag("caution").isCheaterType());
    }

    @Test
    public void badgeTokenBracketsIconInTagColor() {
        assertEquals(" §8[§5CCC§8]", tag("confirmed_cheater").badgeToken());
        assertEquals(" §8[§4S§8]", tag("sniper").badgeToken());
        assertEquals("", tag("info").badgeToken()); // non-displayable -> empty
    }

    @Test
    public void sanitizeStripsFormatAndCaps() {
        assertEquals("hello world", UrchinTag.sanitize("§chello   §lworld"));
        assertEquals("a b", UrchinTag.sanitize("a\tb"));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) big.append('x');
        assertEquals(120, UrchinTag.sanitize(big.toString()).length());
    }

    @Test
    public void sanitizeStripsControlFormatAndBidi() {
        // DEL + C1 control between the words -> space, then collapsed.
        assertEquals("a b", UrchinTag.sanitize("a\u007f\u0085b"));
        // zero-width space / word joiner / BOM -> spaces that collapse to a single separator.
        assertEquals("a b", UrchinTag.sanitize("a\u200b \u2060\ufeffb"));
        // bidi override (RLO) + pop-directional-formatting are stripped, never rendered.
        assertEquals("hello world", UrchinTag.sanitize("hello \u202eworld\u202c"));
        // bidi isolates (LRI/PDI).
        assertEquals("x y", UrchinTag.sanitize("x \u2066\u2069y"));
    }

    @Test
    public void activeTagsFiltersExpiredAtBoundary() {
        long now = 1000L;
        UrchinTag expiredNow = new UrchinTag("sniper", "r", 0L, 1000L);   // expiresAt == now -> inactive
        UrchinTag stillActive = new UrchinTag("sniper", "r", 0L, 1001L);  // expiresAt > now -> active
        UrchinTag never = new UrchinTag("sniper", "r", 0L, null);
        List<UrchinTag> tags = new ArrayList<UrchinTag>(Arrays.asList(expiredNow, stillActive, never));
        List<UrchinTag> active = UrchinTag.activeTags(tags, now);
        assertEquals(2, active.size());
        assertFalse(active.contains(expiredNow));
    }

    @Test
    public void allExpiredIsEmpty() {
        long now = 5000L;
        List<UrchinTag> tags = Arrays.asList(new UrchinTag("sniper", "r", 0L, 100L));
        assertTrue(UrchinTag.activeTags(tags, now).isEmpty());
        assertNull(UrchinTag.priority(tags, now));
    }

    @Test
    public void prioritySelectsHighestSeverity() {
        List<UrchinTag> tags = Arrays.asList(tag("caution"), tag("sniper"), tag("confirmed_cheater"));
        assertEquals("confirmed_cheater", UrchinTag.priority(tags, 0L).type);
        List<UrchinTag> two = Arrays.asList(tag("possible_sniper"), tag("legit_sniper"));
        assertEquals("legit_sniper", UrchinTag.priority(two, 0L).type);
    }
    @Test
    public void sanitizeStripsAlmAndDeprecatedBidiControls() {
        // Characters OUTSIDE the previously hand-picked ranges: U+061C ARABIC LETTER MARK
        // and deprecated bidi control U+206A are Cf and must not survive.
        String s = UrchinTag.sanitize("a\u061cb\u206ac");
        assertEquals("a b c", s);
    }

}
