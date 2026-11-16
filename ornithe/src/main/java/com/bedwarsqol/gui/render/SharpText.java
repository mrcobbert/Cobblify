package com.bedwarsqol.gui.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * Minecraft's font at a fractional number of device pixels per font pixel, drawn "sharp bilinear": from a copy of the
 * font texture enlarged a whole number of times with nearest-neighbour and sampled with linear filtering, so every
 * glyph edge gets the share of a device pixel it covers instead of a whole pixel or none. At whole steps callers keep
 * vanilla's own drawing.
 *
 * <p>Cobblify draws the glyph quads itself, one batch per string, from the game's font texture (resource packs
 * included) and {@link FontRenderer}'s own glyph widths, so layout matches {@link FontRenderer#getStringWidth}. Each
 * glyph cell gets a transparent one-font-pixel border in the copy, so linear sampling never reads a neighbour glyph.
 * The copy is premultiplied, so glyph edges do not darken. Strings the default page cannot draw (unicode glyphs,
 * the unicode font, obfuscated, underlined or struck-through text) are left to the caller.
 */
final class SharpText {

    private SharpText() {
    }

    /** {@link FontRenderer}'s default page, in texture order (cell = index). */
    private static final String PAGE = "ÀÁÂÈÊËÍÓÔÕÚßãõğİıŒœŞşŴŵžȇ\u0000\u0000\u0000\u0000\u0000\u0000\u0000 !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~\u0000ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜø£Ø×ƒáíóúñÑªº¿®¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αβΓπΣσμτΦΘΩδ∞∅∈∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■\u0000";

    private static final ResourceLocation FONT = new ResourceLocation("textures/font/ascii.png");

    /** Above this many device pixels per font pixel the copy would be huge, and plain scaling is already even. */
    static final float MAX_SCALE = 8f;

    /** Same table as {@link FontRenderer}'s colour codes (no anaglyph). */
    private static final int[] CODE_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    /** GL texture per enlargement (index = texels per font pixel in the copy), 0 = not built. */
    private static final int[] TEX = new int[(int) MAX_SCALE + 1];
    private static int[] source;
    private static int sourceW;
    private static boolean sourceTried, listening;

    /**
     * The texels per font pixel to sample from at {@code t} device pixels per font pixel: the whole number just below
     * {@code t} when {@code t} is little over it, else the one above. Measured against exact area coverage, plain
     * bilinear from the next whole multiple under-samples just above a whole step (6/255 mean at 1.06x), where the one
     * below is nearly exact (0.5/255); from a third of a step on, the one above wins.
     */
    static int texelsPerFontPixel(float t) {
        int below = (int) Math.floor(t);
        int n = t - below < 0.34f ? below : below + 1;
        return Math.max(1, Math.min((int) MAX_SCALE, n));
    }

