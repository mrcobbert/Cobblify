package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.api.event.ChatEvent;
import net.weavemc.api.event.RenderWorldEvent;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;
import net.weavemc.api.event.WorldEvent;
import org.lwjgl.opengl.GL11;

/**
 * Split: for {@link SplitRules#WINDOW_MS} after a teammate's non-final death, draws a red
 * {@code SPLIT n} countdown in the world above the player's home forge, while the player is near it.
 *
 * <p>The death comes from chat ({@link SessionChatLine#deathVictim}); the victim is a teammate when
 * their scoreboard colour (or, failing that, the line's leading colour code) equals the player's own
 * and the victim is not the player. The home forge is the nearest iron/gold item seen within
 * {@link SplitRules#LEARN_RANGE} while alive in a game, learned whether or not the module is enabled
 * and locked for the game. Observe-only: no chat event is cancelled or edited.
 *
 * <p>Weave's chat event fires from {@code GuiNewChat}, which never sees action-bar lines, and
 * {@link RenderWorldEvent} fires in {@code renderWorldPass} just before the hand, where Forge's
 * {@code RenderWorldLastEvent} fires.
 */
public final class SplitWatch {

    private static final int TICK_INTERVAL = 4;
    private static final double LABEL_LIFT = 1.25;
    private static final float TEXT_SCALE = 2.0F;
    private static final int COLOUR = 0xFFFF5555;

    private static final SplitRules RULES = new SplitRules();

    private int ticks;

    @SubscribeEvent
    public void onChat(ChatEvent.Received event) {
        if (event == null || event.getMessage() == null) return;
        try {
            ClientSettings cfg = BedwarsQol.config;
            if (cfg == null || !cfg.splitEnabled) return;
            if (!HypixelContext.isOnHypixel() || !HypixelContext.isInActiveBedwarsGame()) return;
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(event.getMessage().getUnformattedText());
            String victim = SessionChatLine.deathVictim(plain);
            if (victim == null) return;
            char colour = TeamColors.code(victim);
            if (colour == 0) colour = SplitRules.leadingColour(event.getMessage().getFormattedText());
            boolean counted = RULES.onDeath(colour, SessionStatsWatch.ownTeamCode(),
                    SessionStatsWatch.isLocalName(victim), System.currentTimeMillis());
            if (counted) DiagLog.log("split: teammate death");
        } catch (Throwable ignored) {
            // never propagate into the chat pipeline
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
        if (++ticks < TICK_INTERVAL) return;
        ticks = 0;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || !HypixelContext.isInActiveBedwarsGame()) {
            RULES.reset();
            return;
        }
        if (!RULES.hasForge()) learnForge(mc);
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        RULES.reset();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.splitEnabled) return;
        if (!HypixelContext.isOnHypixel() || !HypixelContext.isInActiveBedwarsGame()) return;
        long now = System.currentTimeMillis();
        if (!RULES.active(now) || !RULES.hasForge()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        EntityPlayerSP player = mc.thePlayer;
        if (!RULES.nearForge(player.posX, player.posY, player.posZ)) return;
        int seconds = RULES.secondsLeft(now);
        if (seconds <= 0) return;
        drawLabel(mc, "\u00a7lSPLIT " + seconds);
    }

    /** Offers the nearest dropped iron/gold ingot to the rules, which lock it when in range and learnable. */
    private static void learnForge(Minecraft mc) {
        EntityPlayerSP player = mc.thePlayer;
        boolean canLearn = !player.capabilities.allowFlying && !player.isInvisible();
        if (!canLearn) return;
        EntityItem nearest = null;
        double best = Double.MAX_VALUE;
        for (Object o : mc.theWorld.loadedEntityList) {
            if (!(o instanceof EntityItem)) continue;
            EntityItem item = (EntityItem) o;
            ItemStack stack = item.getEntityItem();
            if (stack == null) continue;
            Item kind = stack.getItem();
            if (kind != Items.iron_ingot && kind != Items.gold_ingot) continue;
            double d = item.getDistanceSqToEntity(player);
            if (d < best) {
                best = d;
                nearest = item;
            }
        }
        if (nearest == null) return;
        if (RULES.offerResource(canLearn, player.posX, player.posY, player.posZ,
                nearest.posX, nearest.posY, nearest.posZ)) {
            DiagLog.log("split: forge locked");
        }
    }

    /**
     * A camera-facing billboard at forge + {@link #LABEL_LIFT}, shaped like
     * {@code NametagStats.drawLineAbove} but with no background, bold red text at twice the nametag
     * scale, and the depth test off for the whole draw so it shows through walls.
     */
    private static void drawLabel(Minecraft mc, String text) {
        RenderManager rm = mc.getRenderManager();
        FontRenderer fr = rm == null ? null : rm.getFontRenderer();
        if (fr == null) return;

        double x = RULES.forgeX() - rm.viewerPosX;
        double y = RULES.forgeY() + LABEL_LIFT - rm.viewerPosY;
        double z = RULES.forgeZ() - rm.viewerPosZ;
        float scale = 0.016666668F * 1.6F * TEXT_SCALE;

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y, (float) z);
        GL11.glNormal3f(0f, 1f, 0f);
        GlStateManager.rotate(-rm.playerViewY, 0f, 1f, 0f);
        GlStateManager.rotate(rm.playerViewX, 1f, 0f, 0f);
        GlStateManager.scale(-scale, -scale, scale);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableTexture2D();

        fr.drawString(text, -fr.getStringWidth(text) / 2, 0, COLOUR);

        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.popMatrix();
    }
}
