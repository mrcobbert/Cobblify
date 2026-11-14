package com.bedwarsqol.hud;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * Synthetic stand-ins for the slice of Mojang's client jar: zip local entries built in the test, starting at
 * {@link EffectIcons#SPAN_FROM}, each holding made-up "PNG" bytes. Nothing here comes from Mojang.
 */
final class EffectIconFixtures {

    private EffectIconFixtures() {
    }

    /** A made-up icon file for {@code name}: a PNG signature and some name-dependent bytes. */
    static byte[] png(String name) {
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        byte[] body = ("synthetic icon " + name + " ").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + body.length * 4];
        System.arraycopy(head, 0, out, 0, head.length);
        for (int i = 0; i < 4; i++) System.arraycopy(body, 0, out, head.length + i * body.length, body.length);
        return out;
    }

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
    }

    /** A built slice and the table that describes it. */
    static final class Jar {
        final byte[] span;
        final List<EffectIcons.Entry> table;

        Jar(byte[] span, List<EffectIcons.Entry> table) {
            this.span = span;
            this.table = table;
        }
    }

    /** A slice holding one local entry per name, each with the icon {@link #png} makes for it. */
    static Jar jar(String... names) {
        byte[][] files = new byte[names.length][];
        for (int i = 0; i < names.length; i++) files[i] = png(names[i]);
        return jar(names, files);
    }

    /** As {@link #jar(String...)}, with the given file contents; the table pins each file's real SHA-1. */
    static Jar jar(String[] names, byte[][] files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<EffectIcons.Entry> table = new ArrayList<EffectIcons.Entry>();
        for (int i = 0; i < names.length; i++) {
            byte[] name = (EffectIcons.PATH_PREFIX + names[i] + ".png").getBytes(StandardCharsets.UTF_8);
            byte[] extra = {1, 2, 3, 4};
            byte[] data = deflate(files[i]);
            long offset = EffectIcons.SPAN_FROM + out.size();
            le32(out, 0x04034b50);
            le16(out, 20);
            le16(out, 0x0808);      // data descriptor: sizes below are 0, as in the real jar
            le16(out, 8);           // deflate
            le16(out, 0);
            le16(out, 0);
            le32(out, 0);
            le32(out, 0);
            le32(out, 0);
            le16(out, name.length);
            le16(out, extra.length);
            out.write(name, 0, name.length);
            out.write(extra, 0, extra.length);
            out.write(data, 0, data.length);
            out.write(new byte[16], 0, 16); // the data descriptor
            table.add(new EffectIcons.Entry(names[i], offset, data.length, EffectIcons.sha1Hex(files[i])));
        }
        return new Jar(out.toByteArray(), table);
    }

    private static void le16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream out, int v) {
        le16(out, v & 0xFFFF);
        le16(out, (v >>> 16) & 0xFFFF);
    }
}
