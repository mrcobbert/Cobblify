package com.bedwarsqol.rewind.watch;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.lang.ref.WeakReference;

/**
 * Rewind's two views (PLAN section 4; G8, G37, G43, Q1, Q2): <b>your view</b> (key 1, the default) puts the camera at
 * your recorded eye position and camera rotation every frame; the <b>free camera</b> (key 2) starts where your view
 * was and moves by real time, so it flies while paused, at any speed and during a forward seek.
 *
 * <p><b>For the viewer screen</b> (it turns keys and the mouse into these calls; all on the client thread):
 * <ul>
 *   <li>{@link #selectYourView()}, {@link #selectFreeCamera()}, {@link #selectCamera(int)} (the camera key's index,
 *       {@code ViewerLayout.CAMERA_LABELS}), {@link #cameraIndex()}, {@link #freeCamera()};</li>
 *   <li>{@link #move(int, int, int)}: the movement keys held this frame (call it every frame while the free camera is
 *       up; each call drives the next drawn frame only, so nothing keeps flying once the calls stop), or
 *       {@link #moveFromKeyBindings()}, which reads the user's forward/back/left/right/jump/sneak bindings;</li>
 *   <li>{@link #look(double, double)}: a mouse movement in pixels (the viewer holds the cursor in the free camera);</li>
 *   <li>{@link #scroll(int)}: the mouse wheel changes the flight speed; {@link #speedLevel()} and
 *       {@link #speedBarAlpha()} feed {@code RewindPainter.flySpeed}.</li>
 * </ul>
 * In your view {@code move}, {@code look} and {@code scroll} do nothing.
 *
 * <p>A new recording starts in your view; seeks and rebuilt worlds keep the view and the free camera's place. Which
 * view is up lives in the session's {@link ViewState} (the single owner of what the view shows); this class poses the
 * camera for it.
 */
public final class CameraController {

    /** The camera key's index of each view ({@code ViewerLayout.CAMERA_LABELS}). */
    public static final int YOUR_VIEW = ViewerLayout.YOU;
    public static final int FREE_CAMERA = ViewerLayout.FREE;
    /** The longest frame step the free camera moves by (a hitch or the loading card does not jump it). */
    static final double MAX_STEP_SECONDS = 0.25;

    private static final SelfTrack.Pose youPose = new SelfTrack.Pose();
    private static final FreeCameraMath.Camera free = new FreeCameraMath.Camera();
    private static boolean youPoseValid;
    /** The recording the views belong to (weak: a finished recording is not kept in memory). */
    private static WeakReference<Object> recording = new WeakReference<Object>(null);
    private static int level = FreeCameraMath.DEFAULT_LEVEL;
    private static long scrollNanos = Long.MIN_VALUE;
    private static long lastFrameNanos;
    private static int moveForward;
    private static int moveStrafe;
    private static int moveVertical;

    private CameraController() {
    }

    // ================================================================== viewer API

    private static ViewState view() {
        return RewindPlayback.view();
    }

    /** Key 1: your recorded view (with your hand, hotbar and hearts); it also ends the F5 and clean-view overrides. */
    public static void selectYourView() {
        int before = view().version();
        view().selectYou();
        clearMove();
        if (view().version() != before) {
            viewChanged();
        }
    }

    /** Key 2: the free camera, starting where your view is (no hand, hotbar or hearts; it never tilts). */
    public static void selectFreeCamera() {
        if (view().freeCamera()) {
            return;
        }
        RewindCamera cam = camera();
        if (youPoseValid) {
            free.x = youPose.x;
            free.y = youPose.eyeY;
            free.z = youPose.z;
            free.yaw = youPose.yaw;
            free.pitch = youPose.pitch;
        } else if (cam != null) {
            free.x = cam.posX;
            free.y = cam.posY + cam.getEyeHeight();
            free.z = cam.posZ;
            free.yaw = cam.rotationYaw;
            free.pitch = cam.rotationPitch;
        }
        free.pitch = Math.max(-90f, Math.min(90f, free.pitch));
        view().selectFree();
        clearMove();
        lastFrameNanos = System.nanoTime();
        viewChanged();
    }

