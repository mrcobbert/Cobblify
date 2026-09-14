package com.bedwarsqol.feature;

import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.HypixelContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.EnumFacing;
import net.weavemc.api.event.ChatEvent;
import net.weavemc.api.event.PacketEvent;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;

/**
 * Weave adapter for {@link HeightLimitCore}. Weave's {@link PacketEvent} hooks NetworkManager on
 * both directions, so every custom payload and block placement request is visible: {@code
 * hypixel:hello} triggers our {@code hypixel:register}, {@code hyevent:location} names the map,
 * and each C08 becomes a placement attempt the core settles a moment later against the world. Chat
 * supplies Hypixel's "Build height limit reached!" verdict and the tick drives world-change expiry
 * and the sidebar {@code Map:} fallback. Everything reaches the core on the client thread. Lunar
 * registers the same event itself; a second registration is harmless and both see each packet.
 * The register message is only ever sent after Hypixel's own hello and only on the exact Hypixel
 * host; off Hypixel this class sends nothing.
 */
public final class HeightLimitWatch {

    private static final int SIDEBAR_INTERVAL = 10;

    private static final HeightLimitCore CORE = new HeightLimitCore(
            HeightLimitStore.load(HeightLimitStore.defaultFile()),
            learned -> HeightLimitStore.save(HeightLimitStore.defaultFile(), learned));

    private int ticks;

    /** The live state; read on the client thread by the HUD and the command. */
    public static HeightLimitCore core() {
        return CORE;
    }

    @SubscribeEvent
    public void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> msg = event == null ? null : event.getPacket();
        if (!(msg instanceof S3FPacketCustomPayload)) return;
        try {
            S3FPacketCustomPayload p = (S3FPacketCustomPayload) msg;
            final String channel = p.getChannelName();
            if (HypixelLocationCodec.HELLO_CHANNEL.equals(channel)
                    || HypixelLocationCodec.LOCATION_CHANNEL.equals(channel)) {
                final byte[] data = copy(p.getBufferData());
                Minecraft.getMinecraft().addScheduledTask(() -> onPayload(channel, data));
            }
        } catch (RuntimeException e) {
            DiagLog.log("heightlimit: payload read failed " + e);
        }
    }

    @SubscribeEvent
    public void onPacketSend(PacketEvent.Send event) {
        Packet<?> msg = event == null ? null : event.getPacket();
        if (!(msg instanceof C08PacketPlayerBlockPlacement)) return;
        final C08PacketPlayerBlockPlacement p = (C08PacketPlayerBlockPlacement) msg;
        Minecraft.getMinecraft().addScheduledTask(() -> onPlacement(p));
    }

    @SubscribeEvent
    public void onChat(ChatEvent.Received event) {
        if (event == null || event.getMessage() == null) return;
        if (!HypixelContext.isOnHypixel()) return;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(event.getMessage().getUnformattedText());
        if (!HeightLimitCore.isLimitMessage(plain)) return;
        CORE.onHeightLimitMessage(System.currentTimeMillis());
        DiagLog.log("heightlimit: denied " + CORE.describe());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
        final Minecraft mc = Minecraft.getMinecraft();
        Object world = mc == null ? null : mc.theWorld;
        boolean inGame = world != null && HypixelContext.isInActiveBedwarsGame();
        if (++ticks >= SIDEBAR_INTERVAL) {
            ticks = 0;
            if (world != null && HypixelContext.isInBedwars()) {
                String map = HypixelContext.sidebarMapLabel();
                if (map != null) CORE.onSidebarMap(world, map);
            }
        }
        CORE.onTick(System.currentTimeMillis(), world, inGame, world == null ? null : (x, y, z) -> {
            if (mc.theWorld == null) return false;
            return mc.theWorld.getBlockState(new BlockPos(x, y, z)).getBlock() != Blocks.air;
        });
    }

    /** Whether the box is currently allowed to draw (enabled, map known, In Game Only honoured). */
    public static boolean visible(ClientSettings cfg, boolean example) {
        if (cfg == null || !cfg.heightLimitEnabled) return false;
        if (example) return true;
        if (!HypixelContext.isOnHypixel() || CORE.map() == null) return false;
        return !cfg.heightLimitInGameOnly || HypixelContext.isInActiveBedwarsGame();
    }

    // ----- packet plumbing -----

    private static void sendRegister() {
        Minecraft mc = Minecraft.getMinecraft();
        NetHandlerPlayClient net = mc == null ? null : mc.getNetHandler();
        if (net == null || !HypixelContext.isOnHypixel()) return;
        if (!net.getNetworkManager().isChannelOpen()) return;
        PacketBuffer buf = new PacketBuffer(Unpooled.wrappedBuffer(HypixelLocationCodec.registerPayload()));
        net.addToSendQueue(new C17PacketCustomPayload(HypixelLocationCodec.REGISTER_CHANNEL, buf));
        DiagLog.log("heightlimit: sent " + HypixelLocationCodec.REGISTER_CHANNEL);
    }

    private static void onPayload(String channel, byte[] data) {
        if (HypixelLocationCodec.HELLO_CHANNEL.equals(channel)) {
            sendRegister();
        } else if (HypixelLocationCodec.LOCATION_CHANNEL.equals(channel)) {
            HypixelLocationCodec.Location loc = HypixelLocationCodec.decodeLocation(data);
            if (loc == null) return;
            Minecraft mc = Minecraft.getMinecraft();
            CORE.onLocation(mc == null ? null : mc.theWorld, loc.mode, loc.map);
            DiagLog.log("heightlimit: " + loc + " -> " + CORE.describe());
        }
    }

    private static void onPlacement(C08PacketPlayerBlockPlacement packet) {
        if (packet.getPlacedBlockDirection() == 255) return; // "use item" in the air
        ItemStack stack = packet.getStack();
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) return;
        BlockPos pos = packet.getPosition();
        if (pos == null || pos.getY() < 0) return;
        EnumFacing face = EnumFacing.getFront(packet.getPlacedBlockDirection());
        BlockPos target = pos.offset(face);
        CORE.onPlaceAttempt(target.getX(), target.getY(), target.getZ(), System.currentTimeMillis());
    }

    private static byte[] copy(ByteBuf buf) {
        if (buf == null) return new byte[0];
        ByteBuf view = buf.duplicate(); // own reader index; the vanilla handler still sees the whole payload
        byte[] out = new byte[view.readableBytes()];
        view.readBytes(out);
        return out;
    }
}
