package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.feature.CameraOptions;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.command.server.CommandBlockLogic;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IMerchant;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.stats.StatFileWriter;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.DamageSource;
import net.minecraft.world.IInteractionObject;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;

/**
 * The client player while a Rewind recording plays (PLAN section 4): an inert camera. Vanilla's mouse look, sound
 * listener, terrain culling and render-view entity are wired to {@code thePlayer}, so the camera is
 * {@code thePlayer}; the recorded player is someone else ({@link RewindSelfPuppet}). Its pose comes from
 * {@link CameraController} each frame (your view or the free camera).
 *
 * <p>Created only during a session, by {@code PlayerControllerMPRewindMixin} at the one place vanilla creates the
 * client player. It is never drawn, never clips, ignores motion, its tick
 * only carries the pose over (previous = current) and counts the hotbar's pickup animations down, it never sends
 * (its chat is swallowed here, so Cobblify's {@code EntityPlayerSPMixin} and {@code OutgoingChat} are never reached
 * from playback), never swings, closes,
 * reports abilities or opens a sign, book, container or trade screen through vanilla (each of those would send or
 * open a screen), never takes damage and never dies. Its connection is the socketless playback connection anyway,
 * which discards every write.
 *
 * <p>The session gives it a reserved negative entity id after each S01, so every packet aimed at the recorded
 * player's id misses it. The pose is set once per frame ({@link #pose}).
 *
 * <p><b>Your view's hand and bars</b> (Rev 3 amendment 1): vanilla's HUD and first-person hand draw what
 * {@code thePlayer} holds, so the recorded player's state is put on the camera. S06, S09, S1F and S2F/S30 for window 0
 * land on it through vanilla's handlers; each world tick {@link #applyHand} adds the self track's part (selected slot,
 * EQUIP stacks, swing, item in use) from {@link HandState}, counts {@code hurtResistantTime} down, and
 * {@link #applyView} the view's part: the recorded {@code hurtTime}, absorption, air and effects (copied from the
 * puppet, where S1C and S1D land) in your view, none of them in the free camera, so it never tilts or tints. Whether
 * the hand and bars are drawn at all is the controller's game type ({@link ViewGameType}). The camera never runs
 * vanilla's death tick, so health at 0 opens nothing.
 *
 * <p><b>Camera state</b> (Phase 7): {@link #isSpectator()} is true only in your view of a recorded spectator, so your
 * view of a survival game draws the bare arm and the water and fire overlays, and invisible entities are translucent
 * only where they were live. The bob, the hand's lag, the nausea warp, the FOV multiplier and the recorded invisible
 * and burning flags come from {@link CameraStatePart} through {@link #cameraState}; {@link #pose} leaves them alone.
 *
 * <p><b>Seams</b> (PLAN "B-seam"): the {@code displayGUI*} and close calls vanilla's window handlers make go to the
 * installed {@link RecordedScreenHolder} ({@link #setScreenHolder}; none until Phase 9, so nothing opens), and
 * {@link #getPlayerInfo()} asks the playback parts first ({@link RewindPlayback#playerInfo}; Phase 13's
 * {@code Identity}), else vanilla's lookup.
 */
public class RewindCamera extends EntityPlayerSP {

    /** The camera's entity id: no server ever sends a negative id for a real entity. */
    public static final int RESERVED_ID = -0x52574E44; // "RWND"

    private static volatile RecordedScreenHolder screens = RecordedScreenHolder.NONE;

    /** {@link #getFovModifier()}: 1 until {@link CameraStatePart} sets your view's derived value. */
    private float fovModifier = 1f;

    /** Installs where window calls go (Phase 9's {@code RecordedScreens}); null: {@link RecordedScreenHolder#NONE}. */
    public static void setScreenHolder(RecordedScreenHolder holder) {
        screens = holder == null ? RecordedScreenHolder.NONE : holder;
    }

    /** The installed holder. */
    public static RecordedScreenHolder screenHolder() {
        return screens;
    }

    public RewindCamera(Minecraft mc, World world, NetHandlerPlayClient handler, StatFileWriter stats) {
        super(mc, world, handler, stats);
        this.noClip = true;
        this.maxHurtTime = 10; // vanilla's value after any hit; the tilt divides by it
    }

