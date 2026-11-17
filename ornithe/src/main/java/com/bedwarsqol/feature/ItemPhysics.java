package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemTransformVec3f;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Item Physics (Ornithe build): the module switch and the world lookups behind
 * {@link ItemPhysicsRules#surfaceShift}. Render thread only; reads the world, changes nothing in it.
 */
public final class ItemPhysics {

    /** How far above and below the item's Y the "inside" and "support" blocks are looked up. */
    private static final double PROBE = 1e-3;

    /** Rest lift per baked model; models live until a resource reload. */
    private static final Map<IBakedModel, Float> LIFTS = new IdentityHashMap<>();
    private static final int LIFT_CACHE_LIMIT = 512;

    private ItemPhysics() {
    }

    /** {@link ItemPhysicsRules#restLift} for this model's own geometry and GROUND transform, measured once. */
    public static float restLift(IBakedModel model) {
        Float cached = LIFTS.get(model);
        if (cached != null) return cached;
        if (LIFTS.size() >= LIFT_CACHE_LIMIT) LIFTS.clear();
        float lift = ItemPhysicsRules.restLift(vertices(model), ground(model), model.isGui3d());
        LIFTS.put(model, lift);
        return lift;
    }

    /** Every quad corner of the model as x, y, z triples; empty for a built-in renderer. */
    private static float[] vertices(IBakedModel model) {
        if (model.isBuiltInRenderer()) return new float[0];
        int count = corners(model.getGeneralQuads());
        for (EnumFacing face : EnumFacing.values()) count += corners(model.getFaceQuads(face));
        float[] out = new float[count * 3];
        int at = copy(model.getGeneralQuads(), out, 0);
        for (EnumFacing face : EnumFacing.values()) at = copy(model.getFaceQuads(face), out, at);
        return out;
    }

    private static int corners(List<BakedQuad> quads) {
        return quads == null ? 0 : quads.size() * 4;
    }

    private static int copy(List<BakedQuad> quads, float[] out, int at) {
        if (quads == null) return at;
        for (BakedQuad quad : quads) {
            int[] data = quad.getVertexData();
            int stride = data.length / 4;
            for (int v = 0; v < 4; v++) {
                if (stride >= 3) {
                    out[at] = Float.intBitsToFloat(data[v * stride]);
                    out[at + 1] = Float.intBitsToFloat(data[v * stride + 1]);
                    out[at + 2] = Float.intBitsToFloat(data[v * stride + 2]);
                } else {
                    out[at] = out[at + 1] = out[at + 2] = 0.5f;
                }
                at += 3;
            }
        }
        return at;
    }

    private static float[] ground(IBakedModel model) {
        ItemCameraTransforms transforms = model.getItemCameraTransforms();
        ItemTransformVec3f t = transforms == null ? ItemTransformVec3f.DEFAULT : transforms.ground;
        return new float[]{t.rotation.x, t.rotation.y, t.rotation.z, t.translation.x, t.translation.y,
                t.translation.z, t.scale.x, t.scale.y, t.scale.z};
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
