package com.bedwarsqol.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Pins that a settings save never truncates the live file: temp sibling, then rename over. */
public class AtomicFileWriteTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void createsTheFileAndItsParentDirectories() throws Exception {
        File target = new File(tmp.getRoot(), "a/b/cobblify.json");
        AtomicFileWrite.write(target, utf8("{\"x\":1}"));
        assertEquals("{\"x\":1}", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(target.getParentFile(), "cobblify.json.tmp").exists());
    }

    @Test
    public void replacesExistingContentsCompletely() throws Exception {
        File target = tmp.newFile("cobblify.json");
        Files.write(target.toPath(), utf8("{\"old\":true,\"padding\":\"xxxxxxxxxxxxxxxxxxxx\"}"));
        AtomicFileWrite.write(target, utf8("{\"new\":1}"));
        assertArrayEquals(utf8("{\"new\":1}"), Files.readAllBytes(target.toPath()));
        assertFalse(new File(tmp.getRoot(), "cobblify.json.tmp").exists());
    }

    @Test
    public void aFailedWriteLeavesTheOldFileUntouched() throws Exception {
        File target = new File(tmp.newFolder("cfg"), "cobblify.json");
        Files.write(target.toPath(), utf8("{\"keep\":true}"));
        // Make the temp file impossible to create by taking away the parent's write permission.
        File parent = target.getParentFile();
        if (!parent.setWritable(false, false)) return; // filesystem cannot express this; nothing to pin
        try {
            if (parent.canWrite()) return; // running as a user the permission does not bind (root)
            try {
                AtomicFileWrite.write(target, utf8("{\"never\":1}"));
                fail("expected the write to fail");
            } catch (IOException expected) {
                // fine
            }
            assertEquals("{\"keep\":true}", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        } finally {
            parent.setWritable(true, false);
        }
    }

    /** Overlapping saves never share a temp file: the result is exactly one caller's payload, intact. */
    @Test
    public void concurrentWritesYieldOneWholePayloadAndNoLeftovers() throws Exception {
        final File target = new File(tmp.newFolder("cfg"), "cobblify.json");
        final int n = 8;
        final byte[][] payloads = new byte[n][];
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder("{\"writer\":").append(i).append(",\"pad\":\"");
            for (int k = 0; k < 20000; k++) sb.append((char) ('a' + i));
            payloads[i] = utf8(sb.append("\"}").toString());
        }
        final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        final java.util.List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<Throwable>());
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int w = i;
            threads[i] = new Thread(() -> {
                try {
                    go.await();
                    for (int r = 0; r < 5; r++) AtomicFileWrite.write(target, payloads[w]);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread t : threads) t.join();
        assertTrue(errors.toString(), errors.isEmpty());
        byte[] got = Files.readAllBytes(target.toPath());
        boolean whole = false;
        for (byte[] p : payloads) whole |= java.util.Arrays.equals(p, got);
        assertTrue("final file is not any single payload intact (" + got.length + " bytes)", whole);
        String[] leftovers = target.getParentFile().list();
        assertEquals(java.util.Arrays.toString(leftovers), 1, leftovers.length);
    }
}
