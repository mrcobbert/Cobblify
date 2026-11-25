package com.bedwarsqol.config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.File;

/**
 * Where Cobblify keeps its files: Fabric's config directory, {@code <gameDir>/config}, the same place
 * Forge's {@code Loader.getConfigDir()} pointed at, so an existing {@code cobblify.json} carries over.
 *
 * <p>The {@code cobblify.test.configDir} system property overrides it, so a test or harness can point
 * persistence at a temp directory and never touch the player's real files. Outside a running game
 * Fabric has no game directory; then this throws instead of guessing a location.
 */
public final class ConfigDir {

    static final String OVERRIDE_PROPERTY = "cobblify.test.configDir";

    private ConfigDir() {
    }

    public static File get() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isEmpty()) return new File(override);
        try {
            return FabricLoader.getInstance().getConfigDir().toFile();
        } catch (RuntimeException e) {
            // Fail loudly rather than silently writing next to the build.
            throw new IllegalStateException(OVERRIDE_PROPERTY
                    + " is not set and Fabric has no config directory; refusing to guess one", e);
        }
    }
}
