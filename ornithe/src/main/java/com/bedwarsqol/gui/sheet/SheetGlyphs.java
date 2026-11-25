package com.bedwarsqol.gui.sheet;

import com.bedwarsqol.feature.DiagLog;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The sheet's Inter glyphs, drawn in device pixels. Each size the sheet needs is rasterized on first use from the
 * outlines {@code tools/fonts/bake-sheet-font.py} writes ({@link GlyphRaster}, which draws as FreeType does), so text
 * is drawn 1:1 on whole pixels at every GUI scale and GUI Size. Should that ever fail, text falls back to the baked,
 * mipmapped masters. Everything loads straight from the classpath, so Forge and Weave load it the same way.
 */
final class SheetGlyphs {

    private static final String DIR = "/assets/bedwarsqol/font/sheet/";
    private static final int[] WEIGHTS = {400, 500, 600};
    private static final int[] MASTERS = {24, 48};

    private static final class Atlas {
        int texture = -1;
        final float px;
        final boolean master;
        int width, height;
        final int[] x = new int[256], y = new int[256], w = new int[256], h = new int[256];
        final int[] left = new int[256], top = new int[256];

        Atlas(float px, boolean master) {
            this.px = px;
            this.master = master;
        }
    }

    private static boolean loaded;
    private static final FontMetrics[] METRICS = new FontMetrics[3];
    private static final GlyphRaster.Outlines[] OUTLINES = new GlyphRaster.Outlines[3];
    private static boolean rasterFailed;
    private static final Map<String, Atlas> ATLASES = new HashMap<String, Atlas>();

    private SheetGlyphs() {
    }

    static FontMetrics metrics(int weight) {
        ensure();
        return METRICS[slot(weight)];
    }

    private static int slot(int weight) {
        return weight >= 600 ? 2 : weight >= 500 ? 1 : 0;
    }

    private static void ensure() {
        if (loaded) return;
        loaded = true;
        try {
            for (int i = 0; i < WEIGHTS.length; i++) {
                InputStream in = SheetGlyphs.class.getResourceAsStream(DIR + "inter-" + WEIGHTS[i] + ".metrics");
                METRICS[i] = FontMetrics.read(in);
                in.close();
            }
        } catch (Exception e) {
            throw new IllegalStateException("sheet font metrics missing", e);
        }
    }

    /** The atlas for this weight and device size: rasterized for it, else the smallest big-enough master. */
    private static Atlas atlas(int weight, float sizePx) {
        // the size as FreeType sets it, in 1/64 px
        long size64 = Math.round(sizePx * 64.0);
        String key = weight + "@" + size64;
        Atlas exact = ATLASES.get(key);
        if (exact == null && !ATLASES.containsKey(key)) {
            exact = raster(weight, size64 / 64f);
            ATLASES.put(key, exact);
        }
        if (exact != null) return exact;
        int m = MASTERS[MASTERS.length - 1];
        for (int candidate : MASTERS) {
            if (candidate >= sizePx) {
                m = candidate;
                break;
            }
        }
        String mk = weight + "-m" + m;
        Atlas master = ATLASES.get(mk);
        if (master == null) {
            master = load(weight, "m" + m, m, true);
            ATLASES.put(mk, master);
        }
        return master;
    }

    /** Every glyph of a weight at {@code px}, rasterized and uploaded; null (and masters from then on) on failure. */
    private static Atlas raster(int weight, float px) {
        if (rasterFailed) return null;
        try {
            int i = slot(weight);
            if (OUTLINES[i] == null) {
                InputStream in = SheetGlyphs.class.getResourceAsStream(DIR + "inter-" + WEIGHTS[i] + ".outlines");
                if (in == null) throw new IllegalStateException("inter-" + WEIGHTS[i] + ".outlines missing");
                try {
                    OUTLINES[i] = GlyphRaster.Outlines.read(in);
                } finally {
                    in.close();
                }
            }
            GlyphRaster.Atlas g = GlyphRaster.atlas(OUTLINES[i], px, 1);
            Atlas a = new Atlas(px, false);
            a.width = g.width;
            a.height = g.height;
            System.arraycopy(g.x, 0, a.x, 0, 256);
            System.arraycopy(g.y, 0, a.y, 0, 256);
            System.arraycopy(g.w, 0, a.w, 0, 256);
            System.arraycopy(g.h, 0, a.h, 0, 256);
            System.arraycopy(g.left, 0, a.left, 0, 256);
            System.arraycopy(g.top, 0, a.top, 0, 256);
            int[] cov = new int[g.width * g.height];
            for (int k = 0; k < cov.length; k++) cov[k] = g.alpha[k] & 255;
            a.texture = upload(cov, g.width, g.height, false);
            return a;
        } catch (RuntimeException | IOException e) {
            rasterFailed = true;
            DiagLog.log("sheet font: rasterizing failed, using the baked masters: " + e);
            return null;
        }
    }

