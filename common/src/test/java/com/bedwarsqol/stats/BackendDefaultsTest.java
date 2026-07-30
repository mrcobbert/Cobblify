package com.bedwarsqol.stats;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the parsing rules of {@link BackendDefaults} via the package-private stream overload (the
 * static init cannot be swapped per test) plus the static wiring itself: the test classpath carries
 * a canary {@code cobblify-backend.properties} (common/src/test/resources), so the statics must
 * resolve the canary pair — if resource lookup or classpath precedence ever breaks, this fails.
 */
public class BackendDefaultsTest {

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    public void absentResourceResolvesEmpty() {
        BackendTarget t = BackendDefaults.fromStream(null);
        assertEquals("", t.url);
        assertEquals("", t.token);
        assertFalse("blank build: no backend configured", t.isConfigured());
    }

    @Test
    public void absentKeysResolveEmpty() {
        BackendTarget t = BackendDefaults.fromStream(stream("other=x\n"));
        assertEquals("", t.url);
        assertEquals("", t.token);
    }

    @Test
    public void presentPairIsTrimmed() {
        BackendTarget t = BackendDefaults.fromStream(
                stream("url= https://w.example \ntoken= tok123 \n"));
        assertEquals("https://w.example", t.url);
        assertEquals("tok123", t.token);
        assertTrue(t.isConfigured());
    }

    @Test
    public void tokenWithoutUrlIsTreatedAsAbsent() {
        BackendTarget t = BackendDefaults.fromStream(stream("token=orphan\n"));
        assertEquals("", t.url);
        assertEquals("a token may never exist without the URL it belongs to", "", t.token);
    }

    @Test
    public void staticsResolveTheTestCanaryResource() {
        assertEquals("https://baked-canary.test", BackendDefaults.url());
        assertEquals("BAKED_CANARY_TOKEN_00000000", BackendDefaults.token());
    }
}
