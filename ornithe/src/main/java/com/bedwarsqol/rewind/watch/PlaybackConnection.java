package com.bedwarsqol.rewind.watch;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.ChatComponentText;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The connection a Rewind recording plays through (PLAN section 5 layer 1, TASK A13): vanilla's client
 * {@link NetworkManager} and {@link NetHandlerPlayClient} on a Netty {@link EmbeddedChannel}, which has no socket.
 * The pipeline is {@code rewind_discard}, then {@code packet_handler} (the NetworkManager). {@code rewind_discard}
 * is the only outbound handler, so every write the handler or NetworkManager makes ends there: it is released and
 * its promise completed, and nothing is queued or sent anywhere.
 *
 * <p>Recorded packets are not read from the channel: the session decodes them with {@link VanillaPacketDecoder}
 * and hands them to {@link #handler()} itself. The embedded event loop runs everything inline on the calling
 * thread, so the connection is used from the client thread only.
 */
public final class PlaybackConnection {

    static final String DISCARD = "rewind_discard";
    static final String PACKET_HANDLER = "packet_handler";

    private final EmbeddedChannel channel;
    private final NetworkManager networkManager;
    private final NetHandlerPlayClient handler;
    private final Discard discard;

    private PlaybackConnection(EmbeddedChannel channel, NetworkManager networkManager, NetHandlerPlayClient handler,
                               Discard discard) {
        this.channel = channel;
        this.networkManager = networkManager;
        this.handler = handler;
        this.discard = discard;
    }

    /**
     * A new open connection in the PLAY state with a play handler for {@code profile} (the session's profile). The
     * handler's constructor only stores its arguments, so {@code mc} may be null in tests.
     */
    public static PlaybackConnection open(Minecraft mc, GameProfile profile) {
        final NetworkManager nm = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
        final Discard discard = new Discard();
        // Named handlers are added while the channel registers, before it goes active, so the NetworkManager sees
        // channelActive and takes the channel as its own.
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel c) {
                c.pipeline().addLast(DISCARD, discard);
                c.pipeline().addLast(PACKET_HANDLER, nm);
            }
        });
        nm.setConnectionState(EnumConnectionState.PLAY);
        NetHandlerPlayClient handler = new NetHandlerPlayClient(mc, null, nm, profile);
        nm.setNetHandler(handler);
        if (!nm.isChannelOpen()) {
            throw new IllegalStateException("playback channel did not open");
        }
        return new PlaybackConnection(ch, nm, handler, discard);
    }

    public NetworkManager networkManager() {
        return networkManager;
    }

    public NetHandlerPlayClient handler() {
        return handler;
    }

    EmbeddedChannel channel() {
        return channel;
    }

    /** Outbound messages thrown away so far. */
    public long discarded() {
        return discard.count.get();
    }

    public boolean isOpen() {
        return channel.isOpen();
    }

    /** Closes the channel; idempotent. Writes after this stay in NetworkManager's own queue and go nowhere. */
    public void close() {
        if (channel.isOpen()) {
            networkManager.closeChannel(new ChatComponentText("Rewind playback ended"));
        }
    }

    /** Releases every outbound message and reports the write as done. Never forwards a write. */
    static final class Discard extends ChannelOutboundHandlerAdapter {
        final AtomicLong count = new AtomicLong();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            count.incrementAndGet();
            ReferenceCountUtil.release(msg);
            promise.trySuccess();
        }
    }
}
