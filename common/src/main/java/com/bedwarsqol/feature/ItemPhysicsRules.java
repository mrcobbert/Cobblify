package com.bedwarsqol.feature;

/**
 * Item Physics, a Visuals module of the Ornithe build: dropped items lie flat and tumble. Off by default.
 *
 * <p>Every number the render hooks use. Units are blocks and degrees. Sizes come from vanilla 1.8.9: the
 * item model is drawn at 0.5 ({@code RenderItem.renderItem}), a 3D block model a further 0.5
 * ({@code RenderEntityItem.doRender}), and vanilla's GROUND transform is identity. So a flat sprite is
 * 1/32 thick and a full block 1/4 wide, both centred on the transform origin.
 *
 * <p>The pose: translate to the rest height, turn by a fixed yaw per entity about Y, then pitch about X
 * ({@link #REST_PITCH} at rest, tumbling while airborne). After that pitch the model's local -Z is world
 * up, which is the direction {@link #pileUp} moves copies.
 */
public final class ItemPhysicsRules {

    public static final boolean DEFAULT_ENABLED = false;

    /** Pitch about X of an item at rest: lying flat. */
    public static final float REST_PITCH = 90f;
    /** Tumble speed while airborne, degrees per tick (400 degrees a second). */
    public static final float TUMBLE_DEG_PER_TICK = 20f;
    /** Half the thickness of a flat sprite: 1 px of a 16 px model drawn at 0.5. */
    public static final float FLAT_HALF_THICKNESS = 1f / 64f;
    /** Half the size of a full 3D block model drawn at 0.5 * 0.5. */
    public static final float BLOCK_HALF_SIZE = 1f / 8f;
    /** Gap between the model's bottom and the surface, so a flat sprite does not z-fight the ground. */
    public static final float GROUND_GAP = 1f / 128f;
    /** Vertical step between flat copies; more than their 1/32 thickness so they do not overlap. */
    public static final float FLAT_PILE_STEP = 3f / 64f;
    /** Vertical step between 3D copies. */
    public static final float BLOCK_PILE_STEP = 1f / 32f;
    /** Side step between 3D copies, so overlapping copies' faces are never coplanar. */
    public static final float BLOCK_PILE_SHIFT = 1f / 64f;
    /** Largest gap between an item's collision rest and the drawn surface that is corrected. */
    public static final double MAX_SURFACE_SHIFT = 3.0 / 16.0;
    /** Below this, a collision top and a drawn top count as the same height. */
    private static final double SAME_HEIGHT = 1e-3;

    private ItemPhysicsRules() {
    }

    /** Vanilla's spin about Y runs only with the module off; on, the item keeps its {@link #yaw}. */
    public static boolean vanillaSpin(boolean on) {
        return !on;
    }

    /** Vanilla centres a flat pile on the origin only with the module off; on, the pile grows up from the rest. */
    public static boolean vanillaPileCentering(boolean on) {
        return !on;
    }

    /** A fixed yaw, 0 (inclusive) to 360 (exclusive), stable for an entity id and spread between ids. */
    public static float yaw(int entityId) {
        int h = entityId * 0x9E3779B9;
        return (h >>> 16) % 360;
    }

    /** Pitch about X: {@link #REST_PITCH} on the ground; airborne, it advances from there with age at one speed. */
    public static float pitch(int age, float partialTicks, boolean onGround) {
        if (onGround) return REST_PITCH;
        double a = (REST_PITCH + (age + (double) partialTicks) * TUMBLE_DEG_PER_TICK) % 360.0;
        if (a < 0) a += 360.0;
        return (float) a;
    }

    /** Height of the model's centre above the surface it rests on: half its size plus {@link #GROUND_GAP}. */
    public static float restLift(boolean gui3d) {
        return (gui3d ? BLOCK_HALF_SIZE : FLAT_HALF_THICKNESS) + GROUND_GAP;
    }

    /**
     * The Y of the item's first translate. Off: vanilla's (bob included). On: the render Y plus
     * {@link #restLift} plus the {@link #surfaceShift}; no bob.
     */
    public static float restY(boolean on, float vanillaY, double renderY, boolean gui3d, double surfaceShift) {
        if (!on) return vanillaY;
        return (float) (renderY + restLift(gui3d) + surfaceShift);
    }

    /**
     * How far to move a resting item so it lies on the drawn surface rather than its collision rest.
     *
     * <p>{@code posY} is where vanilla physics left it (the collision top under it). The "inside" block is
     * the one containing {@code posY}, the "support" block the one just below it; a top is that block's
     * drawn top in world Y, and solid means it has a collision box. Inside block drawn above {@code posY}
     * (snow layers, soul sand): lift to it. Otherwise support drawn below {@code posY} (farmland): lower to
     * it. Gaps beyond {@link #MAX_SURFACE_SHIFT} (ladders, fences) and non-solid blocks (grass) give 0.
     */
    public static double surfaceShift(double posY, boolean insideSolid, double insideTop,
                                      boolean supportSolid, double supportTop) {
        if (insideSolid) {
            double up = insideTop - posY;
            if (up > SAME_HEIGHT) return up <= MAX_SURFACE_SHIFT ? up : 0.0;
        }
        if (supportSolid) {
            double down = supportTop - posY;
            if (down < -SAME_HEIGHT) return down >= -MAX_SURFACE_SHIFT ? down : 0.0;
        }
        return 0.0;
    }

    /** How far copy {@code copy} (0 = the first) of a pile sits above the first, at a fixed step. */
    public static float pileUp(int copy, boolean gui3d) {
        return copy * (gui3d ? BLOCK_PILE_STEP : FLAT_PILE_STEP);
    }

    /** Sideways shift of copy {@code copy} along each horizontal model axis: 3D copies only. */
    public static float pileSide(int copy, boolean gui3d) {
        return gui3d ? copy * BLOCK_PILE_SHIFT : 0f;
    }
}