    /**
     * Draws {@code s} at the current origin, {@code t} device pixels per font pixel (the caller has scaled the matrix
     * by {@code t}). {@code shadowArgb} with {@code ownShadow}: that shadow under every run; else {@code shadow} gives
     * vanilla's darkened shadow. Returns false, drawing nothing, when the string needs vanilla's renderer.
     */
    static boolean draw(FontRenderer fr, String s, float t, int argb, boolean shadow, boolean ownShadow, int shadowArgb) {
        if (fr.getUnicodeFlag() || fr.getBidiFlag() || !supported(s)) return false;
        int n = texelsPerFontPixel(t);
        int tex = texture(n);
        if (tex == 0) return false;
        boolean alphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        GlStateManager.disableAlpha(); // keep the faint edge pixels vanilla's alpha test would drop
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA); // the copy is premultiplied
        GlStateManager.bindTexture(tex);
        GlStateManager.color(1f, 1f, 1f, 1f);
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        if (ownShadow) emit(wr, fr, s, 1f, 1f, shadowArgb, false);
        else if (shadow) emit(wr, fr, s, 1f, 1f, argb, true);
        emit(wr, fr, s, 0f, 0f, argb, false);
        tess.draw();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (alphaTest) GlStateManager.enableAlpha();
        GlStateManager.resetColor();
        return true;
    }

    private static boolean supported(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(++i));
                if (code == 'k' || code == 'm' || code == 'n') return false;
                continue;
            }
            if (c == ' ') continue;
            if (c == 0 || PAGE.indexOf(c) < 0) return false;
        }
        return true;
    }

    /** One pass of {@code s} as vanilla's renderStringAtPos lays it out, offset by (ox, oy) font pixels. */
    private static void emit(WorldRenderer wr, FontRenderer fr, String s, float ox, float oy, int baseArgb, boolean darken) {
        float a = (baseArgb >>> 24) / 255f;
        int base = baseArgb & 0xFFFFFF;
        int rgb = darken ? (base & 0xFCFCFC) >> 2 : base;
        boolean bold = false, italic = false;
        float pen = ox;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(++i));
                int ci = "0123456789abcdefklmnor".indexOf(code);
                if (ci < 16) { // a colour, or as vanilla, an unknown code means white
                    if (ci < 0) ci = 15;
                    bold = false;
                    italic = false;
                    rgb = darken ? (CODE_RGB[ci] & 0xFCFCFC) >> 2 : CODE_RGB[ci];
                } else if (code == 'l') {
                    bold = true;
                } else if (code == 'o') {
                    italic = true;
                } else if (code == 'r') {
                    bold = false;
                    italic = false;
                    rgb = darken ? (base & 0xFCFCFC) >> 2 : base;
                }
                continue;
            }
            int adv = fr.getCharWidth(c);
            if (c != ' ') {
                int cell = PAGE.indexOf(c);
                glyph(wr, cell, pen, oy, italic, rgb, a);
                if (bold) glyph(wr, cell, pen + 1f, oy, italic, rgb, a);
            }
            pen += adv + (bold ? 1 : 0);
        }
    }

    /** Cell {@code cell} with its border: 10 font pixels square from one up and left of (x, y). Premultiplied colour. */
    private static void glyph(WorldRenderer wr, int cell, float x, float y, boolean italic, int rgb, float a) {
        float u0 = (cell % 16) / 16f, v0 = (cell / 16) / 16f, u1 = u0 + 1f / 16f, v1 = v0 + 1f / 16f;
        float x0 = x - 1f, y0 = y - 1f, x1 = x + 9f, y1 = y + 9f;
        // vanilla shears italics one font pixel right at the top and left at the bottom of the 8 px cell
        float top = italic ? 1.25f : 0f, bottom = italic ? -1.25f : 0f;
        float r = ((rgb >> 16) & 255) / 255f * a, g = ((rgb >> 8) & 255) / 255f * a, b = (rgb & 255) / 255f * a;
        wr.pos(x0 + bottom, y1, 0).tex(u0, v1).color(r, g, b, a).endVertex();
        wr.pos(x1 + bottom, y1, 0).tex(u1, v1).color(r, g, b, a).endVertex();
        wr.pos(x1 + top, y0, 0).tex(u1, v0).color(r, g, b, a).endVertex();
        wr.pos(x0 + top, y0, 0).tex(u0, v0).color(r, g, b, a).endVertex();
    }

    /** The copy at {@code n} texels per font pixel, built on first use; 0 if the font texture cannot be read. */
    private static int texture(int n) {
        listen();
        if (TEX[n] != 0) return TEX[n];
        if (!loadSource()) return 0;
        int c = sourceW / 128;                  // texels per font pixel in the source (HD resource packs: more)
        int m = Math.max(1, (n + c - 1) / c);   // whole enlargement of the source
        int cellSrc = 8 * c, pad = c * m, cellOut = cellSrc * m + 2 * pad, size = 16 * cellOut;
        int[] out = new int[size * size];
        for (int cy = 0; cy < 16; cy++) {
            for (int cx = 0; cx < 16; cx++) {
                for (int sy = 0; sy < cellSrc; sy++) {
                    for (int sx = 0; sx < cellSrc; sx++) {
                        int p = premultiply(source[(cy * cellSrc + sy) * sourceW + cx * cellSrc + sx]);
                        if (p == 0) continue;
                        int ox = cx * cellOut + pad + sx * m, oy = cy * cellOut + pad + sy * m;
                        for (int dy = 0; dy < m; dy++) {
                            int row = (oy + dy) * size + ox;
                            for (int dx = 0; dx < m; dx++) out[row + dx] = p;
                        }
                    }
                }
            }
        }
        int id = TextureUtil.glGenTextures();
        TextureUtil.allocateTexture(id, size, size);
        TextureUtil.uploadTexture(id, out, size, size);
        GlStateManager.bindTexture(id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        TEX[n] = id;
        return id;
    }

    private static int premultiply(int argb) {
        int a = argb >>> 24;
        if (a == 0) return 0;
        int r = ((argb >> 16) & 255) * a / 255, g = ((argb >> 8) & 255) * a / 255, b = (argb & 255) * a / 255;
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static boolean loadSource() {
        if (source != null) return true;
        if (sourceTried) return false;
        sourceTried = true;
        try (InputStream in = Minecraft.getMinecraft().getResourceManager().getResource(FONT).getInputStream()) {
            BufferedImage img = TextureUtil.readBufferedImage(in);
            int w = img.getWidth(), h = img.getHeight();
            if (w < 128 || w % 128 != 0 || h != w) return false;
            source = img.getRGB(0, 0, w, h, null, 0, w);
            sourceW = w;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** A resource reload (a resource pack change) can swap the font texture: drop the copies. */
    private static void listen() {
        if (listening) return;
        listening = true;
        IResourceManager rm = Minecraft.getMinecraft().getResourceManager();
        if (rm instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) rm).registerReloadListener(r -> clear());
        }
    }

    private static void clear() {
        for (int i = 0; i < TEX.length; i++) {
            if (TEX[i] != 0) TextureUtil.deleteTexture(TEX[i]);
            TEX[i] = 0;
        }
        source = null;
        sourceTried = false;
    }
}