    /**
     * The free camera starting at an exact eye pose (Phase 14: where another player's view was, when that player
     * leaves the world or key 2 is pressed while riding).
     */
    public static void selectFreeCameraAt(double x, double eyeY, double z, float yaw, float pitch) {
        free.x = x;
        free.y = eyeY;
        free.z = z;
        free.yaw = yaw;
        free.pitch = Math.max(-90f, Math.min(90f, pitch));
        view().selectFree();
        clearMove();
        lastFrameNanos = System.nanoTime();
        viewChanged();
    }

    /** The controller's game type and the camera's view state follow the view at once (Rev 3 amendment 1). */
    private static void viewChanged() {
        PlaybackSink sink = RewindPlayback.currentSink();
        if (sink != null) {
            sink.viewChanged();
        }
    }

    /** The camera key's choice: {@link #YOUR_VIEW} or {@link #FREE_CAMERA}. */
    public static void selectCamera(int index) {
        if (index == FREE_CAMERA) {
            selectFreeCamera();
        } else if (index == YOUR_VIEW) {
            selectYourView();
        }
    }

    /** {@link #YOUR_VIEW} or {@link #FREE_CAMERA}. */
    public static int cameraIndex() {
        return view().freeCamera() ? FREE_CAMERA : YOUR_VIEW;
    }

    public static boolean freeCamera() {
        return view().freeCamera();
    }

    /**
     * Whether {@code EntityRenderer.hurtCameraEffect} is skipped (code review 1, O2): in the free camera of a playing
     * recording it never tilts, also not vanilla's separate death branch at recorded health 0. Your view keeps vanilla.
     */
    public static boolean cancelsHurtTilt(boolean playing, boolean freeCamera) {
        return playing && freeCamera;
    }

    /** Your view is up (also when no recording plays). */
    public static boolean yourView() {
        return view().yourView();
    }

    /**
     * The free camera's movement keys held this frame: forward +1 W / -1 S, strafe +1 A / -1 D, vertical +1 up
     * (Space) / -1 down (Shift). Moves the next drawn frame by its real time at the current speed.
     */
    public static void move(int forward, int strafe, int vertical) {
        if (!view().freeCamera()) {
            return;
        }
        moveForward = Integer.signum(forward);
        moveStrafe = Integer.signum(strafe);
        moveVertical = Integer.signum(vertical);
    }

    /** {@link #move} from the user's movement key bindings (forward, back, left, right, jump, sneak). */
    public static void moveFromKeyBindings() {
        GameSettings g = Minecraft.getMinecraft().gameSettings;
        move(FreeCameraMath.axis(down(g.keyBindForward), down(g.keyBindBack)),
                FreeCameraMath.axis(down(g.keyBindLeft), down(g.keyBindRight)),
                FreeCameraMath.axis(down(g.keyBindJump), down(g.keyBindSneak)));
    }

