package com.bedwarsqol.rewind.watch;

/**
 * The FOV multiplier your view derives from the recording when it holds no FOV records (PLAN "Camera state", TASK B6):
 * vanilla's {@code AbstractClientPlayer.getFovModifier()}. The camera returns it from {@code getFovModifier()}, and
 * vanilla's own {@code EntityRenderer.updateFovModifierHand} eases the world FOV toward it each tick, as live.
 * Pure.
 */
public final class DerivedFov {

    /** A bow's draw: full at 20 ticks. */
    static final float FULL_DRAW_TICKS = 20f;

    private DerivedFov() {
    }

    /**
     * {@code getFovModifier()}: 1 (x 1.1 flying) x {@code (movementSpeed / walkSpeed + 1) / 2} (1 when the walk speed
     * is 0 or the result is not finite), then for a bow in use x {@code 1 - min(duration / 20, 1)^2 x 0.15} (vanilla
     * squares only below a full draw).
     *
     * @param movementSpeed  the recorded player's {@code generic.movementSpeed} value (sprint and Speed included)
     * @param walkSpeed      {@code capabilities.getWalkSpeed()} (0.1 by default)
     * @param usingBow       a bow is the item in use
     * @param useDuration    {@code getItemInUseDuration()}: ticks the item has been used
     */
    public static float modifier(double movementSpeed, float walkSpeed, boolean flying, boolean usingBow,
                                 int useDuration) {
        float f = 1f;
        if (flying) {
            f *= 1.1f;
        }
        f = (float) ((double) f * ((movementSpeed / (double) walkSpeed + 1.0D) / 2.0D));
        if (walkSpeed == 0f || Float.isNaN(f) || Float.isInfinite(f)) {
            f = 1f;
        }
        if (usingBow) {
            float draw = useDuration / FULL_DRAW_TICKS;
            if (draw > 1f) {
                draw = 1f;
            } else {
                draw *= draw;
            }
            f *= 1f - draw * 0.15f;
        }
        return f;
    }
}
