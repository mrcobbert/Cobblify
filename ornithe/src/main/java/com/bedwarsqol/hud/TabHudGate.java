package com.bedwarsqol.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.scoreboard.Scoreboard;

/**
 * Holding Tab hides the Cobblify HUD: one decision per in-game HUD frame. {@link #beginFrame} runs at
 * the Text layer ({@code GuiIngameHudMixin}), before vanilla decides whether to draw the tab list, and
 * asks vanilla's own question ({@link TabListVisibility#showing}); the Text layer and Session Stats in
 * the Post layer then skip their drawing while {@link #hidesHud()} is true. The scoreboard and chat
 * are vanilla's and keep drawing.
 *
 * <p>The tab call-site wrapper reports that vanilla really drew the list ({@link #vanillaDrewTab()});
 * {@link #endFrame()} counts every frame where the prediction and vanilla disagreed, so the dev and
 * prod smoke can prove the two never differ ({@code mismatchFrames == 0}).
 */
public final class TabHudGate {

    /** Frames where the prediction disagreed with vanilla's tab-list draw. Read by the dev smoke. */
    public static int mismatchFrames;

    private static boolean hidden;
    private static boolean drewTab;
    private static boolean frameOpen;

    private TabHudGate() {
    }

    /** Start of the HUD frame's Text layer: decide whether the tab list shows this frame. */
    public static void beginFrame(Minecraft mc) {
        hidden = tabListShowing(mc);
        drewTab = false;
        frameOpen = true;
    }

    /** True while the tab list shows this frame: no Cobblify HUD element draws. */
    public static boolean hidesHud() {
        return hidden;
    }

    /** Vanilla is drawing the tab list (called from the {@code renderPlayerlist} call-site wrapper). */
    public static void vanillaDrewTab() {
        drewTab = true;
    }

    /** End of the HUD frame: counts a frame whose prediction vanilla did not confirm. */
    public static void endFrame() {
        if (frameOpen && !TabListVisibility.agrees(hidden, drewTab)) mismatchFrames++;
        frameOpen = false;
    }

    private static boolean tabListShowing(Minecraft mc) {
        if (mc == null || mc.gameSettings == null || mc.thePlayer == null || mc.theWorld == null) return false;
        boolean keyDown = mc.gameSettings.keyBindPlayerList.isKeyDown();
        if (!keyDown) return false;
        NetHandlerPlayClient net = mc.thePlayer.sendQueue;
        int players = net == null ? 0 : net.getPlayerInfoMap().size();
        Scoreboard board = mc.theWorld.getScoreboard();
        boolean listObjective = board != null && board.getObjectiveInDisplaySlot(0) != null;
        return TabListVisibility.showing(true, mc.isIntegratedServerRunning(), players, listObjective);
    }
}
