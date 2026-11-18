package com.bedwarsqol.rewind.watch;

import net.minecraft.client.gui.GuiScreen;

/**
 * Base of Rewind's own screens (loading card, viewer, Esc menu, and any screen a session opens).
 *
 * <ul>
 *   <li><b>Never pauses</b> the game: the session owns the pause flag.</li>
 *   <li><b>Input once per frame.</b> Vanilla hands a screen its input only from {@code runTick}, which runs 0 times a
 *       frame while playback is paused and many times during a catch-up. A Rewind screen ignores that call and pumps
 *       its keyboard and mouse events itself, once per frame, when it is drawn ({@link #pumpInput()}). The screen
 *       does not allow user input to the world either, so vanilla's in-world key and mouse handling never runs.</li>
 *   <li><b>Allowed during a session.</b> The screen policy lets Rewind screens open while a recording plays.</li>
 * </ul>
 *
 * Subclasses draw in {@link #drawRewind} and handle keys and clicks with the usual {@code GuiScreen} methods.
 */
public abstract class RewindScreen extends GuiScreen {

    @Override
    public final boolean doesGuiPauseGame() {
        return false;
    }

    /** {@code runTick}'s call: ignored (input is pumped once per frame by {@link #drawScreen}). */
    @Override
    public final void handleInput() {
    }

    @Override
    public final void drawScreen(int mouseX, int mouseY, float partialTicks) {
        pumpInput();
        if (mc != null && mc.currentScreen != this) {
            return; // the input closed or replaced this screen
        }
        drawRewind(mouseX, mouseY, partialTicks);
    }

    /** Vanilla's {@code GuiScreen.handleInput}: every pending mouse and keyboard event, once per frame. */
    protected final void pumpInput() {
        super.handleInput();
    }

    /** Vanilla's {@code GuiScreen.drawScreen} (buttons and labels), for subclasses that use them. */
    protected final void drawVanilla(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** Draws the screen (after this frame's input). */
    protected abstract void drawRewind(int mouseX, int mouseY, float partialTicks);
}
