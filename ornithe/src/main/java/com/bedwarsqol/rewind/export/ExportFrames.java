package com.bedwarsqol.rewind.export;

import com.bedwarsqol.feature.MotionBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;

/**
 * One diverted frame of the OWN route (Rewind Export PLAN Solution 2), and what an export changes for its whole length.
 *
 * <p><b>Per frame.</b> {@link #begin}, right before {@code EntityRenderer.updateCameraAndRender}: the main framebuffer is
 * cleared (so anything that still lands in it shows up in a screenshot, check K5), {@code mc.displayWidth/Height} and
 * {@code gameSettings.guiScale} are overridden with the export's, the export screen stands in for the open screen, and
 * the target is bound with its viewport. {@link #rebind}, right after vanilla's mid-frame
 * {@code getFramebuffer().bindFramebuffer(true)} (bytecode @606-610, before the HUD @673 and the screen @765): the
 * target again. {@link #readBack} after the frame returns; {@link #end} puts everything back and binds the main
 * framebuffer as vanilla left it at @269.
 *
 * <p><b>Per export</b> ({@link Restore}): Motion Blur suppressed, vsync off and the frame limit at maximum, all put back
 * in one place. Game thread only.
 */
final class ExportFrames {

    /** GameSettings' highest frame limit ("Unlimited"). */
    static final int UNLIMITED_FPS = 260;

    private final Minecraft mc;
    private final ExportTarget target;
    private final int guiScale;
    private final GuiScreen screen;
    private ByteBuffer pixels;

    private boolean inFrame;
    private int savedWidth;
    private int savedHeight;
    private int savedGuiScale;
    private GuiScreen savedScreen;
    /** The last {@link #readBack}: the framebuffer bound and the first GL error after the frame. */
    int boundAtReadBack;
    int glError;

    ExportFrames(Minecraft mc, ExportTarget target, int guiScale, GuiScreen screen) {
        this.mc = mc;
        this.target = target;
        this.guiScale = guiScale;
        this.screen = screen;
    }

    boolean inFrame() {
        return inFrame;
    }

    /** Before {@code updateCameraAndRender}: sizes, screen and target for this frame. */
    void begin(boolean clearMain) {
        if (clearMain) {
            GlStateManager.clearColor(0f, 0f, 0f, 1f);
            GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        }
        savedWidth = mc.displayWidth;
        savedHeight = mc.displayHeight;
        savedGuiScale = mc.gameSettings.guiScale;
        savedScreen = mc.currentScreen;
        mc.displayWidth = target.width;
        mc.displayHeight = target.height;
        mc.gameSettings.guiScale = guiScale;
        if (screen != null) {
            ScaledResolution sr = new ScaledResolution(mc);
            screen.setWorldAndResolution(mc, sr.getScaledWidth(), sr.getScaledHeight());
            mc.currentScreen = screen;
        }
        inFrame = true;
        target.bind();
    }

    /** After vanilla's mid-frame bind of the main framebuffer: ours again. */
    void rebind() {
        if (inFrame) {
            target.bind();
        }
    }

    /**
     * Reads the frame back (BGRA, bottom row first) into a reused direct buffer and records the binding and GL error.
     * Returns the buffer, positioned at 0 with {@code width x height x 4} bytes.
     */
    ByteBuffer readBack() {
        boundAtReadBack = ExportTarget.bound();
        int n = target.width * target.height * 4;
        if (pixels == null || pixels.capacity() < n) {
            pixels = BufferUtils.createByteBuffer(n);
        }
        pixels.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, target.width, target.height, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
        glError = GL11.glGetError();
        pixels.position(0).limit(n);
        return pixels;
    }

    /** After the frame: sizes, GUI scale and screen back, the main framebuffer bound as at @269. */
    void end() {
        if (!inFrame) {
            return;
        }
        inFrame = false;
        mc.displayWidth = savedWidth;
        mc.displayHeight = savedHeight;
        mc.gameSettings.guiScale = savedGuiScale;
        if (screen != null && mc.currentScreen == screen) {
            mc.currentScreen = savedScreen;
        }
        bindMain(mc);
    }

    /** The main framebuffer (or the window when framebuffers are off), with its viewport. */
    static void bindMain(Minecraft mc) {
        if (OpenGlHelper.isFramebufferEnabled()) {
            mc.getFramebuffer().bindFramebuffer(true);
        } else {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
            GlStateManager.viewport(0, 0, mc.displayWidth, mc.displayHeight);
        }
    }

    /** {@code updateDisplay} HEAD: the window's back buffer cleared to black (the spike shows no preview). */
    static void clearWindow(Minecraft mc) {
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
        GlStateManager.viewport(0, 0, mc.displayWidth, mc.displayHeight);
        GlStateManager.clearColor(0f, 0f, 0f, 1f);
        GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
    }

    /** What an export changes for its whole length, put back by {@link #restore()} exactly once. */
    static final class Restore {
        private final Minecraft mc;
        private final boolean motionBlurSuppressed;
        private final boolean vsync;
        private final int limitFramerate;
        private boolean restored;

        Restore(Minecraft mc) {
            this.mc = mc;
            motionBlurSuppressed = MotionBlur.suppressed();
            vsync = mc.gameSettings.enableVsync;
            limitFramerate = mc.gameSettings.limitFramerate;
            MotionBlur.suppress(true);
            mc.gameSettings.enableVsync = false;
            Display.setVSyncEnabled(false);
            mc.gameSettings.limitFramerate = UNLIMITED_FPS;
        }

        void restore() {
            if (restored) {
                return;
            }
            restored = true;
            MotionBlur.suppress(motionBlurSuppressed);
            mc.gameSettings.enableVsync = vsync;
            Display.setVSyncEnabled(vsync);
            mc.gameSettings.limitFramerate = limitFramerate;
        }
    }
}
