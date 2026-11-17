package com.bedwarsqol.rewind;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

import java.nio.ByteOrder;
import java.util.List;
import java.util.function.Supplier;

/**
 * Rewind's tap on one connection (PLAN "Capture"): reads each clientbound play frame, lets its {@link
 * CaptureSession} copy it, and forwards the message untouched. It never writes, sends, consumes, releases
 * or throws; {@code ctx.fireChannelRead(msg)} runs exactly once per message, outside the try. No I/O and
 * no logging here: failures latch in the session for the client thread to report.
 *
 * <p><b>Placement.</b> Remote: before {@code decoder}, so after {@code decompress} when compression is on;
 * frames are decompressed {@code [varint id][body]} {@link ByteBuf}s. If a play-state S46 makes vanilla
 * add {@code decompress} after this handler (it does so on this thread, inside the S46's read), a fresh
 * handler on the same session takes this one's place before {@code decoder} before the next frame.
 * Single-player: before {@code packet_handler}; messages are {@link Packet} objects, encoded through one
 * reused {@link PacketBuffer} as vanilla would (allocates; the no-allocation rule is the remote path's).
 * S3F objects are never encoded: {@code writePacketData} would consume their payload's reader index.
 *
 * <p><b>Copying without allocation</b> (remote): heap buffers by {@code System.arraycopy} from the backing
 * array, buffers with a memory address by {@link ByteBuf#getBytes(int, byte[], int, int)}, anything else
 * by an 8-byte {@link ByteBuf#getLong(int)} loop.
 */
public final class RewindCapture extends ChannelInboundHandlerAdapter {

    public static final String NAME = "cobblify_rewind";

    private static final String DECODER = "decoder";
    private static final String DECOMPRESS = "decompress";
    private static final String PACKET_HANDLER = "packet_handler";

    private final CaptureSession session;
    private final boolean local;
    private final ByteBufFrame frame = new ByteBufFrame();
    /** Local path scratch, created on first use. */
    private PacketBuffer scratch;
    /** Set while this handler is being replaced after an S46, so its removal does not close the session. */
    private boolean moving;

    RewindCapture(CaptureSession session, boolean local) {
        if (session == null) {
            throw new NullPointerException("session");
        }
        this.session = session;
        this.local = local;
    }

    public CaptureSession session() {
        return session;
    }

    // ================================================================== install / remove (Phase 6 calls these)

