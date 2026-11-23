package com.bedwarsqol.rewind.export;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Predicate;

/**
 * An export's file name (TASK F1, F2; decision 5): {@code <mode> - <map> - <yyyy-MM-dd HH.mm.ss>.mp4} in local time at
 * the start of the export. A missing mode or map is left out; characters a file name cannot hold on the Mac or on
 * Windows ({@code / \ : * ? " < > |} and control characters) become {@code _}, and trailing dots and spaces go; a taken
 * name gets {@code (2)}, {@code (3)}... The unfinished file is {@code <name>.<token>.partial.mp4} next to it, the token
 * one export's own. Pure apart from the token counter.
 */
public final class ExportFileName {

    private ExportFileName() {
    }

    /** The name without {@code .mp4}. */
    public static String base(String mode, String map, long epochMs, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT);
        f.setTimeZone(zone);
        StringBuilder b = new StringBuilder();
        for (String part : new String[]{mode, map}) {
            String c = clean(part);
            if (!c.isEmpty()) {
                b.append(c).append(" - ");
            }
        }
        return b.append(f.format(new Date(epochMs))).toString();
    }

    /** One part of the name, safe on both systems ({@code ""} for null or blank). */
    public static String clean(String part) {
        if (part == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(part.length());
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            b.append("/\\:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        int end = b.length();
        while (end > 0 && (b.charAt(end - 1) == '.' || b.charAt(end - 1) == ' ')) {
            end--;
        }
        return b.substring(0, end).trim();
    }

    /** {@code dir/base.mp4}, or the first {@code base (n).mp4} whose file and partial file are both free. */
    public static File pick(File dir, String base, Predicate<File> exists) {
        for (int n = 1; ; n++) {
            File f = new File(dir, base + (n == 1 ? "" : " (" + n + ")") + ".mp4");
            if (!exists.test(f) && !exists.test(partial(f))) {
                return f;
            }
        }
    }

    /**
     * Removes abandoned unfinished files (TASK F2, code review 1 I1): every {@code *.partial.mp4} in {@code listing}
     * (the export folder's files; null: none) is deleted. Only called while no export of this game runs.
     *
     * @return how many were deleted
     */
    public static int sweep(File[] listing, Predicate<File> delete) {
        int n = 0;
        if (listing == null) {
            return 0;
        }
        for (File f : listing) {
            if (f != null && f.getName().toLowerCase(Locale.ROOT).endsWith(PARTIAL_SUFFIX) && delete.test(f)) {
                n++;
            }
        }
        return n;
    }

    /** The next export's file: {@link #sweep} the folder, then {@link #pick} the name. */
    public static File pickAfterSweep(File dir, String base, File[] listing, Predicate<File> exists,
                                      Predicate<File> delete) {
        sweep(listing, delete);
        return pick(dir, base, exists);
    }

    private static final String PARTIAL_SUFFIX = ".partial.mp4";

    /** {@code <name minus .mp4>.partial.mp4}: the name {@link #pick} keeps free (older versions wrote there). */
    public static File partial(File finalFile) {
        return new File(finalFile.getParentFile(), stem(finalFile) + PARTIAL_SUFFIX);
    }

    /**
     * {@code <name minus .mp4>.<token>.partial.mp4}, where one export's helper writes until it is done (code review 2
     * I1): each export has its own token, so a cancelled helper's late delete of its own path (its own cancel, or the
     * game's after it was killed) cannot hit a later export that got the same final name. {@link #sweep} still finds it.
     */
    public static File partial(File finalFile, String token) {
        return new File(finalFile.getParentFile(), stem(finalFile) + "." + token + PARTIAL_SUFFIX);
    }

    private static final java.util.concurrent.atomic.AtomicLong TOKENS =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime() & 0xFFFFFFFFFL);

    /** A token no other export of this game has had (and, very likely, no other game's). */
    public static String newToken() {
        return Long.toString(TOKENS.incrementAndGet() & 0xFFFFFFFFFFL, 36);
    }

    private static String stem(File finalFile) {
        String n = finalFile.getName();
        return n.toLowerCase(Locale.ROOT).endsWith(".mp4") ? n.substring(0, n.length() - 4) : n;
    }
}
