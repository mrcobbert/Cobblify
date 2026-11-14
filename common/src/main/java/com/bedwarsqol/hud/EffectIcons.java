package com.bedwarsqol.hud;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Where the modern potion effect icons come from, and how their bytes are checked. Nothing from Mojang is in this
 * repository: only the location of each icon inside Mojang's own client jar and the SHA-1 of its PNG.
 *
 * <p>The icons are not asset-index objects (textures ship inside the client jar), so the Potion HUD makes one HTTP
 * Range request for the 12,101-byte slice of the release 26.3 client jar that holds the 23 icons it can show, inflates
 * each icon from its zip entry and keeps it only if its SHA-1 matches. The jar URL is content-addressed (its own
 * SHA-1), so its bytes never change; if Mojang ever stops serving it, every check fails and the 1.8.9 icons stay.
 *
 * <p>Pinned 2026-11-14 from the version manifest: release 26.3, version JSON sha1
 * {@code 9f67e10ed013deeaad20ac3c800652765d493408}, client jar 41,483,720 bytes. The entries use a data descriptor,
 * so the compressed sizes come from the jar's central directory. {@code tools/check-effect-icons.py} re-checks all of
 * this against the live server.
 */
public final class EffectIcons {

    /** The release 26.3 client jar on Mojang's server. */
    public static final String JAR_URL =
            "https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar";
    /** First and last byte (inclusive) of the slice holding every icon's local header and data. */
    public static final long SPAN_FROM = 14132950L;
    public static final long SPAN_TO = 14145050L;
    public static final String PATH_PREFIX = "assets/minecraft/textures/mob_effect/";
    /** Largest inflated icon accepted (the real ones are under 1 KB). */
    public static final int MAX_PNG = 65536;

    private static final int LOCAL_HEADER_SIGNATURE = 0x04034b50;
    private static final int LOCAL_HEADER_SIZE = 30;
    private static final int DEFLATE = 8;

    /** One icon's zip entry in the jar: its name, where its local header starts, its compressed size, its PNG's SHA-1. */
    public static final class Entry {
        public final String name;
        public final long localHeaderOffset;
        public final int compressedSize;
        public final String sha1;

        public Entry(String name, long localHeaderOffset, int compressedSize, String sha1) {
            this.name = name;
            this.localHeaderOffset = localHeaderOffset;
            this.compressedSize = compressedSize;
            this.sha1 = sha1;
        }
    }

    /** The 23 icons a 1.8.9 effect can show, in jar order. */
    public static final List<Entry> TABLE = Collections.unmodifiableList(Arrays.asList(
            new Entry("absorption", 14132950L, 248, "d1dd5c36f46a8ca3f68881440135e9d62842719c"),
            new Entry("blindness", 14133605L, 227, "be62e2a053d6194d7fcccc5b6c0df39b31ec4ed3"),
            new Entry("fire_resistance", 14135207L, 246, "849e5ec989ba2ce68ee2ce4ec9b292338cdd888e"),
            new Entry("haste", 14135859L, 208, "81f4cbc4ba5eddc3474388b78cf86adf431e2953"),
            new Entry("health_boost", 14136159L, 203, "f991b79cba53b52e6ba358880752e9ac118813c8"),
            new Entry("hunger", 14136791L, 198, "d72566ab251172fb20a741bd0df5b3c7fdbca9ab"),
            new Entry("instant_damage", 14137421L, 157, "97589028d0d820c9d94907d1bb03bfccf4bbcaed"),
            new Entry("instant_health", 14137679L, 162, "710ac23de9c3c7f5f1139cf80e5e5d424b9f867a"),
            new Entry("invisibility", 14137942L, 213, "90c8c49f7aa4a36ad7f53aec148aca5e7fc2c41a"),
            new Entry("jump_boost", 14138254L, 175, "4f294db2a9d6237872a384d8328b9a80c1bddbca"),
            new Entry("mining_fatigue", 14139085L, 177, "37d559e71eb0a5c41ee07dc5572947af50847e79"),
            new Entry("nausea", 14139363L, 163, "0c53358c7d8468022d6e90fea439fc4c1c9cdb7b"),
            new Entry("night_vision", 14139619L, 188, "372fe19064ff30abc817ac118e041321589886fb"),
            new Entry("poison", 14140190L, 180, "ac775c8d3c30e4b3408dcabb674a644e0e0de538"),
            new Entry("regeneration", 14140893L, 183, "9c27fdfa78fc00f654686e8ea5a54cacf5b36d50"),
            new Entry("resistance", 14141175L, 214, "f85b19f537312a791155f4c0b5b479b5bcb105d0"),
            new Entry("saturation", 14141486L, 224, "ec91544cfe511463f023a88d3bd1f900b002a7e5"),
            new Entry("slowness", 14142079L, 203, "9bf8e501dec060624fa3bb179af49c04c4bd8cb9"),
            new Entry("speed", 14142377L, 228, "82baf20417fee9ea9b4f22c4862ec2388bf3264d"),
            new Entry("strength", 14142697L, 209, "b95a88af1b430a4d938901d62d7a0d3f431a3282"),
            new Entry("water_breathing", 14143585L, 157, "f0b5580a5d471fe3b9797a9894a56cd022ff1e79"),
            new Entry("weakness", 14143844L, 190, "e2eddf60255bb27bc0f2d32b9a04dda86c9c97d1"),
            new Entry("wither", 14144768L, 206, "f935b2dd12133b7e293db5364f8540eda2b78bcb")));

