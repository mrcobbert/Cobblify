package com.bedwarsqol.feature;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/**
 * Clutch Practice fireball: a vanilla large fireball (1x1 hitbox, collidable, so a thrown ender
 * pearl hits it and teleports its thrower) that flies straight at a constant speed, ignores
 * projectile hits, explodes through {@link ClutchPractice#explode} and expires after a while.
 * Server side only; it is never registered, so clients see a plain large fireball and worlds never
 * save it.
 */
class ClutchFireball extends EntityLargeFireball {

    ClutchFireball(World world, EntityLivingBase shooter, double speed) {
        super(world);
        shootingEntity = shooter;
        Vec3 look = shooter.getLookVec();
        setLocationAndAngles(shooter.posX, shooter.posY + shooter.getEyeHeight(), shooter.posZ,
                shooter.rotationYaw, shooter.rotationPitch);
        // Start one step out of the eye so the first tick never collides with the thrower's face.
        setPosition(posX + look.xCoord * 0.5, posY + look.yCoord * 0.5 - height / 2, posZ + look.zCoord * 0.5);
        motionX = look.xCoord * speed;
        motionY = look.yCoord * speed;
        motionZ = look.zCoord * speed;
        double[] a = ClutchRules.steadyAcceleration(look.xCoord, look.yCoord, look.zCoord, speed);
        accelerationX = a[0];
        accelerationY = a[1];
        accelerationZ = a[2];
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (!worldObj.isRemote && ticksExisted > ClutchRules.FIREBALL_LIFETIME_TICKS) setDead();
    }

    /**
     * A pearl hitting the fireball calls this with a thrown-projectile source; vanilla would redirect
     * the fireball along the thrower's look. Keep it flying straight instead. Melee deflects as usual.
     */
    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (source.isProjectile()) return false;
        boolean hit = super.attackEntityFrom(source, amount);
        if (hit && source.getEntity() != null) {
            // Vanilla pointed it along the attacker's look at speed 1; restore the practice speed.
            Vec3 look = source.getEntity().getLookVec();
            double speed = ClutchPractice.fireballSpeed();
            motionX = look.xCoord * speed;
            motionY = look.yCoord * speed;
            motionZ = look.zCoord * speed;
            double[] a = ClutchRules.steadyAcceleration(look.xCoord, look.yCoord, look.zCoord, speed);
            accelerationX = a[0];
            accelerationY = a[1];
            accelerationZ = a[2];
        }
        return hit;
    }

    @Override
    protected void onImpact(MovingObjectPosition hit) {
        if (worldObj.isRemote || isDead) return;
        ClutchPractice.explode(this);
        setDead();
    }
}
