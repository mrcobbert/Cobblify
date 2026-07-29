package net.minecraftforge.fml.common;

import java.io.File;

/**
 * Test-scope stand-in for Forge's {@code Loader}, shadowing it on the test classpath.
 *
 * <p>The real one casts the context classloader to LaunchWrapper's {@code LaunchClassLoader} in its
 * constructor, so {@code Loader.instance()} throws {@code ExceptionInInitializerError} in a plain
 * JVM — which makes {@link com.bedwarsqol.stats.StatsCache} unloadable in tests, because its static
 * initializer reaches {@code Loader.instance().getConfigDir()} through {@code load()}.
 *
 * <p>Only the two members the mod actually uses are provided. The config dir comes from the
 * {@code cobblify.test.configDir} system property so a harness can point persistence at a temp
 * directory and never touch the player's real cache file.
 */
public class Loader {

    private static final Loader INSTANCE = new Loader();

    public static Loader instance() {
        return INSTANCE;
    }

    public File getConfigDir() {
        String dir = System.getProperty("cobblify.test.configDir");
        if (dir == null || dir.isEmpty()) {
            // Fail loudly rather than silently writing next to the build.
            throw new IllegalStateException(
                    "cobblify.test.configDir is not set; refusing to guess a config directory");
        }
        return new File(dir);
    }
}
