package com.bedwarsqol.feature;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure rules behind Clutch Practice (singleplayer pearl + fireball drill): throw cooldown, which
 * blocks a fireball may break, when a fall counts as a void reset, the explosion's knockback and
 * self-damage, and the acceleration that keeps a vanilla fireball flying at a constant speed.
 */
public final class ClutchRules {

    /** Hypixel's fire charge cooldown: 0.5 s. */
    public static final int COOLDOWN_TICKS = 10;
    /** A thrown fireball that has hit nothing is removed after 10 s. */
    public static final int FIREBALL_LIFETIME_TICKS = 200;
    /** Ray-cast power used to pick blocks when Block Damage is on (Bedwars plugins use ~3). */
    public static final float EXPLOSION_POWER = 3.0f;
    /** Entities further than this from the blast centre take no knockback or damage. */
    public static final double BLAST_RADIUS = 4.0;
    /** Knockback at the blast centre, blocks per tick, falling off linearly to zero at the radius. */
    public static final double KNOCKBACK = 1.6;
    /** Extra upward push at the blast centre, so a fireball under your feet lifts you. */
    public static final double LIFT = 0.3;
    /** Damage (half-hearts) at the blast centre: a small self-hit, as on Hypixel. */
    public static final float MAX_DAMAGE = 3.0f;
    /** Fall this far below the last solid ground and you are reset onto it. */
    public static final double VOID_DROP = 40.0;
    /** Vanilla fireball drag per tick out of water ({@code EntityFireball.getMotionFactor}). */
    public static final double FIREBALL_DRAG = 0.95;

    /** Registry names a fireball breaks with Block Damage on: the cheap Bedwars defence blocks. */
    private static final Set<String> BREAKABLE = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "minecraft:wool",
            "minecraft:carpet",
            "minecraft:planks",
            "minecraft:ladder",
            "minecraft:hardened_clay",
            "minecraft:stained_hardened_clay")));

    private ClutchRules() {
    }

    /** True when a throw at {@code nowTick} is allowed after the last one ({@code lastTick < 0}: never). */
    public static boolean cooldownReady(long nowTick, long lastTick) {
        return lastTick < 0 || nowTick < lastTick || nowTick - lastTick >= COOLDOWN_TICKS;
    }

    /** True when a fireball may break the block with this registry name (end stone, obsidian, glass resist). */
    public static boolean breaks(String blockId) {
        return blockId != null && BREAKABLE.contains(blockId);
    }

    /** True when the player at {@code y} has fallen into the void and should go back to safe ground. */
    public static boolean shouldVoidReset(double y, boolean hasSafe, double safeY) {
        return hasSafe && (y < 0 || y < safeY - VOID_DROP);
    }

    /** 1 at the blast centre, falling linearly to 0 at {@link #BLAST_RADIUS}; 0 beyond it. */
    public static double falloff(double distance) {
        if (distance >= BLAST_RADIUS) return 0;
        return 1 - Math.max(0, distance) / BLAST_RADIUS;
    }

    /**
     * Velocity added to an entity whose body centre is offset ({@code dx, dy, dz}) from the blast, or
     * {@code null} when out of range. Pushes straight away from the blast, plus a small lift.
     */
    public static double[] knockback(double dx, double dy, double dz) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double f = falloff(d);
        if (f <= 0) return null;
        double nx = 0, ny = 1, nz = 0;
        if (d > 1.0E-4) {
            nx = dx / d;
            ny = dy / d;
            nz = dz / d;
        }
        double k = KNOCKBACK * f;
        return new double[]{nx * k, ny * k + LIFT * f, nz * k};
    }

    /** Explosion damage at this distance from the blast. */
    public static float damage(double distance) {
        return (float) (MAX_DAMAGE * falloff(distance));
    }

    /**
     * Per-tick acceleration that holds a vanilla fireball at {@code speed} along unit direction
     * ({@code x, y, z}). Vanilla does {@code motion = (motion + accel) * drag}, whose fixed point is
     * {@code accel = speed * (1 - drag) / drag}.
     */
    public static double[] steadyAcceleration(double x, double y, double z, double speed) {
        double a = speed * (1 - FIREBALL_DRAG) / FIREBALL_DRAG;
        return new double[]{x * a, y * a, z * a};
    }
}
