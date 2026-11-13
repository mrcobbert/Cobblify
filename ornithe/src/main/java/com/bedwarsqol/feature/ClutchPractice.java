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
import net.minecraft.util.Vec3;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;

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
 *       on breaks only {@link ClutchRules#breaks wool, planks and clay}.</li>
 *   <li>Pearls, fire charges and wool stay topped up, damage never kills, and falling
 *       {@link ClutchRules#shouldVoidReset into the void} puts you back on your last solid ground.</li>
 * </ul>
 *
 * <p>Ornithe has no Forge event bus, so four server-side mixins call the hooks below at the vanilla
 * positions Forge fired its events from: {@code ItemInWorldManagerMixin} (use item / use item on a
 * block), {@code ExplosionMixin} (the entity query in {@code doExplosionA}, where Forge fired
 * {@code ExplosionEvent.Detonate}), and {@code EntityPlayerMixin} (end of {@code onUpdate}, and the
 * damage argument of {@code damageEntity}). They do nothing until {@link #enable()}.
 */
public class ClutchPractice {

    private static final int VISUAL_RANGE_SQ = 64 * 64;

    /** The explosion currently being detonated by {@link #explode}; its event is ours to filter. */
    private static Explosion detonating;

    private static volatile ClutchPractice instance;

    private long lastThrowTick = -1;
    private EntityPlayerMP safeFor;
    private int safeDim;
    private double safeX, safeY, safeZ;

    /** Creates the instance the mixin hooks use (once, at client start). */
    public static void enable() {
        if (instance == null) instance = new ClutchPractice();
    }

    /** The enabled instance, or null before {@link #enable()}. */
    public static ClutchPractice get() {
        return instance;
    }

    /** Whether Clutch Practice acts on {@code player} right now (see {@link #active}). */
    public static boolean appliesTo(EntityPlayer player) {
        return player != null && active(player);
    }

    /** True only for the owner of a running singleplayer world with Clutch Practice on. */
    private static boolean active(EntityPlayer player) {
        ClientSettings c = BedwarsQol.config;
        if (c == null || !c.clutchPractice) return false;
        if (!(player instanceof EntityPlayerMP) || player.worldObj.isRemote) return false;
        MinecraftServer server = MinecraftServer.getServer();
        return server instanceof IntegratedServer && player.getName().equals(server.getServerOwner());
    }

    /**
     * A server-side right click (in the air or on a block). Returns true when the vanilla use must be
     * denied: the held item is a fire charge and Clutch Practice is active, so it throws a
     * {@link ClutchFireball} instead (Forge: {@code PlayerInteractEvent} canceled, use item and use
     * block DENY).
     */
    public boolean onInteract(World world, EntityPlayer player) {
        if (world == null || player == null || world.isRemote) return false;
        ItemStack held = player.getHeldItem();
        if (held == null || held.getItem() != Items.fire_charge || !active(player)) return false;
        // Never the vanilla fire-charge ignite, even while cooling down.

        long now = world.getTotalWorldTime();
        if (!ClutchRules.cooldownReady(now, lastThrowTick)) return true;
        lastThrowTick = now;
        world.spawnEntityInWorld(new ClutchFireball(world, player));
        world.playAuxSFXAtEntity(null, 1008, new BlockPos(player), 0);
        if (!player.capabilities.isCreativeMode && --held.stackSize <= 0) {
            player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
        }
        return true;
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
            // Anonymous subclass: vanilla's DamageSource(String) is protected (Forge's access transformer opened it).
            entity.attackEntityFrom(new DamageSource("explosion") { }.setExplosion(),
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
                    new S27PacketExplosion(x, y, z, ClutchRules.EXPLOSION_STRENGTH, blocks, none));
        }
    }

    /**
     * Our blast handles entities itself; blocks are kept only when whitelisted and Block Damage is on.
     * Called with the entity list {@code doExplosionA} just queried, after it filled the affected
     * blocks and before it damages and pushes entities (where Forge fired
     * {@code ExplosionEvent.Detonate}). Any other explosion passes through untouched.
     */
    public static void onDetonate(Explosion explosion, World world, List<Entity> affectedEntities) {
        if (explosion != detonating || detonating == null) return;
        affectedEntities.clear();
        ClientSettings c = BedwarsQol.config;
        if (c == null || !c.clutchBlockDamage) {
            explosion.getAffectedBlockPositions().clear();
            return;
        }
        Iterator<BlockPos> it = explosion.getAffectedBlockPositions().iterator();
        while (it.hasNext()) {
            Block block = world.getBlockState(it.next()).getBlock();
            Object id = Block.blockRegistry.getNameForObject(block);
            if (!ClutchRules.breaks(id == null ? null : id.toString())) it.remove();
        }
    }

    /** End of a server-side {@code EntityPlayer.onUpdate} (Forge's {@code PlayerTickEvent} END, SERVER side). */
    public void onPlayerTick(EntityPlayer tickingPlayer) {
        if (tickingPlayer == null || tickingPlayer.worldObj.isRemote || !active(tickingPlayer)) return;
        EntityPlayerMP player = (EntityPlayerMP) tickingPlayer;
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
        } else if (ClutchRules.shouldVoidReset(player.posY, safeFor != null, safeY)) {
            player.motionX = player.motionY = player.motionZ = 0;
            player.fallDistance = 0;
            player.setPositionAndUpdate(safeX, safeY, safeZ);
            player.playerNetServerHandler.sendPacket(new S12PacketEntityVelocity(player.getEntityId(), 0, 0, 0));
            player.setHealth(player.getMaxHealth());
        }
    }

    /**
     * Practice damage never kills: the hit is capped to leave half a heart. Returns the damage
     * {@code EntityPlayer.damageEntity} goes on with (Forge's {@code LivingHurtEvent} amount).
     */
    public float onHurt(EntityPlayer player, float amount) {
        if (player == null || !active(player)) return amount;
        float health = player.getHealth();
        if (amount >= health) return Math.max(0f, health - 1f);
        return amount;
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
