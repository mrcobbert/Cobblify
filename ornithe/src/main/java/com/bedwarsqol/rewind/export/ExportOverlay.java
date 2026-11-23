package com.bedwarsqol.rewind.export;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.gui.sheet.McCanvas;
import com.bedwarsqol.gui.sheet.SheetColors;
import com.bedwarsqol.rewind.watch.RewindPainter;
import com.bedwarsqol.rewind.watch.RewindPlayback;
import com.bedwarsqol.rewind.watch.Timeline;
import com.bedwarsqol.rewind.watch.ViewerLayout;
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
 * rendered as a preview (OWN route: our framebuffer's texture, letterboxed to the window) and the export panel where
 * the viewer has it, its rows dimmed and its Export key filling with the share of frames done, red when the export
 * failed ({@link RewindPainter#exportPanel}). Drawn at {@code Minecraft.updateDisplay} HEAD, after the read-back, so
 * neither is ever in the file. Game thread.
 */
final class ExportOverlay {

    private ExportOverlay() {
    }

    private static final McCanvas CANVAS = new McCanvas();
    private static int accent = -1;
    private static SheetColors.Shades accentShades = SheetColors.Shades.of(0xC0DCFF);

    /**
     * @param target   the OWN route's framebuffer (null: WINDOW, whose window already shows the frame)
     * @param panel    the export panel whose key fills (null: no key)
     * @param progress frames sent / frames
     * @param failed   the export failed
     */
    static void draw(Minecraft mc, ExportTarget target, ExportPanelModel panel, double progress, boolean failed) {
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
        if (panel != null) {
            if (failed) {
                panel.failed();
            } else {
                panel.filling((float) progress);
            }
            panel(mc, panel, w, h);
        }
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
    }

    /** The panel at the viewer's spot: above the bar's top, at the window's GUI scale, in window pixels. */
    private static void panel(Minecraft mc, ExportPanelModel panel, int w, int h) {
        Timeline tl = RewindPlayback.session().timeline();
        if (tl == null) {
            return;
        }
        ClientSettings cfg = BedwarsQol.config;
        int sf = new ScaledResolution(mc).getScaleFactor();
        float sw = w / (float) sf, sh = h / (float) sf;
        CANVAS.begin(sf, 0f, 0f, h, 1, cfg == null || cfg.minecraftFont());
        try {
            float barTop = ViewerLayout.of(sw, sh, CANVAS, tl).bar[1];
            RewindPainter.exportPanel(CANVAS, panel, panel.layout(sw, barTop, CANVAS), null, accent(cfg));
        } finally {
            CANVAS.end();
        }
    }

    private static SheetColors.Shades accent(ClientSettings cfg) {
        int a = SheetColors.legible(GuiTheme.color(cfg == null ? null : cfg.guiAccent));
        if (a != accent) {
            accent = a;
            accentShades = SheetColors.Shades.of(a);
        }
        return accentShades;
    }
}
