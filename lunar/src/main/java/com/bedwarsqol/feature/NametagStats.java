package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.BedwarsModeDetector;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.EligibilitySnapshot;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.SeraphTag;
import com.bedwarsqol.stats.StatsCache;
import com.bedwarsqol.stats.TabListLookup;
import com.bedwarsqol.stats.UrchinTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Scoreboard;
import net.weavemc.api.event.RenderLivingEvent;
import net.weavemc.api.event.SubscribeEvent;
import org.lwjgl.opengl.GL11;

public class NametagStats {

    private static final float TEXT_SCALE = 0.75F;
    private static final float ABOVE_NAMETAG = 0.20F;
    private static final float BELOW_NAME_SHIFT = 0.28F;
    /** Vanilla shifts a sneaking nametag down by 9.374999 text-px * 0.02666667 = 0.25 world units. */
    private static final float SNEAK_Y_SHIFT = 0.25F;

    @SubscribeEvent
    public void onRenderNameTag(RenderLivingEvent.Post event) {
        if (BedwarsQol.config == null) return;
        if (!(event.getEntity() instanceof AbstractClientPlayer)) return;
        AbstractClientPlayer player = (AbstractClientPlayer) event.getEntity();
        EntityPlayer self = Minecraft.getMinecraft().thePlayer;
        if (self == null) return;

        ClientSettings cfg = BedwarsQol.config;
        // Draw when Player Stats OR an Urchin/Seraph nametag badge needs this identity.
        if (!UrchinTag.needsNametagIdentity(cfg) && !SeraphTag.needsNametagIdentity(cfg)) return;
        if (!HypixelContext.isOnHypixel() || !HypixelContext.isInActiveBedwarsGame()) return;

        // RenderLivingEvent.Specials.Post fires even when the vanilla name is hidden,
        // so mirror vanilla's visibility rules: never draw over ourselves, an invisible
        // player, or a sneaking/out-of-range player (32 blocks sneaking, 64 otherwise).
        if (player == self) return;
        if (player.isInvisibleToPlayer(self)) return;
        double maxRange = player.isSneaking() ? 32.0 : 64.0;
        if (player.getDistanceSqToEntity(self) >= maxRange * maxRange) return;

        if (player.getGameProfile() == null) return;
        java.util.UUID uuid = player.getGameProfile().getId();
        if (uuid == null) return;
        String name = player.getGameProfile().getName();
        boolean urchinEligible = cfg.urchinTags
                && UrchinTag.badgeAllowed(EligibilitySnapshot.current(), name, uuid);
        boolean seraphEligible = cfg.seraphTags
                && SeraphTag.badgeAllowed(EligibilitySnapshot.current(), name, uuid);

        BedwarsStats stats = StatsCache.getCached(uuid);
        if (stats == null) {
            StatsCache.ensureFetched(uuid, StatsCache.PRIORITY_VISIBLE, urchinEligible, seraphEligible);
            return;
        }
        if (urchinEligible || seraphEligible) {
            StatsCache.ensureFetched(uuid, StatsCache.PRIORITY_VISIBLE, urchinEligible, seraphEligible);
        }

        // Rendering stays per-module: Player Stats off + a provider on -> only the badge draws.
        String text = "";
        if (cfg.playerStats && cfg.playerStatsNametag) {
            String s = stats.formatForNametag(BedwarsModeDetector.displayMode(cfg), cfg.playerStatsShowRank,
                    BedwarsModeDetector.isForced(cfg));
            if (s != null) text = s;
        }
        if (cfg.urchinTags && cfg.urchinBadgeNametag && urchinEligible) {
            UrchinTag tag = stats.priorityUrchinTag(System.currentTimeMillis());
            if (tag != null) text += tag.badgeToken();
        }
        if (cfg.seraphTags && cfg.seraphBadgeNametag && seraphEligible) {
            SeraphTag tag = stats.prioritySeraphTag();
            if (tag != null) text += tag.badgeToken();
        }
        if (text.isEmpty()) return;
        drawStat(player, event, text.startsWith(" ") ? text.trim() : text);
    }

    private void drawStat(AbstractClientPlayer player, RenderLivingEvent.Post event, String text) {
        float above = ABOVE_NAMETAG;
        if (hasBelowNameLine(player)) above += BELOW_NAME_SHIFT;
        drawLineAbove(player, event.getX(), event.getY(), event.getZ(), text, above);
    }

    private boolean hasBelowNameLine(AbstractClientPlayer player) {
        Scoreboard board = player.getWorldScoreboard();
        if (board == null) return false;
        if (board.getObjectiveInDisplaySlot(2) == null) return false;
        EntityPlayer self = Minecraft.getMinecraft().thePlayer;
        if (self == null) return false;
        return player.getDistanceSqToEntity(self) < 100.0;
    }

    private void drawLineAbove(AbstractClientPlayer player, double x, double y, double z, String text, float above) {
        Minecraft mc = Minecraft.getMinecraft();
        RenderManager rm = mc.getRenderManager();
        FontRenderer fr = rm.getFontRenderer();
        if (fr == null) return;

        float scale = 0.016666668F * 1.6F * TEXT_SCALE;
        float yOffset = player.height + 0.5F + above;
        boolean sneaking = player.isSneaking();
        // Vanilla drops a sneaking player's nametag by 0.25 blocks; match it so the card stays
        // the same distance above the name instead of floating too high over a crouching player.
        if (sneaking) yOffset -= SNEAK_Y_SHIFT;

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y + yOffset, (float) z);
        GL11.glNormal3f(0f, 1f, 0f);
        GlStateManager.rotate(-rm.playerViewY, 0f, 1f, 0f);
        GlStateManager.rotate(rm.playerViewX, 1f, 0f, 0f);
        GlStateManager.scale(-scale, -scale, scale);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        // Vanilla only punches the nametag through walls for a standing player; a sneaking player's
        // name stays depth-tested (occluded by geometry). Mirror that so the stats card hides behind
        // walls exactly like the vanilla name when the target is shifted.
        if (!sneaking) GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        int halfWidth = fr.getStringWidth(text) / 2;
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        GlStateManager.disableTexture2D();
        wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(-halfWidth - 1, -1, 0).color(0f, 0f, 0f, 0.25f).endVertex();
        wr.pos(-halfWidth - 1,  8, 0).color(0f, 0f, 0f, 0.25f).endVertex();
        wr.pos( halfWidth + 1,  8, 0).color(0f, 0f, 0f, 0.25f).endVertex();
        wr.pos( halfWidth + 1, -1, 0).color(0f, 0f, 0f, 0.25f).endVertex();
        tess.draw();
        GlStateManager.enableTexture2D();

        if (sneaking) {
            // Vanilla sneaking name: a single faint, depth-tested pass — no opaque pass and no
            // see-through. The card dims and is occluded exactly like the name beneath it.
            GlStateManager.depthMask(true);
            fr.drawString(text, -halfWidth, 0, 0x20FFFFFF);
        } else {
            // Vanilla standing name: faint pass through walls, then a solid pass in line-of-sight.
            fr.drawString(text, -halfWidth, 0, 0x20FFFFFF);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            fr.drawString(text, -halfWidth, 0, -1);
        }

        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.popMatrix();
    }
}
