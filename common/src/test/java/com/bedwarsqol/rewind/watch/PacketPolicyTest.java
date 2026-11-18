package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.watch.PacketPolicy.Decision;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The packet policy table (PLAN section 2) over every protocol-47 clientbound id, with the rows marked
 * "(amendment 1)" that Phase 4c switched: 06, 09 and 1F pass to the camera, 2F and 30 pass only for window 0 (the
 * player inventory), and 2B reason 3 (game mode) is a session hook.
 */
public class PacketPolicyTest {

    private static final int[] PASS = {
            0x02, 0x03, 0x04, 0x05, 0x06, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F,
            0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2C, 0x33, 0x34, 0x35, 0x38,
            0x3B, 0x3C, 0x3D, 0x3E, 0x41, 0x42, 0x44, 0x45, 0x47, 0x49};
    private static final int[] BOUNDARY = {0x01, 0x07};
    private static final int[] DROP = {0x08, 0x2D, 0x2E, 0x31, 0x32, 0x36, 0x37, 0x39, 0x3A, 0x43, 0x48};
    /** The "(amendment 1)" rows decided by window id: S2F set slot, S30 window items. */
    private static final int[] BY_WINDOW = {0x2F, 0x30};
    /** Containers and screens: never reach the camera (amendment 1 keeps them dropped). */
    private static final int[] CONTAINERS = {0x2D, 0x2E, 0x31, 0x32, 0x36, 0x39};
    /** R1 never stores them; should one appear it is dropped. */
    private static final int[] NEVER_STORED = {0x00, 0x3F, 0x46};
    private static final int END = 0x40;
    private static final int CHANGE_GAME_STATE = 0x2B;

    private static Decision expected(int id) {
        if (contains(PASS, id)) {
            return Decision.PASS;
        }
        if (contains(BOUNDARY, id)) {
            return Decision.BOUNDARY;
        }
        if (contains(DROP, id) || contains(NEVER_STORED, id)) {
            return Decision.DROP;
        }
        if (id == END) {
            return Decision.END;
        }
        throw new AssertionError("id 0x" + Integer.toHexString(id) + " is in no row of the table");
    }

