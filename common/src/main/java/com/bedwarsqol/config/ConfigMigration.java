package com.bedwarsqol.config;

import java.io.File;
import java.nio.file.Files;

/**
 * One-time settings migration for the BedwarsQOL → Cobblify rename. Pure file I/O on purpose
 * (no Minecraft classes) so the copy rule is unit-testable headlessly. Only the settings json is
 * ever migrated — the stats cache and diag log simply start fresh under their new names.
 *
 * <p>The copy goes through {@link AtomicFileWrite} because it gets exactly one chance: the old
 * stream-to-the-target copy left a truncated {@code cobblify.json} behind if the client died
 * part-way through, and that half a file still counted as migrated, so the missing settings were
 * gone for good.
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
            // AtomicFileWrite creates the parent directory and renames the finished bytes into place.
            AtomicFileWrite.write(newFile, Files.readAllBytes(oldFile.toPath()));
        } catch (Exception ignored) {
        }
    }
}
