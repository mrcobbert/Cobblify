package com.bedwarsqol.rewind.watch;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
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
 * controller in the view's game type, which is SPECTATOR until Phase 4c. Phase 4b re-adds the puppet here and
 * restores the camera pose after an S07.
 *
 * <p>{@link #sessionHook}: nothing reaches it before Phase 4c (S2B reason 3 still drops). {@link #sample}: the
 * puppet's per-tick sample is Phase 4b; here it only remembers the time.
 */
public final class PlaybackSink implements PacketSink {

    private final Minecraft mc;
    private final NetHandlerPlayClient handler;
    private Packet<INetHandlerPlayClient> last;
    private int recordedEntityId = Integer.MIN_VALUE;
    private WorldSettings.GameType recordedGameType = WorldSettings.GameType.NOT_SET;
    private long lastSampleTick = -1;
    private long lastSampleUs;

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
    }

    @Override
    public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
        return true; // no session-hook packet before Phase 4c
    }

    @Override
    public void sample(long tick, long timeUs) {
        lastSampleTick = tick;
        lastSampleUs = timeUs;
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