    /**
     * Gate (A10) and install in one step, for {@code SERVER_CONNECT}: does nothing unless {@link
     * CaptureSession#shouldInstall} holds and the pipeline has a place for the handler; only then is {@code
     * factory} asked for the session. Returns the session, or null when nothing was installed. Never throws.
     */
    public static CaptureSession installIfEligible(Channel channel, boolean rewindOn, boolean recordGames,
                                                   boolean local, String serverAddress,
                                                   Supplier<CaptureSession> factory) {
        try {
            if (channel == null || factory == null
                    || !CaptureSession.shouldInstall(rewindOn, recordGames, local, serverAddress)
                    || !placeable(channel.pipeline(), local)) {
                return null;
            }
            CaptureSession session = factory.get();
            return session != null && install(channel, local, session) != null ? session : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Pipeline placement only: remote before {@code decoder} (none: no install), local before {@code
     * packet_handler}. Returns the handler, or null if it could not be placed or one is already there.
     * Never throws.
     */
    public static RewindCapture install(Channel channel, boolean local, CaptureSession session) {
        try {
            ChannelPipeline p = channel.pipeline();
            if (!placeable(p, local)) {
                return null;
            }
            RewindCapture handler = new RewindCapture(session, local);
            p.addBefore(local ? PACKET_HANDLER : DECODER, NAME, handler);
            return handler;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean placeable(ChannelPipeline p, boolean local) {
        return p.get(NAME) == null && p.get(local ? PACKET_HANDLER : DECODER) != null;
    }

    /**
     * Record Games off: removes the handler on the channel's event loop (its removal hands the live chunk
     * off). The file does not depend on this running. Never throws.
     */
    public static void remove(final Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        ChannelPipeline p = channel.pipeline();
                        if (p.get(NAME) != null) {
                            p.remove(NAME);
                        }
                    } catch (RuntimeException ignored) {
                        // the channel is going away; nothing to protect
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // event loop shut down: the channel is dead and the handler with it
        }
    }

    /** The installed handler, or null. */
    public static RewindCapture get(Channel channel) {
        ChannelHandler h = channel == null ? null : channel.pipeline().get(NAME);
        return h instanceof RewindCapture ? (RewindCapture) h : null;
    }

    // ================================================================== Netty callbacks

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        boolean compressionChange = false;
        try {
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                compressionChange = !local && buf.readableBytes() > 0
                        && buf.getByte(buf.readerIndex()) == RewindFormat.PACKET_SET_COMPRESSION;
                frame.set(buf, buf.readerIndex(), buf.readableBytes());
                try {
                    session.onFrame(frame);
                } finally {
                    frame.clear();
                }
            } else if (local && msg instanceof Packet) {
                captureLocal((Packet<?>) msg);
            }
        } catch (Throwable t) {
            session.fail(t);
        }
        ctx.fireChannelRead(msg);
        if (compressionChange) {
            repositionAfterCompressionChange(ctx);
        }
    }

    private void captureLocal(Packet<?> packet) throws Exception {
        Integer boxed = EnumConnectionState.PLAY.getPacketId(EnumPacketDirection.CLIENTBOUND, packet);
        if (boxed == null) {
            return;
        }
        int id = boxed;
        if (id == RewindFormat.PACKET_CUSTOM_PAYLOAD) {
            session.onLocalCustomPayload(packet instanceof S3FPacketCustomPayload
                    ? ((S3FPacketCustomPayload) packet).getChannelName() : null);
            return;
        }
        if (!session.localWants(id)) {
            return;
        }
        PacketBuffer b = scratch;
        if (b == null) {
            b = scratch = new PacketBuffer(Unpooled.buffer(4096));
        }
        b.clear();
        b.writeVarIntToBuffer(id);
        packet.writePacketData(b);
        frame.set(b, b.readerIndex(), b.readableBytes());
        try {
            session.onFrame(frame);
        } finally {
            frame.clear();
        }
    }

    /**
     * After an S46 has passed: if vanilla just put {@code decompress} after this handler, swap in a fresh
     * handler on the same session before {@code decoder}, so the next frame is seen decompressed.
     */
    private void repositionAfterCompressionChange(ChannelHandlerContext ctx) {
        try {
            ChannelPipeline p = ctx.pipeline();
            if (p.get(DECOMPRESS) == null || p.get(DECODER) == null) {
                return;
            }
            List<String> names = p.names();
            int self = names.indexOf(ctx.name());
            int decompress = names.indexOf(DECOMPRESS);
            if (self < 0 || decompress < 0 || self > decompress) {
                return;
            }
            moving = true;
            p.remove(this);
            p.addBefore(DECODER, NAME, new RewindCapture(session, false));
        } catch (Throwable t) {
            session.fail(t);
            if (moving) {
                session.close();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        session.close();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (!moving) {
            session.close();
        }
    }

    // ================================================================== frame view

    /** Reused view of the current message's readable bytes; reads by absolute index only. */
    static final class ByteBufFrame implements CaptureSession.Frame {
        private ByteBuf buf;
        private int base;
        private int length;

        void set(ByteBuf b, int readerIndex, int readable) {
            buf = b;
            base = readerIndex;
            length = readable;
        }

        void clear() {
            buf = null;
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public int byteAt(int index) {
            return buf.getByte(base + index) & 0xFF;
        }

        @Override
        public void copyTo(int index, byte[] dst, int dstOff, int len) {
            if (index < 0 || len < 0 || index + len > length) {
                throw new IndexOutOfBoundsException();
            }
            int src = base + index;
            ByteBuf b = buf;
            if (b.hasArray()) {
                System.arraycopy(b.array(), b.arrayOffset() + src, dst, dstOff, len);
            } else if (b.hasMemoryAddress()) {
                b.getBytes(src, dst, dstOff, len);
            } else {
                int i = 0;
                for (; b.order() == ByteOrder.BIG_ENDIAN && i + 8 <= len; i += 8) {
                    long v = b.getLong(src + i);
                    int o = dstOff + i;
                    dst[o] = (byte) (v >>> 56);
                    dst[o + 1] = (byte) (v >>> 48);
                    dst[o + 2] = (byte) (v >>> 40);
                    dst[o + 3] = (byte) (v >>> 32);
                    dst[o + 4] = (byte) (v >>> 24);
                    dst[o + 5] = (byte) (v >>> 16);
                    dst[o + 6] = (byte) (v >>> 8);
                    dst[o + 7] = (byte) v;
                }
                for (; i < len; i++) {
                    dst[dstOff + i] = b.getByte(src + i);
                }
            }
        }
    }
}
