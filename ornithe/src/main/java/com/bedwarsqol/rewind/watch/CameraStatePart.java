package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

/**
 * Your view's camera state (PLAN "Camera state", Phase 7; TASK B1, B5, B6, B8): the view bob, the hand's sway, the
 * nausea warp, the derived FOV and the recorded invisible and burning flags, put on the playback camera
 * ({@link RewindCamera#cameraState}) so vanilla's renderer draws them as it drew them live. All of them are fields of
 * the client player that vanilla updates in the player's own tick, which the camera never runs.
 *
 * <ul>
 *   <li>Per world tick k ({@link #tick}): tick k's recorded end state ({@link SelfTrack#tickState}) steps
 *       {@link ViewBob} (position, ground, sneak, riding, the recorded health on the camera) and the nausea warp (the
 *       confusion effect on the puppet, where S1D lands); {@link ArmSway} steps toward the camera's rotation at that
 *       tick (your view's CAMERA pose); {@link DerivedFov} takes the puppet's movement speed (sprint and Speed, from
 *       the recorded sprint flag and S20) and the camera's bow draw, only when the recording holds no FOV records
 *       (Phase 12 uses those).</li>
 *   <li>Only your view shows them. The free camera and another player's view get no bob, no sway, no warp, FOV
 *       multiplier 1 and neither flag; the state keeps being computed, so going back to your view shows it at once.
 *       A view switch applies at once ({@link #viewChanged}); the hand's lag restarts from the camera's rotation in
 *       the next drawn frame ({@link #frame}), after the camera took your view's pose.</li>
 *   <li>{@link #restart}, {@link #boundary} (a new camera) and {@link #exit} forget the history.</li>
 * </ul>
 * Whether the camera is a spectator (the hand, overlays and vanilla's translucent invisibles) is the camera's own
 * answer ({@link ViewState#cameraSpectator}); this part never sends, opens nothing and reads no live tracker.
 *
 * <p>Registered by {@link #register()} (Phase 16 calls it from {@code BedwarsQol}).
 */
public final class CameraStatePart implements PlaybackPart {

    private static final CameraStatePart INSTANCE = new CameraStatePart();

    /** Adds the part to Rewind playback once ({@link RewindPlayback#register}). Client thread. */
    public static void register() {
        RewindPlayback.register(INSTANCE);
    }

    /** The registered instance. */
    public static CameraStatePart instance() {
        return INSTANCE;
    }

    private final ViewBob bob = new ViewBob();
    private final ArmSway arm = new ArmSway();
    private final SelfTrackEncoder.State state = new SelfTrackEncoder.State();
    private int flags;
    private float fov = 1f;
    private boolean snapArm = true;

    CameraStatePart() {
    }

    // ================================================================== PlaybackPart

    @Override
    public void restart() {
        forget();
    }

    @Override
    public void boundary() {
        forget();
        apply(camera(), yourView());
    }

    @Override
    public void tick(long tick, long timeUs) {
        RewindCamera cam = camera();
        if (cam == null) {
            return;
        }
        PlaybackSession session = RewindPlayback.session();
        LoadedRecording r = session.recording();
        Timeline t = session.timeline();
        SelfTrack track = r == null ? null : r.selfTrack();
        if (track != null && t != null && track.tickState(t.ticks(), tick, state)) {
            PlaybackSink sink = RewindPlayback.currentSink();
            RewindSelfPuppet puppet = sink == null ? null : sink.puppet();
            boolean derived = !r.has(RewindFormat.TAG_FOV);
            step(state.x, state.y, state.z, state.flags, cam.getHealth() > 0f, confusion(puppet),
                    derived ? derivedFov(puppet, cam) : 1f);
        }
        boolean you = yourView();
        turn(cam, you);
        apply(cam, you);
    }

    @Override
    public void frame(long timeUs, float partial) {
        snapIfPending(camera(), yourView());
    }

