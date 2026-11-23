package com.bedwarsqol.rewind.export;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Properties;

/**
 * Finds the encoder helper or unpacks it from the mod jar (Rewind Export PLAN "Helper shipping route", D1; TASK X2,
 * B2). The jar holds, under {@value #RESOURCE_DIR}, the host OS's helper ({@code cobblify-encode}, {@code .exe} on
 * Windows) and {@code cobblify-encode.properties} with its {@code sha256}. The helper is unpacked to
 * {@code <bin>/cobblify-encode-<first 16 hex of the digest>} through a temp file and a rename, made executable, and its
 * digest is checked again before every start ({@link #verify}); a file that does not match is replaced from the jar;
 * older {@code cobblify-encode-*} files are deleted. A jar without the helper (an owner bundle, a plain Gradle build)
 * gives {@code missing}, and Export then fails as TASK U6 says. {@value #OVERRIDE_PROPERTY} (the dev client) names a
 * helper file to use instead. Problems: {@code missing}, {@code digest} (the jar's helper does not match its own
 * digest), {@code io}. Game thread or any one thread.
 */
public final class HelperStore {

    /** The dev client's helper ({@code cobdev stage} sets it to its copy of {@code encoder/dist/}). */
    public static final String OVERRIDE_PROPERTY = "cobblify.encodeHelper";
    /** Where the jar holds the helper. */
    public static final String RESOURCE_DIR = "/com/bedwarsqol/rewind/export/helper/";
    static final String PROPERTIES = "cobblify-encode.properties";
    static final String PREFIX = "cobblify-encode-";

    /** The jar's resources by name inside {@link #RESOURCE_DIR} (null when absent). */
    public interface Resources {
        InputStream open(String name) throws IOException;
    }

    /** A helper file to run, or why there is none. */
    public static final class Found {
        public final File file;
        public final String problem;

        Found(File file, String problem) {
            this.file = file;
            this.problem = problem;
        }
    }

    private final File bin;
    private final boolean windows;
    private final Resources resources;
    private String digest;

    /**
     * @param binDir    {@code ~/.cobblify/bin}
     * @param windows   the host is Windows ({@code .exe})
     * @param resources the jar's resources ({@link #jar(Class)} in the game)
     */
    public HelperStore(File binDir, boolean windows, Resources resources) {
        this.bin = binDir;
        this.windows = windows;
        this.resources = resources;
    }

    /** The jar of {@code anchor}, as {@link Resources}. */
    public static Resources jar(final Class<?> anchor) {
        return name -> anchor.getResourceAsStream(RESOURCE_DIR + name);
    }

    /** The helper's resource name in the jar. */
    String exeName() {
        return windows ? "cobblify-encode.exe" : "cobblify-encode";
    }

    /**
     * The helper to run: the override when set (it must exist), else the jar's, unpacked or checked.
     *
     * @param override {@value #OVERRIDE_PROPERTY}'s value, or null
     */
    public Found find(String override) {
        if (override != null && !override.trim().isEmpty()) {
            File f = new File(override.trim()).getAbsoluteFile();
            return f.isFile() ? new Found(f, null) : new Found(null, "missing");
        }
        try {
            String want = expectedDigest();
            if (want == null) {
                return new Found(null, "missing");
            }
            File target = new File(bin, PREFIX + want.substring(0, 16) + (windows ? ".exe" : ""));
            if (!(target.isFile() && want.equals(sha256(target)))) {
                Found f = unpack(target, want);
                if (f != null) {
                    return f;
                }
            }
            if (!windows && !target.canExecute() && !target.setExecutable(true, false)) {
                return new Found(null, "io");
            }
            deleteOthers(target);
            return new Found(target, null);
        } catch (IOException e) {
            return new Found(null, "io");
        }
    }

    /** The helper still has the jar's digest (call right before every start). An override is not checked. */
    public boolean verify(File helper) {
        if (helper == null) {
            return false;
        }
        if (!helper.getName().startsWith(PREFIX)) {
            return helper.isFile(); // the dev client's override
        }
        try {
            String want = expectedDigest();
            return want != null && want.equals(sha256(helper));
        } catch (IOException e) {
            return false;
        }
    }

    /** Null when unpacked, else the problem. */
    private Found unpack(File target, String want) throws IOException {
        InputStream in = resources.open(exeName());
        if (in == null) {
            return new Found(null, "missing");
        }
        byte[] data;
        try {
            data = readAll(in);
        } finally {
            in.close();
        }
        if (!want.equals(hex(digest(data)))) {
            return new Found(null, "digest");
        }
        if (!bin.isDirectory() && !bin.mkdirs()) {
            throw new IOException("bin");
        }
        File tmp = File.createTempFile(PREFIX, ".tmp", bin);
        try {
            Files.write(tmp.toPath(), data);
            if (!windows && !tmp.setExecutable(true, false)) {
                throw new IOException("chmod");
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicRefused) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                tmp.deleteOnExit();
            }
        }
        return want.equals(sha256(target)) ? null : new Found(null, "io");
    }

    private void deleteOthers(File keep) {
        File[] all = bin.listFiles();
        if (all == null) {
            return;
        }
        for (File f : all) {
            if (f.getName().startsWith(PREFIX) && !f.getName().equals(keep.getName()) && !f.delete()) {
                f.deleteOnExit();
            }
        }
    }

    /** The jar's digest for the helper (lower-case hex), or null when the jar has none. */
    private String expectedDigest() throws IOException {
        if (digest != null) {
            return digest;
        }
        InputStream in = resources.open(PROPERTIES);
        if (in == null) {
            return null;
        }
        Properties p = new Properties();
        try {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } finally {
            in.close();
        }
        String d = p.getProperty("sha256", "").trim().toLowerCase(Locale.ROOT);
        if (!d.matches("[0-9a-f]{64}")) {
            return null;
        }
        InputStream exe = resources.open(exeName());
        if (exe == null) {
            return null; // properties without the file: nothing to run
        }
        exe.close();
        digest = d;
        return d;
    }

    /** SHA-256 of a file, lower-case hex. */
    public static String sha256(File f) throws IOException {
        MessageDigest md = sha();
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) {
                md.update(b, 0, n);
            }
        }
        return hex(md.digest());
    }

    private static byte[] digest(byte[] data) {
        return sha().digest(data);
    }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder s = new StringBuilder(d.length * 2);
        for (byte x : d) {
            s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        }
        return s.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        for (int n; (n = in.read(b)) > 0; ) {
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
}
