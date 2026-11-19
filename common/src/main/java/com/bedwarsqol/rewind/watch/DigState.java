package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;

/**
 * Your own dig state during playback (PLAN Phase 11, "C. Playback of v2 data"; TASK C1), from the recorded DIG records.
 *
 * <p>Live, while the button is held on a block, {@code PlayerControllerMP} calls
 * {@code sendBlockBreakProgress(yourId, block, stage)} on every damaging tick (stage {@code (int)(damage x 10) - 1},
 * so -1 while the damage is under 0.1, which removes the entry) and {@code Minecraft.sendClickBlockToController} adds
 * hit particles on the hit face; a stop ({@code resetBlockRemoving}) or the break sends -1. The recorder writes a DIG
 * at the end of each tick whose dig state changed (hitting flag, block, face, stage); a stop keeps the last block and
 * face, and each new world starts from "not hitting".
 *
 * <p>Use: {@link #record} each DIG in its tick's local phase, then {@link #tick} once per world tick and read what
 * that tick calls: {@link #crackCall()} with {@link #crackPos()}/{@link #crackStage()} (the render global's crack;
 * every tick while hitting, as vanilla refreshes it, and one -1 when hitting ends with a crack shown) and
 * {@link #hit()} with {@link #hitPos()}/{@link #hitFace()} (hit particles, every tick while hitting on a face 0-5).
 * {@link #takeShown()} hands over a crack still shown (to clear on a restart, a new world or the exit).
 *
 * <p>The state is a function of the records fed so far, so straight play and a seek's catch-up (which feeds the same
 * records to the same ticks) reach the same state. Pure; one thread; allocates nothing.
 */
public final class DigState {

    /** No hit face. */
    public static final int NO_FACE = -1;

    private boolean hitting;
    private long pos;
    private int face = NO_FACE;
    private int stage = -1;

    /** A crack entry is shown for the breaker (the last call sent a stage 0-9). */
    private boolean shown;
    private long shownPos;

    private boolean crackCall;
    private long crackPos;
    private int crackStage = -1;
    private boolean hit;

    /** Not hitting, nothing shown, nothing to call (a restart, a new world, the exit). */
    public void reset() {
        hitting = false;
        pos = 0;
        face = NO_FACE;
        stage = -1;
        shown = false;
        shownPos = 0;
        crackCall = false;
        crackStage = -1;
        hit = false;
    }

    /** A DIG record: {@code flags} ({@link RewindFormat#DIG_HITTING}), block (protocol long), face, stage. */
    public void record(int flags, long pos, int face, int stage) {
        this.hitting = (flags & RewindFormat.DIG_HITTING) != 0;
        this.pos = pos;
        this.face = face;
        this.stage = stage;
    }

    /** One world tick: decides this tick's crack call and hit particles from the current state. */
    public void tick() {
        hit = false;
        if (hitting) {
            crackCall = true;
            crackPos = pos;
            crackStage = stage;
            shown = stage >= 0 && stage <= 9;
            shownPos = pos;
            hit = face >= 0 && face < 6;
        } else if (shown) {
            crackCall = true;
            crackPos = shownPos;
            crackStage = -1;
            shown = false;
        } else {
            crackCall = false;
            crackStage = -1;
        }
    }

    /** This tick calls {@code sendBlockBreakProgress(breaker, crackPos, crackStage)}. */
    public boolean crackCall() {
        return crackCall;
    }

    public long crackPos() {
        return crackPos;
    }

    /** 0-9, or -1 (removes the breaker's entry). */
    public int crackStage() {
        return crackStage;
    }

    /** This tick adds hit particles at {@link #hitPos()} on {@link #hitFace()}. */
    public boolean hit() {
        return hit;
    }

    public long hitPos() {
        return pos;
    }

    public int hitFace() {
        return face;
    }

    /** A crack entry is shown for the breaker now. */
    public boolean shown() {
        return shown;
    }

    /** Where the shown crack is (valid while {@link #shown()}, and after {@link #takeShown()} returned true). */
    public long shownPos() {
        return shownPos;
    }

    /**
     * True once if a crack is shown: the caller removes it ({@code shownPos()} stays readable); afterwards nothing is
     * shown.
     */
    public boolean takeShown() {
        if (!shown) {
            return false;
        }
        shown = false;
        return true;
    }

    /** The recorded state: hitting. */
    public boolean hitting() {
        return hitting;
    }

    /** The recorded block (protocol long). */
    public long pos() {
        return pos;
    }

    /** The recorded face (0-5), or {@link #NO_FACE}. */
    public int face() {
        return face;
    }

    /** The recorded stage (-1, 0-9). */
    public int stage() {
        return stage;
    }
}
