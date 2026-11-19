package com.bedwarsqol.rewind.watch;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.World;

/**
 * The recorded player while a Rewind recording plays (PLAN section 4, "Puppet"): an ordinary other player standing in
 * for you, because the client player is the camera ({@link RewindCamera}). It is added to the world with the recorded
 * entity id after every S01/S07 ({@link PlaybackSink}), so every packet the server sent about you (head look S19,
 * metadata S1C, effects S1D) lands on it.
 *
 * <p>Each world tick it takes {@link PuppetState}'s sample: it moves to the recorded position during the tick, like a
 * player the server moved, takes the recorded flags, swings when the recorded swing starts and wears each EQUIP change.
 * It is not drawn in your view (the camera is you) or while the recorded invisible flag is set.
 *
 * <p>Three one-line hooks for later phases (PLAN "B-seam"): {@link #playSound} asks the playback parts first (Phase
 * 8's {@code PuppetSounds}; vanilla's other-player sound is silent on a client), {@link #getPlayerInfo()} too (Phase
 * 13's {@code Identity}), and whether it is drawn is {@link ViewState#puppetHidden} (Phase 7 changes that rule).
 */
public class RewindSelfPuppet extends EntityOtherPlayerMP {

    private final PuppetState state;

    public RewindSelfPuppet(World world, GameProfile profile, PuppetState state) {
        super(world, profile);
        this.state = state;
        this.noClip = true;
    }

    /** Puts the puppet at the sample's pose at once (when it is added). */
    public void place() {
        setLocationAndAngles(state.x(), state.y(), state.z(), state.yaw(), state.pitch());
        prevPosX = lastTickPosX = posX;
        prevPosY = lastTickPosY = posY;
        prevPosZ = lastTickPosZ = posZ;
        setRotationYawHead(state.headYaw());
        prevRotationYawHead = rotationYawHead;
        renderYawOffset = prevRenderYawOffset = state.yaw();
    }

    /** Takes the latest sample: moves there during the coming world tick, flags, swing and equipment. */
    public void follow() {
        setPositionAndRotation2(state.x(), state.y(), state.z(), state.yaw(), state.pitch(), 1, false);
        setRotationYawHead(state.headYaw());
        onGround = state.onGround();
        setSneaking(state.sneaking());
        setSprinting(state.sprinting());
        setInvisible(state.invisible());
        setFlag(0, state.burning());
        setEating(state.usingItem()); // drives vanilla's other-player use animation
        if (state.swingStarted()) {
            swingItem(); // EntityLivingBase: on a client world it only starts the animation
        }
        for (int slot = 0; slot < PuppetState.SLOTS; slot++) {
            if (state.equipChanged(slot)) {
                setCurrentItemOrArmor(slot, decode(state.equipItem(slot)));
            }
        }
    }

    /** Vanilla's slot reader over R1's EQUIP bytes; unreadable bytes are an empty slot. */
    static ItemStack decode(byte[] item) {
        if (item == null || item.length == 0) {
            return null;
        }
        try {
            return new PacketBuffer(Unpooled.wrappedBuffer(item)).readItemStackFromBuffer();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean isInRangeToRender3d(double x, double y, double z) {
        return !RewindPlayback.view().puppetHidden(state.sampled(), state.invisible())
                && super.isInRangeToRender3d(x, y, z);
    }

    @Override
    public void playSound(String name, float volume, float pitch) {
        if (!RewindPlayback.puppetSound(this, name, volume, pitch)) {
            super.playSound(name, volume, pitch);
        }
    }

    @Override
    protected NetworkPlayerInfo getPlayerInfo() {
        NetworkPlayerInfo info = RewindPlayback.playerInfo(this);
        return info != null ? info : super.getPlayerInfo();
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }
}
