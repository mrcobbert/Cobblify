package com.bedwarsqol.rewind.export;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link HelperStore} (TASK X2, B2; PLAN "Helper shipping route"): the helper ships in the jar with a properties file
 * holding its SHA-256; it is unpacked to {@code bin/cobblify-encode-<first 16 hex>} through a temp file, made
 * executable, re-checked before every start, replaced when tampered with; older {@code cobblify-encode-*} files go; a
 * jar without it says "missing"; the override property wins.
 */
public class HelperStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final byte[] BINARY = "#!/bin/sh\necho fake helper\n".getBytes(StandardCharsets.UTF_8);

    static String hex(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder s = new StringBuilder();
        for (byte x : d) {
            s.append(String.format("%02x", x & 0xFF));
        }
        return s.toString();
    }

    /** A jar's resources: the helper and its properties, by name. */
    static HelperStore.Resources jar(final Map<String, byte[]> files) {
        return name -> {
            byte[] b = files.get(name);
            return b == null ? null : new ByteArrayInputStream(b);
        };
    }

    static Map<String, byte[]> shipped(String exe, byte[] binary) throws Exception {
        Map<String, byte[]> m = new HashMap<String, byte[]>();
        m.put(exe, binary);
        m.put("cobblify-encode.properties", ("sha256=" + hex(binary) + "\nsize=" + binary.length
                + "\nprotocol=1\nos=mac\n").getBytes(StandardCharsets.UTF_8));
        return m;
    }

    @Test
    public void aJarWithoutTheHelperSaysMissing() throws Exception {
        File bin = tmp.newFolder("bin");
        HelperStore.Found f = new HelperStore(bin, false, jar(new HashMap<String, byte[]>())).find(null);
        assertNull(f.file);
        assertEquals("missing", f.problem);
        Map<String, byte[]> noProps = new HashMap<String, byte[]>();
        noProps.put("cobblify-encode", BINARY);
        assertEquals("missing", new HelperStore(bin, false, jar(noProps)).find("").problem);
    }

    @Test
    public void unpackWritesAnExecutableFileNamedByItsDigest() throws Exception {
        File bin = new File(tmp.getRoot(), "bin"); // not there yet: made
        HelperStore.Found f = new HelperStore(bin, false, jar(shipped("cobblify-encode", BINARY))).find(null);
        assertNull(f.problem);
        assertEquals(new File(bin, "cobblify-encode-" + hex(BINARY).substring(0, 16)), f.file);
        assertArrayEquals(BINARY, Files.readAllBytes(f.file.toPath()));
        assertTrue("executable", f.file.canExecute());
        String[] left = bin.list();
        assertEquals("no temp file left: " + String.join(",", left), 1, left.length);
        assertTrue(new HelperStore(bin, false, jar(shipped("cobblify-encode", BINARY))).verify(f.file));
    }

    @Test
    public void onWindowsTheFileEndsInExe() throws Exception {
        File bin = tmp.newFolder("bin");
        HelperStore.Found f = new HelperStore(bin, true, jar(shipped("cobblify-encode.exe", BINARY))).find(null);
        assertNull(f.problem);
        assertEquals("cobblify-encode-" + hex(BINARY).substring(0, 16) + ".exe", f.file.getName());
    }

    @Test
    public void aTamperedFileIsReplacedAndFailsTheCheckBeforeAStart() throws Exception {
        File bin = tmp.newFolder("bin");
        HelperStore store = new HelperStore(bin, false, jar(shipped("cobblify-encode", BINARY)));
        File f = store.find(null).file;
        Files.write(f.toPath(), "evil".getBytes(StandardCharsets.UTF_8));
        assertFalse("the check before every start", store.verify(f));
        HelperStore.Found again = store.find(null);
        assertNull(again.problem);
        assertEquals(f, again.file);
        assertArrayEquals("replaced from the jar", BINARY, Files.readAllBytes(f.toPath()));
        assertTrue(store.verify(f));
    }

    @Test
    public void aJarWhoseHelperDoesNotMatchItsDigestIsRefused() throws Exception {
        File bin = tmp.newFolder("bin");
        Map<String, byte[]> m = shipped("cobblify-encode", BINARY);
        m.put("cobblify-encode", "other bytes".getBytes(StandardCharsets.UTF_8));
        HelperStore.Found f = new HelperStore(bin, false, jar(m)).find(null);
        assertNull(f.file);
        assertEquals("digest", f.problem);
        assertEquals("nothing unpacked", 0, bin.list().length);
    }

    @Test
    public void olderHelpersAreDeleted() throws Exception {
        File bin = tmp.newFolder("bin");
        File old1 = new File(bin, "cobblify-encode-0123456789abcdef");
        File old2 = new File(bin, "cobblify-encode-fedcba9876543210.exe");
        File other = new File(bin, "something-else");
        for (File x : new File[]{old1, old2, other}) {
            Files.write(x.toPath(), BINARY);
        }
        HelperStore.Found f = new HelperStore(bin, false, jar(shipped("cobblify-encode", BINARY))).find(null);
        assertNotNull(f.file);
        assertFalse(old1.exists());
        assertFalse(old2.exists());
        assertTrue("not ours: kept", other.exists());
        assertTrue(f.file.exists());
    }

    @Test
    public void theOverridePropertyWins() throws Exception {
        File bin = tmp.newFolder("bin");
        File dev = tmp.newFile("dev-helper");
        HelperStore.Found f = new HelperStore(bin, false, jar(shipped("cobblify-encode", BINARY)))
                .find(dev.getAbsolutePath());
        assertNull(f.problem);
        assertEquals(dev.getAbsoluteFile(), f.file);
        assertEquals("nothing unpacked", 0, bin.list().length);
        HelperStore.Found gone = new HelperStore(bin, false, jar(shipped("cobblify-encode", BINARY)))
                .find(new File(tmp.getRoot(), "nothing-here").getAbsolutePath());
        assertNull(gone.file);
        assertEquals("an override that points at nothing is missing, not the jar's", "missing", gone.problem);
    }

    @Test
    public void aResourceThatThrowsIsAnIoProblemNotAnException() throws Exception {
        File bin = tmp.newFolder("bin");
        Map<String, byte[]> m = shipped("cobblify-encode", BINARY);
        HelperStore.Resources r = name -> {
            if (name.equals("cobblify-encode")) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("boom");
                    }
                };
            }
            byte[] b = m.get(name);
            return b == null ? null : new ByteArrayInputStream(b);
        };
        HelperStore.Found f = new HelperStore(bin, false, r).find(null);
        assertNull(f.file);
        assertEquals("io", f.problem);
    }
}
