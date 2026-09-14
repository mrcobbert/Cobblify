package com.bedwarsqol.feature;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Wire format of the two Hypixel Mod API plugin messages this mod speaks, so the map name comes from
 * the server itself instead of a {@code /locraw} chat round-trip. Pure byte codec (no Minecraft
 * types); the platform adapters wrap it in C17/S3F custom-payload packets.
 *
 * <p>Protocol (github.com/HypixelDev/ModAPI, 1.0.x): on join the server sends {@code hypixel:hello}.
 * The client answers with {@code hypixel:register} — {@code varint version=1, varint count,
 * [string identifier, varint version]…} — naming the event packets it wants. The server then pushes
 * {@code hyevent:location} on every server switch: {@code bool success, varint version=1, string
 * serverName, optional string serverType, optional string lobbyName, optional string mode, optional
 * string map} where an optional is a {@code bool present} prefix. A {@code success=false} frame
 * carries a varint error id instead. Strings are UTF-8 with a varint byte length.
 *
 * <p>Other clients (Lunar itself, the official Forge Mod API mod) may register the same event; the
 * server sends one packet per registration and every handler sees each, so a second registration
 * is harmless and versions we do not understand are ignored.
 */
public final class HypixelLocationCodec {

    public static final String HELLO_CHANNEL = "hypixel:hello";
    public static final String REGISTER_CHANNEL = "hypixel:register";
    public static final String LOCATION_CHANNEL = "hyevent:location";

    private static final int REGISTER_VERSION = 1;
    private static final int LOCATION_VERSION = 1;
    private static final int MAX_STRING = 32767;

    private HypixelLocationCodec() {
    }

    /** A decoded {@code hyevent:location} event. Optional fields are {@code null} when absent. */
    public static final class Location {
        public final String serverName;
        public final String serverType;
        public final String lobbyName;
        public final String mode;
        public final String map;

        public Location(String serverName, String serverType, String lobbyName, String mode, String map) {
            this.serverName = serverName;
            this.serverType = serverType;
            this.lobbyName = lobbyName;
            this.mode = mode;
            this.map = map;
        }

        @Override
        public String toString() {
            return "Location{server=" + serverName + ", type=" + serverType + ", lobby=" + lobbyName
                    + ", mode=" + mode + ", map=" + map + "}";
        }
    }

    /** Payload of the {@code hypixel:register} message subscribing to {@code hyevent:location} v1. */
    public static byte[] registerPayload() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(24);
        writeVarInt(out, REGISTER_VERSION);
        writeVarInt(out, 1);
        writeString(out, LOCATION_CHANNEL);
        writeVarInt(out, LOCATION_VERSION);
        return out.toByteArray();
    }

    /**
     * Decode a {@code hyevent:location} payload. {@code null} for an error frame, a version we do not
     * speak, or a malformed buffer — never throws.
     */
    public static Location decodeLocation(byte[] data) {
        if (data == null) return null;
        try {
            Reader r = new Reader(data);
            if (!r.bool()) return null;                 // error frame: varint reason follows
            if (r.varInt() != LOCATION_VERSION) return null;
            String server = r.string();
            String type = r.bool() ? r.string() : null;
            String lobby = r.bool() ? r.string() : null;
            String mode = r.bool() ? r.string() : null;
            String map = r.bool() ? r.string() : null;
            return new Location(server, type, lobby, mode, map);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    static void writeString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /** Minimal bounds-checked reader; any overrun throws and {@link #decodeLocation} maps it to null. */
    static final class Reader {
        private final byte[] buf;
        private int pos;

        Reader(byte[] buf) {
            this.buf = buf;
        }

        boolean bool() {
            return byteAt() != 0;
        }

        int byteAt() {
            if (pos >= buf.length) throw new IllegalStateException("eof");
            return buf[pos++] & 0xFF;
        }

        int varInt() {
            int result = 0;
            int shift = 0;
            int b;
            do {
                if (shift > 28) throw new IllegalStateException("varint too big");
                b = byteAt();
                result |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return result;
        }

        String string() {
            int len = varInt();
            if (len < 0 || len > MAX_STRING * 3 || pos + len > buf.length) throw new IllegalStateException("bad string");
            String s = new String(buf, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }
    }
}
