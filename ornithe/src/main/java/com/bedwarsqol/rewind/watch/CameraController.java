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
 *   <li>{@link #look(double, double)}: a drag in mouse pixels (Q2: drag to look, the cursor stays);</li>
 *   <li>{@link #scroll(int)}: the mouse wheel changes the flight speed; {@link #speedLevel()} and
 *       {@link #speedBarAlpha()} feed {@code RewindPainter.flySpeed}.</li>
 * </ul>
 * In your view {@code move}, {@code look} and {@code scroll} do nothing.
 *
 * <p>A new recording starts in your view; seeks and rebuilt worlds keep the view and the free camera's place.
 */
public final class CameraController {

    /** The camera key's index of each view ({@code ViewerLayout.CAMERA_LABELS}). */
    public static final int YOUR_VIEW = 0;
    public static final int FREE_CAMERA = 1;
    /** The longest frame step the free camera moves by (a hitch or the loading card does not jump it). */
    static final double MAX_STEP_SECONDS = 0.25;

    private static final SelfTrack.Pose youPose = new SelfTrack.Pose();
    private static final FreeCameraMath.Camera free = new FreeCameraMath.Camera();
    private static boolean freeMode;
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

    /** Key 1: your recorded view (with your hand, hotbar and hearts). */
    public static void selectYourView() {
        boolean changed = freeMode;
        freeMode = false;
        clearMove();
        if (changed) {
            viewChanged();
        }
    }

    /** Key 2: the free camera, starting where your view is (no hand, hotbar or hearts; it never tilts). */
    public static void selectFreeCamera() {
        if (freeMode) {
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
        freeMode = true;
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
        return freeMode ? FREE_CAMERA : YOUR_VIEW;
    }

    public static boolean freeCamera() {
        return freeMode;
    }

    /** Your view is up (also when no recording plays). */
    public static boolean yourView() {
        return !freeMode;
    }

    /**
     * The free camera's movement keys held this frame: forward +1 W / -1 S, strafe +1 A / -1 D, vertical +1 up
     * (Space) / -1 down (Shift). Moves the next drawn frame by its real time at the current speed.
     */
    public static void move(int forward, int strafe, int vertical) {
        if (!freeMode) {
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

    /** Turns the free camera by a drag of {@code dx, dy} mouse pixels (dy positive = up, as LWJGL reports it). */
    public static void look(double dx, double dy) {
        if (!freeMode) {
            return;
        }
        GameSettings g = Minecraft.getMinecraft().gameSettings;
        FreeCameraMath.look(free, dx, dy, g.mouseSensitivity, g.invertMouse);
    }

    /** A mouse wheel delta: one speed level per notch; shows the speed bar. */
    public static void scroll(int wheel) {
        if (!freeMode || wheel == 0) {
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
        if (!freeMode || scrollNanos == Long.MIN_VALUE) {
            return 0f;
        }
        return FreeCameraMath.speedBarAlpha((System.nanoTime() - scrollNanos) / 1_000_000L);
    }

    // ================================================================== playback hooks

    /** {@code renderWorld} HEAD while a recorded world plays: this frame's camera pose. */
    static void onRender(PlaybackSession session, float partialTicks) {
        LoadedRecording r = session.recording();
        forRecording(r);
        RewindCamera cam = camera();
        SelfTrack track = r == null ? null : r.selfTrack();
        if (track != null && track.pose(session.renderTimeUs(partialTicks), youPose)) {
            youPoseValid = true;
        }
        long now = System.nanoTime();
        if (freeMode) {
            double seconds = Math.min(MAX_STEP_SECONDS, Math.max(0, (now - lastFrameNanos) / 1e9));
            FreeCameraMath.move(free, moveForward, moveStrafe, moveVertical, level, seconds);
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
        if (freeMode) {
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
        if (freeMode) {
            freeMode = false;
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
        return mc.thePlayer instanceof RewindCamera ? (RewindCamera) mc.thePlayer : null;
    }
}
