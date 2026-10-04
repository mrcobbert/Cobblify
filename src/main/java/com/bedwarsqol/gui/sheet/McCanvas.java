package com.bedwarsqol.gui.sheet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * The sheet's {@link Canvas} on Minecraft's GL: everything is drawn in device pixels (the GUI matrix is undone),
 * every rect edge rounds to a whole pixel from its exact position, rects go out in one batch per run, and the clip
 * is a real stack. Text is Inter from {@link SheetGlyphs}, or with the Font setting Minecraft's own font through the
 * game's {@link FontRenderer} (resource packs included), at whole device pixels per font pixel. Identical in both trees.
 */
public final class McCanvas implements Canvas {

    private float unit = 1f;
    private float ox, oy;
    private int displayHeight;
    private boolean minecraft;

    private final float[] alphas = new float[16];
    private int alphaDepth;
    private float alpha = 1f;

    private int disabledDepth;
    private int backdrop;

    private final int[][] clips = new int[16][];
    private int clipDepth;

    private boolean batching;

    /**
     * Start a frame. {@code originX/Y} is the sheet's top-left in device px, {@code unit} the device px per sheet
     * unit, {@code scaleFactor} the GUI scale whose matrix is in effect, {@code minecraftFont} the Font setting.
     */
    public void begin(float unit, float originX, float originY, int displayHeight, int scaleFactor, boolean minecraftFont) {
        this.unit = unit;
        this.minecraft = minecraftFont;
        this.ox = originX;
        this.oy = originY;
        this.displayHeight = displayHeight;
        alphaDepth = 0;
        alpha = 1f;
        disabledDepth = 0;
        clipDepth = 0;
        GlStateManager.pushMatrix();
        GlStateManager.scale(1f / scaleFactor, 1f / scaleFactor, 1f);
        GlStateManager.disableDepth();
        GlStateManager.disableAlpha();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    public void end() {
        flush();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.popMatrix();
    }

    /** Device px of a sheet-unit x, unrounded. */
    public float px(float x) {
        return ox + x * unit;
    }

    public float py(float y) {
        return oy + y * unit;
    }

    @Override
    public float unit() {
        return unit;
    }

    // ------------------------------------------------------------------ colour

    private int tint(int argb) {
        int a = argb >>> 24;
        int rgb = argb & 0xFFFFFF;
        if (disabledDepth > 0) rgb = SheetColors.disabled(rgb, backdrop);
        a = Math.round(a * alpha);
        return (a << 24) | rgb;
    }

    // ------------------------------------------------------------------ rects

    private void startBatch() {
        if (batching) return;
        GlStateManager.disableTexture2D();
        Tessellator.getInstance().getWorldRenderer().begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        batching = true;
    }

    private void flush() {
        if (!batching) return;
        Tessellator.getInstance().draw();
        batching = false;
    }

    @Override
    public void fill(float x, float y, float w, float h, int argb) {
        if (w <= 0 || h <= 0) return;
        int x1 = Math.round(px(x)), x2 = Math.round(px(x + w));
        int y1 = Math.round(py(y)), y2 = Math.round(py(y + h));
        if (x2 <= x1 || y2 <= y1) return;
        fillDevice(x1, y1, x2, y2, argb);
    }

    /** Fill whole device pixels [x1, x2) x [y1, y2). */
    public void fillDevice(float x1, float y1, float x2, float y2, int argb) {
        int c = tint(argb);
        if ((c >>> 24) == 0) return;
        startBatch();
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        int a = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        wr.pos(x1, y2, 0).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, 0).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, 0).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, 0).color(r, g, b, a).endVertex();
    }

    // ------------------------------------------------------------------ strokes

    @Override
    public void stroke(float[] p, float width, int argb) {
        int c = tint(argb);
        if ((c >>> 24) == 0 || p.length < 4) return;
        flush();
        float hw = width * unit / 2f;
        int n = p.length / 2;
        float[] dx = new float[n], dy = new float[n];
        for (int i = 0; i < n; i++) {
            dx[i] = px(p[2 * i]);
            dy[i] = py(p[2 * i + 1]);
        }
        // Per vertex: the offset direction (miter) scaled so the band keeps its width on both segments.
        float[] mx = new float[n], my = new float[n];
        for (int i = 0; i < n; i++) {
            float nx0 = 0, ny0 = 0, nx1 = 0, ny1 = 0;
            if (i > 0) {
                float ex = dx[i] - dx[i - 1], ey = dy[i] - dy[i - 1], l = (float) Math.sqrt(ex * ex + ey * ey);
                nx0 = -ey / l;
                ny0 = ex / l;
            }
            if (i < n - 1) {
                float ex = dx[i + 1] - dx[i], ey = dy[i + 1] - dy[i], l = (float) Math.sqrt(ex * ex + ey * ey);
                nx1 = -ey / l;
                ny1 = ex / l;
            }
            if (i == 0) {
                nx0 = nx1;
                ny0 = ny1;
            }
            if (i == n - 1) {
                nx1 = nx0;
                ny1 = ny0;
            }
            float ax = nx0 + nx1, ay = ny0 + ny1, al = (float) Math.sqrt(ax * ax + ay * ay);
            ax /= al;
            ay /= al;
            float dot = Math.max(0.3f, ax * nx1 + ay * ny1);
            mx[i] = ax / dot;
            my[i] = ay / dot;
        }
        // Solid core at (hw - 0.5) and a 1 px fringe each side, straddling the true edge.
        float in = Math.max(0f, hw - 0.5f), out = hw + 0.5f;
        float coreA = hw >= 0.5f ? 1f : hw * 2f;
        band(dx, dy, mx, my, n, in, -in, c, coreA, coreA);
        band(dx, dy, mx, my, n, out, in, c, 0f, coreA);
        band(dx, dy, mx, my, n, -in, -out, c, coreA, 0f);
    }

    private void band(float[] x, float[] y, float[] mx, float[] my, int n, float o1, float o2, int c, float a1, float a2) {
        GlStateManager.disableTexture2D();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        float base = (c >>> 24) / 255f;
        for (int i = 0; i < n; i++) {
            wr.pos(x[i] + mx[i] * o1, y[i] + my[i] * o1, 0).color(r, g, b, Math.round(255 * base * a1)).endVertex();
            wr.pos(x[i] + mx[i] * o2, y[i] + my[i] * o2, 0).color(r, g, b, Math.round(255 * base * a2)).endVertex();
        }
        tess.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
    }

    @Override
    public void ring(float cx, float cy, float r, float width, int argb) {
        int seg = 48;
        float[] p = new float[(seg + 1) * 2];
        for (int i = 0; i <= seg; i++) {
            double t = Math.PI * 2 * i / seg;
            p[2 * i] = cx + (float) Math.cos(t) * r;
            p[2 * i + 1] = cy + (float) Math.sin(t) * r;
        }
        stroke(p, width, argb);
    }

    // ------------------------------------------------------------------ text

    // Minecraft's font: a glyph cell is 8 font px tall, capitals in its top 7 with the baseline under them and the
    // descenders in the 8th, and the shadow one font px down and right. Vertically, as vanilla centres it, the
    // capitals and their shadow (8 px) centre on the line box: an ascent of 7.5 and a descent of 1.5 (FONT_HEIGHT 9).

    /** Device px per font pixel: whole pixels, never more than one su, so text keeps to the layout's sizes. */
    private int fontPx(SheetFont font) {
        return Math.max(1, (int) Math.floor(unit + 1e-3f));
    }

    private static FontRenderer fontRenderer() {
        return Minecraft.getMinecraft().fontRendererObj;
    }

    /** Minecraft shadows light text; under dark text (on a pale accent) a shadow only smudges it. */
    static boolean shadowed(int argb) {
        return ((argb >> 16) & 255) + ((argb >> 8) & 255) + (argb & 255) >= 3 * 0x40;
    }

    /** The baseline of a line box in device px, on a whole pixel. */
    private float minecraftBaseline(SheetFont font, float lineTop, float lineHeight) {
        int k = fontPx(font);
        return Math.round(py(lineTop) + (lineHeight * unit - 9f * k) / 2f + 7.5f * k);
    }

    @Override
    public boolean minecraftFont() {
        return minecraft;
    }

    /** Whether this role draws in Minecraft's font: the Font setting, unless the role keeps the modern font. */
    private boolean mc(SheetFont font) {
        return minecraft && !font.modern;
    }

    @Override
    public float pixel(SheetFont font) {
        return mc(font) ? fontPx(font) / unit : 0f;
    }

    @Override
    public float width(String s, SheetFont font) {
        if (mc(font)) return fontRenderer().getStringWidth(s) * fontPx(font) / unit;
        return SheetGlyphs.metrics(font.weight).width(s, font.size);
    }

    @Override
    public void text(String s, float x, float lineTop, float lineHeight, SheetFont font, int argb) {
        if (s.isEmpty()) return;
        int c = tint(mc(font) ? SheetColors.minecraftText(argb) : argb);
        if ((c >>> 24) == 0) return;
        flush();
        if (mc(font)) {
            // FontRenderer draws anything under 4/255 alpha opaque, and alpha-tests away 0.1 and under
            if ((c >>> 24) <= 25) return;
            int k = fontPx(font);
            float top = minecraftBaseline(font, lineTop, lineHeight) - 7f * k;
            GlStateManager.enableTexture2D(); // the rect batches leave texturing off; FontRenderer assumes it on
            GlStateManager.pushMatrix();
            GlStateManager.translate(Math.round(px(x)), top, 0f);
            GlStateManager.scale(k, k, 1f);
            fontRenderer().drawString(s, 0f, 0f, c, font.shadow && shadowed(c));
            GlStateManager.popMatrix();
            // it turns the alpha test on, which would drop faint fills
            GlStateManager.disableAlpha();
            GlStateManager.color(1f, 1f, 1f, 1f);
            return;
        }
        float size = font.size * unit;
        FontMetrics m = SheetGlyphs.metrics(font.weight);
        float baseline = SheetText.baseline(m, size, py(lineTop), lineHeight * unit);
        SheetGlyphs.draw(s, px(x), baseline, size, font.weight, c);
    }

    @Override
    public float ascent(SheetFont font) {
        if (mc(font)) return 7.5f * fontPx(font) / unit;
        return SheetText.ascent(SheetGlyphs.metrics(font.weight), font.size * unit) / unit;
    }

    @Override
    public float descent(SheetFont font) {
        if (mc(font)) return 1.5f * fontPx(font) / unit;
        return SheetText.descent(SheetGlyphs.metrics(font.weight), font.size * unit) / unit;
    }

    @Override
    public float normalLineHeight(SheetFont font) {
        if (mc(font)) return 9f * fontPx(font) / unit;
        float size = font.size * unit;
        FontMetrics m = SheetGlyphs.metrics(font.weight);
        return (SheetText.ascent(m, size) + SheetText.descent(m, size)) / unit;
    }

    @Override
    public float baseline(SheetFont font, float lineTop, float lineHeight) {
        if (mc(font)) return (minecraftBaseline(font, lineTop, lineHeight) - oy) / unit;
        float size = font.size * unit;
        FontMetrics m = SheetGlyphs.metrics(font.weight);
        return (SheetText.baseline(m, size, py(lineTop), lineHeight * unit) - oy) / unit;
    }

    @Override
    public float[] contentArea(SheetFont font, float lineTop, float lineHeight) {
        if (mc(font)) {
            // a font pixel clear of the capitals above, and of the descenders' shadow below
            int k = fontPx(font);
            float base = minecraftBaseline(font, lineTop, lineHeight);
            return new float[]{(base - 8f * k - oy) / unit, (base + 2f * k - oy) / unit};
        }
        float size = font.size * unit;
        FontMetrics m = SheetGlyphs.metrics(font.weight);
        float baseline = SheetText.baseline(m, size, py(lineTop), lineHeight * unit);
        float top = baseline - SheetText.ascent(m, size), bottom = baseline + SheetText.descent(m, size);
        return new float[]{(top - oy) / unit, (bottom - oy) / unit};
    }

    // ------------------------------------------------------------------ clip, alpha, disabled

    @Override
    public void pushClip(float x, float y, float w, float h) {
        flush();
        int[] r = {Math.round(px(x)), Math.round(py(y)), Math.round(px(x + w)), Math.round(py(y + h))};
        if (clipDepth > 0) {
            int[] p = clips[clipDepth - 1];
            r[0] = Math.max(r[0], p[0]);
            r[1] = Math.max(r[1], p[1]);
            r[2] = Math.min(r[2], p[2]);
            r[3] = Math.min(r[3], p[3]);
        }
        clips[clipDepth++] = r;
        applyClip();
    }

    @Override
    public void popClip() {
        flush();
        if (clipDepth > 0) clipDepth--;
        applyClip();
    }

    private void applyClip() {
        if (clipDepth == 0) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            return;
        }
        int[] r = clips[clipDepth - 1];
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(r[0], displayHeight - r[3], Math.max(0, r[2] - r[0]), Math.max(0, r[3] - r[1]));
    }

    @Override
    public void pushAlpha(float a) {
        alphas[alphaDepth++] = alpha;
        alpha *= a;
    }

    @Override
    public void popAlpha() {
        if (alphaDepth > 0) alpha = alphas[--alphaDepth];
    }

    @Override
    public void beginDisabled(int backdrop) {
        if (disabledDepth++ == 0) this.backdrop = backdrop;
    }

    @Override
    public void endDisabled() {
        if (disabledDepth > 0) disabledDepth--;
    }
}
