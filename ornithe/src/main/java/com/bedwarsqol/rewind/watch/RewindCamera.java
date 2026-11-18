package com.bedwarsqol.rewind.watch;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.command.server.CommandBlockLogic;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IMerchant;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.stats.StatFileWriter;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.DamageSource;
import net.minecraft.world.IInteractionObject;
import net.minecraft.world.World;

/**
 * The client player while a Rewind recording plays (PLAN section 4): an inert camera. Vanilla's mouse look, sound
 * listener, terrain culling and render-view entity are wired to {@code thePlayer}, so the camera is
 * {@code thePlayer}; the recorded player is someone else ({@link RewindSelfPuppet}). Its pose comes from
 * {@link CameraController} each frame (your view or the free camera).
 *
 * <p>Created only during a session, by {@code PlayerControllerMPRewindMixin} at the one place vanilla creates the
 * client player. It is a spectator for its own physics and terrain culling, never clips, ignores motion, its tick
 * only carries the pose over (previous = current), it never sends (its chat is swallowed here, so Cobblify's
 * {@code EntityPlayerSPMixin} and {@code OutgoingChat} are never reached from playback), never swings, closes,
 * reports abilities or opens a sign, book, container or trade screen through vanilla (each of those would send or
 * open a screen), never takes damage and never dies. Its connection is the socketless playback connection anyway,
 * which discards every write.
 *
 * <p>The session gives it a reserved negative entity id after each S01, so every packet aimed at the recorded
 * player's id misses it. The pose is set once per frame ({@link #pose}).
 */
public class RewindCamera extends EntityPlayerSP {

    /** The camera's entity id: no server ever sends a negative id for a real entity. */
    public static final int RESERVED_ID = -0x52574E44; // "RWND"

    public RewindCamera(Minecraft mc, World world, NetHandlerPlayClient handler, StatFileWriter stats) {
        super(mc, world, handler, stats);
        this.noClip = true;
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
        prevRenderArmYaw = renderArmYaw = rotationYaw;
        prevRenderArmPitch = renderArmPitch = rotationPitch;
        motionX = motionY = motionZ = 0;
    }

    @Override
    public boolean isSpectator() {
        return true;
    }

    @Override
    public void onUpdate() {
        holdPose();
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

    // Everything below would send a packet or open a screen in vanilla; the camera does neither.

    @Override
    public void swingItem() {
        // vanilla sends C0A; the recorded swing is put on the camera's fields directly
    }

    @Override
    public void closeScreen() {
    }

    @Override
    public void closeScreenAndDropStack() {
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
    }

    @Override
    public void displayGUIHorse(EntityHorse horse, IInventory inventory) {
    }

    @Override
    public void displayGui(IInteractionObject object) {
    }

    @Override
    public void displayVillagerTradeGui(IMerchant merchant) {
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

    @Override
    public float getFovModifier() {
        return 1.0f;
    }
}
