package com.bedwarsqol.gui.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * Sidebar section icons. One horizontal alpha atlas (white-on-transparent) baked from Tabler Icons
 * (MIT) — a single 128px cell per section, in {@code SettingsGui} order: 0 Search, 1 HUD, 2 Combat,
 * 3 Visuals, 4 Stats, 5 Keybinds, 6 Settings, 7 Debug. Each icon is drawn as one GL_LINEAR-filtered quad tinted by an
 * ARGB color, exactly like {@link BedwarsQolFont} draws glyphs — so it gets the same smooth scaling and
 * tinting with no new dependency. See {@code assets/bedwarsqol/icons/LICENSE-tabler.txt}.
 */
public final class Icons {

    private Icons() {
    }

    private static final ResourceLocation ATLAS = new ResourceLocation("bedwarsqol", "icons/nav.png");
    private static final int CELLS = 8;

    /** Draws section icon {@code i} centered at (cx,cy), {@code size} px square, tinted {@code argb}. */
    public static void draw(int i, float cx, float cy, float size, int argb) {
        if (i < 0 || i >= CELLS) return;
        float a = (argb >>> 24 & 0xFF) / 255f;
        if (a == 0f) a = 1f;
        float r = (argb >> 16 & 0xFF) / 255f, g = (argb >> 8 & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        float u0 = i / (float) CELLS, u1 = (i + 1) / (float) CELLS;
        float x0 = cx - size / 2f, y0 = cy - size / 2f, x1 = cx + size / 2f, y1 = cy + size / 2f;

        Minecraft.getMinecraft().getTextureManager().bindTexture(ATLAS);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableTexture2D();
        GlStateManager.color(r, g, b, a);

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x0, y1, 0).tex(u0, 1f).endVertex();
        wr.pos(x1, y1, 0).tex(u1, 1f).endVertex();
        wr.pos(x1, y0, 0).tex(u1, 0f).endVertex();
        wr.pos(x0, y0, 0).tex(u0, 0f).endVertex();
        tess.draw();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
