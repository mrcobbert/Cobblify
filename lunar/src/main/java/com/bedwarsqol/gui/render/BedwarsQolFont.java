package com.bedwarsqol.gui.render;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Renders the bundled <b>Inter</b> font (the family OneConfig uses) from pre-baked glyph atlases —
 * one per weight ({@link Weight#REGULAR}, {@link Weight#MEDIUM}) for typographic hierarchy. No
 * runtime AWT, so it's cross-platform safe (identical on Windows/macOS, never touches the
 * {@code -XstartOnFirstThread} run loop that crashes AWT on Mac). Atlases load through Minecraft's
 * normal texture pipeline; metrics come from BMFont-style {@code .fnt} files. If a face is missing
 * it transparently falls back to the vanilla {@link FontRenderer}, so text never disappears.
 *
 * <p>Normalized so {@code scale = 1} ≈ 9px line height — a drop-in for Minecraft's font, which keeps
 * the HUD layout maths valid. Pass larger scales for GUI text.
 *
 * <p>With the Font setting on Minecraft, text is the game's own {@link FontRenderer} (resource packs included)
 * instead, at whole device pixels per font pixel ({@link #textScale}) on whole device pixels, shadowed as vanilla
 * shadows light text.
 */
public final class BedwarsQolFont {

    private BedwarsQolFont() {
    }

    public enum Weight { REGULAR, MEDIUM, BOLD }

    /** On-screen line height (px) at scale 1.0 — matches the old 9px font for layout parity. */
    private static final float PX_LINE = 9f;

    private static final Face REGULAR = new Face(
            new ResourceLocation("bedwarsqol", "font/inter.png"), "/assets/bedwarsqol/font/inter.fnt");
    private static final Face MEDIUM = new Face(
            new ResourceLocation("bedwarsqol", "font/inter_medium.png"), "/assets/bedwarsqol/font/inter_medium.fnt");
    private static final Face BOLD = new Face(
            new ResourceLocation("bedwarsqol", "font/inter_semibold.png"), "/assets/bedwarsqol/font/inter_semibold.fnt");

    private static Face face(Weight w) {
        if (w == Weight.BOLD) return BOLD;
        return w == Weight.MEDIUM ? MEDIUM : REGULAR;
    }

    /** The height text takes: its 9 px line, or in Minecraft's font its capitals and their shadow (8 font px). */
    public static float height(float scale) {
        if (minecraft()) return 8f * textScale(scale);
        return PX_LINE * scale;
    }

    // ------------------------------------------------------------------ Minecraft's font (the Font setting)

    /** Whether text is Minecraft's own font. */
    public static boolean minecraft() {
        ClientSettings c = BedwarsQol.config;
        return c != null && c.minecraftFont();
    }

    /**
     * The scale text is really drawn at: {@code scale} with the modern font; in Minecraft's font the nearest whole
     * number of device pixels per font pixel, so the pixel font stays crisp. Lay text out at this scale.
     */
    public static float textScale(float scale) {
        if (!minecraft()) return scale;
        int sf = scaleFactor();
        return Math.max(1, Math.round(scale * sf)) / (float) sf;
    }

    /** As {@link #textScale}, but rounding down in Minecraft's font: for text that must fit a fixed space. */
    public static float textScaleAtMost(float scale) {
        if (!minecraft()) return scale;
        int sf = scaleFactor();
        return Math.max(1, (int) Math.floor(scale * sf + 1e-3f)) / (float) sf;
    }

    private static int scaleFactor() {
        return new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
    }

    /** Minecraft shadows light text; under dark text (on a pale accent) a shadow only smudges it. */
    private static boolean shadowed(int argb) {
        return ((argb >> 16) & 255) + ((argb >> 8) & 255) + (argb & 255) >= 3 * 0x40;
    }

    /**
     * {@code s} in Minecraft's font over a shadow in {@code shadow}, one font pixel down and right, for text on a
     * coloured surface: vanilla darkens the text's own colour, which under dark text only smudges it. Minecraft's
     * font only; the modern font draws nothing here.
     */
    public static void drawMinecraftOver(String s, float x, float y, float scale, int color, int shadow) {
        FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;
        if (minecraft() && fr != null) drawMinecraft(fr, s, x, y, scale, color, true, shadow);
    }

    /** {@code s} in Minecraft's font with its top-left on the device pixel nearest (x, y), in the GUI's space. */
    private static void drawMinecraft(FontRenderer fr, String s, float x, float y, float scale, int color) {
        drawMinecraft(fr, s, x, y, scale, color, false, 0);
    }

    private static void drawMinecraft(FontRenderer fr, String s, float x, float y, float scale, int color,
                                      boolean ownShadow, int shadow) {
        int a = color >>> 24;
        // FontRenderer draws under 4/255 alpha opaque and alpha-tests away 0.1 and under; no alpha means opaque here
        if (a != 0 && a <= 25) return;
        int sf = scaleFactor();
        int k = Math.round(textScale(scale) * sf);
        boolean alphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        GlStateManager.enableTexture2D(); // FontRenderer assumes texturing on
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.pushMatrix();
        GlStateManager.scale(1f / sf, 1f / sf, 1f);
        GlStateManager.translate(Math.round(x * sf), Math.round(y * sf), 0f);
        GlStateManager.scale(k, k, 1f);
        int argb = a == 0 ? color | 0xFF000000 : color;
        if (ownShadow) {
            fr.drawString(s, 1f, 1f, (argb & 0xFF000000) | (shadow & 0xFFFFFF), false);
            fr.drawString(s, 0f, 0f, argb, false);
        } else {
            fr.drawString(s, 0f, 0f, argb, shadowed(color));
        }
        GlStateManager.popMatrix();
        if (!alphaTest) GlStateManager.disableAlpha(); // it turns the test on, which would drop faint fills after it
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** Top offset (px at {@code scale}) of a capital glyph within the line box — the empty gap above the
     *  caps. With {@link #capHeight} this gives the visible (cap-top → baseline) bounds, so callers can
     *  optically centre text by its visible mass instead of the full line box, which reserves unused
     *  descender space and makes centred text read slightly high. Measured from 'H'; falls back to 0. */
    public static float capTop(float scale, Weight weight) {
        Face f = face(weight);
        f.ensure();
        return (f.ready && f.has['H']) ? f.gyo['H'] * f.k * scale : 0f;
    }

    /** Visible cap height (px at {@code scale}), measured from 'H'. Falls back to the full line height. */
    public static float capHeight(float scale, Weight weight) {
        Face f = face(weight);
        f.ensure();
        return (f.ready && f.has['H']) ? f.gh['H'] * f.k * scale : height(scale);
    }

    public static float width(String s) {
        return width(s, 1f, Weight.REGULAR);
    }

    public static float width(String s, float scale) {
        return width(s, scale, Weight.REGULAR);
    }

    public static float width(String s, float scale, Weight weight) {
        if (minecraft()) {
            FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;
            return fr == null ? 0f : fr.getStringWidth(s) * textScale(scale);
        }
        Face f = face(weight);
        f.ensure();
        FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;
        if (!f.ready) {
            return fr == null ? 0f : fr.getStringWidth(s) * scale;
        }
        float gs = f.k * scale;
        float w = 0f;
        for (int idx = 0; idx < s.length(); idx++) {
            char c = s.charAt(idx);
            if (c == '§' && idx + 1 < s.length()) {
                idx++;
                continue;
            }
            if (c < 256 && f.has[c]) {
                w += f.gxa[c] * gs;                       // Inter glyph
            } else if (fr != null) {
                w += fr.getCharWidth(c) * scale;          // vanilla fallback glyph (symbols/unicode)
            }
        }
        return w;
    }

    public static void draw(String s, float x, float y, float scale, int color, boolean shadow) {
        draw(s, x, y, scale, color, shadow, Weight.REGULAR);
    }

    public static void draw(String s, float x, float y, float scale, int color, boolean shadow, Weight weight) {
        if (minecraft()) {
            FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;
            if (fr != null) drawMinecraft(fr, s, x, y, scale, color);
            return;
        }
        Face f = face(weight);
        f.ensure();
        if (!f.ready) {
            FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;
            if (fr == null) return;
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, 0f);
            GlStateManager.scale(scale, scale, 1f);
            if (shadow) fr.drawStringWithShadow(s, 0f, 0f, color);
            else fr.drawString(s, 0, 0, color);
            GlStateManager.popMatrix();
            GlStateManager.color(1f, 1f, 1f, 1f);
            return;
        }
        if (shadow) {
            float off = Math.max(1f, scale);
            f.drawGlyphs(s, x + off, y + off, scale, color, true);
        }
        f.drawGlyphs(s, x, y, scale, color, false);
    }

    /** Standard Minecraft §0-§f colour table, indexed by the digit/letter in "0123456789abcdef". */
    private static final int[] CODE_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    private static int codeIndex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    // ------------------------------------------------------------------ per-weight face

    private static final class Face {
        private final ResourceLocation atlas;
        private final String fnt;

        private boolean tried;
        private boolean ready;
        private int atlasW, atlasH, bakedLine;
        private float k;

        private final float[] gx = new float[256];
        private final float[] gy = new float[256];
        private final float[] gw = new float[256];
        private final float[] gh = new float[256];
        private final float[] gxo = new float[256];
        private final float[] gyo = new float[256];
        private final float[] gxa = new float[256];
        private final boolean[] has = new boolean[256];

        Face(ResourceLocation atlas, String fnt) {
            this.atlas = atlas;
            this.fnt = fnt;
        }

        void ensure() {
            if (tried) return;
            tried = true;
            try (InputStream in = BedwarsQolFont.class.getResourceAsStream(fnt)) {
                if (in == null) return;
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("info")) {
                        bakedLine = tok(line, "line=");
                        atlasW = tok(line, "scaleW=");
                        atlasH = tok(line, "scaleH=");
                    } else if (line.startsWith("char")) {
                        int id = tok(line, "id=");
                        if (id < 0 || id > 255) continue;
                        gx[id] = tok(line, "x=");
                        gy[id] = tok(line, "y=");
                        gw[id] = tok(line, "w=");
                        gh[id] = tok(line, "h=");
                        gxo[id] = tok(line, "xo=");
                        gyo[id] = tok(line, "yo=");
                        gxa[id] = tok(line, "xa=");
                        has[id] = true;
                    }
                }
                if (bakedLine <= 0 || atlasW <= 0 || atlasH <= 0) return;
                // Weave doesn't register the mod's assets/ domain, so the resource manager can't find
                // the atlas. Load the PNG off the classpath and register it with the texture manager
                // under the same ResourceLocation, so bindAtlas()'s bindTexture(atlas) resolves to it.
                String atlasPath = "/assets/" + atlas.getResourceDomain() + "/" + atlas.getResourcePath();
                if (BedwarsQolFont.class.getResource(atlasPath) == null) return;
                Minecraft.getMinecraft().getTextureManager().loadTexture(atlas, new ClasspathTexture(atlasPath));
                k = PX_LINE / (float) bakedLine;
                ready = true;
            } catch (Exception ignored) {
                ready = false;
            }
        }

        int mapped(char c) {
            int ci = c < 256 ? c : '?';
            return has[ci] ? ci : '?';
        }

        private void bindAtlas() {
            Minecraft.getMinecraft().getTextureManager().bindTexture(atlas);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        }

        void drawGlyphs(String s, float x, float y, float scale, int color, boolean shadowPass) {
            float gs = k * scale;
            int alphaByte = (color >>> 24) & 0xFF;
            if (alphaByte == 0) alphaByte = 0xFF;
            float a = alphaByte / 255f;
            int baseRgb = color & 0xFFFFFF;
            int curRgb = baseRgb; // updated as §-codes are encountered
            FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;

            bindAtlas();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.enableTexture2D();
            // Per-vertex colour (POSITION_TEX_COLOR) so a single batch can carry §-coloured runs; the
            // glColor stays white and tinting is done per glyph.
            GlStateManager.color(1f, 1f, 1f, 1f);

            Tessellator tess = Tessellator.getInstance();
            WorldRenderer wr = tess.getWorldRenderer();
            boolean batching = false;
            float pen = x;
            for (int idx = 0; idx < s.length(); idx++) {
                char c = s.charAt(idx);
                if (c == '§' && idx + 1 < s.length()) {
                    char code = s.charAt(++idx);
                    int ci2 = codeIndex(code);
                    if (ci2 >= 0) curRgb = CODE_RGB[ci2];
                    else if (code == 'r' || code == 'R') curRgb = baseRgb;
                    continue; // k/l/m/n/o styling codes are ignored (colour only)
                }
                int drawRgb = shadowPass ? ((curRgb & 0xFCFCFC) >> 2) : curRgb;
                if (c < 256 && has[c]) {
                    if (!batching) {
                        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                        batching = true;
                    }
                    float r = (drawRgb >> 16 & 0xFF) / 255f;
                    float g = (drawRgb >> 8 & 0xFF) / 255f;
                    float b = (drawRgb & 0xFF) / 255f;
                    float w = gw[c], h = gh[c];
                    if (w > 0 && h > 0) {
                        float sx0 = pen + gxo[c] * gs;
                        float sy0 = y + gyo[c] * gs;
                        float sx1 = sx0 + w * gs;
                        float sy1 = sy0 + h * gs;
                        float u0 = gx[c] / atlasW, v0 = gy[c] / atlasH;
                        float u1 = (gx[c] + w) / atlasW, v1 = (gy[c] + h) / atlasH;
                        wr.pos(sx0, sy1, 0).tex(u0, v1).color(r, g, b, a).endVertex();
                        wr.pos(sx1, sy1, 0).tex(u1, v1).color(r, g, b, a).endVertex();
                        wr.pos(sx1, sy0, 0).tex(u1, v0).color(r, g, b, a).endVertex();
                        wr.pos(sx0, sy0, 0).tex(u0, v0).color(r, g, b, a).endVertex();
                    }
                    pen += gxa[c] * gs;
                } else if (fr != null) {
                    // Glyph absent from the Inter atlas (symbols ✓ ☆ ▪, unicode): draw it with the
                    // vanilla font, which has full coverage. Flush the atlas batch first, then resume.
                    if (batching) { tess.draw(); batching = false; }
                    int col = (alphaByte << 24) | (drawRgb & 0xFFFFFF);
                    GlStateManager.pushMatrix();
                    GlStateManager.translate(pen, y, 0f);
                    GlStateManager.scale(scale, scale, 1f);
                    fr.drawString(String.valueOf(c), 0, 0, col);
                    GlStateManager.popMatrix();
                    pen += fr.getCharWidth(c) * scale;
                    bindAtlas();
                    GlStateManager.enableBlend();
                    GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    GlStateManager.color(1f, 1f, 1f, 1f);
                }
            }
            if (batching) tess.draw();
            GlStateManager.color(1f, 1f, 1f, 1f);
        }

        private static int tok(String s, String key) {
            int i = s.indexOf(key);
            if (i < 0) return 0;
            i += key.length();
            int j = i;
            while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '-')) j++;
            try {
                return Integer.parseInt(s.substring(i, j));
            } catch (Exception e) {
                return 0;
            }
        }
    }
}
