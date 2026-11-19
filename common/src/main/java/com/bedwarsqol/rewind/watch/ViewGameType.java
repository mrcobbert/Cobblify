package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;

/**
 * Which game type the playback controller takes (PLAN section 4.1, Rev 3 amendment 1). Vanilla's HUD and first-person
 * hand read the controller: the hotbar or the spectator bar, hearts, hunger and armour, the XP bar, the held item's
 * name and the hand itself.
 *
 * <ul>
 *   <li><b>Your view:</b> the recorded game mode, from S01, S07 and S2B reason 3 in their order of arrival (after a
 *       final death the spectator bar shows as you saw it); survival before any is recorded.</li>
 *   <li><b>Free camera:</b> always SPECTATOR, so no hand, hotbar, hearts or bars.</li>
 *   <li><b>Another player's view</b> (Phase 14): always CREATIVE, so their hand and no hearts, hunger or XP bar
 *       ({@link #forView(ViewState.View)}).</li>
 * </ul>
 * A view switch is only the other argument to {@link #forView}, so it takes effect at once. The recorded mode is
 * rebuilt by the feed, so after a seek it is the mode recorded at the target.
 *
 * <p>Ids are vanilla's {@code WorldSettings.GameType} ids; an unknown id reads as survival, as vanilla's
 * {@code getByID} does. Packets are stored records: varint packet id + body. Pure.
 */
public final class ViewGameType {

    public static final int NOT_SET = -1;
    public static final int SURVIVAL = 0;
    public static final int CREATIVE = 1;
    public static final int ADVENTURE = 2;
    public static final int SPECTATOR = 3;

    private static final int JOIN_GAME = 0x01;
    private static final int RESPAWN = 0x07;
    private static final int CHANGE_GAME_STATE = 0x2B;
    private static final int REASON_GAME_MODE = 3;
    /** S01's hardcore bit in the game type byte. */
    private static final int HARDCORE = 8;

    private int recorded = NOT_SET;

    /** Nothing recorded (a new connection). */
    public void reset() {
        recorded = NOT_SET;
    }

    /**
     * Reads the game mode from an S01, S07 or S2B reason 3 record at {@code buf[off, off + len)}.
     *
     * @return true if the record set the recorded mode
     */
    public boolean onPacket(byte[] buf, int off, int len) {
        if (len < 1) {
            return false;
        }
        int id = buf[off] & 0xFF; // all three ids are one-byte varints
        switch (id) {
            case JOIN_GAME: // int entityId, u8 gameType (| hardcore)
                if (len < 6) {
                    return false;
                }
                recorded = byId((buf[off + 5] & 0xFF) & ~HARDCORE);
                return true;
            case RESPAWN: // int dimension, u8 difficulty, u8 gameType
                if (len < 7) {
                    return false;
                }
                recorded = byId(buf[off + 6] & 0xFF);
                return true;
            case CHANGE_GAME_STATE: // u8 reason, f32 value
                if (len < 6 || (buf[off + 1] & 0xFF) != REASON_GAME_MODE) {
                    return false;
                }
                recorded = byId((int) Math.floor(RewindFormat.getFloat(buf, off + 2) + 0.5f));
                return true;
            default:
                return false;
        }
    }

    /** The recorded mode, or {@link #NOT_SET} before any. */
    public int recorded() {
        return recorded;
    }

    /** The controller's game type for a view: the recorded mode in your view, SPECTATOR in the free camera. */
    public int forView(boolean yourView) {
        if (!yourView) {
            return SPECTATOR;
        }
        return recorded == NOT_SET ? SURVIVAL : recorded;
    }

    /**
     * The controller's game type for {@code view}: your view the recorded mode, the free camera SPECTATOR, another
     * player's view CREATIVE (Phase 14: vanilla draws the first-person hand, and no hearts, hunger, armour or XP bar,
     * since those need survival or adventure; the hotbar, drawn for every mode but spectator, is cancelled by
     * {@code GuiIngameRewindMixin}).
     */
    public int forView(ViewState.View view) {
        if (view == ViewState.View.RIDDEN) {
            return CREATIVE;
        }
        return forView(view == ViewState.View.YOU);
    }

    static int byId(int id) {
        return id >= SURVIVAL && id <= SPECTATOR ? id : SURVIVAL;
    }
}
