package com.bedwarsqol.rewind.watch;

import io.netty.buffer.Unpooled;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.INetHandlerPlayClient;

import java.io.IOException;

/**
 * Decodes one stored Rewind packet (varint packet id + body, as R1 writes NET records and HEADER packets) with
 * vanilla 1.8.9's own readers: the play-state clientbound class for the id, then {@code readPacketData}. Every byte
 * must be consumed. Packets are decoded here, not through the channel's {@code decoder}, so no other mod's
 * pipeline hook sees playback traffic (PLAN section 2, "Feed bytes through vanilla's decoder" rejected).
 *
 * <p>Failure messages never carry packet content (recordings are player data).
 */
public final class VanillaPacketDecoder {

    private VanillaPacketDecoder() {
    }

    /**
     * The packet stored in {@code buf[off, off+len)}.
     *
     * @throws IOException for an empty or cut packet, an id with no clientbound play packet, a body vanilla cannot
     *                     read, or bytes left over after vanilla's reader
     */
    @SuppressWarnings("unchecked") // every clientbound play packet is a Packet<INetHandlerPlayClient>
    public static Packet<INetHandlerPlayClient> decode(byte[] buf, int off, int len) throws IOException {
        if (len <= 0) {
            throw new IOException("empty packet");
        }
        PacketBuffer in = new PacketBuffer(Unpooled.wrappedBuffer(buf, off, len));
        int id;
        try {
            id = in.readVarIntFromBuffer();
        } catch (RuntimeException e) {
            throw new IOException("unreadable packet id", e);
        }
        Packet<?> packet;
        try {
            packet = EnumConnectionState.PLAY.getPacket(EnumPacketDirection.CLIENTBOUND, id);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IOException("packet 0x" + Integer.toHexString(id) + " cannot be created", e);
        }
        if (packet == null) {
            throw new IOException("unknown packet id 0x" + Integer.toHexString(id));
        }
        try {
            packet.readPacketData(in);
        } catch (IOException | RuntimeException e) {
            throw new IOException("packet 0x" + Integer.toHexString(id) + " unreadable", e);
        }
        if (in.isReadable()) {
            throw new IOException("packet 0x" + Integer.toHexString(id) + " left " + in.readableBytes() + " bytes");
        }
        return (Packet<INetHandlerPlayClient>) packet;
    }
}
