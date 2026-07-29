package com.bedwarsqol.stats;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ScraperBackendClient#parseUrchin} maps the Worker's resolution fields
 * (urchinChecked/urchinUnavailable/urchinNotFound + urchin.tags) into a {@link UrchinResult}, and
 * returns null for a line carrying NO resolution fields (a transient miss the client may retry).
 */
public class UrchinParseTest {

    private static JsonObject obj(String json) {
        return new JsonParser().parse(json).getAsJsonObject();
    }

    @Test
    public void noResolutionFieldsReturnsNull() {
        assertNull(ScraperBackendClient.parseUrchin(obj("{\"name\":\"a\",\"success\":true}")));
    }

    @Test
    public void checkedEmptyIsResolvedNoTags() {
        UrchinResult r = ScraperBackendClient.parseUrchin(obj("{\"urchinChecked\":true}"));
        assertTrue(r.checked);
        assertTrue(r.resolved());
        assertTrue(r.tags.isEmpty());
    }

    @Test
    public void checkedWithTagsParsesExpiry() {
        UrchinResult r = ScraperBackendClient.parseUrchin(obj(
                "{\"urchinChecked\":true,\"urchin\":{\"tags\":["
                + "{\"type\":\"sniper\",\"reason\":\"snipes\",\"addedOn\":1700000000000,\"expiresAtMs\":1800000000000},"
                + "{\"type\":\"confirmed_cheater\",\"reason\":\"\",\"addedOn\":0}]}}"));
        assertTrue(r.checked);
        List<UrchinTag> tags = r.tags;
        assertEquals(2, tags.size());
        assertEquals("sniper", tags.get(0).type);
        assertEquals(Long.valueOf(1800000000000L), tags.get(0).expiresAtMs);
        assertNull(tags.get(1).expiresAtMs);
    }

    @Test
    public void malformedExpiryDropsTag() {
        // Present-but-non-numeric expiry is corrupt metadata: drop the tag rather than treat it as
        // never-expiring. The sibling valid tag survives.
        UrchinResult r = ScraperBackendClient.parseUrchin(obj(
                "{\"urchinChecked\":true,\"urchin\":{\"tags\":["
                + "{\"type\":\"sniper\",\"addedOn\":0,\"expiresAtMs\":\"soon\"},"
                + "{\"type\":\"caution\",\"addedOn\":0}]}}"));
        assertEquals(1, r.tags.size());
        assertEquals("caution", r.tags.get(0).type);
    }

    @Test
    public void negativeExpiryDropsTag() {
        UrchinResult r = ScraperBackendClient.parseUrchin(obj(
                "{\"urchinChecked\":true,\"urchin\":{\"tags\":["
                + "{\"type\":\"sniper\",\"addedOn\":0,\"expiresAtMs\":-1}]}}"));
        assertTrue(r.tags.isEmpty());
    }

    @Test
    public void equalityBoundaryExpiryIsInactive() {
        long now = 1800000000000L;
        // A positive expiry parses (kept), but expiresAtMs == now is inactive via activeTags.
        UrchinResult r = ScraperBackendClient.parseUrchin(obj(
                "{\"urchinChecked\":true,\"urchin\":{\"tags\":["
                + "{\"type\":\"sniper\",\"addedOn\":0,\"expiresAtMs\":" + now + "}]}}"));
        assertEquals(1, r.tags.size());
        assertEquals(Long.valueOf(now), r.tags.get(0).expiresAtMs);
        assertTrue(UrchinTag.activeTags(r.tags, now).isEmpty());
        assertEquals(1, UrchinTag.activeTags(r.tags, now - 1).size());
    }

    @Test
    public void absentExpiryNeverExpires() {
        UrchinResult r = ScraperBackendClient.parseUrchin(obj(
                "{\"urchinChecked\":true,\"urchin\":{\"tags\":["
                + "{\"type\":\"sniper\",\"addedOn\":0}]}}"));
        assertEquals(1, r.tags.size());
        assertNull(r.tags.get(0).expiresAtMs);
    }

    @Test
    public void urchinUuidCanonicalizedAndNullableAbsent() {
        // Present uuid is normalized to lowercase undashed (the B1 merge key); absent stays null.
        UrchinResult with = ScraperBackendClient.parseUrchin(obj(
                "{\"urchinChecked\":true,\"urchinUuid\":\"AB-CD\"}"));
        assertEquals("abcd", with.uuid);
        UrchinResult without = ScraperBackendClient.parseUrchin(obj("{\"urchinChecked\":true}"));
        assertNull(without.uuid);
    }

    @Test
    public void unavailableAndNotFoundFlags() {
        UrchinResult u = ScraperBackendClient.parseUrchin(obj("{\"urchinUnavailable\":true}"));
        assertTrue(u.unavailable);
        assertTrue(u.resolved());
        UrchinResult nf = ScraperBackendClient.parseUrchin(obj("{\"urchinChecked\":true,\"urchinNotFound\":true}"));
        assertTrue(nf.notFound);
        assertFalse(nf.unavailable);
    }
}
