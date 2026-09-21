package com.bedwarsqol.config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Replace a file's contents without ever leaving it truncated. A {@code FileOutputStream} on the
 * live file empties it first and fills it as the writer runs, so a crash, a kill or a power cut
 * between those two moments leaves an empty or partial file — for {@code cobblify.json} that read
 * as "no settings" on the next start and every toggle silently went back to its default. Here the
 * bytes go to a sibling {@code <name>.tmp} first and the temp file is renamed over the target,
 * which the filesystem performs atomically where it can ({@code ATOMIC_MOVE}) and as a plain
 * replace elsewhere; either way the old contents stay intact until the new ones are complete.
 */
public final class AtomicFileWrite {

    private AtomicFileWrite() {
    }

    /**
     * Write {@code bytes} as the complete new contents of {@code target}, creating parent
     * directories as needed. On any failure the target is left as it was and the temp file removed.
     */
    public static void write(File target, byte[] bytes) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = new File(parent, target.getName() + ".tmp");
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.flush();
            }
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit();
        }
    }
}
