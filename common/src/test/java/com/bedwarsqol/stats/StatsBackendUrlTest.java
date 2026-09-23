package com.bedwarsqol.stats;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Pins the {@code /cobblify statsurl} normalization (F4): a bare host or an {@code http://} URL is
 * refused rather than stored, so the stats backend is never asked over a scheme that would carry the
 * token in clear and a "no protocol" URL never becomes the saved setting. The accepted form is
 * returned with its trailing slashes stripped, byte-for-byte otherwise.
 */
public class StatsBackendUrlTest {

    @Test
    public void bareHostIsRefused() {
        assertNull(StatsBackendUrl.normalize("my-worker.workers.dev"));
    }

    @Test
    public void plainHttpIsRefused() {
        assertNull(StatsBackendUrl.normalize("http://x"));
    }

    @Test
    public void embeddedCredentialsAreRefused() {
        assertNull(StatsBackendUrl.normalize("https://u:p@x"));
    }

    @Test
    public void blankIsRefused() {
        assertNull(StatsBackendUrl.normalize(""));
    }

    @Test
    public void nullIsRefused() {
        assertNull(StatsBackendUrl.normalize(null));
    }

    @Test
    public void relativePathIsRefused() {
        assertNull(StatsBackendUrl.normalize("/rel"));
    }

    @Test
    public void trailingSlashIsStripped() {
        assertEquals("https://x", StatsBackendUrl.normalize("https://x/"));
    }

    @Test
    public void surroundingSpaceAndRepeatedSlashesAreStripped() {
        assertEquals("https://x", StatsBackendUrl.normalize(" https://x//  "));
    }

    @Test
    public void caseIsPreservedVerbatim() {
        assertEquals("HTTPS://X", StatsBackendUrl.normalize("HTTPS://X"));
    }
}
