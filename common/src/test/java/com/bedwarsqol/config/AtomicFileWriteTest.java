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
        File target = tmp.newFile("cobblify.json");
        Files.write(target.toPath(), utf8("{\"keep\":true}"));
        // Make the temp path unwritable by planting a directory where the temp file would go.
        File blocker = new File(tmp.getRoot(), "cobblify.json.tmp");
        assertTrue(blocker.mkdir());
        try {
            AtomicFileWrite.write(target, utf8("{\"never\":1}"));
            fail("expected the write to fail");
        } catch (IOException expected) {
            // fine
        }
        assertEquals("{\"keep\":true}", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
    }
}
