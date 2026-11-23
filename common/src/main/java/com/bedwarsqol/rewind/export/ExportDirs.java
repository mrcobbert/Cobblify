package com.bedwarsqol.rewind.export;

import java.io.File;
import java.util.Locale;

/**
 * Where exports go (TASK F1; decision 5): {@code Cobblify} inside the system's videos folder as the helper's probe
 * reports it ({@code videosDir}), else {@code ~/Movies} on the Mac and {@code ~/Videos} elsewhere. The system property
 * {@value #OVERRIDE_PROPERTY} (the dev client's own export folder) wins and is used as is. Pure; the caller creates the
 * folder.
 */
public final class ExportDirs {

    /** The dev client's export folder (cobdev stage sets it inside {@code /Users/Shared/cobblify-dev/exports}). */
    public static final String OVERRIDE_PROPERTY = "cobblify.exportDir";

    private ExportDirs() {
    }

    /**
     * @param override  {@value #OVERRIDE_PROPERTY}'s value, or null
     * @param videosDir the helper's {@code videosDir}, or null
     * @param userHome  {@code user.home}
     * @param osName    {@code os.name}
     */
    public static File resolve(String override, String videosDir, String userHome, String osName) {
        if (override != null && !override.trim().isEmpty()) {
            return new File(override.trim());
        }
        boolean windows = osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
        boolean mac = osName != null && osName.toLowerCase(Locale.ROOT).startsWith("mac");
        File base = absolute(videosDir, windows) ? new File(videosDir)
                : new File(userHome == null ? "." : userHome, mac ? "Movies" : "Videos");
        return new File(base, "Cobblify");
    }

    private static boolean absolute(String p, boolean windows) {
        if (p == null || p.trim().isEmpty()) {
            return false;
        }
        return windows ? p.matches("^[A-Za-z]:[\\\\/].*") || p.startsWith("\\\\") : p.startsWith("/");
    }
}
