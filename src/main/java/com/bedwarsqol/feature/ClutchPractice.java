package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Clutch Practice: drill the Bedwars pearl + fireball void clutch in singleplayer. Everything runs
 * on the integrated server thread and only for the world's owner, so it can never act on a
 * multiplayer server (there is no integrated server there).
 *
 * <ul>
 *   <li>Right-clicking a fire charge throws a {@link ClutchFireball} (no vanilla ignite), 0.5 s
 *       cooldown, consumed outside creative.</li>
 *   <li>Its blast pushes and lightly hurts nearby entities and never sets fire. With Block Damage on
 *       it breaks what it reaches by blast resistance, with no drops; glass stops it and
 *       {@link ClutchRules#breaks end stone, obsidian, beds} and containers never break.</li>
 *   <li>Pearls, fire charges and wool stay topped up. Damage is vanilla: falls, pearls and blasts
 *       can kill. Falling {@link ClutchRules#shouldVoidReset into the void}, with nothing solid
 *       beneath you, puts you back on your last solid ground at full health.</li>
 * </ul>
 */
public class ClutchPractice {

    private static final int VISUAL_RANGE_SQ = 64 * 64;

    private long lastThrowTick = -1;
    private EntityPlayerMP safeFor;
    private int safeDim;
    private double safeX, safeY, safeZ;

    /** True only for the owner of a running singleplayer world with Clutch Practice on. */
    private static boolean active(EntityPlayer player) {
        ClientSettings c = BedwarsQol.config;
        if (c == null || !c.clutchPractice) return false;
        if (!(player instanceof EntityPlayerMP) || player.worldObj.isRemote) return false;
        MinecraftServer server = MinecraftServer.getServer();
        return server instanceof IntegratedServer && player.getName().equals(server.getServerOwner());
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent event) {
        if (event.world.isRemote || event.action == PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) return;
        EntityPlayer player = event.entityPlayer;
        ItemStack held = player.getHeldItem();
        if (held == null || held.getItem() != Items.fire_charge || !active(player)) return;
        // Never the vanilla fire-charge ignite, even while cooling down.
        event.useItem = Event.Result.DENY;
        event.useBlock = Event.Result.DENY;
        event.setCanceled(true);

        long now = event.world.getTotalWorldTime();
        if (!ClutchRules.cooldownReady(now, lastThrowTick)) return;
        lastThrowTick = now;
        event.world.spawnEntityInWorld(new ClutchFireball(event.world, player));
        event.world.playAuxSFXAtEntity(null, 1008, new BlockPos(player), 0);
        if (!player.capabilities.isCreativeMode && --held.stackSize <= 0) {
            player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
        }
    }

    /**
     * Blast at the fireball, the way Hypixel delivers it: a flat heart of damage, then the knockback
     * replaces the victim's motion and reaches the client as exactly one S12 (set). The S27 carries
     * no player motion, so the client never adds a second push on top. Blocks are our own vanilla
     * ray-cast ({@link ClutchRules#blastBlocks}), not a vanilla {@code Explosion}, so no Forge event
     * or vanilla entity damage is involved.
     */
    static void explode(ClutchFireball fireball) {
        World world = fireball.worldObj;
        ClientSettings c = BedwarsQol.config;
        double x = fireball.posX, y = fireball.posY, z = fireball.posZ;
        List<BlockPos> broken = c != null && c.clutchBlockDamage
                ? breakBlocks(world, fireball, x, y, z) : Collections.<BlockPos>emptyList();
        // What vanilla's doExplosionB plays server side; the client adds its own from the S27.
        world.playSoundEffect(x, y, z, "random.explode", 4.0F,
                (1.0F + (world.rand.nextFloat() - world.rand.nextFloat()) * 0.2F) * 0.7F);

        double r = ClutchRules.BLAST_RADIUS;
        List<Entity> near = world.getEntitiesWithinAABBExcludingEntity(fireball,
                new AxisAlignedBB(x - r, y - r, z - r, x + r, y + r, z + r));
        for (Entity entity : near) {
            if (!(entity instanceof EntityLivingBase)) continue;
            double dx = entity.posX - x, dy = entity.posY - y, dz = entity.posZ - z;
            double[] push = ClutchRules.knockback(dx, dy, dz);
            if (push == null) continue;
            // Damage first: its own velocity bookkeeping must not outlive the push set below. A plain
            // explosion source, not difficulty scaled: Hypixel takes a flat heart.
            entity.attackEntityFrom(new DamageSource("explosion").setExplosion(),
                    ClutchRules.damage(Math.sqrt(dx * dx + dy * dy + dz * dz)));
            entity.motionX = push[0];
            entity.motionY = push[1];
            entity.motionZ = push[2];
            if (entity instanceof EntityPlayerMP) {
                entity.velocityChanged = false;
                ((EntityPlayerMP) entity).playerNetServerHandler.sendPacket(new S12PacketEntityVelocity(entity));
            } else {
                entity.velocityChanged = true;
            }
        }

        // Blast position and strength, the broken blocks for the client to clear and puff, no player motion.
        Vec3 none = new Vec3(0, 0, 0);
        for (EntityPlayer player : world.playerEntities) {
            if (!(player instanceof EntityPlayerMP) || player.getDistanceSq(x, y, z) > VISUAL_RANGE_SQ) continue;
            ((EntityPlayerMP) player).playerNetServerHandler.sendPacket(
                    new S27PacketExplosion(x, y, z, ClutchRules.EXPLOSION_POWER, broken, none));
        }
    }

    /**
     * Breaks every block the blast reaches, with no drops: wool, dirt and leaves easily, wood and clay
     * sometimes, stone rarely. Glass stops the rays; protected blocks and containers never break.
     */
    private static List<BlockPos> breakBlocks(final World world, final ClutchFireball fireball, double x, double y, double z) {
        final Explosion explosion = new Explosion(world, fireball, x, y, z, ClutchRules.EXPLOSION_POWER, false, true);
        List<int[]> reached = ClutchRules.blastBlocks(x, y, z, ClutchRules.EXPLOSION_POWER, world.rand,
                (bx, by, bz) -> {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    Block block = world.getBlockState(pos).getBlock();
                    if (block.getMaterial() == Material.air) return -1f;
                    if (ClutchRules.shieldsBlast(name(block))) return ClutchRules.SHIELD_RESISTANCE;
                    return block.getExplosionResistance(world, pos, fireball, explosion);
                });
        List<BlockPos> broken = new ArrayList<BlockPos>();
        for (int[] p : reached) {
            BlockPos pos = new BlockPos(p[0], p[1], p[2]);
            IBlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            if (block.getMaterial() == Material.air || block.hasTileEntity(state) || !ClutchRules.breaks(name(block))) continue;
            block.onBlockExploded(world, pos, explosion);
            broken.add(pos);
        }
        return broken;
    }

    private static String name(Block block) {
        Object id = Block.blockRegistry.getNameForObject(block);
        return id == null ? null : id.toString();
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != Side.SERVER || !active(event.player)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        refill(player);

        if (safeFor != player || safeDim != player.dimension) {
            safeFor = null;
        }
        if (player.onGround && standsOnSolid(player)) {
            safeFor = player;
            safeDim = player.dimension;
            safeX = player.posX;
            safeY = player.posY;
            safeZ = player.posZ;
        } else if (ClutchRules.shouldVoidReset(player.posY, safeFor != null, safeY) && !groundBelow(player)) {
            player.motionX = player.motionY = player.motionZ = 0;
            player.fallDistance = 0;
            player.setPositionAndUpdate(safeX, safeY, safeZ);
            player.playerNetServerHandler.sendPacket(new S12PacketEntityVelocity(player.getEntityId(), 0, 0, 0));
            player.setHealth(player.getMaxHealth());
        }
    }

    /** True when any block lies under the player's column: they will land, not fall into the void. */
    private static boolean groundBelow(EntityPlayer player) {
        World world = player.worldObj;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x = MathHelper.floor_double(player.posX), z = MathHelper.floor_double(player.posZ);
        for (int y = MathHelper.floor_double(player.posY); y >= 0; y--) {
            pos.set(x, y, z);
            if (!world.isAirBlock(pos)) return true;
        }
        return false;
    }

    private static boolean standsOnSolid(EntityPlayer player) {
        BlockPos below = new BlockPos(player.posX, player.posY - 0.5, player.posZ);
        return player.worldObj.getBlockState(below).getBlock().getMaterial().isSolid();
    }

    private static void refill(EntityPlayerMP player) {
        topUp(player, Items.ender_pearl);
        topUp(player, Items.fire_charge);
        topUp(player, Item.getItemFromBlock(Blocks.wool));
    }

    /** Fills every stack of {@code item} to its max, or adds a full stack when there is none. */
    private static void topUp(EntityPlayerMP player, Item item) {
        ItemStack[] inv = player.inventory.mainInventory;
        boolean found = false;
        for (ItemStack stack : inv) {
            if (stack == null || stack.getItem() != item) continue;
            found = true;
            if (stack.stackSize < stack.getMaxStackSize()) stack.stackSize = stack.getMaxStackSize();
        }
        if (!found) {
            ItemStack stack = new ItemStack(item);
            stack.stackSize = stack.getMaxStackSize();
            player.inventory.addItemStackToInventory(stack);
        }
    }
}
