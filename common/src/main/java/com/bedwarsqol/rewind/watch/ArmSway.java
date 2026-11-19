package com.bedwarsqol.rewind.watch;

/**
 * The first-person hand's lag behind a turn (PLAN "Camera state", TASK B5): vanilla's
 * {@code EntityPlayerSP.updateEntityActionState} once per client tick, {@code renderArm += (rotation - renderArm) x 0.5}
 * for pitch and yaw (double arithmetic, stored as float), with the previous values kept for the frame's partial tick.
 * {@code ItemRenderer} turns the hand by {@code (rotation - lerp(prevRenderArm, renderArm)) x 0.1}. Pure.
 */
public final class ArmSway {

    private float yaw;
    private float pitch;
    private float prevYaw;
    private float prevPitch;

    /** No lag: previous and current are {@code rotationYaw}/{@code rotationPitch} (a new camera, a view switch). */
    public void snap(float rotationYaw, float rotationPitch) {
        yaw = prevYaw = rotationYaw;
        pitch = prevPitch = rotationPitch;
    }

    /** One client tick toward the rotation the camera has at that tick. */
    public void step(float rotationYaw, float rotationPitch) {
        prevYaw = yaw;
        prevPitch = pitch;
        pitch = (float) ((double) pitch + (double) (rotationPitch - pitch) * 0.5D);
        yaw = (float) ((double) yaw + (double) (rotationYaw - yaw) * 0.5D);
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public float prevYaw() {
        return prevYaw;
    }

    public float prevPitch() {
        return prevPitch;
    }
}
