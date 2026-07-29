package com.bedwarsqol.stats;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link ScraperBackendClient#validateSecretUrl} rejects http/relative/userinfo/malformed URLs BEFORE
 * any connection is opened or the secret is touched (the pure gate the key transport depends on).
 */
public class PostSecretValidationTest {

    @Test
    public void acceptsHttpsAbsoluteNoUserinfo() {
        assertNull(ScraperBackendClient.validateSecretUrl("https://worker.example.com"));
        assertNull(ScraperBackendClient.validateSecretUrl("https://worker.example.com:8443/base"));
    }

    @Test
    public void rejectsInsecureScheme() {
        assertEquals("insecure_scheme", ScraperBackendClient.validateSecretUrl("http://worker.example.com"));
    }

    @Test
    public void rejectsRelativeAndMalformed() {
        assertEquals("invalid_url", ScraperBackendClient.validateSecretUrl("worker.example.com"));
        assertEquals("invalid_url", ScraperBackendClient.validateSecretUrl("/relative/path"));
        assertEquals("invalid_url", ScraperBackendClient.validateSecretUrl("ht tp://bad"));
        assertEquals("invalid_url", ScraperBackendClient.validateSecretUrl(""));
        assertEquals("invalid_url", ScraperBackendClient.validateSecretUrl(null));
    }

    @Test
    public void rejectsUserinfo() {
        assertEquals("userinfo_present",
                ScraperBackendClient.validateSecretUrl("https://user:pass@worker.example.com"));
    }
}
