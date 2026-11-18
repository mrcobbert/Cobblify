package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.feature.DiagLog;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.world.WorldSettings;

import java.io.IOException;

/**
 * Where {@link TickFeeder} hands a recording's packets in the client (PLAN section 2-3): each record is decoded with
 * vanilla's own readers ({@link VanillaPacketDecoder}) and processed by the playback connection's
 * {@link NetHandlerPlayClient} on the client thread, where {@code PacketThreadUtil} runs it inline. Packets never pass
 * through the channel, so no other mod's pipeline hook sees them, and nothing is written anywhere.
 *
 * <p><b>Boundary hook</b> (after every S01 and S07 that applied): S01 remembers the recorded entity id and gives the
 * camera {@link RewindCamera#RESERVED_ID}; both make the packet's game type the recorded game mode and put the
 * controller in the view's game type, which is SPECTATOR until Phase 4c; then the recorded player's puppet
 * ({@link RewindSelfPuppet}) is added with the recorded id if the world lacks it, and the new camera takes the current
 * view's pose at once ({@link CameraController#restorePose}).
 *
 * <p>{@link #sample} (each world tick, after its records): the puppet takes the self track's state for the tick
 * ({@link PuppetState}), and is re-added if something removed it. {@link #sessionHook}: nothing reaches it before
 * Phase 4c (S2B reason 3 still drops).
 */
public final class PlaybackSink implements PacketSink {

    private final Minecraft mc;
    private final NetHandlerPlayClient handler;
    private Packet<INetHandlerPlayClient> last;
    private int recordedEntityId = Integer.MIN_VALUE;
    private WorldSettings.GameType recordedGameType = WorldSettings.GameType.NOT_SET;
    private long lastSampleTick = -1;
    private long lastSampleUs;
    private final PuppetState puppetState = new PuppetState();
    private RewindSelfPuppet puppet;
    private boolean puppetFailureLogged;

    public PlaybackSink(Minecraft mc, PlaybackConnection connection) {
        this.mc = mc;
        this.handler = connection.handler();
    }

    @Override
    public boolean apply(int packetId, byte[] buf, int off, int len) {
        last = null;
        Packet<INetHandlerPlayClient> p;
        try {
            p = VanillaPacketDecoder.decode(buf, off, len);
        } catch (IOException e) {
            return false;
        }
        p.processPacket(handler);
        last = p;
        return true;
    }

    @Override
    public void boundary(int packetId, byte[] buf, int off, int len) {
        Packet<INetHandlerPlayClient> p = last;
        last = null;
        WorldSettings.GameType type = null;
        if (p instanceof S01PacketJoinGame) {
            S01PacketJoinGame join = (S01PacketJoinGame) p;
            recordedEntityId = join.getEntityId();
            type = join.getGameType();
            if (mc.thePlayer != null) {
                mc.thePlayer.setEntityId(RewindCamera.RESERVED_ID);
            }
        } else if (p instanceof S07PacketRespawn) {
            type = ((S07PacketRespawn) p).getGameType();
        }
        if (type != null && type != WorldSettings.GameType.NOT_SET) {
            recordedGameType = type;
        }
        if (mc.playerController != null) {
            mc.playerController.setGameType(WorldSettings.GameType.SPECTATOR); // the view's game type until P4c
        }
        try {
            ensurePuppet(feedTimeUs());
        } catch (RuntimeException e) {
            puppetFailed(e);
        }
        CameraController.restorePose();
    }

    @Override
    public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
        return true; // no session-hook packet before Phase 4c
    }

    @Override
    public void sample(long tick, long timeUs) {
        lastSampleTick = tick;
        lastSampleUs = timeUs;
        try {
            if (ensurePuppet(timeUs)) {
                return; // a new puppet took this tick's sample already
            }
            SelfTrack track = track();
            if (puppet != null && track != null && puppetState.sample(track, timeUs)) {
                puppet.follow();
            }
        } catch (RuntimeException e) {
            puppetFailed(e);
        }
    }

    /** The puppet is a picture of you, not the world's state: a failure is logged once and playback goes on. */
    private void puppetFailed(RuntimeException e) {
        if (!puppetFailureLogged) {
            puppetFailureLogged = true;
            DiagLog.log("rewind-watch: puppet failed " + e.getClass().getSimpleName());
        }
    }

    /**
     * Adds the puppet with the recorded id when the current world lacks it (a new world after S01/S07, or something
     * removed it): a fresh {@link PuppetState} sampled at {@code timeUs}, so it stands at the recorded pose wearing
     * everything recorded so far. The camera must already have its reserved id (an id clash would remove it).
     *
     * @return true if a puppet was added
     */
    private boolean ensurePuppet(long timeUs) {
        WorldClient world = mc.theWorld;
        if (world == null || recordedEntityId == Integer.MIN_VALUE || recordedEntityId == RewindCamera.RESERVED_ID) {
            return false;
        }
        if (puppet != null && puppet.worldObj == world && world.getEntityByID(recordedEntityId) == puppet) {
            return false;
        }
        SelfTrack track = track();
        if (track == null) {
            return false;
        }
        puppetState.reset();
        if (!puppetState.sample(track, timeUs)) {
            return false;
        }
        RewindSelfPuppet p = new RewindSelfPuppet(world, puppetProfile(), puppetState);
        p.place();
        p.follow();
        world.addEntityToWorld(recordedEntityId, p);
        puppet = p;
        return true;
    }

    /** The tab entry's profile for the session's account (its skin), else the session profile (format gap 1). */
    private GameProfile puppetProfile() {
        GameProfile own = mc.getSession().getProfile();
        NetworkPlayerInfo info = own.getId() == null ? null : handler.getPlayerInfo(own.getId());
        return info != null && info.getGameProfile() != null ? info.getGameProfile() : own;
    }

    private static SelfTrack track() {
        LoadedRecording r = RewindPlayback.session().recording();
        return r == null ? null : r.selfTrack();
    }

    /** The time of the record being fed (the origin for headers). */
    private static long feedTimeUs() {
        TickFeeder f = RewindPlayback.session().feeder();
        return f == null ? 0 : f.recordTimeUs();
    }

    /** The recorded player's stand-in in the current world, or null. */
    public RewindSelfPuppet puppet() {
        return puppet;
    }

    /** The recorded player's entity id from the last S01, or {@link Integer#MIN_VALUE}. */
    public int recordedEntityId() {
        return recordedEntityId;
    }

    /** The recorded game mode from the last S01/S07. */
    public WorldSettings.GameType recordedGameType() {
        return recordedGameType;
    }

    public long lastSampleTick() {
        return lastSampleTick;
    }

    public long lastSampleUs() {
        return lastSampleUs;
    }
}
