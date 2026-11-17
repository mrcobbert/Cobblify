package com.bedwarsqol.rewind;

import com.bedwarsqol.config.AtomicFileWrite;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Rewind folder: naming, listing, locking and deleting recordings (PLAN "File states",
 * "Pruning"). Every file it touches has a name matching {@code ^\d{8}-\d{6}-[0-9a-f]{4}\.(cbr|json|stats\.json)$}
 * ({@link PruneRules#idOf}) and is a regular file reached without following a link.
 *
 * <p><b>File states.</b> ACTIVE = held open by this process's writer under an exclusive
 * {@link FileChannel#tryLock} ({@link #isOpenHere}); a file another running instance holds cannot be
 * locked and is never recovered, pruned or deleted. CLOSED = END present and {@code .json}
 * {@code complete:true}.
 *
 * <p>Lock checks go through {@link Locker} so tests can inject "locked". Files open here are never
 * probed: on POSIX systems closing any second channel on a file drops the process's lock on it.
 */
public final class RecordingStore {

    /** One open, locked {@code .cbr}. Positions are absolute; nothing here keeps a file pointer. */
    public interface Handle extends Closeable {
        long size() throws IOException;

        /** Reads up to {@code len} bytes at {@code position}; -1 at end of file. */
        int read(long position, byte[] dst, int off, int len) throws IOException;

        /** Writes all {@code len} bytes at {@code position}. */
        void write(long position, byte[] src, int off, int len) throws IOException;

        void truncate(long size) throws IOException;

        void force() throws IOException;

        /** Releases the lock and closes the file. */
        @Override
        void close() throws IOException;
    }

    /** Opens a {@code .cbr} under an exclusive lock. */
    public interface Locker {
        /**
         * @param create create a new file (fails if it exists) instead of opening an existing one
         * @return the locked handle, or null if another holder has the lock
         */
        Handle open(File cbr, boolean create) throws IOException;
    }

    /** The real lock: {@link FileChannel#tryLock} on a channel opened without following links. */
    public static final Locker FILE_LOCKS = new Locker() {
        @Override
        public Handle open(File cbr, boolean create) throws IOException {
            Path path = cbr.toPath();
            Set<OpenOption> opts = new HashSet<OpenOption>();
            opts.add(StandardOpenOption.READ);
            opts.add(StandardOpenOption.WRITE);
            opts.add(LinkOption.NOFOLLOW_LINKS);
            if (create) {
                opts.add(StandardOpenOption.CREATE_NEW);
            } else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("not a regular file");
            }
            FileChannel ch = FileChannel.open(path, opts);
            FileLock lock;
            try {
                lock = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null; // held by this process through another channel
            } catch (IOException e) {
                ch.close();
                throw e;
            }
            if (lock == null) {
                ch.close();
                return null;
            }
            return new ChannelHandle(ch, lock);
        }
    };

    private static final class ChannelHandle implements Handle {
        private final FileChannel ch;
        private final FileLock lock;

        ChannelHandle(FileChannel ch, FileLock lock) {
            this.ch = ch;
            this.lock = lock;
        }

        @Override
        public long size() throws IOException {
            return ch.size();
        }

        @Override
        public int read(long position, byte[] dst, int off, int len) throws IOException {
            return ch.read(ByteBuffer.wrap(dst, off, len), position);
        }

        @Override
        public void write(long position, byte[] src, int off, int len) throws IOException {
            ByteBuffer b = ByteBuffer.wrap(src, off, len);
            long at = position;
            while (b.hasRemaining()) {
                at += ch.write(b, at);
            }
        }

        @Override
        public void truncate(long size) throws IOException {
            ch.truncate(size);
        }

        @Override
        public void force() throws IOException {
            ch.force(true);
        }

        @Override
        public void close() throws IOException {
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } finally {
                ch.close();
            }
        }
    }

    /** Largest {@code .json} read back; a bigger one is not ours and reads as missing. */
    private static final long MAX_JSON_BYTES = 1L << 20;

    private final File dir;
    private final Locker locker;
    private final Set<String> openHere = Collections.synchronizedSet(new HashSet<String>());

    public RecordingStore(File dir) {
        this(dir, FILE_LOCKS);
    }

    public RecordingStore(File dir, Locker locker) {
        this.dir = dir;
        this.locker = locker;
    }

    public File dir() {
        return dir;
    }

    public File cbrFile(String id) {
        return new File(dir, id + ".cbr");
    }

    public File jsonFile(String id) {
        return new File(dir, id + ".json");
    }

    public File statsFile(String id) {
        return new File(dir, id + ".stats.json");
    }

    /** True while this process's writer holds {@code id} open. */
    public boolean isOpenHere(String id) {
        return openHere.contains(id);
    }

    private static boolean isRegular(File f) {
        return Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean existsNoFollow(File f) {
        return Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    // ---------------------------------------------------------------- naming

    /**
     * A fresh id {@code yyyyMMdd-HHmmss-xxxx}: local time of {@code wallMs} plus 4 random hex digits,
     * none of whose three files exists yet. Holds no name, server or chat.
     */
    public String newId(long wallMs, Random random) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(wallMs));
        for (int i = 0; i < 256; i++) {
            String id = stamp + "-" + String.format(Locale.ROOT, "%04x", random.nextInt(0x10000));
            boolean free = true;
            for (String name : PruneRules.fileNames(id)) {
                free &= !existsNoFollow(new File(dir, name));
            }
            if (free && !openHere.contains(id)) {
                return id;
            }
        }
        throw new IOException("no free recording id");
    }

    // ---------------------------------------------------------------- locking

    /** Creates {@code id.cbr} and locks it; it is then open here until {@link #release}. */
    public Handle create(String id) throws IOException {
        if (!PruneRules.isValidId(id)) {
            throw new IllegalArgumentException("not a recording id");
        }
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create the Rewind folder");
        }
        Handle h = locker.open(cbrFile(id), true);
        if (h == null) {
            throw new IOException("new recording is locked");
        }
        openHere.add(id);
        return h;
    }

    /**
     * Opens an existing {@code id.cbr} under the lock for recovery; null if it is open here, locked by
     * another instance, or not a regular file. It is then open here until {@link #release}.
     */
    public Handle openExisting(String id) {
        if (!PruneRules.isValidId(id) || openHere.contains(id) || !isRegular(cbrFile(id))) {
            return null;
        }
        try {
            Handle h = locker.open(cbrFile(id), false);
            if (h != null) {
                openHere.add(id);
            }
            return h;
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Closes a handle from {@link #create} or {@link #openExisting} (releasing the lock). */
    public void release(String id, Handle h) {
        try {
            if (h != null) {
                h.close();
            }
        } catch (IOException ignored) {
            // the lock goes with the descriptor either way
        } finally {
            openHere.remove(id);
        }
    }

    /** Whether this process can take the lock on {@code id.cbr} right now (a file open here cannot). */
    public boolean lockable(String id) {
        Handle h = openExisting(id);
        if (h == null) {
            return false;
        }
        release(id, h);
        return true;
    }

    // ---------------------------------------------------------------- listing and metadata

    /** Ids of every recording file in the folder (regular files with a matching name), sorted. */
    public List<String> listIds() {
        File[] files = dir.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        Set<String> ids = new TreeSet<String>();
        for (File f : files) {
            String id = PruneRules.idOf(f.getName());
            if (id != null && isRegular(f)) {
                ids.add(id);
            }
        }
        return new ArrayList<String>(ids);
    }

    public boolean hasCbr(String id) {
        return isRegular(cbrFile(id));
    }

    /** The {@code .json} of {@code id}, or null if it is missing, a link, too large or not metadata. */
    public RecordingMeta readMeta(String id) {
        File f = jsonFile(id);
        if (!isRegular(f) || f.length() > MAX_JSON_BYTES) {
            return null;
        }
        try {
            return RecordingMeta.fromUtf8(Files.readAllBytes(f.toPath()));
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void writeMeta(String id, RecordingMeta meta) throws IOException {
        AtomicFileWrite.write(jsonFile(id), meta.toUtf8());
    }

    public void writeStats(String id, String json) throws IOException {
        AtomicFileWrite.write(statsFile(id), json.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- deleting

    /**
     * Deletes the three files of {@code id} that exist as regular files; never a link, never a file
     * open here. Returns whether every existing file is gone.
     */
    public boolean delete(String id) {
        if (!PruneRules.isValidId(id) || openHere.contains(id)) {
            return false;
        }
        boolean ok = true;
        for (String name : PruneRules.fileNames(id)) {
            Path p = new File(dir, name).toPath();
            if (Files.isSymbolicLink(p)) {
                ok = false;
                continue;
            }
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Files.delete(p);
            } catch (IOException e) {
                ok = false;
            }
        }
        return ok;
    }

    /**
     * Pruning (G15, A5): describes every recording for {@link PruneRules} and deletes the ids it
     * returns. Only CLOSED recordings that are not starred, key-marked or open here are probed for the
     * lock; nothing else is opened. Returns the number of recordings deleted.
     */
    public int prune() {
        List<PruneRules.Recording> all = new ArrayList<PruneRules.Recording>();
        for (String id : listIds()) {
            boolean open = openHere.contains(id);
            RecordingMeta m = readMeta(id);
            boolean closed = m != null && m.complete && hasCbr(id);
            boolean starred = m != null && m.starred;
            boolean keyMarked = m != null && m.keyMarked;
            boolean lockable = closed && !open && !starred && !keyMarked && lockable(id);
            all.add(new PruneRules.Recording(id, m != null ? m.startedAtMs : 0L, closed, lockable, open, starred,
                    keyMarked));
        }
        int deleted = 0;
        for (String id : PruneRules.idsToDelete(all)) {
            if (delete(id)) {
                deleted++;
            }
        }
        return deleted;
    }
}