    @Override
    public void viewChanged() {
        viewSwitched(camera(), yourView());
    }

    @Override
    public void exit() {
        forget();
    }

    // ================================================================== state (package-private for tests)

    /** One recorded tick's inputs. */
    void step(double x, double y, double z, int recordedFlags, boolean alive, int confusionDuration, float fovMod) {
        bob.step(x, y, z, recordedFlags, alive);
        bob.nausea(confusionDuration);
        flags = recordedFlags;
        fov = fovMod;
    }

    /** The hand's lag one tick toward the camera's rotation (your view, once it has a pose). */
    void turn(RewindCamera cam, boolean yourView) {
        if (cam != null && yourView && !snapArm) {
            arm.step(cam.rotationYaw, cam.rotationPitch);
        }
    }

    /** After a view switch or a new camera: the hand starts without lag from the pose this frame drew. */
    void snapIfPending(RewindCamera cam, boolean yourView) {
        if (cam == null || !snapArm || !yourView) {
            return;
        }
        arm.snap(cam.rotationYaw, cam.rotationPitch);
        snapArm = false;
        apply(cam, true);
    }

    void viewSwitched(RewindCamera cam, boolean yourView) {
        snapArm = true;
        apply(cam, yourView);
    }

    /** Puts the state on the camera: everything in your view, nothing elsewhere. */
    void apply(RewindCamera cam, boolean yourView) {
        if (cam == null) {
            return;
        }
        // until the frame after a switch snaps it, the hand has no lag (never a stale one)
        cam.cameraState(yourView ? bob : null, yourView && !snapArm ? arm : null,
                yourView && (flags & RewindFormat.FLAG_INVISIBLE) != 0,
                yourView && (flags & RewindFormat.FLAG_BURNING) != 0,
                yourView ? fov : 1f);
    }

    private void forget() {
        bob.reset();
        flags = 0;
        fov = 1f;
        snapArm = true;
    }

    // ================================================================== vanilla reads

    /** The confusion effect's remaining ticks on the recorded player's puppet, or -1. */
    static int confusion(EntityLivingBase puppet) {
        PotionEffect e = puppet == null ? null : puppet.getActivePotionEffect(Potion.confusion);
        return e == null ? -1 : e.getDuration();
    }

    /**
     * {@link DerivedFov} from the puppet's {@code generic.movementSpeed} (vanilla's {@code setSprinting} adds the
     * sprint modifier from the recorded flag; S20 brings Speed) and the camera's walk speed and bow draw (the item in
     * use {@code applyHand} put on it). No puppet: walking speed.
     */
    static float derivedFov(EntityLivingBase puppet, EntityPlayer cam) {
        float walk = cam.capabilities.getWalkSpeed();
        IAttributeInstance speed = puppet == null ? null
                : puppet.getEntityAttribute(SharedMonsterAttributes.movementSpeed);
        double value = speed == null ? walk : speed.getAttributeValue();
        ItemStack use = cam.getItemInUse();
        boolean bow = cam.isUsingItem() && use != null && use.getItem() == Items.bow;
        return DerivedFov.modifier(value, walk, false, bow, bow ? cam.getItemInUseDuration() : 0);
    }

    /**
     * {@code RenderGlobal.setupTerrain}'s spectator flag ({@code renderWorldPass} passes {@code thePlayer.isSpectator()},
     * javap 2026-11-18): the free camera of a playing recording keeps spectator culling, so flying into a wall still
     * shows the world, although the camera is not a spectator there any more (amendment 1). Elsewhere vanilla's value.
     */
    public static boolean terrainSpectator(boolean playing, boolean freeCamera, boolean vanilla) {
        return playing && freeCamera || vanilla;
    }

    private static boolean yourView() {
        return RewindPlayback.view().yourView();
    }

    private static RewindCamera camera() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer instanceof RewindCamera ? (RewindCamera) mc.thePlayer : null;
    }
}
