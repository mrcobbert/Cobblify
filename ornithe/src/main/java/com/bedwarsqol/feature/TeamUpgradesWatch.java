package com.bedwarsqol.feature;

import com.bedwarsqol.mixin.NetworkManagerAccessor;
import com.bedwarsqol.ornithe.ChatReceivedEvent;
import com.bedwarsqol.ornithe.ServerConnectEvent;
import com.bedwarsqol.stats.HypixelContext;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Ornithe adapter for {@link TeamUpgradeLifecycle}: reads client state and feeds the Phase 1 rules,
 * holding no rules of its own (PLAN "TeamUpgradesWatch").
 *
 * <ul>
 *   <li>Observations, always: every world load, the queue / active-game flags each tick, every Mod API
 *       {@code hyevent:location} payload (a read-only netty tap of its own, ahead of the vanilla packet
 *       handler), and every title and subtitle ({@code GuiIngameMixin.displayTitle}).</li>
 *   <li>Ingestion, which the lifecycle applies only inside an active game: Hypixel's purchase /
 *       set-off / removal chat lines, the {@code Upgrades & Traps} window while it is open (read every
 *       few ticks, applied once per window and again only when its contents change), and once a second
 *       the player's armour enchantments and Haste / Regeneration effects (never swords).</li>
 * </ul>
 *
 * <p>This class never sends anything on any channel. The location payload only arrives because
 * {@link HeightLimitWatch} registers for it after Hypixel's hello.
 */
public final class TeamUpgradesWatch {

    private static final String HANDLER_NAME = "cobblify_team_upgrades";
    private static final int SHOP_INTERVAL = 5;
    private static final int GEAR_INTERVAL = 20;
    private static final int NBT_STRING = 8;

    private static final TeamUpgradeLifecycle LIFECYCLE = new TeamUpgradeLifecycle();

    private int shopTicks;
    private int gearTicks;
    private String lastShopSummary;

    /** The live lifecycle; read on the client thread by the HUD. */
    public static TeamUpgradeLifecycle lifecycle() {
        return LIFECYCLE;
    }

    // ----- observations -----

