package com.bedwarsqol.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public final class SettingsManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SettingsManager() {
    }

    public static ClientSettings load() {
        File file = settingsFile();
        if (!file.isFile()) {
            ClientSettings defaults = new ClientSettings();
            save(defaults);
            return defaults;
        }

        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            ClientSettings settings = GSON.fromJson(reader, ClientSettings.class);
            if (settings == null) settings = new ClientSettings();
            settings.sanitize();
            return settings;
        } catch (Exception ignored) {
            ClientSettings defaults = new ClientSettings();
            defaults.sanitize();
            return defaults;
        }
    }

    public static void save(ClientSettings settings) {
        if (settings == null) return;
        settings.sanitize();
        File file = settingsFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;

        // Serialise first, then swap the bytes in through a temp file + rename: a crash mid-save
        // must never leave an empty cobblify.json that reads back as "all defaults" next launch.
        try {
            AtomicFileWrite.write(file, GSON.toJson(settings).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    /** Mod data dir (~/.cobblify); Forge's config dir is unavailable under Weave. */
    private static File settingsFile() {
        File dir = new File(System.getProperty("user.home"), ".cobblify");
        File file = new File(dir, "cobblify.json");
        // BedwarsQOL → Cobblify rename: one-time copy of the old settings json (old file kept).
        File oldDir = new File(System.getProperty("user.home"), ".bedwarsqol");
        ConfigMigration.copySettingsIfNeeded(new File(oldDir, "bedwarsqol.json"), file);
        return file;
    }
}
