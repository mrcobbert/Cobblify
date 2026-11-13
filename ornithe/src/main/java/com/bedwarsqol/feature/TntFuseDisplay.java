package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityTNTPrimed;

import java.util.List;
import java.util.Locale;

/**
 * TNT fuse countdown: finds the nearest ignited {@link EntityTNTPrimed} within a configurable radius
 * of the player and renders its remaining fuse (in seconds) as centered white text just above the
 * crosshair. Useful for judging when to back off from a primed bomb in BedWars/SkyWars.
 *
 * <p>Drawn on the {@link RenderGameOverlayEvent.Text} pass — the same once-per-frame overlay hook the
 * BedWars HUD uses — so no {@link RenderGameOverlayEvent.ElementType} gate is required (that subtype
 * already fires a single time per frame, after the world but with the HUD projection set up).
 */
public class TntFuseDisplay {

    /** Text scale above the crosshair — large enough to read at a glance over the world. */
    private static final float TEXT_SCALE = 2.0f;
    /** Pixels above screen-center to seat the text, clearing the crosshair. */
    private static final float CROSSHAIR_OFFSET = 30f;
    /** White, fully opaque (ARGB). */
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    public void onRenderText() {
        Minecraft mc = Minecraft.getMinecraft();
        ClientSettings cfg = BedwarsQol.config;
        if (mc == null || cfg == null || !cfg.tntFuseEnabled) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;

        EntityTNTPrimed nearest = nearestPrimedTnt(mc, cfg.tntFuseRadius);
        if (nearest == null) return;

        // fuse is the remaining ticks (public int field; no getFuse() in 1.8.9). 20 ticks = 1s.
        float seconds = nearest.fuse / 20.0f;
        String text = String.format(Locale.US, "%.1f", seconds);

        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        int sh = sr.getScaledHeight();
        float x = sw / 2f - BedwarsQolFont.width(text, TEXT_SCALE, BedwarsQolFont.Weight.MEDIUM) / 2f;
        float y = sh / 2f - CROSSHAIR_OFFSET;
        BedwarsQolFont.draw(text, x, y, TEXT_SCALE, TEXT_COLOR, true, BedwarsQolFont.Weight.MEDIUM);
    }

    /**
     * @return the closest primed TNT entity within {@code radius} blocks of the player, or
     *         {@code null} if none are in range. Non-positive radii disable the scan.
     */
    private static EntityTNTPrimed nearestPrimedTnt(Minecraft mc, int radius) {
        if (radius <= 0) return null;
        double maxSq = (double) radius * (double) radius;

        EntityTNTPrimed nearest = null;
        double nearestSq = Double.MAX_VALUE;

        List<Entity> entities = mc.theWorld.loadedEntityList;
        for (int i = 0; i < entities.size(); i++) {
            Entity ent = entities.get(i);
            if (!(ent instanceof EntityTNTPrimed)) continue;
            double dSq = mc.thePlayer.getDistanceSqToEntity(ent);
            if (dSq <= maxSq && dSq < nearestSq) {
                nearestSq = dSq;
                nearest = (EntityTNTPrimed) ent;
            }
        }
        return nearest;
    }
}
