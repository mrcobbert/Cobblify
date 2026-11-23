package com.bedwarsqol.rewind.export;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The game's side of the {@code cobblify-encode} protocol (Rewind Export PLAN Solution 4; HANDOFF "Phase 3"). Input
 * packets: {@code u8 kind, u32 little-endian length, payload}: {@value #KIND_FRAME} one BGRA frame, bottom row first;
 * {@value #KIND_SOUND} s16 little-endian stereo 48 kHz; {@value #KIND_FINISH} finish (empty). Output: one JSON object
 * per line: {@code {"ready":true,"protocol":1,...}}, {@code {"frames":n}}, {@code {"done":true,"frames":n}} or
 * {@code {"error":"code"[,"status":n]}}. Unknown keys are ignored; a line that is not one flat JSON object is no status.
 * Pure.
 */
public final class HelperProtocol {

    /** The protocol this build speaks; a helper that says another fails the export. */
    public static final int PROTOCOL = 1;
    public static final int KIND_FRAME = 1;
    public static final int KIND_SOUND = 2;
    public static final int KIND_FINISH = 3;

    /** One parsed status line. */
    public static final class Status {
        public enum Kind { READY, PROGRESS, DONE, ERROR, OTHER }

        public Kind kind = Kind.OTHER;
        /** {@code protocol}, -1 if absent. */
        public int protocol = -1;
        /** {@code frames}, -1 if absent. */
        public long frames = -1;
        /** {@code error}'s code, or null. */
        public String error;
        /** {@code status} (an OS error number), -1 if absent. */
        public int status = -1;
        /** {@code encoder} ({@code hardware}, {@code software}, ...), or null. */
        public String encoder;
    }

    private HelperProtocol() {
    }

    /** A packet's five header bytes. */
    public static byte[] header(int kind, int length) {
        return new byte[]{(byte) kind, (byte) length, (byte) (length >>> 8), (byte) (length >>> 16),
                (byte) (length >>> 24)};
    }

    /** A status line, or null when it is not one flat JSON object. */
    public static Status parse(String line) {
        if (line == null) {
            return null;
        }
        Parser p = new Parser(line.trim());
        try {
            Status s = new Status();
            boolean ready = false;
            boolean done = false;
            p.expect('{');
            p.ws();
            if (p.peek() != '}') {
                while (true) {
                    p.ws();
                    String key = p.string();
                    p.ws();
                    p.expect(':');
                    p.ws();
                    Object v = p.value();
                    if ("ready".equals(key)) {
                        ready = Boolean.TRUE.equals(v);
                    } else if ("done".equals(key)) {
                        done = Boolean.TRUE.equals(v);
                    } else if ("protocol".equals(key) && v instanceof Long) {
                        s.protocol = (int) (long) (Long) v;
                    } else if ("frames".equals(key) && v instanceof Long) {
                        s.frames = (Long) v;
                    } else if ("error".equals(key) && v instanceof String) {
                        s.error = (String) v;
                    } else if ("status".equals(key) && v instanceof Long) {
                        s.status = (int) (long) (Long) v;
                    } else if ("encoder".equals(key) && v instanceof String) {
                        s.encoder = (String) v;
                    }
                    p.ws();
                    if (p.peek() == ',') {
                        p.pos++;
                        continue;
                    }
                    break;
                }
            }
            p.expect('}');
            p.ws();
            if (p.pos != p.s.length()) {
                return null;
            }
            s.kind = s.error != null ? Status.Kind.ERROR : done ? Status.Kind.DONE : ready ? Status.Kind.READY
                    : s.frames >= 0 ? Status.Kind.PROGRESS : Status.Kind.OTHER;
            return s;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * {@code helper encode --out OUT --width W --height H --fps N --codec C --bitrate BPS}: one argument per element,
     * the output path whole whatever it holds (no shell ever sees it).
     */
    public static List<String> encodeArgs(String helper, File out, int width, int height, int fps, String codec,
                                          long bitsPerSecond) {
        return new ArrayList<String>(Arrays.asList(helper, "encode", "--out", out.getAbsolutePath(), "--width",
                String.valueOf(width), "--height", String.valueOf(height), "--fps", String.valueOf(fps), "--codec",
                codec, "--bitrate", String.valueOf(bitsPerSecond)));
    }

    /** A strict, small JSON reader: values are strings, whole numbers, booleans, null, and skipped arrays/objects. */
    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        char peek() {
            if (pos >= s.length()) {
                throw new IllegalArgumentException("end");
            }
            return s.charAt(pos);
        }

        void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("expected " + c);
            }
            pos++;
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    char e = peek();
                    pos++;
                    switch (e) {
                        case 'n':
                            b.append('\n');
                            break;
                        case 't':
                            b.append('\t');
                            break;
                        case 'r':
                            b.append('\r');
                            break;
                        case 'b':
                            b.append('\b');
                            break;
                        case 'f':
                            b.append('\f');
                            break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("escape");
                            }
                            b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            b.append(e);
                            break;
                    }
                } else {
                    b.append(c);
                }
            }
        }

        Object value() {
            char c = peek();
            if (c == '"') {
                return string();
            }
            if (c == '{' || c == '[') {
                skip();
                return null;
            }
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            int start = pos;
            if (c == '-') {
                pos++;
            }
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || ".eE+-".indexOf(s.charAt(pos)) >= 0)) {
                pos++;
            }
            String n = s.substring(start, pos);
            if (n.isEmpty() || n.equals("-")) {
                throw new IllegalArgumentException("value");
            }
            try {
                return Long.parseLong(n);
            } catch (NumberFormatException e) {
                Double.parseDouble(n); // a number, just not a whole one
                return n;
            }
        }

        /** Skips a nested array or object (strings inside may hold brackets). */
        void skip() {
            int depth = 0;
            do {
                char c = peek();
                if (c == '"') {
                    string();
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                }
                pos++;
            } while (depth > 0);
        }
    }
}