    public void onServerConnect(ServerConnectEvent event) {
        if (event == null || event.manager == null) return;
        try {
            io.netty.channel.Channel channel = ((NetworkManagerAccessor) event.manager).bedwarsqol$getChannel();
            if (channel == null) return;
            if (channel.pipeline().get(HANDLER_NAME) != null) return;
            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new LocationTap());
        } catch (RuntimeException e) {
            DiagLog.log("teamupgrades: pipeline hook failed " + e);
        }
    }

    public void onWorldLoad() {
        LIFECYCLE.worldLoad(System.currentTimeMillis());
        lastShopSummary = null;
    }

    /** Every title and subtitle shown, from {@code GuiIngameMixin.displayTitle}; client thread. */
    public static void onTitle(String text) {
        if (text == null) return;
        long now = System.currentTimeMillis();
        LIFECYCLE.title(text, now);
        TeamUpgradeChat.Event e = TeamUpgradeChat.classifyTitle(text);
        if (e != null) DiagLog.log("teamupgrades: title " + e + " ingest=" + LIFECYCLE.ingestionAllowed());
        else if (TeamUpgradeLifecycle.isGameEndTitle(text)) DiagLog.log("teamupgrades: game end title");
        LIFECYCLE.chat(e, now);
    }

    public void onChat(ChatReceivedEvent event) {
        if (event == null || event.message == null || event.type == 2) return;
        String plain = event.message.getUnformattedText();
        String sender = ChatSender.typedChatName(event.message);
        TeamUpgradeChat.Event e = TeamUpgradeChat.classify(plain, event.message.getFormattedText(), sender);
        if (e != null) {
            DiagLog.log("teamupgrades: chat " + e + " ingest=" + LIFECYCLE.ingestionAllowed());
            LIFECYCLE.chat(e, System.currentTimeMillis());
            return;
        }
        if (sender != null || !LIFECYCLE.ingestionAllowed()) return; // a player's own words, or no game: never logged
        String text = plain == null ? "" : TeamUpgradeCatalog.strip(plain);
        if (text.contains(": ")) return; // chat-shaped even without a recognised sender
        if (text.contains(" purchased ") || text.contains("set off") || text.contains("from the queue")) {
            DiagLog.log("teamupgrades: unmatched \"" + clip(text) + "\"");
        }
    }

    public void onClientTick() {
        long now = System.currentTimeMillis();
        LIFECYCLE.tick(HypixelContext.isInBedwarsQueue(), HypixelContext.isInActiveBedwarsGame(), now);

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc == null ? null : mc.thePlayer;
        if (player == null) return;

        ContainerChest shop = upgradesWindow(mc.currentScreen);
        if (shop == null) {
            shopTicks = 0;
            lastShopSummary = null;
            LIFECYCLE.shopClosed();
        } else if (++shopTicks >= SHOP_INTERVAL) {
            shopTicks = 0;
            readShop(shop, now);
        }

        if (++gearTicks >= GEAR_INTERVAL) {
            gearTicks = 0;
            readGear(player, now);
        }
    }

    // ----- shop window -----

    /** The open chest window titled {@code Upgrades & Traps}, else null. */
    private static ContainerChest upgradesWindow(GuiScreen screen) {
        if (!(screen instanceof GuiChest)) return null;
        Container container = ((GuiChest) screen).inventorySlots;
        if (!(container instanceof ContainerChest)) return null;
        IInventory lower = ((ContainerChest) container).getLowerChestInventory();
        if (lower == null) return null;
        String title = title(lower);
        if (title == null || !TeamUpgradeShop.TITLE.equals(TeamUpgradeCatalog.strip(title).trim())) return null;
        return (ContainerChest) container;
    }

    /** Reads the window; the lifecycle applies it only for a new window or changed contents. */
    private void readShop(ContainerChest window, long now) {
        IInventory inv = window.getLowerChestInventory();
        List<TeamUpgradeShop.Slot> slots = new ArrayList<TeamUpgradeShop.Slot>();
        int size = inv.getSizeInventory();
        for (int i = 0; i < size; i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack == null) continue;
            slots.add(new TeamUpgradeShop.Slot(stack.getDisplayName(), lore(stack), stack.stackSize));
        }
        TeamUpgradeShop.Snapshot snapshot = TeamUpgradeShop.parse(title(inv), slots);
        String summary = snapshot == null
                ? "no snapshot (" + slots.size() + " items)"
                : "tiers=" + snapshot.tiers + " traps=" + snapshot.traps;
        if (!summary.equals(lastShopSummary)) {
            lastShopSummary = summary;
            DiagLog.log("teamupgrades: shop " + summary + " ingest=" + LIFECYCLE.ingestionAllowed());
        }
        LIFECYCLE.shopWindow(window.windowId, snapshot, now);
    }

    private static String title(IInventory inv) {
        IChatComponent name = inv.getDisplayName();
        return name == null ? null : name.getUnformattedText();
    }

    /** The stack's display lore lines with their § codes, empty when it has none. */
    private static List<String> lore(ItemStack stack) {
        List<String> out = new ArrayList<String>();
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey("display", 10)) return out;
        NBTTagCompound display = tag.getCompoundTag("display");
        if (!display.hasKey("Lore", 9)) return out;
        NBTTagList lines = display.getTagList("Lore", NBT_STRING);
        for (int i = 0; i < lines.tagCount(); i++) out.add(lines.getStringTagAt(i));
        return out;
    }

    // ----- gear -----

    /** Armour Protection, boots Feather Falling, Haste and Regeneration. Swords are never read. */
    private static void readGear(EntityPlayer player, long now) {
        ItemStack[] armour = player.inventory.armorInventory;
        int[] protection = new int[armour.length];
        for (int i = 0; i < armour.length; i++) {
            protection[i] = armour[i] == null ? 0
                    : EnchantmentHelper.getEnchantmentLevel(Enchantment.protection.effectId, armour[i]);
        }
        ItemStack boots = armour.length > 0 ? armour[0] : null;
        int featherFalling = boots == null ? 0
                : EnchantmentHelper.getEnchantmentLevel(Enchantment.featherFalling.effectId, boots);
        PotionEffect haste = player.getActivePotionEffect(Potion.digSpeed);
        PotionEffect regen = player.getActivePotionEffect(Potion.regeneration);
        LIFECYCLE.gear(protection, featherFalling,
                haste == null ? -1 : haste.getAmplifier(), haste == null ? 0 : haste.getDuration(),
                regen == null ? 0 : regen.getDuration(), now);
    }

    // ----- location tap -----

    private static void onLocation(byte[] data) {
        HypixelLocationCodec.Location loc = HypixelLocationCodec.decodeLocation(data);
        if (loc == null) return;
        LIFECYCLE.location(loc.serverName, loc.mode, loc.map, loc.lobbyName, System.currentTimeMillis());
        DiagLog.log("teamupgrades: " + loc + " pending=" + LIFECYCLE.pending() + " mode=" + LIFECYCLE.mode());
    }

    private static String clip(String s) {
        return s.length() <= 160 ? s : s.substring(0, 160) + "...";
    }

    /** Reads (never consumes, never releases) {@code hyevent:location} payloads; passes every message on. */
    private static final class LocationTap extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof S3FPacketCustomPayload) {
                try {
                    S3FPacketCustomPayload p = (S3FPacketCustomPayload) msg;
                    if (HypixelLocationCodec.LOCATION_CHANNEL.equals(p.getChannelName())) {
                        final byte[] data = copy(p.getBufferData());
                        Minecraft.getMinecraft().addScheduledTask(() -> onLocation(data));
                    }
                } catch (RuntimeException e) {
                    DiagLog.log("teamupgrades: payload read failed " + e);
                }
            }
            super.channelRead(ctx, msg);
        }

        private static byte[] copy(ByteBuf buf) {
            if (buf == null) return new byte[0];
            ByteBuf view = buf.duplicate(); // own reader index; the other tap and vanilla still see it all
            byte[] out = new byte[view.readableBytes()];
            view.readBytes(out);
            return out;
        }
    }
}
