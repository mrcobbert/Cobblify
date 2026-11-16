package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Item Physics (Ornithe build): the module switch and the world lookups behind
 * {@link ItemPhysicsRules#surfaceShift}. Render thread only; reads the world, changes nothing in it.
 */
public final class ItemPhysics {

    /** How far above and below the item's Y the "inside" and "support" blocks are looked up. */
    private static final double PROBE = 1e-3;

    private ItemPhysics() {
    }

    public static boolean on() {
        ClientSettings c = BedwarsQol.config;
        return c != null && c.itemPhysics;
    }

    /**
     * The rest correction for {@code item}: 0 while airborne. On the ground it compares the item's Y (its
     * collision rest) with the drawn top of the block it is in and of the block under it.
     */
    public static double surfaceShift(EntityItem item) {
        if (!item.onGround) return 0.0;
        World world = item.worldObj;
        if (world == null) return 0.0;
        double y = item.posY;
        BlockPos inside = new BlockPos(item.posX, y + PROBE, item.posZ);
        BlockPos support = new BlockPos(item.posX, y - PROBE, item.posZ);
        IBlockState insideState = world.getBlockState(inside);
        boolean insideSolid = solid(world, inside, insideState);
        double insideTop = insideSolid ? drawnTop(world, inside, insideState) : 0.0;
        boolean supportSolid;
        double supportTop;
        if (support.equals(inside)) {
            supportSolid = insideSolid;
            supportTop = insideTop;
        } else {
            IBlockState supportState = world.getBlockState(support);
            supportSolid = solid(world, support, supportState);
            supportTop = supportSolid ? drawnTop(world, support, supportState) : 0.0;
        }
        return ItemPhysicsRules.surfaceShift(y, insideSolid, insideTop, supportSolid, supportTop);
    }

    private static boolean solid(World world, BlockPos pos, IBlockState state) {
        return state.getBlock().getCollisionBoundingBox(world, pos, state) != null;
    }

    /** World Y of the block's drawn top: its bounds for this state, as vanilla sets them before a ray trace. */
    private static double drawnTop(World world, BlockPos pos, IBlockState state) {
        Block block = state.getBlock();
        block.setBlockBoundsBasedOnState(world, pos);
        return pos.getY() + block.getBlockBoundsMaxY();
    }
}