    private static Atlas load(int weight, String key, float px, boolean master) {
        String base = DIR + "inter-" + weight + "-" + key;
        InputStream table = SheetGlyphs.class.getResourceAsStream(base + ".txt");
        if (table == null) return null;
        Atlas a = new Atlas(px, master);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(table, StandardCharsets.UTF_8));
            String line = r.readLine();
            String[] head = line.split(" ");
            a.width = Integer.parseInt(head[2]);
            a.height = Integer.parseInt(head[3]);
            while ((line = r.readLine()) != null) {
                String[] t = line.split(" ");
                int c = Integer.parseInt(t[1]);
                a.x[c] = Integer.parseInt(t[2]);
                a.y[c] = Integer.parseInt(t[3]);
                a.w[c] = Integer.parseInt(t[4]);
                a.h[c] = Integer.parseInt(t[5]);
                a.left[c] = Integer.parseInt(t[6]);
                a.top[c] = Integer.parseInt(t[7]);
            }
            table.close();
            InputStream png = SheetGlyphs.class.getResourceAsStream(base + ".png");
            BufferedImage img = ImageIO.read(png);
            png.close();
            int w = img.getWidth(), h = img.getHeight();
            int[] cov = new int[w * h];
            java.awt.image.Raster raster = img.getRaster();
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) cov[yy * w + xx] = raster.getSample(xx, yy, 0);
            }
            a.texture = upload(cov, w, h, master);
        } catch (Exception e) {
            throw new IllegalStateException("sheet font atlas " + base + " failed to load", e);
        }
        return a;
    }

    /** Upload grayscale coverage (0..255, row-major) as white with alpha; masters get a box-filtered mip chain. */
    private static int upload(int[] cov, int w, int h, boolean mipmapped) {
        int tex = GL11.glGenTextures();
        GlStateManager.bindTexture(tex);
        int levels = mipmapped ? 4 : 1;
        int lw = w, lh = h;
        for (int level = 0; level < levels; level++) {
            IntBuffer buf = BufferUtils.createIntBuffer(lw * lh);
            for (int i = 0; i < lw * lh; i++) buf.put((cov[i] << 24) | 0xFFFFFF);
            buf.flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, lw, lh, 0, GL12.GL_BGRA,
                    GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
            if (level + 1 < levels) {
                int nw = Math.max(1, lw / 2), nh = Math.max(1, lh / 2);
                int[] next = new int[nw * nh];
                for (int yy = 0; yy < nh; yy++) {
                    for (int xx = 0; xx < nw; xx++) {
                        int s = cov[(2 * yy) * lw + 2 * xx] + cov[(2 * yy) * lw + 2 * xx + 1]
                                + cov[(2 * yy + 1) * lw + 2 * xx] + cov[(2 * yy + 1) * lw + 2 * xx + 1];
                        next[yy * nw + xx] = (s + 2) / 4;
                    }
                }
                cov = next;
                lw = nw;
                lh = nh;
            }
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                mipmapped ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, mipmapped ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return tex;
    }

    /**
     * Draw {@code s} with its pen starting at {@code x} and its baseline on {@code baseline}, all in device px.
     * Exact atlases put each glyph on a whole pixel; masters scale and filter. The caller has blending on.
     */
    static void draw(String s, float x, float baseline, float sizePx, int weight, int argb) {
        FontMetrics m = metrics(weight);
        Atlas a = atlas(weight, sizePx);
        if (a == null || a.texture < 0) return;
        float k = sizePx / m.upm;
        float scale = a.master ? sizePx / a.px : 1f;
        float r = ((argb >> 16) & 255) / 255f, g = ((argb >> 8) & 255) / 255f, b = (argb & 255) / 255f;
        float al = ((argb >>> 24) & 255) / 255f;
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(a.texture);
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        float pen = x;
        char prev = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = m.mapped(s.charAt(i));
            pen += m.kerning(prev, c) * k;
            if (a.w[c] > 0 && a.h[c] > 0) {
                float x0, y0, x1, y1;
                if (a.master) {
                    x0 = pen + a.left[c] * scale;
                    y0 = baseline - a.top[c] * scale;
                    x1 = x0 + a.w[c] * scale;
                    y1 = y0 + a.h[c] * scale;
                } else {
                    x0 = Math.round(pen) + a.left[c];
                    y0 = Math.round(baseline) - a.top[c];
                    x1 = x0 + a.w[c];
                    y1 = y0 + a.h[c];
                }
                float u0 = a.x[c] / (float) a.width, v0 = a.y[c] / (float) a.height;
                float u1 = (a.x[c] + a.w[c]) / (float) a.width, v1 = (a.y[c] + a.h[c]) / (float) a.height;
                wr.pos(x0, y1, 0).tex(u0, v1).color(r, g, b, al).endVertex();
                wr.pos(x1, y1, 0).tex(u1, v1).color(r, g, b, al).endVertex();
                wr.pos(x1, y0, 0).tex(u1, v0).color(r, g, b, al).endVertex();
                wr.pos(x0, y0, 0).tex(u0, v0).color(r, g, b, al).endVertex();
            }
            pen += m.advance(c) * k;
            prev = c;
        }
        tess.draw();
    }
}
