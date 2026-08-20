package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JvmWriterIdentityTest {

    @Test
    public void parsePidFromRuntimeName_java8Style() {
        assertEquals(Long.valueOf(12345), JvmWriterIdentity.parsePidFromRuntimeName("12345@hostname"));
        assertEquals(Long.valueOf(1), JvmWriterIdentity.parsePidFromRuntimeName("1@host"));
    }

    @Test
    public void parsePidFromRuntimeName_rejectsInvalid() {
        assertNull(JvmWriterIdentity.parsePidFromRuntimeName(null));
        assertNull(JvmWriterIdentity.parsePidFromRuntimeName(""));
        assertNull(JvmWriterIdentity.parsePidFromRuntimeName("@host"));
        assertNull(JvmWriterIdentity.parsePidFromRuntimeName("abc@host"));
        assertNull(JvmWriterIdentity.parsePidFromRuntimeName("12.3@host"));
    }

    @Test
    public void isValid_acceptsHealthyValues() {
        assertTrue(JvmWriterIdentity.isValid(1, 1_700_000_000_000L));
        assertTrue(JvmWriterIdentity.isValid(JvmWriterIdentity.MAX_PID, JvmWriterIdentity.MAX_SAFE_JS_INTEGER));
    }

    @Test
    public void isValid_rejectsOutOfRange() {
        assertFalse(JvmWriterIdentity.isValid(0, 1000));
        assertFalse(JvmWriterIdentity.isValid(-1, 1000));
        assertFalse(JvmWriterIdentity.isValid(JvmWriterIdentity.MAX_PID + 1, 1000));
        assertFalse(JvmWriterIdentity.isValid(100, 0));
        assertFalse(JvmWriterIdentity.isValid(100, -1));
        assertFalse(JvmWriterIdentity.isValid(100, JvmWriterIdentity.MAX_SAFE_JS_INTEGER + 1));
    }

    @Test
    public void current_returnsStableIdentity() {
        JvmWriterIdentity a = JvmWriterIdentity.current();
        JvmWriterIdentity b = JvmWriterIdentity.current();
        if (a == null) {
            // Rare on supported runtimes; parsing tests cover the fallback path.
            return;
        }
        assertEquals(a, b);
        assertTrue(a.pid > 0);
        assertTrue(a.startTimeMs > 0);
        assertTrue(a.tempFileName().matches("lobby\\.[0-9]+\\.[0-9]+\\.tmp"));
    }

    @Test
    public void tempFileName_usesDigitsOnly() {
        JvmWriterIdentity id = JvmWriterIdentity.current();
        assertNotNull(id);
        String name = id.tempFileName();
        assertTrue(name.startsWith("lobby."));
        assertTrue(name.endsWith(".tmp"));
        assertFalse(name.contains("lobby.json.tmp"));
    }
}