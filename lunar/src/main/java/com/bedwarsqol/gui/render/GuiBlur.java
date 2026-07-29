package com.bedwarsqol.gui.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

/**
 * Background blur for the custom GUI screens, done with raw OpenGL on the framebuffer — deliberately NOT
 * via Minecraft's {@code ShaderGroup}/{@code .json} post-pipeline. That pipeline loads its shader files
 * through the resource manager, which (a) silently no-ops on a vanilla "vertex":"blur" + custom fragment
 * mismatch, and (b) can't find a mod's assets at all under Weave/Lunar (the same reason {@link Icons}
 * needs {@code ClasspathTexture}). This class touches no assets and no resource manager, so the SAME code
 * runs identically on Forge and on the Lunar/Weave port.
 *
 * <p>Technique: a GLSL-free "dual filter" blur. Each frame, while the GUI is open, we progressively
 * downsample the freshly-rendered world framebuffer (1/2 → 1/4 → 1/8) into scratch FBOs using GL_LINEAR
 * filtering — each halving box-averages 2x2 texels — then upsample back (1/8 → 1/4 → 1/2 → main). The
 * down/up scaling with bilinear filtering is what produces a wide, smooth, Gaussian-like blur, with no
 * shader program to compile. The final composite back onto the main framebuffer is alpha-blended by an
 * eased {@code 0→1} factor over {@link #FADE_MS}, so the world fades from sharp to blurred when the GUI
 * opens. It reuses the exact fixed-function {@link Tessellator} textured-quad path {@link Icons} uses.
 *
 * <p>Lifecycle (called from the shared {@code SettingsGui}): {@link #begin()} in {@code initGui} starts
 * the fade; {@link #update()} once per frame at the top of {@code drawScreen} renders the blur onto the
 * main framebuffer (before the panel draws on top); {@link #end()} in {@code onGuiClosed} frees the FBOs.
 */
public final class GuiBlur {

    private GuiBlur() {
    }

    private static final long FADE_MS = 220L;

    // Blend-color enums (GL 1.4 / ARB_imaging); this LWJGL 2 build exposes glBlendColor but not the enums.
    private static final int GL_CONSTANT_ALPHA = 0x8003;
    private static final int GL_ONE_MINUS_CONSTANT_ALPHA = 0x8004;

    private static long openTimeMs;
    private static boolean active;
    private static Framebuffer half, quarter, eighth, sixteenth; // scratch targets at 1/2, 1/4, 1/8, 1/16 resolution
    private static int builtW, builtH;                // base size the scratch FBOs were built for