    private static boolean down(KeyBinding k) {
        int code = k == null ? 0 : k.getKeyCode();
        try {
            if (code < 0) {
                return Mouse.isButtonDown(code + 100);
            }
            return code > 0 && Keyboard.isKeyDown(code);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Turns the free camera by {@code dx, dy} mouse pixels (dy positive = up, as LWJGL reports it). */
    public static void look(double dx, double dy) {
        if (!view().freeCamera()) {
            return;
        }
        GameSettings g = Minecraft.getMinecraft().gameSettings;
        FreeCameraMath.look(free, dx, dy, g.mouseSensitivity, g.invertMouse);
    }

    /** A mouse wheel delta: one speed level per notch; shows the speed bar. */
    public static void scroll(int wheel) {
        if (!view().freeCamera() || wheel == 0) {
            return;
        }
        level = FreeCameraMath.scrolled(level, wheel);
        scrollNanos = System.nanoTime();
    }

    /** The free camera's speed level, {@code FreeCameraMath.MIN_LEVEL}-{@code MAX_LEVEL}. */
    public static int speedLevel() {
        return level;
    }

    /** The speed bar's opacity now (0 when hidden or in your view). */
    public static float speedBarAlpha() {
        if (!view().freeCamera() || scrollNanos == Long.MIN_VALUE) {
            return 0f;
        }
        return FreeCameraMath.speedBarAlpha((System.nanoTime() - scrollNanos) / 1_000_000L);
    }

    private static final ViewerCameraControls CONTROLS = new Controls();

    /** The viewer's camera seam ({@link ViewerCameraControls}) over this controller; one instance. */
    public static ViewerCameraControls controls() {
        return CONTROLS;
    }

    /**
     * {@link ViewerCameraControls} for the viewer: the camera index maps to {@link #selectCamera}, the free camera's
     * movement to {@link #move} (the controller moves by its own frame time, so the viewer's step length is not
     * needed), drag and wheel to {@link #look} and {@link #scroll}. Selecting works before the world is complete
     * (no camera, no connection yet): your view then only clears the free camera's state.
     */
    private static final class Controls implements ViewerCameraControls {
        @Override
        public void select(int camera) {
            selectCamera(camera == FREE ? FREE_CAMERA : YOUR_VIEW);
        }

        @Override
        public int camera() {
            return view().freeCamera() ? FREE : YOU;
        }

        @Override
        public void move(int forward, int strafe, int vertical, double seconds) {
            CameraController.move(forward, strafe, vertical);
        }

        @Override
        public void look(double dx, double dy) {
            CameraController.look(dx, dy);
        }

        @Override
        public void scroll(int wheel) {
            CameraController.scroll(wheel);
        }

        @Override
        public int flyLevel() {
            return level;
        }
    }

    // ================================================================== playback hooks

    /**
     * {@code renderWorld} HEAD while a recorded world plays: this frame's camera pose (your view: the session's
     * {@link PlaybackSession#yourPose}, by the recorded tick table).
     */
    static void onRender(PlaybackSession session, float partialTicks) {
        LoadedRecording r = session.recording();
        forRecording(r);
        RewindCamera cam = camera();
        if (session.yourPose(youPose)) {
            youPoseValid = true;
        }
        long now = System.nanoTime();
        if (view().freeCamera() && !com.bedwarsqol.rewind.export.RewindExport.active()) {
            // an export's free camera neither moves nor turns (Rewind Export PLAN Solution 2)
            double seconds = Math.min(MAX_STEP_SECONDS, Math.max(0, (now - lastFrameNanos) / 1e9));
            FreeCameraMath.move(free, moveForward, moveStrafe, moveVertical, level, seconds);
            clearMove();
        } else if (com.bedwarsqol.rewind.export.RewindExport.active()) {
            clearMove();
        }
        lastFrameNanos = now;
        apply(cam);
    }

    /** After an S01/S07 made a new camera: it takes the current pose at once. */
    static void restorePose() {
        apply(camera());
    }

    private static void apply(RewindCamera cam) {
        if (cam == null) {
            return;
        }
        if (view().freeCamera()) {
            cam.pose(free.x, free.y, free.z, free.yaw, free.pitch);
        } else if (youPoseValid) {
            cam.pose(youPose.x, youPose.eyeY, youPose.z, youPose.yaw, youPose.pitch);
        }
    }

    /** A connection or frame of recording {@code r}: a recording other than the last one starts in your view. */
    static void forRecording(Object r) {
        if (r != recording.get()) {
            newRecording(r);
        }
    }

    private static void newRecording(Object r) {
        recording = new WeakReference<Object>(r);
        if (!view().yourView()) {
            view().selectYou();
            viewChanged();
        }
        youPoseValid = false;
        scrollNanos = Long.MIN_VALUE;
        clearMove();
    }

    private static void clearMove() {
        moveForward = 0;
        moveStrafe = 0;
        moveVertical = 0;
    }

    private static RewindCamera camera() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer instanceof RewindCamera ? (RewindCamera) mc.thePlayer : null;
    }
}
