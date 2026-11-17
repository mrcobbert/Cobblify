package com.bedwarsqol.rewind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Builds the source-0 carry-over HEADER packets: protocol-47 (Minecraft 1.8.9) play packets
 * synthesized from plain values the client thread copied at a world boundary. Each packet is
 * {@code varint packetId} + body, laid out exactly as vanilla's {@code writePacketData} writes it
 * (big-endian numbers, LEB128 varints, strings as varint UTF-8 length + bytes).
 *
 * <p>Order (see {@link #encode}): S01, one S38 ADD_PLAYER with every tab entry, S3B per objective,
 * S3E per team with its members, S3C per score, S3D per occupied display slot (0-18), S39, S09.
 * Source 0 never contains an S47 (tab header/footer): that is applied on the Netty thread, so the
 * capture handler freezes it in stream order instead (HEADER source 1).
 */
public final class CarryOverEncoder {

    public static final int S01_JOIN_GAME = 0x01;
    public static final int S09_HELD_ITEM = 0x09;
    public static final int S38_PLAYER_LIST = 0x38;
    public static final int S39_ABILITIES = 0x39;
    public static final int S3B_OBJECTIVE = 0x3B;
    public static final int S3C_SCORE = 0x3C;
    public static final int S3D_DISPLAY = 0x3D;
    public static final int S3E_TEAMS = 0x3E;

    /** Scoreboard display slots in 1.8.9: list, sidebar, below name, 16 team-colour sidebars. */
    public static final int DISPLAY_SLOTS = 19;

    /** Vanilla {@code PacketBuffer.writeString} refuses more than this many UTF-8 bytes. */
    private static final int MAX_STRING_BYTES = 32767;

    private static final int PLAYER_LIST_ADD = 0;
    private static final int OBJECTIVE_CREATE = 0;
    private static final int TEAM_CREATE = 0;
    private static final int SCORE_CHANGE = 0;

    private CarryOverEncoder() {
    }

    // ------------------------------------------------------------------ plain values

    /** One game-profile property (skin textures and the like); {@code signature} may be null. */
    public static final class Property {
        public final String name;
        public final String value;
        public final String signature;

        public Property(String name, String value, String signature) {
            this.name = name;
            this.value = value;
            this.signature = signature;
        }
    }

    /** One tab-list entry. {@code displayNameJson} is the chat-component JSON, or null for none. */
    public static final class TabEntry {
        public final long uuidMost;
        public final long uuidLeast;
        public final String name;
        public final List<Property> properties;
        /** {@code WorldSettings.GameType} id; -1 is NOT_SET. */
        public final int gameType;
        public final int ping;
        public final String displayNameJson;

        public TabEntry(long uuidMost, long uuidLeast, String name, List<Property> properties, int gameType,
                        int ping, String displayNameJson) {
            this.uuidMost = uuidMost;
            this.uuidLeast = uuidLeast;
            this.name = name;
            this.properties = properties == null ? Collections.<Property>emptyList() : properties;
            this.gameType = gameType;
            this.ping = ping;
            this.displayNameJson = displayNameJson;
        }
    }

    /** One scoreboard objective; {@code renderType} is "integer" or "hearts". */
    public static final class Objective {
        public final String name;
        public final String displayName;
        public final String renderType;

        public Objective(String name, String displayName, String renderType) {
            this.name = name;
            this.displayName = displayName;
            this.renderType = renderType;
        }
    }

    /**
     * One team. {@code friendlyFlags}: 1 friendly fire, 2 see friendly invisibles.
     * {@code nameTagVisibility} is the internal name ("always", "never", ...). {@code color} is the
     * chat colour index, -1 for none.
     */
    public static final class Team {
        public final String name;
        public final String displayName;
        public final String prefix;
        public final String suffix;
        public final int friendlyFlags;
        public final String nameTagVisibility;
        public final int color;
        public final List<String> members;

        public Team(String name, String displayName, String prefix, String suffix, int friendlyFlags,
                    String nameTagVisibility, int color, List<String> members) {
            this.name = name;
            this.displayName = displayName;
            this.prefix = prefix;
            this.suffix = suffix;
            this.friendlyFlags = friendlyFlags;
            this.nameTagVisibility = nameTagVisibility;
            this.color = color;
            this.members = members == null ? Collections.<String>emptyList() : members;
        }
    }

    /** One score: {@code owner} on {@code objective}. */
    public static final class Score {
        public final String owner;
        public final String objective;
        public final int value;

        public Score(String owner, String objective, int value) {
            this.owner = owner;
            this.objective = objective;
            this.value = value;
        }
    }

    /** Everything source 0 carries, as copied on the client thread at the boundary. */
    public static final class Snapshot {
        public int entityId;
        /** Game type id, with bit 3 set for hardcore, as S01 carries it. */
        public int gameType;
        public int dimension;
        public int difficulty;
        public int maxPlayers;
        public String levelType;
        public boolean reducedDebug;

        public final List<TabEntry> tab = new ArrayList<TabEntry>();
        public final List<Objective> objectives = new ArrayList<Objective>();
        public final List<Team> teams = new ArrayList<Team>();
        public final List<Score> scores = new ArrayList<Score>();
        /** Objective name per display slot 0-18, null for an empty slot. */
        public final String[] displaySlots = new String[DISPLAY_SLOTS];

        public boolean invulnerable;
        public boolean flying;
        public boolean allowFlying;
        public boolean creative;
        public float flySpeed;
        public float walkSpeed;
        public int heldSlot;
    }

    // ------------------------------------------------------------------ encoding

    /** All source-0 packets for {@code s}, in feed order. Never contains an S47. */
    public static List<byte[]> encode(Snapshot s) {
        List<byte[]> out = new ArrayList<byte[]>();
        out.add(joinGame(s.entityId, s.gameType, s.dimension, s.difficulty, s.maxPlayers, s.levelType, s.reducedDebug));
        out.add(playerListAdd(s.tab));
        for (Objective o : s.objectives) out.add(objective(o));
        for (Team t : s.teams) out.add(team(t));
        for (Score sc : s.scores) out.add(score(sc));
        for (int slot = 0; slot < s.displaySlots.length && slot < DISPLAY_SLOTS; slot++) {
            if (s.displaySlots[slot] != null) out.add(displaySlot(slot, s.displaySlots[slot]));
        }
        out.add(abilities(s.invulnerable, s.flying, s.allowFlying, s.creative, s.flySpeed, s.walkSpeed));
        out.add(heldSlot(s.heldSlot));
        return out;
    }

    /** S01: int entity id, u8 game type, i8 dimension, u8 difficulty, u8 max players, level type, reduced debug. */
    public static byte[] joinGame(int entityId, int gameType, int dimension, int difficulty, int maxPlayers,
                                  String levelType, boolean reducedDebug) {
        Buf b = new Buf(S01_JOIN_GAME);
        b.writeInt(entityId);
        b.writeByte(gameType);
        b.writeByte(dimension);
        b.writeByte(difficulty);
        b.writeByte(maxPlayers);
        b.writeString(levelType);
        b.writeBoolean(reducedDebug);
        return b.toByteArray();
    }

    /**
     * S38 ADD_PLAYER: varint action 0, varint count, then per entry UUID (two longs), name, varint
     * property count, properties (name, value, bool signed, [signature]), varint game type, varint
     * ping, bool has display name, [display name JSON].
     */
    public static byte[] playerListAdd(List<TabEntry> entries) {
        Buf b = new Buf(S38_PLAYER_LIST);
        b.writeVarInt(PLAYER_LIST_ADD);
        List<TabEntry> list = entries == null ? Collections.<TabEntry>emptyList() : entries;
        b.writeVarInt(list.size());
        for (TabEntry e : list) {
            b.writeLong(e.uuidMost);
            b.writeLong(e.uuidLeast);
            b.writeString(e.name);
            b.writeVarInt(e.properties.size());
            for (Property p : e.properties) {
                b.writeString(p.name);
                b.writeString(p.value);
                if (p.signature != null) {
                    b.writeBoolean(true);
                    b.writeString(p.signature);
                } else {
                    b.writeBoolean(false);
                }
            }
            b.writeVarInt(e.gameType);
            b.writeVarInt(e.ping);
            if (e.displayNameJson != null) {
                b.writeBoolean(true);
                b.writeString(e.displayNameJson);
            } else {
                b.writeBoolean(false);
            }
        }
        return b.toByteArray();
    }

    /** S3B create: name, i8 mode 0, display name, render type. */
    public static byte[] objective(Objective o) {
        Buf b = new Buf(S3B_OBJECTIVE);
        b.writeString(o.name);
        b.writeByte(OBJECTIVE_CREATE);
        b.writeString(o.displayName);
        b.writeString(o.renderType);
        return b.toByteArray();
    }

    /**
     * S3E create: name, i8 mode 0, display name, prefix, suffix, i8 friendly flags, name tag
     * visibility, i8 colour, varint member count, members.
     */
    public static byte[] team(Team t) {
        Buf b = new Buf(S3E_TEAMS);
        b.writeString(t.name);
        b.writeByte(TEAM_CREATE);
        b.writeString(t.displayName);
        b.writeString(t.prefix);
        b.writeString(t.suffix);
        b.writeByte(t.friendlyFlags);
        b.writeString(t.nameTagVisibility);
        b.writeByte(t.color);
        b.writeVarInt(t.members.size());
        for (String m : t.members) b.writeString(m);
        return b.toByteArray();
    }

    /** S3C change: owner, varint action 0, objective, varint value. */
    public static byte[] score(Score s) {
        Buf b = new Buf(S3C_SCORE);
        b.writeString(s.owner);
        b.writeVarInt(SCORE_CHANGE);
        b.writeString(s.objective);
        b.writeVarInt(s.value);
        return b.toByteArray();
    }

    /** S3D: i8 slot, objective name. */
    public static byte[] displaySlot(int slot, String objective) {
        Buf b = new Buf(S3D_DISPLAY);
        b.writeByte(slot);
        b.writeString(objective);
        return b.toByteArray();
    }

    /** S39: i8 flags (1 invulnerable, 2 flying, 4 allow flying, 8 creative), f32 fly speed, f32 walk speed. */
    public static byte[] abilities(boolean invulnerable, boolean flying, boolean allowFlying, boolean creative,
                                   float flySpeed, float walkSpeed) {
        int flags = 0;
        if (invulnerable) flags |= 1;
        if (flying) flags |= 2;
        if (allowFlying) flags |= 4;
        if (creative) flags |= 8;
        Buf b = new Buf(S39_ABILITIES);
        b.writeByte(flags);
        b.writeFloat(flySpeed);
        b.writeFloat(walkSpeed);
        return b.toByteArray();
    }

    /** S09: i8 slot. */
    public static byte[] heldSlot(int slot) {
        Buf b = new Buf(S09_HELD_ITEM);
        b.writeByte(slot);
        return b.toByteArray();
    }

    // ------------------------------------------------------------------ writer

    /** Growable big-endian writer; one per packet (client thread, once per world switch). */
    private static final class Buf {
        private byte[] data = new byte[64];
        private int size;

        Buf(int packetId) {
            writeVarInt(packetId);
        }

        private void ensure(int extra) {
            if (size + extra > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, size + extra));
        }

        void writeByte(int v) {
            ensure(1);
            data[size++] = (byte) v;
        }

        void writeBoolean(boolean v) {
            writeByte(v ? 1 : 0);
        }

        void writeInt(int v) {
            ensure(4);
            data[size++] = (byte) (v >>> 24);
            data[size++] = (byte) (v >>> 16);
            data[size++] = (byte) (v >>> 8);
            data[size++] = (byte) v;
        }

        void writeLong(long v) {
            writeInt((int) (v >>> 32));
            writeInt((int) v);
        }

        void writeFloat(float v) {
            writeInt(Float.floatToRawIntBits(v));
        }

        /** Protocol varint (LEB128 over the unsigned 32-bit value, so -1 is five bytes). */
        void writeVarInt(int v) {
            while ((v & ~0x7F) != 0) {
                writeByte((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            writeByte(v);
        }

        /** Varint UTF-8 length + bytes; null is written as the empty string. */
        void writeString(String s) {
            byte[] bytes = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_STRING_BYTES) throw new IllegalArgumentException("string too long for protocol 47");
            writeVarInt(bytes.length);
            ensure(bytes.length);
            System.arraycopy(bytes, 0, data, size, bytes.length);
            size += bytes.length;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
