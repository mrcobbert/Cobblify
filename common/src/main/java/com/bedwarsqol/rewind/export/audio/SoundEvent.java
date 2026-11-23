package com.bedwarsqol.rewind.export.audio;

/**
 * One sound the replay played during an export, as the offline mix needs it (Rewind Export PLAN Solution 3; TASK
 * S2, S3).
 *
 * <ul>
 *   <li>{@link #timeUs()}: when it starts on the recording's clock (a distance-delayed sound already includes its
 *       delay, see {@link SoundLog#add(SoundEvent, int)}).</li>
 *   <li>{@link #sound()}: the sound file variant picked for it, e.g. {@code minecraft:sounds/dig/stone2.ogg}.</li>
 *   <li>{@link #volume()} in 0..1 and {@link #pitch()} in 0.5..2, already clamped; {@link #range()} the distance in
 *       blocks at which it fades to silence.</li>
 *   <li>The listener's position and yaw (degrees, Minecraft's: 0 faces +z) when it started; the stereo position is
 *       fixed at the start.</li>
 * </ul>
 *
 * Immutable.
 */
public final class SoundEvent {

    private final long timeUs;
    private final String sound;
    private final double x;
    private final double y;
    private final double z;
    private final float volume;
    private final float pitch;
    private final float range;
    private final double listenerX;
    private final double listenerY;
    private final double listenerZ;
    private final float listenerYaw;

    public SoundEvent(long timeUs, String sound, double x, double y, double z, float volume, float pitch, float range,
                      double listenerX, double listenerY, double listenerZ, float listenerYaw) {
        this.timeUs = timeUs;
        this.sound = sound;
        this.x = x;
        this.y = y;
        this.z = z;
        this.volume = volume;
        this.pitch = pitch;
        this.range = range;
        this.listenerX = listenerX;
        this.listenerY = listenerY;
        this.listenerZ = listenerZ;
        this.listenerYaw = listenerYaw;
    }

    /** The same sound starting at {@code us}. */
    public SoundEvent at(long us) {
        return us == timeUs ? this
                : new SoundEvent(us, sound, x, y, z, volume, pitch, range, listenerX, listenerY, listenerZ, listenerYaw);
    }

    public long timeUs() {
        return timeUs;
    }

    public String sound() {
        return sound;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float volume() {
        return volume;
    }

    public float pitch() {
        return pitch;
    }

    public float range() {
        return range;
    }

    public double listenerX() {
        return listenerX;
    }

    public double listenerY() {
        return listenerY;
    }

    public double listenerZ() {
        return listenerZ;
    }

    public float listenerYaw() {
        return listenerYaw;
    }
}
