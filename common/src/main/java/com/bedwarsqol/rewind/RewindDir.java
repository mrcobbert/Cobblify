package com.bedwarsqol.rewind;

import java.io.File;
import java.util.Locale;

/**
 * Where Rewind keeps its recordings: {@code ~/.cobblify/rewind/}. The {@code cobblify.rewindDir} system property
 * overrides it, so tests and the dev loop write to a folder of their own and never touch the player's games.
 */
public final class RewindDir {

    public static final String OVERRIDE_PROPERTY = "cobblify.rewindDir";

    private RewindDir() {
    }

    /** The folder, from the override property if set, else {@code ~/.cobblify/rewind}. */
    public static File get() {
        return resolve(System.getProperty(OVERRIDE_PROPERTY), System.getProperty("user.home")).getAbsoluteFile();
    }

    static File resolve(String override, String userHome) {
        if (override != null && !override.trim().isEmpty()) return new File(override);
        return new File(new File(userHome, ".cobblify"), "rewind");
    }

    /** The command that shows {@code dir} in the file browser of OS {@code osName} (the {@code os.name} property). */
    public static String[] openCommand(String osName, File dir) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String path = dir.getAbsolutePath();
        if (os.startsWith("mac") || os.contains("os x") || os.contains("darwin")) return new String[]{"open", path};
        if (os.startsWith("windows")) return new String[]{"explorer.exe", path};
        return new String[]{"xdg-open", path};
    }

    /** Open Folder: creates the folder if needed and shows it, on a daemon thread. Never throws. */
    public static Thread open() {
        File dir = get();
        return open(dir, openCommand(System.getProperty("os.name"), dir));
    }

    /** Shows {@code dir} with {@code command} ({@link #openCommand}) on a daemon thread (Rewind Export's Open Folder). */
    public static Thread open(final File dir, final String[] command) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!dir.isDirectory()) dir.mkdirs();
                    new ProcessBuilder(command).start();
                } catch (Throwable ignored) {
                    // nothing to show the player from here; the folder simply does not open
                }
            }
        }, "Cobblify Rewind Open Folder");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
