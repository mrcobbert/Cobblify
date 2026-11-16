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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minecraft's font at a fractional number of device pixels per font pixel, drawn with exact area coverage: every
 * device pixel gets the share of it a glyph's square pixels cover, the way a vector font of the same glyphs would draw
 * them. A copy of the font texture is box-filtered for the size (one device pixel wide) on a fine grid and sampled with
 * linear filtering at pixel centres. At whole steps callers keep vanilla's own drawing.
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

    /** Their shadows: a quarter of each, but gold's without its extra red, as vanilla's table has it. */
    private static int shadowOf(int code) {
        return code == 6 ? 0x2A2A00 : (CODE_RGB[code] & 0xFCFCFC) >> 2;
    }

    /**
     * Texels per font pixel in every copy. Bilinear between grid points this fine stays within ~0.7/255 of exact
     * coverage at every size (Java sim against the real Map Info and FPS pixels, 2026-11-15); sampling a whole-number
     * enlargement instead spread edges over two pixels just above a whole step, which read as blur.
     */
    private static final int GRID = 8;

    /** Sizes are matched to 1/16 device pixel per font pixel: the filter width then differs by at most 3 %. */
    private static final int SIZE_KEY = 16;

    /** Copies kept, most recently used first; each is 1280 px square (6.5 MB). */
    private static final int CACHE = 8;

    /** GL texture per size key (-1 = cannot be built), least recently used first; evicted copies are deleted. */
    private static final Map<Integer, Integer> COPIES = new LinkedHashMap<Integer, Integer>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
            if (size() <= CACHE) return false;
            if (eldest.getValue() > 0) TextureUtil.deleteTexture(eldest.getValue());
            return true;
        }
    };
    private static int[] source; // premultiplied
    private static int sourceW;
    private static boolean sourceTried, listening;

    /**
     * Draws {@code s} at the current origin, {@code t} device pixels per font pixel (the caller has scaled the matrix
     * by {@code t}). {@code shadowArgb} with {@code ownShadow}: that shadow under every run; else {@code shadow} gives
     * vanilla's darkened shadow. Returns false, drawing nothing, when the string needs vanilla's renderer.
     */
    static boolean draw(FontRenderer fr, String s, float t, int argb, boolean shadow, boolean ownShadow, int shadowArgb) {
        if (fr.getUnicodeFlag() || fr.getBidiFlag() || !supported(s)) return false;
        int tex = texture(Math.round(t * SIZE_KEY));
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
                    rgb = darken ? shadowOf(ci) : CODE_RGB[ci];
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

    /**
     * The copy for size key {@code key} ({@code key / SIZE_KEY} device px per font px), built on first use; 0 if the
     * font texture cannot be read (then -1 is cached, so it is not tried every frame).
     */
    private static int texture(int key) {
        listen();
        Integer have = COPIES.get(key);
        if (have != null) return Math.max(0, have);
        if (!loadSource()) return 0;
        int c = sourceW / 128;                       // texels per font pixel in the source (HD resource packs: more)
        int cellSrc = 8 * c, cellOut = 10 * GRID, size = 16 * cellOut; // each cell with a one-font-pixel border
        if (size > GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)) {
            COPIES.put(key, -1);
            return 0;
        }
        // Box filter one device pixel wide, separable: for output column X (a grid point, in font px from the cell's
        // border) the source texels its box overlaps and how much of the box each covers. Outside the cell is clear.
        float half = 0.5f * SIZE_KEY / key;          // half a device pixel, in font px
        int[][] taps = new int[cellOut][];
        float[][] weights = new float[cellOut][];
        for (int x = 0; x < cellOut; x++) {
            float centre = (x + 0.5f) / GRID - 1f;
            float lo = (centre - half) * c, hi = (centre + half) * c;
            int j0 = Math.max(0, (int) Math.floor(lo)), j1 = Math.min(cellSrc, (int) Math.ceil(hi));
            int n = Math.max(0, j1 - j0);
            taps[x] = new int[n];
            weights[x] = new float[n];
            for (int k = 0; k < n; k++) {
                int j = j0 + k;
                taps[x][k] = j;
                weights[x][k] = (Math.min(j + 1, hi) - Math.max(j, lo)) / (hi - lo);
            }
        }
        int[] out = new int[size * size];
        float[] rows = new float[cellSrc * cellOut * 4];
        for (int cy = 0; cy < 16; cy++) {
            for (int cx = 0; cx < 16; cx++) {
                int base = cy * cellSrc * sourceW + cx * cellSrc;
                boolean empty = true;
                for (int sy = 0; sy < cellSrc && empty; sy++) {
                    for (int sx = 0; sx < cellSrc; sx++) {
                        if (source[base + sy * sourceW + sx] != 0) {
                            empty = false;
                            break;
                        }
                    }
                }
                if (empty) continue;
                for (int sy = 0; sy < cellSrc; sy++) {         // across: source rows onto the grid's columns
                    for (int x = 0; x < cellOut; x++) {
                        float a = 0, r = 0, g = 0, b = 0;
                        for (int k = 0; k < taps[x].length; k++) {
                            int p = source[base + sy * sourceW + taps[x][k]];
                            float w = weights[x][k];
                            a += w * (p >>> 24);
                            r += w * ((p >> 16) & 255);
                            g += w * ((p >> 8) & 255);
                            b += w * (p & 255);
                        }
                        int o = (sy * cellOut + x) * 4;
                        rows[o] = a;
                        rows[o + 1] = r;
                        rows[o + 2] = g;
                        rows[o + 3] = b;
                    }
                }
                for (int y = 0; y < cellOut; y++) {            // down: those rows onto the grid's rows
                    int row = (cy * cellOut + y) * size + cx * cellOut;
                    for (int x = 0; x < cellOut; x++) {
                        float a = 0, r = 0, g = 0, b = 0;
                        for (int k = 0; k < taps[y].length; k++) {
                            int o = (taps[y][k] * cellOut + x) * 4;
                            float w = weights[y][k];
                            a += w * rows[o];
                            r += w * rows[o + 1];
                            g += w * rows[o + 2];
                            b += w * rows[o + 3];
                        }
                        out[row + x] = channel(a) << 24 | channel(r) << 16 | channel(g) << 8 | channel(b);
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
        COPIES.put(key, id);
        return id;
    }

    private static int channel(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
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
            for (int i = 0; i < source.length; i++) source[i] = premultiply(source[i]);
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
        for (int id : COPIES.values()) {
            if (id > 0) TextureUtil.deleteTexture(id);
        }
        COPIES.clear();
        source = null;
        sourceTried = false;
    }
}
