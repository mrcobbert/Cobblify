package com.bedwarsqol.rewind.watch;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C03PacketPlayer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Rewind's playback connection (PLAN Phase 3, TASK A13): vanilla's {@link NetworkManager} on an
 * {@link EmbeddedChannel} whose first outbound handler throws away every write. Nothing reaches a socket, nothing
 * is queued, and the connection stays open until {@link PlaybackConnection#close()}. Fake profile only.
 */
public class PlaybackConnectionTest {

    private static final GameProfile PROFILE = new GameProfile(new UUID(1L, 2L), "FakeViewer");

    @Test
    public void handlerIsBuiltWithoutMinecraftOnAnOpenPlayConnection() {
        PlaybackConnection c = PlaybackConnection.open(null, PROFILE);
        try {
            NetworkManager nm = c.networkManager();
            NetHandlerPlayClient h = c.handler();
            assertNotNull(nm);
            assertNotNull(h);
            assertSame("the handler listens on the playback connection", h, nm.getNetHandler());
            assertSame(nm, h.getNetworkManager());
            assertSame(PROFILE, h.getGameProfile());
            assertTrue(nm.isChannelOpen());
            assertTrue(c.isOpen());
            assertEquals(EnumConnectionState.PLAY,
                    c.channel().attr(NetworkManager.attrKeyConnectionState).get());
            assertNotNull(c.channel().pipeline().get(PlaybackConnection.DISCARD));
            assertSame(nm, c.channel().pipeline().get(PlaybackConnection.PACKET_HANDLER));
        } finally {
            c.close();
        }
    }

    @Test
    public void everyOutboundPacketIsDiscardedAndNothingReachesASocket() {
        PlaybackConnection c = PlaybackConnection.open(null, PROFILE);
        try {
            NetworkManager nm = c.networkManager();
            EmbeddedChannel ch = c.channel();

            nm.sendPacket(new C01PacketChatMessage("fake chat"));
            nm.sendPacket(new C03PacketPlayer(true));
            c.handler().addToSendQueue(new C01PacketChatMessage("/fake command"));
            c.handler().addToSendQueue(new C03PacketPlayer(false));

            assertEquals("every write reached the discard handler (none queued inside NetworkManager)", 4,
                    c.discarded());
            assertTrue("nothing left the pipeline", ch.outboundMessages().isEmpty());
            assertNull(ch.readOutbound());
            assertTrue(ch.isOpen());
            assertTrue(nm.isChannelOpen());
            assertFalse("no socket behind the connection", nm.getRemoteAddress() instanceof InetSocketAddress);
            assertFalse(ch.remoteAddress() instanceof InetSocketAddress);
            assertFalse(ch.localAddress() instanceof InetSocketAddress);
            assertFalse(nm.isLocalChannel());
        } finally {
            c.close();
        }
    }

    @Test
    public void closeClosesTheChannelAndLaterWritesStayLocal() {
        PlaybackConnection c = PlaybackConnection.open(null, PROFILE);
        Channel ch = c.channel();
        c.close();
        assertFalse(ch.isOpen());
        assertFalse(c.isOpen());
        assertFalse(c.networkManager().isChannelOpen());
        c.close(); // idempotent

        // A packet sent after close stays in NetworkManager's own queue: the closed channel gets nothing.
        c.networkManager().sendPacket(new C03PacketPlayer(true));
        assertEquals(0, c.discarded());
        assertTrue(c.channel().outboundMessages().isEmpty());
    }
}
