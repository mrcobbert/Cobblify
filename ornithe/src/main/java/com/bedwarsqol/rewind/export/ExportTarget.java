package com.bedwarsqol.rewind.export;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;

/**
 * The OWN route's framebuffer (Rewind Export PLAN Solution 2): a colour texture and a 24-bit depth renderbuffer at the
 * export size, made with raw GL30, ARB or EXT framebuffer calls (whichever the context has, as vanilla's
 * {@code OpenGlHelper} chooses), never through vanilla's {@code Framebuffer}, which other mods patch. Game thread only.
 */
final class ExportTarget {

    static final int GL_FRAMEBUFFER = 0x8D40;
    static final int GL_RENDERBUFFER = 0x8D41;
    static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    static final int GL_DEPTH_ATTACHMENT = 0x8D00;
    static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
    static final int GL_DEPTH_COMPONENT24 = 0x81A6;

    /** Which entry points the context has: 0 GL30, 1 ARB, 2 EXT. */
    private final int api;
    final int width;
    final int height;
    private int fbo = -1;
    private int texture = -1;
    private int depth = -1;
    /** {@code glCheckFramebufferStatus} after creation. */
    final int status;

    private ExportTarget(int api, int width, int height) {
        this.api = api;
        this.width = width;
        this.height = height;
        fbo = genFramebuffer();
        texture = GL11.glGenTextures();
        GlStateManager.bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GlStateManager.bindTexture(0);
        depth = genRenderbuffer();
        bindRenderbuffer(depth);
        renderbufferStorage(GL_DEPTH_COMPONENT24, width, height);
        bindRenderbuffer(0);
        bindRaw(fbo);
        framebufferTexture2D(GL_COLOR_ATTACHMENT0, texture);
        framebufferRenderbuffer(GL_DEPTH_ATTACHMENT, depth);
        status = checkStatus();
    }

    /** A new target, or null when the context has no framebuffer objects at all. Leaves the target bound. */
    static ExportTarget create(int width, int height) {
        ContextCapabilities c = GLContext.getCapabilities();
        int api = c.OpenGL30 ? 0 : c.GL_ARB_framebuffer_object ? 1 : c.GL_EXT_framebuffer_object ? 2 : -1;
        if (api < 0) {
            return null;
        }
        return new ExportTarget(api, width, height);
    }

    boolean complete() {
        return status == GL_FRAMEBUFFER_COMPLETE;
    }

    int id() {
        return fbo;
    }

    /** The colour texture (the preview draws it into the window). */
    int texture() {
        return texture;
    }

    /** Binds the target and sets the viewport to its size. */
    void bind() {
        bindRaw(fbo);
        GlStateManager.viewport(0, 0, width, height);
    }

    /** The framebuffer bound now. */
    static int bound() {
        return GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
    }

    void bindRaw(int id) {
        switch (api) {
            case 0:
                GL30.glBindFramebuffer(GL_FRAMEBUFFER, id);
                break;
            case 1:
                ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, id);
                break;
            default:
                EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER, id);
                break;
        }
    }

    /** Frees the GL objects (the default framebuffer is bound afterwards). */
    void delete() {
        bindRaw(0);
        if (depth >= 0) {
            switch (api) {
                case 0:
                    GL30.glDeleteRenderbuffers(depth);
                    break;
                case 1:
                    ARBFramebufferObject.glDeleteRenderbuffers(depth);
                    break;
                default:
                    EXTFramebufferObject.glDeleteRenderbuffersEXT(depth);
                    break;
            }
        }
        if (texture >= 0) {
            GlStateManager.deleteTexture(texture);
        }
        if (fbo >= 0) {
            switch (api) {
                case 0:
                    GL30.glDeleteFramebuffers(fbo);
                    break;
                case 1:
                    ARBFramebufferObject.glDeleteFramebuffers(fbo);
                    break;
                default:
                    EXTFramebufferObject.glDeleteFramebuffersEXT(fbo);
                    break;
            }
        }
        fbo = texture = depth = -1;
    }

    String apiName() {
        return api == 0 ? "GL30" : api == 1 ? "ARB" : "EXT";
    }

    private int genFramebuffer() {
        return api == 0 ? GL30.glGenFramebuffers() : api == 1 ? ARBFramebufferObject.glGenFramebuffers()
                : EXTFramebufferObject.glGenFramebuffersEXT();
    }

    private int genRenderbuffer() {
        return api == 0 ? GL30.glGenRenderbuffers() : api == 1 ? ARBFramebufferObject.glGenRenderbuffers()
                : EXTFramebufferObject.glGenRenderbuffersEXT();
    }

    private void bindRenderbuffer(int id) {
        switch (api) {
            case 0:
                GL30.glBindRenderbuffer(GL_RENDERBUFFER, id);
                break;
            case 1:
                ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, id);
                break;
            default:
                EXTFramebufferObject.glBindRenderbufferEXT(GL_RENDERBUFFER, id);
                break;
        }
    }

    private void renderbufferStorage(int format, int w, int h) {
        switch (api) {
            case 0:
                GL30.glRenderbufferStorage(GL_RENDERBUFFER, format, w, h);
                break;
            case 1:
                ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER, format, w, h);
                break;
            default:
                EXTFramebufferObject.glRenderbufferStorageEXT(GL_RENDERBUFFER, format, w, h);
                break;
        }
    }

    private void framebufferTexture2D(int attachment, int tex) {
        switch (api) {
            case 0:
                GL30.glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, tex, 0);
                break;
            case 1:
                ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, tex, 0);
                break;
            default:
                EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, tex, 0);
                break;
        }
    }

    private void framebufferRenderbuffer(int attachment, int rb) {
        switch (api) {
            case 0:
                GL30.glFramebufferRenderbuffer(GL_FRAMEBUFFER, attachment, GL_RENDERBUFFER, rb);
                break;
            case 1:
                ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER, attachment, GL_RENDERBUFFER, rb);
                break;
            default:
                EXTFramebufferObject.glFramebufferRenderbufferEXT(GL_FRAMEBUFFER, attachment, GL_RENDERBUFFER, rb);
                break;
        }
    }

    private int checkStatus() {
        return api == 0 ? GL30.glCheckFramebufferStatus(GL_FRAMEBUFFER)
                : api == 1 ? ARBFramebufferObject.glCheckFramebufferStatus(GL_FRAMEBUFFER)
                : EXTFramebufferObject.glCheckFramebufferStatusEXT(GL_FRAMEBUFFER);
    }
}
