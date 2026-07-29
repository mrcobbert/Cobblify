package com.bedwarsqol.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One-time settings migration for the BedwarsQOL → Cobblify rename. Pure {@code java.io} on purpose
 * (no Minecraft classes) so the copy rule is unit-testable headlessly. Only the settings json is
 * ever migrated — the stats cache and diag log simply start fresh under their new names.
 */
public final class ConfigMigration {

    private ConfigMigration() {
    }

    /**
     * If {@code newFile} does not exist and {@code oldFile} does, copy old → new (the old file is
     * left untouched). An existing {@code newFile} is never overwritten. Silent on any I/O failure.
     */
    public static void copySettingsIfNeeded(File oldFile, File newFile) {
        try {
            if (oldFile == null || newFile == null) return;
            if (newFile.isFile() || !oldFile.isFile()) return;
            File parent = newFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            byte[] buf = new byte[8192];
            try (InputStream in = new FileInputStream(oldFile);
                 OutputStream out = new FileOutputStream(newFile)) {
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        } catch (Exception ignored) {
        }
    }
}