    /**
     * This world tick's self-track state (both views, so it does not depend on the view): selected slot, EQUIP stacks
     * ({@code full}: every recorded stack newer than the last window-0 S30/S2F for its slot, for a camera made anew;
     * else only this tick's changes, so a later S2F for a slot wins), the swing and the item in use; then
     * {@link #applyView}.
     */
    public void applyHand(HandState h, boolean full, boolean yourView, EntityLivingBase puppet) {
        inventory.currentItem = h.selectedSlot();
        for (int i = 0; i < HandState.HOTBAR; i++) {
            if (full ? h.hotbarItem(i) != null : h.hotbarChanged(i)) {
                inventory.mainInventory[i] = RewindSelfPuppet.decode(h.hotbarItem(i));
            }
        }
        for (int slot = 1; slot <= inventory.armorInventory.length; slot++) {
            if (full ? h.armourItem(slot) != null : h.armourChanged(slot)) {
                inventory.armorInventory[slot - 1] = RewindSelfPuppet.decode(h.armourItem(slot));
            }
        }
        swingProgressInt = h.swingProgressInt();
        isSwingInProgress = h.swingInProgress();
        prevSwingProgress = h.prevSwingProgress();
        swingProgress = h.swingProgress();
        ItemStack held = inventory.getCurrentItem();
        if (h.inUse() && held != null) {
            clearItemInUse(); // setItemInUse only takes a new stack object
            setItemInUse(held, h.useCount(held.getMaxItemUseDuration()));
        } else if (isUsingItem()) {
            clearItemInUse();
        }
        hurtResistantTime = HandState.countDown(hurtResistantTime);
        applyView(h, yourView, puppet);
    }

    /**
     * The view's part, also applied at once on a view switch: {@code hurtTime} as recorded in your view and 0 in the
     * free camera; absorption, air and effects from the puppet in your view, cleared in the free camera.
     */
    public void applyView(HandState h, boolean yourView, EntityLivingBase puppet) {
        hurtTime = h.hurtTime(yourView);
        boolean copy = yourView && puppet != null;
        setAbsorptionAmount(copy ? puppet.getAbsorptionAmount() : 0f);
        setAir(copy ? puppet.getAir() : 300);
        Collection<PotionEffect> have = getActivePotionEffects();
        if (!have.isEmpty()) {
            for (PotionEffect e : new ArrayList<PotionEffect>(have)) {
                removePotionEffectClient(e.getPotionID());
            }
        }
        if (copy) {
            for (PotionEffect e : puppet.getActivePotionEffects()) {
                addPotionEffect(new PotionEffect(e));
            }
        }
    }

    /**
     * Places the camera with its eye at {@code (x, eyeY, z)}, looking along {@code yaw}/{@code pitch}, with previous
     * = current so the frame shows exactly this pose whatever the partial tick.
     */
    public void pose(double x, double eyeY, double z, float yaw, float pitch) {
        double y = eyeY - getEyeHeight();
        setPositionAndRotation(x, y, z, yaw, pitch);
        holdPose();
    }

    private void holdPose() {
        prevPosX = lastTickPosX = posX;
        prevPosY = lastTickPosY = posY;
        prevPosZ = lastTickPosZ = posZ;
        prevRotationYaw = rotationYaw;
        prevRotationPitch = rotationPitch;
        rotationYawHead = prevRotationYawHead = rotationYaw;
        renderYawOffset = prevRenderYawOffset = rotationYaw;
        motionX = motionY = motionZ = 0;
    }

    /**
     * Each stack's pickup animation one tick on (code review 1, O1): vanilla's
     * {@code InventoryPlayer.decrementAnimations} counts {@code animationsToGo} down from the 5 an S2F sets, but the
     * camera skips the player update that calls it. Only the countdown: no item update runs.
     */
    static void countDownAnimations(ItemStack[] stacks) {
        if (stacks == null) {
            return;
        }
        for (ItemStack s : stacks) {
            if (s != null && s.animationsToGo > 0) {
                s.animationsToGo--;
            }
        }
    }

    /**
     * Amendment 1 and B1: a spectator only in your view while the recorded game mode is spectator
     * ({@link ViewState#cameraSpectator}). This is what vanilla's {@code Entity.isInvisibleToPlayer} reads from the
     * viewer (invisible entities are drawn translucent for a spectator), what hides the bare arm's body parts
     * ({@code RenderPlayer.setModelVisibilities}) and the water and fire overlays ({@code ItemRenderer.renderOverlays}),
     * and the terrain culling flag ({@code renderWorldPass}; the free camera keeps spectator culling through
     * {@code EntityRendererRewindPlaybackMixin}).
     */
    @Override
    public boolean isSpectator() {
        PlaybackSink sink = RewindPlayback.currentSink();
        return RewindPlayback.view().cameraSpectator(
                sink == null ? ViewGameType.NOT_SET : sink.recordedGameType().getID());
    }

