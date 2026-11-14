package com.bedwarsqol.gui;

/**
 * Holding Tab in Edit HUD (G28) shows the tab list alone, to move and resize it; releasing Tab brings the items back.
 * The only input is the player-list key's keyboard state, read each frame and on each input event. The editor presses
 * the player-list binding while held so vanilla draws the tab list, but that binding is output only and never feeds
 * back here, so a physical release is always seen (plan review R1-B1).
 */
public final class EditTabHold {

    /** What a new keyboard state means. */
    public enum Step { NONE, PRESS, RELEASE }

    private boolean held;

    /** The next keyboard state of the player-list key: PRESS on going down, RELEASE on going up, else NONE. */
    public Step step(boolean keyDown) {
        if (keyDown == held) return Step.NONE;
        held = keyDown;
        return keyDown ? Step.PRESS : Step.RELEASE;
    }

    public boolean held() {
        return held;
    }
}
