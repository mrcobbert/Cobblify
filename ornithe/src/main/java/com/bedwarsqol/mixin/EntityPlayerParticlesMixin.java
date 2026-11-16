package com.bedwarsqol.mixin;

import com.bedwarsqol.feature.ParticleOptions;
import com.bedwarsqol.feature.ParticleRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Always Crit: when the local player's attack reaches vanilla's damage step on a living target, the crit and
 * sharpness bursts vanilla did not show for that hit are added on the target, at the end of
 * {@code attackTargetEntityWithCurrentItem}. The bursts are what {@code EntityPlayerSP.onCriticalHit} /
 * {@code onEnchantmentCritical} do: {@code effectRenderer.emitParticleAtEntity}, client particles only. This
 * runs after vanilla's controller has already told the server about the attack, and sends nothing itself.
 * The other hooks only note what vanilla did, in this mixin's own fields. A hit "lands" when vanilla builds
 * its damage source ({@code DamageSource.causePlayerDamage}, called once, just before the damage call).
 * Module or row off: nothing added.
 */
@Mixin(EntityPlayer.class)
public abstract class EntityPlayerParticlesMixin {

    private static final String ATTACK = "attackTargetEntityWithCurrentItem(Lnet/minecraft/entity/Entity;)V";

    @Unique private boolean bedwarsqol$hitLanded;
    @Unique private boolean bedwarsqol$vanillaCrit;
    @Unique private boolean bedwarsqol$vanillaMagic;

    @Inject(method = ATTACK, at = @At("HEAD"), require = 1)
    private void bedwarsqol$hitStart(Entity target, CallbackInfo ci) {
        bedwarsqol$hitLanded = false;
        bedwarsqol$vanillaCrit = false;
        bedwarsqol$vanillaMagic = false;
    }

    @Inject(method = ATTACK, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/DamageSource;causePlayerDamage(Lnet/minecraft/entity/player/EntityPlayer;)Lnet/minecraft/util/DamageSource;"),
            require = 1)
    private void bedwarsqol$hitLands(Entity target, CallbackInfo ci) {
        bedwarsqol$hitLanded = true;
    }

    @Inject(method = ATTACK, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayer;onCriticalHit(Lnet/minecraft/entity/Entity;)V"),
            require = 1)
    private void bedwarsqol$vanillaCrit(Entity target, CallbackInfo ci) {
        bedwarsqol$vanillaCrit = true;
    }

    @Inject(method = ATTACK, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayer;onEnchantmentCritical(Lnet/minecraft/entity/Entity;)V"),
            require = 1)
    private void bedwarsqol$vanillaMagic(Entity target, CallbackInfo ci) {
        bedwarsqol$vanillaMagic = true;
    }

    @Inject(method = ATTACK, at = @At("RETURN"), require = 1)
    private void bedwarsqol$alwaysCrit(Entity target, CallbackInfo ci) {
        if (!bedwarsqol$hitLanded) return;
        bedwarsqol$hitLanded = false;
        if (!((Object) this instanceof EntityPlayerSP) || !(target instanceof EntityLivingBase)) return;
        int extra = ParticleRules.extraHitParticles(bedwarsqol$vanillaCrit, bedwarsqol$vanillaMagic,
                ParticleOptions.alwaysCrit());
        if (extra == 0) return;
        EffectRenderer fx = Minecraft.getMinecraft().effectRenderer;
        if (fx == null) return;
        if ((extra & ParticleRules.EXTRA_CRIT) != 0) fx.emitParticleAtEntity(target, EnumParticleTypes.CRIT);
        if ((extra & ParticleRules.EXTRA_MAGIC) != 0) fx.emitParticleAtEntity(target, EnumParticleTypes.CRIT_MAGIC);
    }
}
