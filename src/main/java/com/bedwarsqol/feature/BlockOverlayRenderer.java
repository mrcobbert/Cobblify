package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.gui.render.GuiTheme;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

/**
 * Replaces the vanilla black block-selection outline with a custom highlight on the block the
 * player is looking at: the settings accent (or white), with configurable opacity, draw style
 * (outline / fill / both) and an optional see-through (depth-disabled) mode.
 *
 * <p>This renders in <b>world space</b>: {@link DrawBlockHighlightEvent} fires while the camera
 * modelview is active, so the block's world-space {@link AxisAlignedBB} is offset by the
 * interpolated camera eye (anti-jitter) and drawn directly — there is no GUI transform here, unlike
 * the immediate-mode helpers in {@code gui/render/GuiRender}. The GL blend/depth/texture dance and
 * the {@link Tessellator}/{@link WorldRenderer} {@code POSITION_COLOR} quad pattern mirror
 * {@code feature/NametagStats.drawLineAbove}.
 *
 * <p>The event is {@code @Cancelable}; we always cancel it because we draw the replacement outline
 * ourselves (otherwise the vanilla outline would still show underneath).
 */
public class BlockOverlayRenderer {

    /** Vanilla's selection-box inflation so the highlight sits just outside the block faces. */
    private static final double EXPAND = 0.0020000000949949026D;

    @SubscribeEvent
    public void onDrawBlockHighlight(DrawBlockHighlightEvent event) {
        if (BedwarsQol.config == null || !BedwarsQol.config.blockOverlayEnabled) return;

        MovingObjectPosition target = event.target;
        if (target == null || target.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;

        EntityPlayer player = event.player;
        if (player == null) return;
        World world = player.worldObj;
        if (world == null) return;

        BlockPos pos = target.getBlockPos();
        if (pos == null) return;

        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        if (block.getMaterial() == Material.air) return;

        // We are rendering the highlight ourselves; suppress the vanilla black outline.
        event.setCanceled(true);

        // World-space selection box for this block, slightly inflated like vanilla.
        block.setBlockBoundsBasedOnState(world, pos);
        AxisAlignedBB box = block.getSelectedBoundingBox(world, pos).expand(EXPAND, EXPAND, EXPAND);

        // Interpolated camera eye (anti-jitter): shift the world-space box into camera-relative space,
        // matching how vanilla RenderGlobal.drawSelectionBox subtracts the interpolated eye.
        float partialTicks = event.partialTicks;
        double eyeX = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double eyeY = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
        double eyeZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
        AxisAlignedBB aabb = box.offset(-eyeX, -eyeY, -eyeZ);

        int style = BedwarsQol.config.blockOverlayStyle; // 0 = outline, 1 = fill, 2 = both
        // the settings accent, or white; the stored colour only carries the Opacity
        int rgb = BedwarsQol.config.blockOverlayWhite ? 0xFFFFFF : GuiTheme.fromToken(BedwarsQol.config.guiAccent).base();
        int color = (BedwarsQol.config.blockOverlayColor & 0xFF000000) | (rgb & 0xFFFFFF);
        int a = (color >>> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        // ---- GL setup (mirrors NametagStats: blend on, texturing off, depth-write off) ----
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0); // SRC_ALPHA, ONE_MINUS_SRC_ALPHA
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask(false);
        boolean seeThrough = BedwarsQol.config.blockOverlaySeeThrough;
        if (seeThrough) GlStateManager.disableDepth();

        // ---- FILL (style 1 = fill-only, style 2 = both) ----
        if (style == 1 || style == 2) {
            // For "both", dim the fill so the outline still reads on top of it.
            float fillA = (style == 2 ? a * 0.35f : (float) a) / 255f;
            drawFilledBox(aabb, r / 255f, g / 255f, b / 255f, fillA);
        }

        // ---- OUTLINE (style 0 = outline-only, style 2 = both) ----
        if (style == 0 || style == 2) {
            GL11.glLineWidth(BedwarsQol.config.blockOverlayLineWidth);
            RenderGlobal.drawOutlinedBoundingBox(aabb, r, g, b, a);
        }

        // ---- restore GL state ----
        if (seeThrough) GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GL11.glLineWidth(1.0F);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** Six translucent {@code POSITION_COLOR} quads covering every face of {@code aabb}. */
    private void drawFilledBox(AxisAlignedBB aabb, float r, float g, float b, float a) {
        GlStateManager.disableCull(); // both faces of each quad must show regardless of winding
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        double x0 = aabb.minX, y0 = aabb.minY, z0 = aabb.minZ;
        double x1 = aabb.maxX, y1 = aabb.maxY, z1 = aabb.maxZ;

        // bottom (y = y0)
        wr.pos(x0, y0, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y0, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y0, z1).color(r, g, b, a).endVertex();
        wr.pos(x0, y0, z1).color(r, g, b, a).endVertex();
        // top (y = y1)
        wr.pos(x0, y1, z0).color(r, g, b, a).endVertex();
        wr.pos(x0, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z0).color(r, g, b, a).endVertex();
        // north (z = z0)
        wr.pos(x0, y0, z0).color(r, g, b, a).endVertex();
        wr.pos(x0, y1, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y0, z0).color(r, g, b, a).endVertex();
        // south (z = z1)
        wr.pos(x0, y0, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y0, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x0, y1, z1).color(r, g, b, a).endVertex();
        // west (x = x0)
        wr.pos(x0, y0, z0).color(r, g, b, a).endVertex();
        wr.pos(x0, y0, z1).color(r, g, b, a).endVertex();
        wr.pos(x0, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x0, y1, z0).color(r, g, b, a).endVertex();
        // east (x = x1)
        wr.pos(x1, y0, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z0).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y0, z1).color(r, g, b, a).endVertex();

        tess.draw();
        GlStateManager.enableCull();
    }
}
