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
 * {@link ScraperBackendClient#parseSeraph} maps the Worker's resolution fields
 * (seraphChecked/seraphUnavailable/seraphNotFound + seraph.tags/threatLevel/encounters) into a
 * {@link SeraphResult}, and returns null for a line carrying NO resolution fields.
 */
public class SeraphParseTest {

    private static JsonObject obj(String json) {
        return new JsonParser().parse(json).getAsJsonObject();
    }

    @Test
    public void noResolutionFieldsReturnsNull() {
        assertNull(ScraperBackendClient.parseSeraph(obj("{\"name\":\"a\",\"success\":true}")));
    }

    @Test
    public void checkedEmptyIsResolvedNoTags() {
        SeraphResult r = ScraperBackendClient.parseSeraph(obj("{\"seraphChecked\":true}"));
        assertTrue(r.checked);
        assertTrue(r.resolved());
        assertTrue(r.tags.isEmpty());
        assertEquals(-1, r.threatLevel);
        assertEquals(-1, r.encounters);
    }

    @Test
    public void tagsKindsAndVerifiedParsed() {
        SeraphResult r = ScraperBackendClient.parseSeraph(obj(
                "{\"seraphChecked\":true,\"seraph\":{\"tags\":["
                + "{\"kind\":\"blacklist\",\"subtype\":\"cheater\",\"reason\":\"autoclicker\",\"addedOn\":1700000000000,\"verified\":true},"
                + "{\"kind\":\"safelist\",\"reason\":\"trusted\",\"addedOn\":0}],"
                + "\"threatLevel\":8,\"encounters\":3}}"));
        List<SeraphTag> tags = r.tags;
        assertEquals(2, tags.size());
        assertEquals("blacklist", tags.get(0).kind);
        assertEquals("cheater", tags.get(0).subtype);
        assertTrue(tags.get(0).verified);
        assertEquals(1700000000000L, tags.get(0).addedOnMs);
        assertEquals("safelist", tags.get(1).kind);
        assertFalse(tags.get(1).verified);
        assertEquals(8, r.threatLevel);
        assertEquals(3, r.encounters);
    }

    @Test
    public void tagMissingKindIsDropped() {
        SeraphResult r = ScraperBackendClient.parseSeraph(obj(
                "{\"seraphChecked\":true,\"seraph\":{\"tags\":["
                + "{\"reason\":\"no kind\"},{\"kind\":\"bot\"}]}}"));
        assertEquals(1, r.tags.size());
        assertEquals("bot", r.tags.get(0).kind);
    }

    @Test
    public void statsOnlyResolutionCarriesEncountersNoTags() {
        SeraphResult r = ScraperBackendClient.parseSeraph(obj(
                "{\"seraphChecked\":true,\"seraph\":{\"encounters\":5}}"));
        assertTrue(r.tags.isEmpty());
        assertEquals(-1, r.threatLevel);
        assertEquals(5, r.encounters);
    }

    @Test
    public void seraphUuidCanonicalizedAndNullableAbsent() {
        SeraphResult with = ScraperBackendClient.parseSeraph(obj(
                "{\"seraphChecked\":true,\"seraphUuid\":\"AB-CD\"}"));
        assertEquals("abcd", with.uuid);
        SeraphResult without = ScraperBackendClient.parseSeraph(obj("{\"seraphChecked\":true}"));
        assertNull(without.uuid);
    }

    @Test
    public void unavailableAndNotFoundFlags() {
        SeraphResult u = ScraperBackendClient.parseSeraph(obj("{\"seraphUnavailable\":true}"));
        assertTrue(u.unavailable);
        assertTrue(u.resolved());
        SeraphResult nf = ScraperBackendClient.parseSeraph(obj("{\"seraphChecked\":true,\"seraphNotFound\":true}"));
        assertTrue(nf.notFound);
        assertFalse(nf.unavailable);
    }
}