    /**
     * Your view's camera state from {@link CameraStatePart} (PLAN "Camera state"): the bob, walked distance and nausea
     * warp ({@code bob}), the hand's lag ({@code arm}), the recorded invisible flag (vanilla hides the bare arm), the
     * recorded burning flag (vanilla's fire overlay reads entity flag 0 on a client world) and the FOV multiplier.
     * Null {@code bob} or {@code arm} (outside your view): no bob, no warp, no lag.
     */
    void cameraState(ViewBob bob, ArmSway arm, boolean invisible, boolean burning, float fov) {
        prevCameraYaw = bob == null ? 0f : bob.prevCameraYaw();
        cameraYaw = bob == null ? 0f : bob.cameraYaw();
        prevCameraPitch = bob == null ? 0f : bob.prevCameraPitch();
        cameraPitch = bob == null ? 0f : bob.cameraPitch();
        prevDistanceWalkedModified = bob == null ? 0f : bob.prevDistanceWalked();
        distanceWalkedModified = bob == null ? 0f : bob.distanceWalked();
        prevTimeInPortal = bob == null ? 0f : bob.prevTimeInPortal();
        timeInPortal = bob == null ? 0f : bob.timeInPortal();
        prevRenderArmYaw = arm == null ? rotationYaw : arm.prevYaw();
        renderArmYaw = arm == null ? rotationYaw : arm.yaw();
        prevRenderArmPitch = arm == null ? rotationPitch : arm.prevPitch();
        renderArmPitch = arm == null ? rotationPitch : arm.pitch();
        if (isInvisible() != invisible) {
            setInvisible(invisible);
        }
        if (getFlag(0) != burning) {
            setFlag(0, burning);
        }
        fovModifier = fov;
    }

    /** Never drawn (PLAN "Camera state"): in F5 the puppet is the recorded body, so there is never a second one. */
    @Override
    public boolean isInRangeToRender3d(double x, double y, double z) {
        return false;
    }

    /**
     * The free camera draws no underwater overlay, as before Phase 7 when the camera was always a spectator; your
     * view and another player's view keep vanilla's (the overlay and the air bubbles).
     */
    @Override
    public boolean isInsideOfMaterial(Material material) {
        return !RewindPlayback.view().freeCamera() && super.isInsideOfMaterial(material);
    }

    /** One-line hook: the parts' tab entry for the camera (Phase 13: the recorded identity), else vanilla's. */
    @Override
    protected NetworkPlayerInfo getPlayerInfo() {
        NetworkPlayerInfo info = RewindPlayback.playerInfo(this);
        return info != null ? info : super.getPlayerInfo();
    }

    @Override
    public void onUpdate() {
        holdPose();
        countDownAnimations(inventory.mainInventory);
    }

    @Override
    public void onLivingUpdate() {
        // inert: no movement input, no physics
    }

    @Override
    public void onUpdateWalkingPlayer() {
        // never reports a position
    }

    @Override
    public void moveEntity(double x, double y, double z) {
        // ignores motion (an explosion's push, velocity)
    }

    @Override
    public void addVelocity(double x, double y, double z) {
    }

    @Override
    public void setVelocity(double x, double y, double z) {
    }

    @Override
    public void sendChatMessage(String message) {
        // nothing is ever sent from playback
    }

    @Override
    public void respawnPlayer() {
    }

    // Everything below would send a packet or open a screen in vanilla; the camera does neither. Window opens and
    // closes go to the recorded-window holder, which draws but never opens them.

    @Override
    public void swingItem() {
        // vanilla sends C0A; the recorded swing is put on the camera's fields directly
    }

    @Override
    public void closeScreen() {
        screens.close(this); // vanilla would send C0D
    }

    @Override
    public void closeScreenAndDropStack() {
        screens.close(this); // S2E
    }

    @Override
    public void sendPlayerAbilities() {
    }

    @Override
    protected void sendHorseJump() {
    }

    @Override
    public void sendHorseInventory() {
    }

    @Override
    public void openEditSign(TileEntitySign sign) {
    }

    @Override
    public void openEditCommandBlock(CommandBlockLogic logic) {
    }

    @Override
    public void displayGUIBook(ItemStack book) {
    }

    @Override
    public void displayGUIChest(IInventory inventory) {
        screens.displayChest(this, inventory);
    }

    @Override
    public void displayGUIHorse(EntityHorse horse, IInventory inventory) {
        screens.displayHorse(this, horse, inventory);
    }

    @Override
    public void displayGui(IInteractionObject object) {
        screens.displayGui(this, object);
    }

    @Override
    public void displayVillagerTradeGui(IMerchant merchant) {
        screens.displayMerchant(this, merchant);
    }

    @Override
    public EntityItem dropOneItem(boolean dropAll) {
        return null;
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        return false;
    }

    @Override
    public void onDeath(DamageSource cause) {
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    @Override
    public void applyEntityCollision(Entity other) {
    }

    @Override
    public boolean isPushedByWater() {
        return false;
    }

    @Override
    public boolean isEntityInsideOpaqueBlock() {
        return false;
    }

    @Override
    protected boolean pushOutOfBlocks(double x, double y, double z) {
        return false;
    }

    /**
     * {@link DerivedFov} in your view (set by {@link CameraStatePart}), else 1; vanilla eases the FOV toward it. The
     * camera overrides vanilla's method, so Cobblify's Dynamic FOV option ({@code AbstractClientPlayerFovMixin}) is
     * applied here: with it off the view stays steady, as it was live.
     */
    @Override
    public float getFovModifier() {
        return CameraOptions.fovModifier(fovModifier);
    }
}
