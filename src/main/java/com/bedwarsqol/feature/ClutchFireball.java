package com.bedwarsqol.feature;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.util.BlockPos;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;

/**
 * Clutch Practice fireball: a vanilla large fireball (1x1 hitbox, collidable, so a thrown ender
 * pearl hits it and teleports its thrower) launched like Hypixel's: from the eye at 1 block per
 * tick, speeding up the vanilla way toward 1.9. It ignores projectile hits, explodes through
 * {@link ClutchPractice#explode} at its own position (as Hypixel does) and expires after 55 ticks.
 * Server side only; it is never registered, so clients see a plain large fireball and worlds never
 * save it.
 */
class ClutchFireball extends EntityLargeFireball {

    ClutchFireball(World world, EntityLivingBase shooter) {
        super(world);
        shootingEntity = shooter;
        Vec3 look = shooter.getLookVec();
        // Hypixel spawns it at the eye itself; the thrower is ignored by the entity ray-trace anyway.
        setLocationAndAngles(shooter.posX, shooter.posY + shooter.getEyeHeight(), shooter.posZ,
                shooter.rotationYaw, shooter.rotationPitch);
        double[] l = ClutchRules.launch(look.xCoord, look.yCoord, look.zCoord);
        motionX = l[0];
        motionY = l[1];
        motionZ = l[2];
        accelerationX = l[3];
        accelerationY = l[4];
        accelerationZ = l[5];
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (!worldObj.isRemote && ticksExisted >= ClutchRules.FIREBALL_LIFETIME_TICKS) setDead();
    }

    /**
     * A pearl hitting the fireball calls this with a thrown-projectile source; vanilla would redirect
     * the fireball along the thrower's look. Keep it flying instead. Melee deflects the vanilla way
     * (1 block per tick along the hitter's look, same 0.1 acceleration), as on Hypixel.
     */
    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (source.isProjectile()) return false;
        return super.attackEntityFrom(source, amount);
    }

    /** Glass stops the blast's block rays, so blast-proof glass shields what is behind it. */
    @Override
    public float getExplosionResistance(Explosion explosion, World world, BlockPos pos, IBlockState state) {
        Object id = Block.blockRegistry.getNameForObject(state.getBlock());
        if (ClutchRules.shieldsBlast(id == null ? null : id.toString())) return ClutchRules.SHIELD_RESISTANCE;
        return super.getExplosionResistance(explosion, world, pos, state);
    }

    @Override
    protected void onImpact(MovingObjectPosition hit) {
        if (worldObj.isRemote || isDead) return;
        ClutchPractice.explode(this);
        setDead();
    }
}
