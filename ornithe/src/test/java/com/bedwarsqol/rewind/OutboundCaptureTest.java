package com.bedwarsqol.rewind;

import com.bedwarsqol.rewind.watch.PlaybackGate;
import io.netty.buffer.Unpooled;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A4: each C02, C07, C08, C09, C0A, C0B, C0D, C0E and C16 sent on the client thread while a segment is recorded is
 * stored once, as {@code varint id + writePacketData}; no other outbound type and no send from another thread is
 * stored; the packet is left as it was. Also: nothing is stored unbound, not recording, or while a recording plays.
 */
public class OutboundCaptureTest {

    private RecordingCaptureSink sink;
    private PacketStats.Feed feed;

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    @Before
    public void setUp() {
        sink = new RecordingCaptureSink();
        feed = new PacketStats.Feed();
        ActionCapture.bind(sink, null, feed);
        ActionCapture.startSegment(1);
    }

    @After
    public void tearDown() {
        ActionCapture.unbind();
        if (PlaybackGate.global().isMuted()) {
            PlaybackGate.global().exit();
        }
    }

    static PacketBuffer buf() {
        return new PacketBuffer(Unpooled.buffer());
    }

    static byte[] bytes(PacketBuffer b) {
        byte[] out = new byte[b.readableBytes()];
        b.getBytes(b.readerIndex(), out);
        return out;
    }

    /** {@code writePacketData} as vanilla's encoder runs it. */
    static byte[] data(Packet<?> p) throws IOException {
        PacketBuffer b = buf();
        p.writePacketData(b);
        return bytes(b);
    }

    static <P extends Packet<?>> P read(P p, PacketBuffer in) throws IOException {
        p.readPacketData(in);
        return p;
    }

    static List<Packet<?>> nineStored() throws IOException {
        ItemStack sword = new ItemStack(Items.diamond_sword);
        sword.setStackDisplayName("Named");
        sword.setItemDamage(7);
        List<Packet<?>> out = new ArrayList<Packet<?>>();
        PacketBuffer use = buf();
        use.writeVarIntToBuffer(42);
        use.writeVarIntToBuffer(1); // ATTACK
        out.add(read(new C02PacketUseEntity(), use));
        out.add(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.START_DESTROY_BLOCK, new BlockPos(1, 64, -3),
                EnumFacing.UP));
        out.add(new C08PacketPlayerBlockPlacement(new BlockPos(5, 70, 9), 1, new ItemStack(Items.stick, 3), 0.5f,
                1f, 0.25f));
        out.add(new C09PacketHeldItemChange(4));
        out.add(new C0APacketAnimation());
        PacketBuffer action = buf();
        action.writeVarIntToBuffer(42);
        action.writeVarIntToBuffer(3); // START_SPRINTING
        action.writeVarIntToBuffer(0);
        out.add(read(new C0BPacketEntityAction(), action));
        out.add(new C0DPacketCloseWindow(2));
        out.add(new C0EPacketClickWindow(2, 13, 0, 1, sword, (short) 5));
        out.add(new C16PacketClientStatus(C16PacketClientStatus.EnumState.PERFORM_RESPAWN));
        return out;
    }

    static int serverboundId(Packet<?> p) {
        return EnumConnectionState.PLAY.getPacketId(EnumPacketDirection.SERVERBOUND, p);
    }

    @Test
    public void eachOfTheNineStoredTypesWritesOneOutWithVanillasBytes() throws Exception {
        List<Packet<?>> packets = nineStored();
        int[] ids = new int[packets.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = serverboundId(packets.get(i));
        }
        assertArrayEquals(new int[]{0x02, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x16}, ids);

        for (Packet<?> p : packets) {
            byte[] before = data(p);
            ActionCapture.onSend(p);
            assertArrayEquals("the packet is left as it was: " + p.getClass().getSimpleName(), before, data(p));
        }
        List<RecordingCaptureSink.Rec> outs = sink.ofTag(RewindFormat.TAG_OUT);
        assertEquals(9, outs.size());
        assertEquals("nothing but OUT", 9, sink.recs.size());
        ExtCodec.Out out = new ExtCodec.Out();
        for (int i = 0; i < 9; i++) {
            byte[] body = outs.get(i).body;
            out.read(body, 0, body.length);
            assertEquals(ids[i], out.packetId);
            assertArrayEquals(packets.get(i).getClass().getSimpleName(), data(packets.get(i)),
                    Arrays.copyOfRange(out.buf, out.dataOff, out.dataOff + out.dataLen));
        }
        assertEquals("one timing per stored packet", 9, feed.out.count());
    }

    @Test
    public void otherTypesAndOtherThreadsAreNeverStored() throws Exception {
        ActionCapture.onSend(new C01PacketChatMessage("hello"));
        ActionCapture.onSend(new C03PacketPlayer(true));
        ActionCapture.onSend(new C0FPacketConfirmTransaction(0, (short) 1, true));
        final List<Packet<?>> nine = nineStored();
        Thread other = new Thread(new Runnable() {
            @Override
            public void run() {
                for (Packet<?> p : nine) {
                    ActionCapture.onSend(p);
                }
            }
        });
        other.start();
        other.join();
        assertEquals(0, sink.appends);
        // the client thread still records
        ActionCapture.onSend(nine.get(4));
        assertEquals(1, sink.appends);
    }

    @Test
    public void unboundNotRecordingOrMutedStoresNothing() throws Exception {
        List<Packet<?>> nine = nineStored();
        sink.recording = false;
        for (Packet<?> p : nine) {
            ActionCapture.onSend(p);
        }
        sink.recording = true;
        PlaybackGate.global().enter();
        for (Packet<?> p : nine) {
            ActionCapture.onSend(p);
        }
        PlaybackGate.global().exit();
        ActionCapture.unbind();
        for (Packet<?> p : nine) {
            ActionCapture.onSend(p);
        }
        assertEquals(0, sink.appends);
        assertTrue(!ActionCapture.bound());
    }
}
