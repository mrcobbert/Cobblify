package com.bedwarsqol.feature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
     * Blast power: the strength Hypixel sends in its S27 packet, also used for the block ray-cast.
     * Nothing more exact is published. With vanilla resistances it reproduces what players report:
     * wool always breaks, planks and clay only sometimes, end stone never (see
     * {@code .ai/research/hypixel-fireball-blocks.md}). Dirt, leaves and the like go like wool.
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

    /** Registry names a blast never breaks: Hypixel's blast-proof blocks, beds, and unbreakables. */
    private static final Set<String> PROTECTED = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "minecraft:end_stone",
            "minecraft:obsidian",
            "minecraft:bedrock",
            "minecraft:barrier",
            "minecraft:bed",
            "minecraft:glass",
            "minecraft:stained_glass",
            "minecraft:glass_pane",
            "minecraft:stained_glass_pane")));

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

    /**
     * True when a blast that reaches the block with this registry name may break it: anything but
     * end stone, obsidian, glass, beds and unbreakables. How far a blast reaches is
     * {@link #blastBlocks}'s job, so tough blocks like stone survive on resistance, not on this list.
     */
    public static boolean breaks(String blockId) {
        return blockId != null && !PROTECTED.contains(blockId);
    }

    /** True when the block with this registry name stops a blast ray (glass), so nothing behind it breaks. */
    public static boolean shieldsBlast(String blockId) {
        return blockId != null && SHIELDS.contains(blockId);
    }

    /** Blast resistance of the block at a position, or a negative value for air. */
    public interface Resistance {
        float at(int x, int y, int z);
    }

    /**
     * Blocks a blast centred at ({@code x, y, z}) reaches: the vanilla 1.8.9 {@code Explosion}
     * ray-cast. 1352 rays leave through the faces of a 16x16x16 grid, each starting with
     * {@code power * (0.7..1.3)} (one {@code rand.nextFloat()} per ray, in vanilla order), stepping
     * 0.3 blocks and losing 0.225 per step plus {@code (resistance + 0.3) * 0.3} per block sample.
     * A block is reached while the ray still has energy. Air is never listed. Each entry is
     * {@code {x, y, z}}, first-reached order, no duplicates.
     */
    public static List<int[]> blastBlocks(double x, double y, double z, float power, Random rand, Resistance res) {
        Map<Long, int[]> reached = new LinkedHashMap<Long, int[]>();
        for (int j = 0; j < 16; j++) {
            for (int k = 0; k < 16; k++) {
                for (int l = 0; l < 16; l++) {
                    if (j != 0 && j != 15 && k != 0 && k != 15 && l != 0 && l != 15) continue;
                    double dx = (double) ((float) j / 15.0F * 2.0F - 1.0F);
                    double dy = (double) ((float) k / 15.0F * 2.0F - 1.0F);
                    double dz = (double) ((float) l / 15.0F * 2.0F - 1.0F);
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= len;
                    dy /= len;
                    dz /= len;
                    float f = power * (0.7F + rand.nextFloat() * 0.6F);
                    double px = x, py = y, pz = z;
                    for (; f > 0.0F; f -= 0.22500001F) {
                        int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
                        float r = res.at(bx, by, bz);
                        if (r >= 0) {
                            f -= (r + 0.3F) * 0.3F;
                            if (f > 0.0F) {
                                long key = ((long) bx & 0x3FFFFFFL) << 38 | ((long) by & 0xFFFL) << 26 | ((long) bz & 0x3FFFFFFL);
                                if (!reached.containsKey(key)) reached.put(key, new int[]{bx, by, bz});
                            }
                        }
                        px += dx * 0.30000001192092896D;
                        py += dy * 0.30000001192092896D;
                        pz += dz * 0.30000001192092896D;
                    }
                }
            }
        }
        return new ArrayList<int[]>(reached.values());
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
