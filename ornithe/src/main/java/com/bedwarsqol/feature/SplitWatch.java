package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.ornithe.ChatReceivedEvent;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.opengl.GL11;

/**
 * Split: for {@link SplitRules#WINDOW_MS} after a teammate's non-final death in a Hypixel Bed Wars game, a red
 * {@code SPLIT 5} ... {@code SPLIT 1} countdown is drawn in the world above the player's home forge, while the player
 * is within {@link SplitRules#NEAR_RANGE} blocks of it. After the player's own respawn, while a teammate is still dead,
 * the same countdown is also drawn on the HUD above the crosshair ({@link SplitRules#hudCue}).
 *
 * <p>The death comes from chat ({@link SessionChatLine#deathVictim}); the victim is a teammate when their team colour
 * (scoreboard, else the line's first colour code) equals the player's own and they are not the player. The home forge
 * is the nearest iron/gold item within {@link SplitRules#LEARN_RANGE} blocks the first time one is seen while the
 * player is alive in a game, learned whether or not the module is on, and forgotten on world load and outside a game.
 * Observe-only: no chat event is cancelled or edited. Every entry point swallows its own errors.
 */
public final class SplitWatch {

    private static final int TICK_INTERVAL = 4;
    private static final double LABEL_HEIGHT = 1.25;
    private static final float SCALE = 0.016666668F * 1.6F * 2.0F;
    private static final int COLOUR = 0xFFFF5555;
    private static final float HUD_SCALE = 2.0f;
    /** Pixels above screen centre; TNT Fuse sits at 30, so the two stay apart. */
    private static final float HUD_CROSSHAIR_OFFSET = 48f;

    private static final SplitRules RULES = new SplitRules();

    private int ticks;

    public void onWorldLoad() {
        RULES.reset();
    }

    public void onChat(ChatReceivedEvent event) {
        if (event == null || event.message == null) return;
        if (event.type == 2) return; // action bar
        try {
            ClientSettings cfg = BedwarsQol.config;
            if (cfg == null || !cfg.splitEnabled) return;
            if (!HypixelContext.isOnHypixel() || !HypixelContext.isInActiveBedwarsGame()) return;
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
            String victim = SessionChatLine.deathVictim(plain);
            if (victim == null) return;
            long now = System.currentTimeMillis();
            if (SessionStatsWatch.isLocalName(victim)) {
                RULES.onOwnDeath(now);
                return;
            }
            char victimColour = TeamColors.code(victim);
            if (victimColour == 0) victimColour = SplitRules.leadingColour(event.message.getFormattedText());
            boolean counted = RULES.onDeath(victimColour, SessionStatsWatch.ownTeamCode(), false, now);
            if (counted) DiagLog.log("split: teammate death");
        } catch (Throwable ignored) {
            // never propagate into the chat pipeline
        }
    }

    public void onClientTick() {
        if (++ticks < TICK_INTERVAL) return;
        ticks = 0;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            EntityPlayerSP player = mc == null ? null : mc.thePlayer;
            if (mc == null || mc.theWorld == null || player == null || !HypixelContext.isInActiveBedwarsGame()) {
                RULES.reset();
                return;
            }
            if (RULES.hasForge()) return;
            boolean canLearn = !player.capabilities.allowFlying && !player.isInvisible();
            if (!canLearn) return;
            EntityItem nearest = null;
            double best = Double.MAX_VALUE;
            for (Object o : mc.theWorld.loadedEntityList) {
                if (!(o instanceof EntityItem)) continue;
                EntityItem item = (EntityItem) o;
                if (!isForgeResource(item.getEntityItem())) continue;
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
        } catch (Throwable ignored) {
            // a failed scan only delays the lock
        }
    }

    private static boolean isForgeResource(ItemStack stack) {
        if (stack == null) return false;
        Item item = stack.getItem();
        return item == Items.iron_ingot || item == Items.gold_ingot;
    }

    /** End of the world pass ({@code ModEvents.WORLD_LAST}). */
    public void onRenderWorldLast(float partialTicks) {
        try {
            ClientSettings cfg = BedwarsQol.config;
            if (cfg == null || !cfg.splitEnabled) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.thePlayer == null) return;
            long now = System.currentTimeMillis();
            if (!RULES.active(now) || !RULES.hasForge()) return;
            if (!RULES.nearForge(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ)) return;
            if (!HypixelContext.isInActiveBedwarsGame()) return;
            int seconds = RULES.secondsLeft(now);
            if (seconds <= 0) return;
            draw(mc, "§lSPLIT " + seconds);
        } catch (Throwable ignored) {
            // a failed draw never breaks the frame
        }
    }

    /**
     * HUD text pass ({@code ModEvents.HUD_TEXT}): after the player's own respawn, while a teammate is still dead, the
     * same countdown above the crosshair, because a freshly respawned player runs at the forge without looking at it.
     */
    public void onRenderHud() {
        try {
            ClientSettings cfg = BedwarsQol.config;
            if (cfg == null || !cfg.splitEnabled || !cfg.splitHudCue) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.currentScreen != null) return;
            if (!HypixelContext.isInActiveBedwarsGame()) return;
            long now = System.currentTimeMillis();
            if (!RULES.hudCue(now)) return;
            int seconds = RULES.secondsLeft(now);
            if (seconds <= 0) return;
            if (RULES.hasForge() && !RULES.nearForge(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ)) return;
            String text = "SPLIT " + seconds;
            ScaledResolution sr = new ScaledResolution(mc);
            float x = sr.getScaledWidth() / 2f - BedwarsQolFont.width(text, HUD_SCALE, BedwarsQolFont.Weight.MEDIUM) / 2f;
            float y = sr.getScaledHeight() / 2f - HUD_CROSSHAIR_OFFSET;
            BedwarsQolFont.draw(text, x, y, HUD_SCALE, COLOUR, true, BedwarsQolFont.Weight.MEDIUM);
        } catch (Throwable ignored) {
            // a failed draw never breaks the frame
        }
    }

    /**
     * A camera-facing label at the forge, like the nametag stats line but with no background, through walls, and
     * with the touched GL state put back as it was.
     */
    private static void draw(Minecraft mc, String text) {
        RenderManager rm = mc.getRenderManager();
        FontRenderer fr = rm == null ? null : rm.getFontRenderer();
        if (fr == null) return;
        double x = RULES.forgeX() - rm.viewerPosX;
        double y = RULES.forgeY() + LABEL_HEIGHT - rm.viewerPosY;
        double z = RULES.forgeZ() - rm.viewerPosZ;

        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) x, (float) y, (float) z);
            GL11.glNormal3f(0f, 1f, 0f);
            GlStateManager.rotate(-rm.playerViewY, 0f, 1f, 0f);
            GlStateManager.rotate(rm.playerViewX, 1f, 0f, 0f);
            GlStateManager.scale(-SCALE, -SCALE, SCALE);
            GlStateManager.disableLighting();
            GlStateManager.depthMask(false);
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            fr.drawString(text, -fr.getStringWidth(text) / 2, 0, COLOUR);
        } finally {
            if (depthTest) GlStateManager.enableDepth();
            else GlStateManager.disableDepth();
            GlStateManager.depthMask(depthMask);
            if (blend) GlStateManager.enableBlend();
            else GlStateManager.disableBlend();
            if (lighting) GlStateManager.enableLighting();
            else GlStateManager.disableLighting();
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.popMatrix();
        }
    }
}
