package com.bedwarsqol.rewind;

/**
 * The local scope (PLAN section A "Local scope"): a pure depth counter that says whether one of the client's
 * own-action methods is on the client thread's stack ({@code EntityPlayerSP.onUpdate}, {@code Minecraft.clickMouse},
 * {@code rightClickMouse}, {@code sendClickBlockToController}). Server packet handlers never run inside them (they
 * run in the scheduled-task drain before the tick loop), so a block change or sound made at depth &gt; 0 is the
 * player's own, and BLOCK and SOUND records are written only then.
 *
 * <p>A second counter tracks {@code PlayerControllerMP.onPlayerDestroyBlock} for the BLOCK cause.
 *
 * <p>Client thread only; no allocation, no locks. Hooks call {@link #enter} at a method's HEAD and {@link #exit} at
 * its RETURN. An exit without an enter clamps at 0. A vanilla method that throws skips its RETURN hook, so the owner
 * calls {@link #reset} where no scope can be open ({@code Minecraft.runTick} HEAD).
 */
public final class LocalScope {

    private int depth;
    private int destroyDepth;

    /** An own-action method was entered. */
    public void enter() {
        depth++;
    }

    /** An own-action method returned; never goes below 0. */
    public void exit() {
        if (depth > 0) {
            depth--;
        }
    }

    /** True while at least one own-action method is on the stack. */
    public boolean local() {
        return depth > 0;
    }

    public int depth() {
        return depth;
    }

    /** {@code PlayerControllerMP.onPlayerDestroyBlock} was entered. */
    public void enterDestroy() {
        destroyDepth++;
    }

    /** {@code PlayerControllerMP.onPlayerDestroyBlock} returned; never goes below 0. */
    public void exitDestroy() {
        if (destroyDepth > 0) {
            destroyDepth--;
        }
    }

    public boolean destroying() {
        return destroyDepth > 0;
    }

    /** The BLOCK cause for a change made now: {@link RewindFormat#BLOCK_CAUSE_OWN_BREAK} inside the destroy scope. */
    public int blockCause() {
        return destroyDepth > 0 ? RewindFormat.BLOCK_CAUSE_OWN_BREAK : RewindFormat.BLOCK_CAUSE_OTHER;
    }

    /** Closes every scope. */
    public void reset() {
        depth = 0;
        destroyDepth = 0;
    }
}
