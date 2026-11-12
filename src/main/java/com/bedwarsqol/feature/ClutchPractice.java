package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.block.Block;
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
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Clutch Practice: drill the Bedwars pearl + fireball void clutch in singleplayer. Everything runs
 * on the integrated server thread and only for the world's owner, so it can never act on a
 * multiplayer server (there is no integrated server there).
 *
 * <ul>
 *   <li>Right-clicking a fire charge throws a {@link ClutchFireball} (no vanilla ignite), 0.5 s
 *       cooldown, consumed outside creative.</li>
 *   <li>Its blast pushes and lightly hurts nearby entities, never sets fire, and with Block Damage
 *       on breaks only {@link ClutchRules#breaks wool, planks, clay and ladders}; glass stops it.</li>
 *   <li>Pearls, fire charges and wool stay topped up. Damage is vanilla: falls, pearls and blasts
 *       can kill. Falling {@link ClutchRules#shouldVoidReset into the void}, with nothing solid
 *       beneath you, puts you back on your last solid ground at full health.</li>
 * </ul>
 */
public class ClutchPractice {

    private static final int VISUAL_RANGE_SQ = 64 * 64;

    /** The explosion currently being detonated by {@link #explode}; its event is ours to filter. */
    private static Explosion detonating;

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
     * no player motion, so the client never adds a second push on top.
     */
    static void explode(ClutchFireball fireball) {
        World world = fireball.worldObj;
        ClientSettings c = BedwarsQol.config;
        boolean blockDamage = c != null && c.clutchBlockDamage;
        double x = fireball.posX, y = fireball.posY, z = fireball.posZ;
        Explosion explosion = new Explosion(world, fireball, x, y, z, ClutchRules.EXPLOSION_POWER, false, blockDamage);
        detonating = explosion;
        try {
            explosion.doExplosionA();
        } finally {
            detonating = null;
        }
        explosion.doExplosionB(false);

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

        // Visuals only, as on Hypixel: blast position and strength, no player motion.
        Vec3 none = new Vec3(0, 0, 0);
        List<BlockPos> blocks = blockDamage ? explosion.getAffectedBlockPositions() : Collections.<BlockPos>emptyList();
        for (EntityPlayer player : world.playerEntities) {
            if (!(player instanceof EntityPlayerMP) || player.getDistanceSq(x, y, z) > VISUAL_RANGE_SQ) continue;
            ((EntityPlayerMP) player).playerNetServerHandler.sendPacket(
                    new S27PacketExplosion(x, y, z, ClutchRules.EXPLOSION_POWER, blocks, none));
        }
    }

    /** Our blast handles entities itself; blocks are kept only when whitelisted and Block Damage is on. */
    @SubscribeEvent
    public void onDetonate(ExplosionEvent.Detonate event) {
        if (event.explosion != detonating || detonating == null) return;
        event.getAffectedEntities().clear();
        ClientSettings c = BedwarsQol.config;
        if (c == null || !c.clutchBlockDamage) {
            event.getAffectedBlocks().clear();
            return;
        }
        Iterator<BlockPos> it = event.getAffectedBlocks().iterator();
        while (it.hasNext()) {
            Block block = event.world.getBlockState(it.next()).getBlock();
            Object id = Block.blockRegistry.getNameForObject(block);
            if (!ClutchRules.breaks(id == null ? null : id.toString())) it.remove();
        }
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
