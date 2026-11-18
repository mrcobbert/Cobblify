package com.bedwarsqol.rewind.watch;

/**
 * What the viewer screen needs from the playback cameras (G37, G43, Q1, Q2; PLAN section 4): which camera is active,
 * switching it, and the free camera's input. The viewer reads keys and the mouse and calls these; the camera phase's
 * controller does the moving with {@link FreeCameraMath} and connects itself with {@link #set}. Until then
 * {@link Unwired} stands in: it remembers the chosen camera and flight speed and moves nothing.
 *
 * <p>Client thread only. Cameras are numbered as the bar's camera key lists them ({@link ViewerLayout#CAMERA_LABELS}):
 * {@link #YOU}, {@link #FREE}.
 */
public interface ViewerCameraControls {

    /** Your view: your recorded eye position and camera rotation. */
    int YOU = 0;
    /** The free camera. */
    int FREE = 1;

    /**
     * Makes {@code camera} ({@link #YOU} or {@link #FREE}) the active camera; the free camera starts where your view
     * is. The viewer calls {@code select(YOU)} once when it first shows a new session, possibly before the world is
     * complete.
     */
    void select(int camera);

    /** The active camera, {@link #YOU} or {@link #FREE}. */
    int camera();

    /**
     * Moves the free camera for {@code seconds} of real time (paused or not, at any speed), at the current flight
     * speed. Called every viewer frame while the free camera is active and a movement key is down.
     *
     * @param forward  +1 forward, -1 back (the user's forward and back bindings)
     * @param strafe   +1 left, -1 right (the user's left and right bindings)
     * @param vertical +1 up (Space), -1 down (Shift), Q1
     */
    void move(int forward, int strafe, int vertical, double seconds);

    /**
     * Turns the free camera by a drag of {@code dx, dy} mouse pixels (LWJGL's deltas: dy positive = the mouse moved
     * up), Q2. The controller applies the user's sensitivity and invert setting ({@link FreeCameraMath#look}).
     */
    void look(double dx, double dy);

    /** A mouse wheel delta while the free camera is active: one flight speed level per notch, by sign. */
    void scroll(int wheel);

    /** The free camera's flight speed level, {@link FreeCameraMath#MIN_LEVEL} to {@link FreeCameraMath#MAX_LEVEL}. */
    int flyLevel();

    /** The connected controls ({@link Unwired} until {@link #set}). */
    static ViewerCameraControls get() {
        return Holder.current;
    }

    /** Connects the cameras' controller; null goes back to a fresh {@link Unwired}. */
    static void set(ViewerCameraControls controls) {
        Holder.current = controls != null ? controls : new Unwired();
    }

    /** Holds the connected controls. */
    final class Holder {
        static volatile ViewerCameraControls current = new Unwired();

        private Holder() {
        }
    }

    /** The stand-in before the cameras are connected: remembers the camera and the flight speed, moves nothing. */
    final class Unwired implements ViewerCameraControls {
        private int camera = YOU;
        private int level = FreeCameraMath.DEFAULT_LEVEL;

        @Override
        public void select(int c) {
            camera = c == FREE ? FREE : YOU;
        }

        @Override
        public int camera() {
            return camera;
        }

        @Override
        public void move(int forward, int strafe, int vertical, double seconds) {
        }

        @Override
        public void look(double dx, double dy) {
        }

        @Override
        public void scroll(int wheel) {
            level = FreeCameraMath.scrolled(level, wheel);
        }

        @Override
        public int flyLevel() {
            return level;
        }
    }
}
