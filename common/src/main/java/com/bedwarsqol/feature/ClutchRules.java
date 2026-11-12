package com.bedwarsqol.feature;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure rules behind Clutch Practice (singleplayer pearl + fireball drill): throw cooldown, which
 * blocks a fireball may break, when a fall counts as a void reset, and the fireball flight, blast
 * knockback and self-damage measured on Hypixel (recording 2026-10-27, see
 * {@code .ai/research/hypixel-fireball-measured.md}).
 */
public final class ClutchRules {

    /** Hypixel's fire charge cooldown: 0.5 s. */
    public static final int COOLDOWN_TICKS = 10;
    /** A thrown fireball that has hit nothing is removed after this long (Hypixel: 55 ticks). */
    public static final int FIREBALL_LIFETIME_TICKS = 55;
    /**
     * Blast power: the strength Hypixel sends in its S27 packet, also used for the vanilla block
     * ray-cast. Nothing more exact is published. With vanilla resistances it reproduces what players
     * report: wool always breaks, planks and clay only sometimes, end stone never (see
     * {@code .ai/research/hypixel-fireball-blocks.md}).
     */
    public static final float EXPLOSION_POWER = 2.0f;
    /** Resistance that stops a blast ray dead: blast-proof glass shields the blocks behind it. */
    public static final float SHIELD_RESISTANCE = 1200f;
    /**
     * Entities further than this (feet to blast) take no knockback or damage. Not measured (the
     * furthest recorded hit was 2.85 blocks); 4 is vanilla's reach for strength 2.
     */
    public static final double BLAST_RADIUS = 4.0;
    /** Knockback speed at zero distance, blocks per tick (fitted, Hypixel). */
    public static final double KNOCKBACK = 1.80;
    /** Knockback lost per block of feet-to-blast distance (fitted, Hypixel). */
    public static final double KNOCKBACK_FALLOFF = 0.254;
    /** The push aims from the blast at this point above the feet (fitted, Hypixel; between body and eye). */
    public static final double AIM_HEIGHT = 1.234;
    /** Self-damage per blast in health points: Hypixel takes a flat heart at any range. */
    public static final float MAX_DAMAGE = 2.0f;
    /** Fall this far below the last solid ground, over open void, and you are reset onto it. */
    public static final double VOID_DROP = 40.0;
    /** Fireball speed when thrown, blocks per tick, along the look (Hypixel and vanilla). */
    public static final double FIREBALL_START_SPEED = 1.0;
    /** Fireball acceleration per tick along the look (vanilla {@code EntityFireball}, 0.1). */
    public static final double FIREBALL_ACCEL = 0.1;
    /** Vanilla fireball drag per tick out of water ({@code EntityFireball.getMotionFactor}). */
    public static final double FIREBALL_DRAG = 0.95;

    /** Registry names a fireball breaks with Block Damage on: Hypixel's wool, wood, clay and ladders. */
    private static final Set<String> BREAKABLE = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "minecraft:wool",
            "minecraft:carpet",
            "minecraft:planks",
            "minecraft:ladder",
            "minecraft:hardened_clay",
            "minecraft:stained_hardened_clay")));

    /** Registry names that stop a blast ray: all glass counts as Hypixel's blast-proof glass. */
    private static final Set<String> SHIELDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "minecraft:glass",
            "minecraft:stained_glass",
            "minecraft:glass_pane",
            "minecraft:stained_glass_pane")));

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

    /** True when the block with this registry name stops a blast ray (glass), so nothing behind it breaks. */
    public static boolean shieldsBlast(String blockId) {
        return blockId != null && SHIELDS.contains(blockId);
    }

    /**
     * True when the player at {@code y} is past the void line: under the world, or {@link #VOID_DROP}
     * below their last solid ground. The caller resets them only when nothing solid is beneath; over
     * ground they land and take vanilla fall damage, which can kill.
     */
    public static boolean shouldVoidReset(double y, boolean hasSafe, double safeY) {
        return hasSafe && (y < 0 || y < safeY - VOID_DROP);
    }

    /**
     * Velocity Hypixel sets on an entity whose feet are offset ({@code dx, dy, dz}) from the blast,
     * or {@code null} when out of range. It points from the blast at {@link #AIM_HEIGHT} above the
     * feet and its speed falls linearly with the feet distance. It replaces the old motion.
     */
    public static double[] knockback(double dx, double dy, double dz) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d >= BLAST_RADIUS) return null;
        double ay = dy + AIM_HEIGHT;
        double n = Math.sqrt(dx * dx + ay * ay + dz * dz);
        double speed = Math.max(0, KNOCKBACK - KNOCKBACK_FALLOFF * d);
        if (n < 1.0E-6) return new double[]{0, speed, 0};
        return new double[]{dx / n * speed, ay / n * speed, dz / n * speed};
    }

    /** Explosion damage at this distance from the blast: flat inside the radius. */
    public static float damage(double distance) {
        return distance < BLAST_RADIUS ? MAX_DAMAGE : 0f;
    }

    /**
     * Launch state for a fireball thrown along unit look ({@code x, y, z}): motion
     * {@code [0..2]} and per-tick acceleration {@code [3..5]}. Vanilla then flies it with
     * {@code motion = (motion + accel) * drag}: 1.0, 1.045, 1.088 ... toward 1.9 blocks per tick.
     */
    public static double[] launch(double x, double y, double z) {
        return new double[]{
                x * FIREBALL_START_SPEED, y * FIREBALL_START_SPEED, z * FIREBALL_START_SPEED,
                x * FIREBALL_ACCEL, y * FIREBALL_ACCEL, z * FIREBALL_ACCEL};
    }
}
