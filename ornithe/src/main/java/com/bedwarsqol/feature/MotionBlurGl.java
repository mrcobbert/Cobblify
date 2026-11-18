package com.bedwarsqol.feature;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Motion Blur's GL pass: copies the world from whatever framebuffer is bound into its own display-sized RGBA8
 * texture with {@code glCopyTexSubImage2D}, then draws a clip-space quad with the blur program back into the same
 * buffer. It never binds a framebuffer and never calls {@code Framebuffer.bindFramebuffer} or
 * {@code framebufferRender*}, so Argentum's Direct Rendering (window bound) and the main framebuffer take the same
 * path. Matrices and viewport are untouched.
 *
 * <p>State: every change goes through {@link GlStateManager} or {@link OpenGlHelper} where they wrap the call
 * (scissor is not wrapped). The prior values are read on entry and restored in a {@code finally}: program, depth test
 * and mask, blend enable, alpha test, cull, scissor, colour mask, the unit-0 2D binding and the active unit. Never
 * touched: fog, lighting, the blend function, colour and equation, stencil, units 1-2, client arrays and VBOs.
 *
 * <p>Fallbacks: shaders unsupported, a compile or link failure, a texture allocation error, a GL error in the one-shot
 * check of the first pass, or a {@link RuntimeException} each call {@link MotionBlur#fail} (logged once; the effect
 * stays off for the session) and free what is held. The quad is drawn only after the copy, and only with a linked
 * program. Render thread only.
 */
final class MotionBlurGl {

    private static final int INFO_LOG_LENGTH = 32768;
    private static final int MAX_DRAINED_ERRORS = 32;

    private static int program;
    private static int locScene = -1, locSmear = -1, locTaps = -1;
    private static int texture;
    private static int texWidth, texHeight;
    /** Whether the one-shot error check after the first pass has run (again after a release). */
    private static boolean checked;

    private static FloatBuffer smearBuf, tapsBuf;
    private static ByteBuffer maskBuf;

    private MotionBlurGl() {
    }

    /**
     * Runs one world pass into the bound framebuffer, {@code width} x {@code height} pixels; {@code smearX/Y} is the
     * smear in screen fractions. Returns whether the blurred frame was drawn.
     */
    static boolean run(float smearX, float smearY, int taps, int width, int height) {
        MotionBlur.countGlEntry();
        boolean drew = false;
        try {
            if (!OpenGlHelper.shadersSupported) {
                MotionBlur.fail("shaders are not supported");
            } else if (width <= 0 || height <= 0) {
                return false;
            } else if (program != 0 || link()) {
                drew = pass(smearX, smearY, taps, width, height);
            }
        } catch (RuntimeException e) {
            MotionBlur.fail("pass failed: " + e);
            drew = false;
        }
        if (MotionBlur.failed() != null) {
            freeQuietly();
            return false;
        }
        return drew;
    }

