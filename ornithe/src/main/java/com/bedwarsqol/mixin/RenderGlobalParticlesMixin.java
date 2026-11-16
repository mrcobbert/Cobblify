package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.ParticleOptions;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Particles Multiplier: {@code spawnEntityFX} is the client funnel every world particle goes through
 * ({@code World.spawnParticle} -> {@code RenderGlobal.spawnParticle} -> here -> {@code EffectRenderer
 * .spawnEffectParticle}): entity emitters (hit bursts), arrow trails and server-sent particles. For crit and
 * sharpness it spawns the extra copies with the same arguments before vanilla spawns its own; the guard keeps
 * those copies from multiplying again. Vanilla's spawn and its return value are untouched. Module off: 1 copy,
 * nothing added. Spawn time only; rendering is not touched.
 */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalParticlesMixin {

    @Unique private static boolean bedwarsqol$copying;

    @Shadow
    private EntityFX spawnEntityFX(int particleId, boolean ignoreRange, double x, double y, double z,
                                   double dx, double dy, double dz, int[] params) {
        throw new AssertionError();
    }

    @Inject(method = "spawnEntityFX(IZDDDDDD[I)Lnet/minecraft/client/particle/EntityFX;", at = @At("HEAD"),
            require = 1)
    private void bedwarsqol$multiply(int particleId, boolean ignoreRange, double x, double y, double z,
                                     double dx, double dy, double dz, int[] params,
                                     CallbackInfoReturnable<EntityFX> cir) {
        if (bedwarsqol$copying) return;
        int copies = ParticleOptions.copies(particleId);
        if (copies <= 1) return;
        bedwarsqol$copying = true;
        try {
            for (int i = 1; i < copies; i++) spawnEntityFX(particleId, ignoreRange, x, y, z, dx, dy, dz, params);
        } finally {
            bedwarsqol$copying = false;
        }
    }
}