    /** Icon name by 1.8.9 potion id (1 = speed ... 23 = saturation). */
    private static final String[] NAME_BY_ID = {null,
            "speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health", "instant_damage",
            "jump_boost", "nausea", "regeneration", "resistance", "fire_resistance", "water_breathing",
            "invisibility", "blindness", "night_vision", "hunger", "weakness", "poison", "wither",
            "health_boost", "absorption", "saturation"};

    private EffectIcons() {
    }

    /** The modern icon's name for 1.8.9 potion id {@code potionId}, or null if it has none. */
    public static String nameFor(int potionId) {
        return potionId > 0 && potionId < NAME_BY_ID.length ? NAME_BY_ID[potionId] : null;
    }

    /**
     * The verified PNGs in {@code span}, the jar's bytes from offset {@code spanFrom}, by icon name. Each entry of
     * {@code table} is bounds-checked, must start with a local file header naming {@code PATH_PREFIX + name + ".png"}
     * stored with deflate, must inflate from exactly its compressed size to at most {@link #MAX_PNG} bytes, and its
     * SHA-1 must equal the pinned one. An entry that fails any check is left out; this never throws.
     */
    public static Map<String, byte[]> parse(byte[] span, long spanFrom, List<Entry> table) {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        if (span == null || table == null) return out;
        for (Entry e : table) {
            try {
                byte[] png = extract(span, spanFrom, e);
                if (png != null && e.sha1.equalsIgnoreCase(sha1Hex(png))) out.put(e.name, png);
            } catch (RuntimeException ignored) {
                // a malformed entry is skipped like any other failed check
            }
        }
        return out;
    }

    private static byte[] extract(byte[] span, long spanFrom, Entry e) {
        long rel = e.localHeaderOffset - spanFrom;
        if (rel < 0 || rel + LOCAL_HEADER_SIZE > span.length || e.compressedSize <= 0) return null;
        int at = (int) rel;
        if (u32(span, at) != LOCAL_HEADER_SIGNATURE) return null;
        if (u16(span, at + 8) != DEFLATE) return null;
        int nameLen = u16(span, at + 26);
        int extraLen = u16(span, at + 28);
        byte[] want = (PATH_PREFIX + e.name + ".png").getBytes(StandardCharsets.UTF_8);
        if (nameLen != want.length || at + LOCAL_HEADER_SIZE + nameLen > span.length) return null;
        for (int i = 0; i < nameLen; i++) if (span[at + LOCAL_HEADER_SIZE + i] != want[i]) return null;
        long data = (long) at + LOCAL_HEADER_SIZE + nameLen + extraLen;
        if (data + e.compressedSize > span.length) return null;
        return inflate(span, (int) data, e.compressedSize);
    }

    private static byte[] inflate(byte[] src, int off, int len) {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(src, off, len);
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
            byte[] buf = new byte[4096];
            boolean padded = false;
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsDictionary()) return null;
                    if (!inf.needsInput() || padded) return null;
                    // zlib may want one byte past a raw stream to see its end; never more than that
                    inf.setInput(new byte[1]);
                    padded = true;
                    continue;
                }
                if (out.size() + n > MAX_PNG) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException ex) {
            return null;
        } finally {
            inf.end();
        }
    }

    /** Lower-case hex SHA-1 of {@code bytes}. */
    public static String sha1Hex(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder b = new StringBuilder(40);
            for (byte x : d) b.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            return b.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 is missing", ex);
        }
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    private static int u32(byte[] b, int at) {
        return u16(b, at) | u16(b, at + 2) << 16;
    }
}
