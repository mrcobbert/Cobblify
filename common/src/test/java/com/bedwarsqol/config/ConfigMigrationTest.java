package com.bedwarsqol.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the one-time BedwarsQOL → Cobblify settings migration: old-file-only installs get a COPY at
 * the new name (old file untouched), and an existing new file is never overwritten.
 */
public class ConfigMigrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f, String content) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    @Test
    public void oldFileOnlyIsCopiedAndLeftIntact() throws Exception {
        File oldFile = tmp.newFile("bedwarsqol.json");
        write(oldFile, "{\"guiAccent\":\"orange\"}");
        File newFile = new File(tmp.getRoot(), "cobblify.json");

        ConfigMigration.copySettingsIfNeeded(oldFile, newFile);

        assertTrue("new file created", newFile.isFile());
        assertEquals("{\"guiAccent\":\"orange\"}", read(newFile));
        assertTrue("old file left in place", oldFile.isFile());
        assertEquals("{\"guiAccent\":\"orange\"}", read(oldFile));
    }

    @Test
    public void existingNewFileIsNeverOverwritten() throws Exception {
        File oldFile = tmp.newFile("bedwarsqol.json");
        write(oldFile, "{\"old\":true}");
        File newFile = tmp.newFile("cobblify.json");
        write(newFile, "{\"new\":true}");

        ConfigMigration.copySettingsIfNeeded(oldFile, newFile);

        assertEquals("{\"new\":true}", read(newFile));
        assertEquals("{\"old\":true}", read(oldFile));
    }

    @Test
    public void missingOldFileCreatesNothing() {
        File oldFile = new File(tmp.getRoot(), "bedwarsqol.json");
        File newFile = new File(tmp.getRoot(), "cobblify.json");

        ConfigMigration.copySettingsIfNeeded(oldFile, newFile);

        assertFalse(newFile.exists());
    }

    @Test
    public void missingNewParentDirIsCreated() throws Exception {
        File oldFile = tmp.newFile("bedwarsqol.json");
        write(oldFile, "{}");
        File newFile = new File(new File(tmp.getRoot(), ".cobblify"), "cobblify.json");

        ConfigMigration.copySettingsIfNeeded(oldFile, newFile);

        assertTrue(newFile.isFile());
        assertEquals("{}", read(newFile));
    }

    /**
     * The migration writes atomically: nothing but the two settings files is left in the directory
     * (no {@code *.tmp} sibling) and the new file's bytes equal the old file's exactly, even for a
     * payload far larger than one write buffer.
     */
    @Test
    public void copyLeavesOnlyTheTwoFilesAndExactBytes() throws Exception {
        File oldFile = tmp.newFile("bedwarsqol.json");
        StringBuilder sb = new StringBuilder("{\"pad\":\"");
        while (sb.length() < 100 * 1024) sb.append("abcdefghij0123456789");
        write(oldFile, sb.append("\"}").toString());
        File newFile = new File(tmp.getRoot(), "cobblify.json");

        ConfigMigration.copySettingsIfNeeded(oldFile, newFile);

        String[] names = tmp.getRoot().list();
        Arrays.sort(names);
        assertArrayEquals(new String[]{"bedwarsqol.json", "cobblify.json"}, names);
        assertArrayEquals(Files.readAllBytes(oldFile.toPath()), Files.readAllBytes(newFile.toPath()));
    }
}