    /** Start the fade-in. Cheap — the scratch FBOs are (re)built lazily in {@link #update()}. */
    public static void begin() {
        if (!OpenGlHelper.isFramebufferEnabled()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return; // nothing meaningful to blur (e.g. main menu)
        openTimeMs = System.currentTimeMillis();
        active = true;
    }

    /** Renders the blur onto the main framebuffer. Best-effort — any GL hiccup just skips this frame. */
    public static void update() {
        if (!active || !OpenGlHelper.isFramebufferEnabled()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        Framebuffer main = mc.getFramebuffer();
        if (main == null || main.framebufferWidth <= 0 || main.framebufferHeight <= 0) return;
        int w = main.framebufferWidth, h = main.framebufferHeight;

        boolean pushed = false;
        try {
            ensureScratch(w, h);
            float t = Math.min(1f, (System.currentTimeMillis() - openTimeMs) / (float) FADE_MS);
            float eased = t * t * (3f - 2f * t); // smoothstep sharp→blurred

            // GUI matrices/state saved, identity matrices set so the NDC quads fill whatever FBO is bound.
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.pushMatrix();
            GlStateManager.loadIdentity();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.pushMatrix();
            GlStateManager.loadIdentity();
            pushed = true; // matrices are on the stack now; the finally must balance them even if a blit throws
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableAlpha();
            GlStateManager.disableLighting();
            GlStateManager.enableTexture2D();

            // Downsample the live world, then upsample back — bilinear at each step does the blurring.
            // Four octaves (down to 1/16) roughly double the blur radius of a 1/8 chain.
            blit(main.framebufferTexture, half, 1f, false);         // 1/2
            blit(half.framebufferTexture, quarter, 1f, false);      // 1/4
            blit(quarter.framebufferTexture, eighth, 1f, false);    // 1/8
            blit(eighth.framebufferTexture, sixteenth, 1f, false);  // 1/16
            blit(sixteenth.framebufferTexture, eighth, 1f, false);  // up → 1/8
            blit(eighth.framebufferTexture, quarter, 1f, false);    // up → 1/4
            blit(quarter.framebufferTexture, half, 1f, false);      // up → 1/2
            blit(half.framebufferTexture, main, eased, true);       // composite onto the screen, faded in
        } catch (Exception ignored) {
            // Never let a GL issue break the GUI — just render this frame without blur.
        } finally {
            // Always balance the matrix stack and restore GL state, even if a blit threw mid-way: a leaked
            // push/disabled-state would corrupt the GUI's own drawing and, over many frames, can cascade
            // into a GL_STACK_OVERFLOW.
            if (pushed) {
                GlStateManager.matrixMode(GL11.GL_PROJECTION);
                GlStateManager.popMatrix();
                GlStateManager.matrixMode(GL11.GL_MODELVIEW);
                GlStateManager.popMatrix();
                // Restore the state the GUI expects for its own (immediate-mode) drawing.
                GlStateManager.bindTexture(0);
                GlStateManager.color(1f, 1f, 1f, 1f);
                GlStateManager.enableDepth();
                GlStateManager.depthMask(true);
                GlStateManager.enableAlpha();
                GlStateManager.enableBlend();
                GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            }
        }
        // Guarantee the GUI draws to the SCREEN at full viewport. The blit chain should already leave the
        // main framebuffer bound, but if a blit (or the lazy FBO build) threw, a tiny scratch FBO can stay
        // bound with a 1/16-size viewport — content would then render into it (off-screen) and the panel
        // "wouldn't appear" while the last good blur frame still showed. Rebinding here is cheap insurance.
        Framebuffer main2 = mc.getFramebuffer();
        if (main2 != null) main2.bindFramebuffer(true);
    }

    /** Frees the scratch framebuffers and ends the fade. */
    public static void end() {
        active = false;
        deleteScratch();
    }

    /** Whether the blur is currently running (set in {@link #begin()} when a world + framebuffers exist).
     *  The GUI uses this to pick a lighter background scrim when the world behind it is blurred. */
    public static boolean isActive() {
        return active;
    }

    // ---- internals ----

    /** Draws {@code srcTex} as a fullscreen quad into {@code dst}. {@code blend} alpha-composites (used for
     *  the faded final pass); otherwise it overwrites. The source samples with GL_LINEAR + edge clamp. */
    private static void blit(int srcTex, Framebuffer dst, float alpha, boolean blend) {
        dst.bindFramebuffer(true); // bind as render target + set the viewport to its size
        GlStateManager.bindTexture(srcTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        // CLAMP_TO_EDGE (not GL_CLAMP): GL_CLAMP bleeds the transparent texture border into the edge
        // texels when down/upsampling, which left a sharp un-blurred band at the screen edges.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        if (blend) {
            // Blend by a CONSTANT alpha, not GL_SRC_ALPHA: the world framebuffer's alpha channel is
            // non-uniform (sky clears to ~0, glass/water write partial alpha) and the blur chain copies
            // it, so SRC_ALPHA blending composited the blur weakly over those regions — the sky and
            // windows showed through sharp. Constant alpha ignores texel alpha entirely.
            GlStateManager.enableBlend();
            GL14.glBlendColor(0f, 0f, 0f, alpha);
            GlStateManager.blendFunc(GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA);
        } else {
            GlStateManager.disableBlend();
        }
        GlStateManager.color(1f, 1f, 1f, 1f);

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(-1, -1, 0).tex(0, 0).endVertex();
        wr.pos(1, -1, 0).tex(1, 0).endVertex();
        wr.pos(1, 1, 0).tex(1, 1).endVertex();
        wr.pos(-1, 1, 0).tex(0, 1).endVertex();
        tess.draw();
    }

    private static void ensureScratch(int w, int h) {
        if (half != null && builtW == w && builtH == h) return;
        deleteScratch();
        builtW = w;
        builtH = h;
        half = newScratch(Math.max(1, w / 2), Math.max(1, h / 2));
        quarter = newScratch(Math.max(1, w / 4), Math.max(1, h / 4));
        eighth = newScratch(Math.max(1, w / 8), Math.max(1, h / 8));
        sixteenth = newScratch(Math.max(1, w / 16), Math.max(1, h / 16));
        Minecraft.getMinecraft().getFramebuffer().bindFramebuffer(false); // restore the main FBO binding
    }

    private static Framebuffer newScratch(int w, int h) {
        Framebuffer fb = new Framebuffer(w, h, false); // no depth buffer needed
        fb.setFramebufferFilter(GL11.GL_LINEAR);
        return fb;
    }

    private static void deleteScratch() {
        if (half != null) { half.deleteFramebuffer(); half = null; }
        if (quarter != null) { quarter.deleteFramebuffer(); quarter = null; }
        if (eighth != null) { eighth.deleteFramebuffer(); eighth = null; }
        if (sixteenth != null) { sixteenth.deleteFramebuffer(); sixteenth = null; }
        builtW = builtH = 0;
    }
}