    private static boolean contains(int[] ids, int id) {
        for (int i : ids) {
            if (i == id) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void everyClientboundIdHasItsRow() {
        for (int id = 0x00; id <= PacketPolicy.MAX_PACKET_ID; id++) {
            if (id == CHANGE_GAME_STATE || contains(BY_WINDOW, id)) {
                continue; // by reason or by window, below
            }
            Decision want = expected(id);
            String label = "0x" + Integer.toHexString(id);
            assertEquals(label, want, PacketPolicy.decide(id, PacketPolicy.NO_BYTE, PacketPolicy.NO_BYTE));
            // The decision of an id never depends on body bytes except where the table says so.
            assertEquals(label + " with body", want, PacketPolicy.decide(id, 0, 0));
            assertEquals(label + " with body 255", want, PacketPolicy.decide(id, 255, 7));
        }
    }

    @Test
    public void changeGameStateDecidesByReason() {
        for (int reason = 0; reason <= 10; reason++) {
            Decision want = reason == 4 || reason == 5 // credits screen, demo screen
                    ? Decision.DROP
                    : reason == 3 ? Decision.SESSION_HOOK // game mode: the session decides the controller's type
                    : Decision.PASS;
            assertEquals("reason " + reason, want, PacketPolicy.decide(CHANGE_GAME_STATE, reason, 0));
        }
        assertEquals("no reason byte", Decision.DROP,
                PacketPolicy.decide(CHANGE_GAME_STATE, PacketPolicy.NO_BYTE, PacketPolicy.NO_BYTE));
    }

    @Test
    public void healthHeldSlotAndExperiencePassToTheCamera() {
        for (int id : new int[]{0x06, 0x09, 0x1F}) {
            for (int body = 0; body <= 255; body += 51) {
                assertEquals("0x" + Integer.toHexString(id) + " body " + body, Decision.PASS,
                        PacketPolicy.decide(id, body, body));
            }
            assertEquals(Decision.PASS, PacketPolicy.decide(id, PacketPolicy.NO_BYTE, PacketPolicy.NO_BYTE));
        }
    }

    @Test
    public void slotAndWindowItemsPassOnlyForThePlayerInventory() {
        for (int id : BY_WINDOW) {
            String label = "0x" + Integer.toHexString(id);
            assertEquals(label + " window 0", Decision.PASS, PacketPolicy.decide(id, 0, 0));
            assertEquals(label + " window 0, any second byte", Decision.PASS, PacketPolicy.decide(id, 0, 255));
            for (int window = 1; window <= 100; window++) {
                assertEquals(label + " window " + window, Decision.DROP, PacketPolicy.decide(id, window, 0));
            }
            assertEquals(label + " window 255", Decision.DROP, PacketPolicy.decide(id, 255, 0));
            assertEquals(label + " no window byte", Decision.DROP,
                    PacketPolicy.decide(id, PacketPolicy.NO_BYTE, PacketPolicy.NO_BYTE));
        }
        // S2F's cursor window is -1, a signed byte on the wire.
        assertEquals(Decision.DROP, PacketPolicy.decide(new byte[]{0x2F, (byte) 0xFF, 0, 0, (byte) 0xFF, (byte) 0xFF},
                0, 6));
        assertEquals(Decision.PASS, PacketPolicy.decide(new byte[]{0x2F, 0, 0, 36, (byte) 0xFF, (byte) 0xFF}, 0, 6));
        assertEquals(Decision.PASS, PacketPolicy.decide(new byte[]{0x30, 0, 0, 0}, 0, 4));
        assertEquals(Decision.DROP, PacketPolicy.decide(new byte[]{0x30, 3, 0, 0}, 0, 4));
        assertEquals(Decision.DROP, PacketPolicy.decide(new byte[]{0x2F}, 0, 1));
    }

    @Test
    public void gameModeIsASessionHookAndContainersStillDrop() {
        assertEquals(Decision.SESSION_HOOK, PacketPolicy.decide(CHANGE_GAME_STATE, 3, 0));
        assertEquals(Decision.SESSION_HOOK, PacketPolicy.decide(new byte[]{0x2B, 3, 0x40, 0x40, 0, 0}, 0, 6));
        for (int id : CONTAINERS) {
            for (int body = 0; body <= 255; body += 51) {
                assertEquals("0x" + Integer.toHexString(id) + " body " + body, Decision.DROP,
                        PacketPolicy.decide(id, body, 0));
            }
        }
    }

    @Test
    public void aRecordedKickEndsTheTimeline() {
        assertEquals(Decision.END, PacketPolicy.decide(END, PacketPolicy.NO_BYTE, PacketPolicy.NO_BYTE));
        assertEquals(Decision.END, PacketPolicy.decide(new byte[]{0x40, 2, '{', '}'}, 0, 4));
    }

    @Test
    public void unknownIdsDrop() {
        assertEquals(Decision.DROP, PacketPolicy.decide(-1, 0, 0));
        assertEquals(Decision.DROP, PacketPolicy.decide(0x4A, 0, 0));
        assertEquals(Decision.DROP, PacketPolicy.decide(0x100, 0, 0));
    }

    @Test
    public void decidesFromStoredRecordBytes() {
        // Record = varint packet id + body; the policy peeks at most two body bytes.
        assertEquals(Decision.PASS, PacketPolicy.decide(new byte[]{9, 9, 0x2B, 1, 0, 0, 0, 0}, 2, 6));
        assertEquals(Decision.DROP, PacketPolicy.decide(new byte[]{0x2B, 4, 0, 0, 0, 0}, 0, 6));
        assertEquals(Decision.DROP, PacketPolicy.decide(new byte[]{0x2B, 5, 0, 0, 0, 0}, 0, 6));
        assertEquals(Decision.DROP, PacketPolicy.decide(new byte[]{0x2B}, 0, 1));
        assertEquals(Decision.BOUNDARY, PacketPolicy.decide(new byte[]{0x01, 0, 0, 0, 42}, 0, 5));
        assertEquals(Decision.BOUNDARY, PacketPolicy.decide(new byte[]{0x07, 0, 0, 0, 0}, 0, 5));
        assertEquals(Decision.PASS, PacketPolicy.decide(new byte[]{0x21}, 0, 1));
        assertEquals(Decision.DROP, PacketPolicy.decide(new byte[]{0x08, 1, 2}, 0, 3));
        // A two-byte varint id (128) is outside protocol 47's clientbound range.
        assertEquals(Decision.DROP, PacketPolicy.decide(new byte[]{(byte) 0x80, 0x01, 0}, 0, 3));
        // Empty or truncated records.
        assertEquals(Decision.DROP, PacketPolicy.decide(new byte[0], 0, 0));
        assertEquals(Decision.DROP, PacketPolicy.decide(new byte[]{(byte) 0x80}, 0, 1));
    }
}
