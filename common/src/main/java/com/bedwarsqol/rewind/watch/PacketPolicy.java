package com.bedwarsqol.rewind.watch;

/**
 * What playback does with each stored clientbound packet (protocol 47), decided by packet id and at most two body
 * bytes, the same in every mode. Pure.
 *
 * <table>
 *   <caption>Decisions</caption>
 *   <tr><td>{@link Decision#PASS}</td><td>02-05, 0A-1E, 20-2A, 2C, 33-35, 38, 3B-3E, 41, 42, 44, 45, 47, 49 (world,
 *   entities, chat, sidebar, tab, titles; 27 too: its motion for {@code thePlayer} lands on the inert camera)</td></tr>
 *   <tr><td>{@link Decision#BOUNDARY}</td><td>01, 07: passed, then the session's boundary hook</td></tr>
 *   <tr><td>{@link Decision#DROP}</td><td>08, 2D, 2E, 31, 32, 36, 37, 39, 3A, 43, 48 (they act on the client player,
 *   open or close screens, or would send or download); 2B reasons 4 (credits) and 5 (demo); the never-stored 00, 3F,
 *   46; any unknown id</td></tr>
 *   <tr><td>{@link Decision#END}</td><td>40: a recorded kick ends the timeline instead of disconnecting
 *   playback</td></tr>
 * </table>
 *
 * <p><b>Rev 3 amendment 1.</b> The rows that will put the recorded player's state on the camera (06, 09, 1F; 2F and
 * 30 for window 0; 2B reason 3 as a {@link Decision#SESSION_HOOK}) still {@link Decision#DROP} here; Phase 4c switches
 * them, so no phase in between leaves the camera half updated. {@link TickFeeder} already dispatches
 * {@link Decision#SESSION_HOOK} to {@link PacketSink#sessionHook}.
 */
public final class PacketPolicy {

    public enum Decision {
        /** Hand to vanilla's handler ({@link PacketSink#apply}). */
        PASS,
        /** S01 / S07: hand to vanilla, then {@link PacketSink#boundary} when that succeeded. */
        BOUNDARY,
        /** Never reaches vanilla. */
        DROP,
        /** Not passed to vanilla; the session acts on it ({@link PacketSink#sessionHook}). */
        SESSION_HOOK,
        /** The recorded connection ended here: no record after it is fed. */
        END
    }

    /** "No such body byte" for {@link #decide(int, int, int)}. */
    public static final int NO_BYTE = -1;
    /** The highest protocol-47 clientbound packet id. */
    public static final int MAX_PACKET_ID = 0x49;

    private static final int CHANGE_GAME_STATE = 0x2B;
    private static final int REASON_GAME_MODE = 3;
    private static final int REASON_CREDITS = 4;
    private static final int REASON_DEMO = 5;

    private static final Decision[] TABLE = new Decision[MAX_PACKET_ID + 1];

    static {
        for (int id = 0; id <= MAX_PACKET_ID; id++) {
            TABLE[id] = Decision.PASS;
        }
        TABLE[0x01] = Decision.BOUNDARY;
        TABLE[0x07] = Decision.BOUNDARY;
        int[] drop = {0x08, 0x2D, 0x2E, 0x31, 0x32, 0x36, 0x37, 0x39, 0x3A, 0x43, 0x48};
        for (int id : drop) {
            TABLE[id] = Decision.DROP;
        }
        // (amendment 1) health, held slot, XP, inventory slots and window items: Phase 4c passes them to the camera.
        int[] amendment = {0x06, 0x09, 0x1F, 0x2F, 0x30};
        for (int id : amendment) {
            TABLE[id] = Decision.DROP;
        }
        // Never stored by R1 (keep-alive, custom payload, compression).
        TABLE[0x00] = Decision.DROP;
        TABLE[0x3F] = Decision.DROP;
        TABLE[0x46] = Decision.DROP;
        TABLE[0x40] = Decision.END;
        TABLE[CHANGE_GAME_STATE] = null; // by reason
    }

    private PacketPolicy() {
    }

    /**
     * @param packetId the varint packet id
     * @param body0    the first body byte as 0-255, or {@link #NO_BYTE}
     * @param body1    the second body byte as 0-255, or {@link #NO_BYTE} (unused until Phase 4c)
     */
    public static Decision decide(int packetId, int body0, int body1) {
        if (packetId < 0 || packetId > MAX_PACKET_ID) {
            return Decision.DROP;
        }
        if (packetId == CHANGE_GAME_STATE) {
            return changeGameState(body0);
        }
        return TABLE[packetId];
    }

    /** {@link #decide(int, int, int)} for a stored record: varint packet id + body at {@code [off, off + len)}. */
    public static Decision decide(byte[] buf, int off, int len) {
        int id = 0;
        int idLength = 0;
        int end = off + Math.min(len, 5);
        boolean complete = false;
        for (int i = off, shift = 0; i < end; i++, shift += 7) {
            int x = buf[i];
            id |= (x & 0x7F) << shift;
            idLength++;
            if ((x & 0x80) == 0) {
                complete = true;
                break;
            }
        }
        if (!complete) {
            return Decision.DROP;
        }
        int body0 = len > idLength ? buf[off + idLength] & 0xFF : NO_BYTE;
        int body1 = len > idLength + 1 ? buf[off + idLength + 1] & 0xFF : NO_BYTE;
        return decide(id, body0, body1);
    }

    private static Decision changeGameState(int reason) {
        switch (reason) {
            case NO_BYTE:
            case REASON_CREDITS:
            case REASON_DEMO:
            case REASON_GAME_MODE: // (amendment 1) a session hook from Phase 4c
                return Decision.DROP;
            default:
                return Decision.PASS;
        }
    }
}
