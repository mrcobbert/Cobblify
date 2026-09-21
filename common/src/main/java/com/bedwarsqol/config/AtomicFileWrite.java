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
 * bytes go to a fresh sibling temp file first ({@code <name>.<unique>.tmp}) and that file is
 * renamed over the target, which the filesystem performs atomically where it can
 * ({@code ATOMIC_MOVE}) and as a plain replace elsewhere; either way the old contents stay intact
 * until the new ones are complete.
 *
 * <p>Calls are serialised on a class-wide lock and each gets its own temp file, so two saves that
 * overlap (a GUI close racing a command, say) can never share a temp inode — one would otherwise
 * rename the other's still-open file onto the target and the loser's writes would land on the live
 * file directly. The lock also makes "last save wins" a whole-file guarantee, not a byte-level one.
 */
public final class AtomicFileWrite {

    private static final Object LOCK = new Object();

    private AtomicFileWrite() {
    }

    /**
     * Write {@code bytes} as the complete new contents of {@code target}, creating parent
     * directories as needed. On any failure the target is left as it was and the temp file removed.
     */
    public static void write(File target, byte[] bytes) throws IOException {
        synchronized (LOCK) {
            File parent = target.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("cannot create " + parent);
            }
            // A unique temp per call: never a fixed "<name>.tmp" that a concurrent caller could reopen.
            File tmp = File.createTempFile(target.getName() + ".", ".tmp", parent);
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
}
