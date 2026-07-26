package com.bedwarsqol.stats;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SeraphTag policy/format authority: kind mapping, severity ordering (a real accusation always wins
 * the badge over a positive safelist signal), cheater-type gating, displayability, and the shared
 * sanitize policy. Pure — no Minecraft, no network.
 */
public class SeraphTagTest {

    private static SeraphTag tag(String kind) {
        return new SeraphTag(kind, "", "", 0L, false);
    }

    @Test
    public void kindMapsToIconColorAndDisplayable() {
        assertEquals("BL", tag("blacklist").displayIcon());
        assertEquals("SL", tag("safelist").displayIcon());
        assertEquals("BOT", tag("bot").displayIcon());
        assertEquals("AL", tag("annoy").displayIcon());
        assertNull(tag("mystery").displayIcon());          // unknown kind not rendered
        assertFalse(tag("mystery").isDisplayable());
        assertTrue(tag("blacklist").isDisplayable());
    }

    @Test
    public void verifiedBlacklistIsDarkerAndHigherSeverity() {
        SeraphTag plain = new SeraphTag("blacklist", "cheater", "r", 0L, false);
        SeraphTag verified = new SeraphTag("blacklist", "cheater", "r", 0L, true);
        assertEquals("§c", plain.color());
        assertEquals("§4", verified.color());
        assertTrue(verified.severity() > plain.severity());
    }

    @Test
    public void onlyBlacklistIsCheaterType() {
        assertTrue(tag("blacklist").isCheaterType()); // gates the alert sound
        assertFalse(tag("safelist").isCheaterType());
        assertFalse(tag("bot").isCheaterType());
        assertFalse(tag("annoy").isCheaterType());
    }

    @Test
    public void priorityPrefersAccusationOverSafelist() {
        // A player both blacklisted AND safelisted: the badge must show the accusation, never the
        // reassuring green — safelist is the lowest severity by design.
        List<SeraphTag> tags = Arrays.asList(tag("safelist"), tag("blacklist"), tag("annoy"));
        SeraphTag best = SeraphTag.priority(tags);
        assertEquals("blacklist", best.kind);
    }

    @Test
    public void activeTagsDropsUnknownKindsAndNulls() {
        List<SeraphTag> tags = Arrays.asList(tag("blacklist"), tag("mystery"), null, tag("safelist"));
        List<SeraphTag> active = SeraphTag.activeTags(tags);
        assertEquals(2, active.size());
        assertEquals("blacklist", active.get(0).kind);
        assertEquals("safelist", active.get(1).kind);
        assertEquals(Collections.emptyList(), SeraphTag.activeTags(null));
    }

    @Test
    public void badgeTokenBracketsIconAndFusionForcesRed() {
        SeraphTag bl = tag("blacklist");
        assertEquals(" §8[§cBL§8]", bl.badgeToken(false));
        assertEquals(" §8[§c§lBL§8]", bl.badgeToken(true)); // fusion = red bold
        assertEquals("", tag("mystery").badgeToken(false)); // non-displayable -> empty
    }

    @Test
    public void sanitizeStripsSectionAndControlCharsViaSharedPolicy() {
        // Delegates to UrchinTag.sanitize — untrusted reasons/types can never carry section codes.
        SeraphTag t = new SeraphTag("BLACKLIST", "CHEATER", "§4§lautoclicker", 0L, true);
        assertEquals("blacklist", t.kind);   // lowercased + sanitized
        assertEquals("cheater", t.subtype);
        assertFalse(t.reason.contains("§"));
        assertEquals("autoclicker", t.reason);
    }
}
