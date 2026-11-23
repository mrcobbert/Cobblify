package com.bedwarsqol.rewind.export;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * What the window shows while an export renders (Rewind Export PLAN Solution 2, decision 4; TASK U5): the frame just
 * rendered as a preview (OWN route: our framebuffer's texture, letterboxed to the window) and the Export key filling
 * with the share of frames done, red when the export failed. Drawn at {@code Minecraft.updateDisplay} HEAD, after the
 * read-back, so neither is ever in the file. Phase 7 replaces the key with the export panel's own; this is the
 * minimal one the pipeline needs. Game thread.
 */
final class ExportOverlay {

    private ExportOverlay() {
    }

    /**
     * @param target   the OWN route's framebuffer (null: WINDOW, whose window already shows the frame)
     * @param progress frames sent / frames
     * @param failed   the export failed
     */
    static void draw(Minecraft mc, ExportTarget target, double progress, boolean failed) {
        int w = mc.displayWidth;
        int h = mc.displayHeight;
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
        GlStateManager.viewport(0, 0, w, h);
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0, w, h, 0, -1, 1);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableFog();
        GlStateManager.colorMask(true, true, true, false);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer r = t.getWorldRenderer();
        if (target != null) {
            GlStateManager.clearColor(0f, 0f, 0f, 1f);
            GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
            double s = Math.min(w / (double) target.width, h / (double) target.height);
            double pw = target.width * s;
            double ph = target.height * s;
            double x0 = (w - pw) / 2;
            double y0 = (h - ph) / 2;
            GlStateManager.disableBlend();
            GlStateManager.enableTexture2D();
            GlStateManager.bindTexture(target.texture());
            GlStateManager.color(1f, 1f, 1f, 1f);
            r.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            r.pos(x0, y0 + ph, 0).tex(0, 0).endVertex();
            r.pos(x0 + pw, y0 + ph, 0).tex(1, 0).endVertex();
            r.pos(x0 + pw, y0, 0).tex(1, 1).endVertex();
            r.pos(x0, y0, 0).tex(0, 1).endVertex();
            t.draw();
            GlStateManager.bindTexture(0);
        }
        int sf = new ScaledResolution(mc).getScaleFactor();
        int kw = 120 * sf;
        int kh = 6 * sf;
        int kx = w - kw - 8 * sf;
        int ky = h - kh - 8 * sf;
        double share = Math.max(0, Math.min(1, progress));
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        rect(t, r, kx, ky, kx + kw, ky + kh, 0x99000000);
        rect(t, r, kx, ky, kx + (int) Math.round(kw * (failed ? 1 : share)), ky + kh, failed ? 0xFFD9493F : 0xFFF0F0F0);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
    }

    private static void rect(Tessellator t, WorldRenderer r, int x0, int y0, int x1, int y1, int argb) {
        float a = (argb >>> 24) / 255f;
        float red = (argb >> 16 & 255) / 255f;
        float g = (argb >> 8 & 255) / 255f;
        float b = (argb & 255) / 255f;
        r.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        r.pos(x0, y1, 0).color(red, g, b, a).endVertex();
        r.pos(x1, y1, 0).color(red, g, b, a).endVertex();
        r.pos(x1, y0, 0).color(red, g, b, a).endVertex();
        r.pos(x0, y0, 0).color(red, g, b, a).endVertex();
        t.draw();
    }
}
