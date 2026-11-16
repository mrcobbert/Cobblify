package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;

/**
 * Particles (Ornithe build): reads the module and its rows for the spawn-time hooks and feeds
 * {@link ParticleRules}. Reads only; spawns nothing itself.
 */
public final class ParticleOptions {

    private ParticleOptions() {
    }

    private static ClientSettings config() {
        return BedwarsQol.config;
    }

    /** Always Crit is effective. */
    public static boolean alwaysCrit() {
        ClientSettings c = config();
        return c != null && ParticleRules.effective(c.particles, c.ptAlwaysCrit);
    }

    /** How many of particle {@code particleId} to spawn: 1 unless the module is on and it is crit or sharpness. */
    public static int copies(int particleId) {
        ClientSettings c = config();
        return c == null ? 1 : ParticleRules.copies(particleId, c.ptMultiplier, c.particles);
    }

    /** Whether to skip this potion swirl of {@code entity}: yours, in first person, with Hide Own Potion effective. */
    public static boolean hideOwnPotion(EntityLivingBase entity) {
        ClientSettings c = config();
        if (c == null || !ParticleRules.effective(c.particles, c.ptHideOwnPotion)) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) return false;
        return ParticleRules.hideOwnPotion(entity == mc.thePlayer, mc.gameSettings.thirdPersonView == 0, true);
    }

    /** Hide Block Break is effective. */
    public static boolean hideBlockBreak() {
        ClientSettings c = config();
        return c != null && ParticleRules.hideBlockBreak(ParticleRules.effective(c.particles, c.ptHideBlockBreak));
    }
}