    private static boolean pass(float smearX, float smearY, int taps, int width, int height) {
        // Prior state, read once.
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean alphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (maskBuf == null) maskBuf = BufferUtils.createByteBuffer(16);
        maskBuf.clear();
        GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, maskBuf);
        boolean maskR = maskBuf.get(0) != 0, maskG = maskBuf.get(1) != 0;
        boolean maskB = maskBuf.get(2) != 0, maskA = maskBuf.get(3) != 0;
        int prevUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        boolean unitSwitched = false;
        boolean haveTex0 = false;
        int prevTex0 = 0;
        boolean drew = false;
        try {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            unitSwitched = true;
            prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            haveTex0 = true;

            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableBlend();
            GlStateManager.disableAlpha();
            GlStateManager.disableCull();
            if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GlStateManager.colorMask(true, true, true, false);

            boolean first = !checked;
            if (first) drainErrors();
            if (!ensureTexture(width, height)) return false;
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
            if (first) {
                int err = GL11.glGetError();
                if (err != GL11.GL_NO_ERROR) {
                    MotionBlur.fail("copy error 0x" + Integer.toHexString(err));
                    return false;
                }
            }

            OpenGlHelper.glUseProgram(program);
            OpenGlHelper.glUniform1i(locScene, 0);
            if (smearBuf == null) smearBuf = BufferUtils.createFloatBuffer(2);
            if (tapsBuf == null) tapsBuf = BufferUtils.createFloatBuffer(1);
            smearBuf.clear();
            smearBuf.put(smearX).put(smearY).flip();
            OpenGlHelper.glUniform2(locSmear, smearBuf);
            tapsBuf.clear();
            tapsBuf.put((float) Math.max(MotionBlurRules.MIN_TAPS, Math.min(MotionBlurShaders.LOOP_TAPS, taps))).flip();
            OpenGlHelper.glUniform1(locTaps, tapsBuf);

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(-1f, -1f);
            GL11.glVertex2f(1f, -1f);
            GL11.glVertex2f(1f, 1f);
            GL11.glVertex2f(-1f, 1f);
            GL11.glEnd();
            drew = true;
        } finally {
            OpenGlHelper.glUseProgram(prevProgram);
            GlStateManager.colorMask(maskR, maskG, maskB, maskA);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            if (cull) GlStateManager.enableCull(); else GlStateManager.disableCull();
            if (alphaTest) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha();
            if (blend) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
            GlStateManager.depthMask(depthMask);
            if (depthTest) GlStateManager.enableDepth(); else GlStateManager.disableDepth();
            if (haveTex0) GlStateManager.bindTexture(prevTex0);
            if (unitSwitched) GlStateManager.setActiveTexture(prevUnit);
        }
        if (drew && !checked) {
            checked = true;
            int err = GL11.glGetError();
            if (err != GL11.GL_NO_ERROR) {
                MotionBlur.fail("GL error 0x" + Integer.toHexString(err) + " after the first pass");
                return false;
            }
        }
        return drew;
    }

    /** Binds the scratch texture on the active unit (0), allocating it at {@code width} x {@code height} if needed. */
    private static boolean ensureTexture(int width, int height) {
        boolean fresh = texture == 0;
        if (fresh) texture = GlStateManager.generateTexture();
        GlStateManager.bindTexture(texture);
        if (!fresh && width == texWidth && height == texHeight) return true;
        drainErrors();
        if (fresh) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            texWidth = 0;
            texHeight = 0;
            MotionBlur.fail("texture allocation " + width + "x" + height + " error 0x" + Integer.toHexString(err));
            return false;
        }
        texWidth = width;
        texHeight = height;
        return true;
    }

    /** Builds and links the program; on failure logs the info log through {@link MotionBlur#fail}. */
    private static boolean link() {
        int vs = compile(OpenGlHelper.GL_VERTEX_SHADER, MotionBlurShaders.VERTEX, "vertex");
        if (vs == 0) return false;
        int fs = compile(OpenGlHelper.GL_FRAGMENT_SHADER, MotionBlurShaders.FRAGMENT, "fragment");
        if (fs == 0) {
            OpenGlHelper.glDeleteShader(vs);
            return false;
        }
        int p = OpenGlHelper.glCreateProgram();
        OpenGlHelper.glAttachShader(p, vs);
        OpenGlHelper.glAttachShader(p, fs);
        OpenGlHelper.glLinkProgram(p);
        // Flagged for deletion; freed with the program.
        OpenGlHelper.glDeleteShader(vs);
        OpenGlHelper.glDeleteShader(fs);
        if (OpenGlHelper.glGetProgrami(p, OpenGlHelper.GL_LINK_STATUS) == 0) {
            String log = OpenGlHelper.glGetProgramInfoLog(p, INFO_LOG_LENGTH);
            OpenGlHelper.glDeleteProgram(p);
            MotionBlur.fail("program link failed: " + trim(log));
            return false;
        }
        int scene = OpenGlHelper.glGetUniformLocation(p, MotionBlurShaders.U_SCENE);
        int smear = OpenGlHelper.glGetUniformLocation(p, MotionBlurShaders.U_SMEAR);
        int taps = OpenGlHelper.glGetUniformLocation(p, MotionBlurShaders.U_TAPS);
        if (scene < 0 || smear < 0 || taps < 0) {
            OpenGlHelper.glDeleteProgram(p);
            MotionBlur.fail("program uniforms missing: " + scene + " " + smear + " " + taps);
            return false;
        }
        program = p;
        locScene = scene;
        locSmear = smear;
        locTaps = taps;
        return true;
    }

    private static int compile(int type, String source, String name) {
        int shader = OpenGlHelper.glCreateShader(type);
        if (shader == 0) {
            MotionBlur.fail(name + " shader could not be created");
            return 0;
        }
        byte[] bytes = source.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = BufferUtils.createByteBuffer(bytes.length);
        buf.put(bytes).flip();
        OpenGlHelper.glShaderSource(shader, buf);
        OpenGlHelper.glCompileShader(shader);
        if (OpenGlHelper.glGetShaderi(shader, OpenGlHelper.GL_COMPILE_STATUS) == 0) {
            String log = OpenGlHelper.glGetShaderInfoLog(shader, INFO_LOG_LENGTH);
            OpenGlHelper.glDeleteShader(shader);
            MotionBlur.fail(name + " shader compile failed: " + trim(log));
            return 0;
        }
        return shader;
    }

    /** Clears errors raised before the pass, so a check after it reads only the pass's own. */
    private static void drainErrors() {
        for (int i = 0; i < MAX_DRAINED_ERRORS && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
            // drained
        }
    }

    private static String trim(String log) {
        return log == null ? "" : log.trim();
    }

    /** Frees the texture and program (the module was switched off). No GL call when nothing is held. */
    static void release() {
        if (program == 0 && texture == 0) return;
        MotionBlur.countGlEntry();
        freeQuietly();
    }

    private static void freeQuietly() {
        int p = program;
        int t = texture;
        program = 0;
        texture = 0;
        texWidth = 0;
        texHeight = 0;
        locScene = locSmear = locTaps = -1;
        checked = false;
        try {
            if (p != 0) OpenGlHelper.glDeleteProgram(p);
            if (t != 0) GlStateManager.deleteTexture(t);
        } catch (RuntimeException ignored) {
            // Freeing is best effort; the effect is off either way.
        }
    }
}
