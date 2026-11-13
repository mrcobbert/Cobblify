package com.bedwarsqol.ornithe;

/**
 * One event from the in-game mouse loop of {@code Minecraft.runTick} (Forge's {@code MouseEvent}):
 * {@code button} -1 for movement/wheel, {@code buttonstate} true on press.
 */
public final class MouseButtonEvent {

    public final int button;
    public final boolean buttonstate;

    public MouseButtonEvent(int button, boolean buttonstate) {
        this.button = button;
        this.buttonstate = buttonstate;
    }
}
