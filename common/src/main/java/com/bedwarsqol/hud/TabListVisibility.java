package com.bedwarsqol.hud;

/**
 * Whether vanilla draws the tab list this frame, so the Cobblify HUD can step aside while it shows.
 * Vanilla 1.8.9 {@code GuiIngame.renderGameOverlay} draws the player list when
 * {@code keyBindPlayerList.isKeyDown() && (!isIntegratedServerRunning()
 * || thePlayer.sendQueue.getPlayerInfoMap().size() > 1 || scoreboard.getObjectiveInDisplaySlot(0) != null)}
 * (bytecode offsets 1250-1339 of the mapped method); otherwise it calls {@code updatePlayerList(false)}.
 * The inputs only change in {@code runTick}, never inside one {@code renderGameOverlay}, so the HUD's
 * Text layer can ask the same question earlier in the same frame and get vanilla's answer.
 */
public final class TabListVisibility {

    private TabListVisibility() {
    }

    /**
     * Vanilla's tab-list condition.
     *
     * @param keyDown          the player-list key is held
     * @param integratedServer singleplayer (an integrated server is running)
     * @param playerCount      entries in the client's player info map
     * @param listObjective    an objective is shown in the list display slot (slot 0)
     */
    public static boolean showing(boolean keyDown, boolean integratedServer, int playerCount, boolean listObjective) {
        return keyDown && (!integratedServer || playerCount > 1 || listObjective);
    }

    /** True when the frame's prediction matches what vanilla actually drew. */
    public static boolean agrees(boolean predicted, boolean vanillaDrew) {
        return predicted == vanillaDrew;
    }
}
