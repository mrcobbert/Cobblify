package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.mixin.EntityLivingBaseSwingAccessor;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemTransformVec3f;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 1.7 Animations (Ornithe build): reads the module and its rows, gathers the game state the hooks need and feeds
 * {@link OldAnimRules}. Render/client thread only. A swing started here only sets the local player's two swing
 * fields; nothing here sends anything or changes mining, item use or attacks.
 */
public final class OldAnimations {

    private static final OldAnimRules.SneakState SNEAK = new OldAnimRules.SneakState();
    private static EntityPlayerSP sneakPlayer;

    /** Ops per baked model, by flag combination; models live until a resource reload. */
    private static final Map<IBakedModel, float[][]> FIRST_OPS = new IdentityHashMap<>();
    private static final Map<IBakedModel, float[][]> THIRD_OPS = new IdentityHashMap<>();
    private static final int OPS_CACHE_LIMIT = 512;

    private OldAnimations() {
    }

    private static boolean row(boolean row) {
        ClientSettings c = BedwarsQol.config;
        return c != null && OldAnimRules.effective(c.oldAnimations, row);
    }

    public static boolean blockHit() {
        ClientSettings c = BedwarsQol.config;
        return c != null && row(c.oaBlockHit);
    }

    public static boolean itemPosition() {
        ClientSettings c = BedwarsQol.config;
        return c != null && row(c.oaItemPosition);
    }

    public static boolean smoothSneak() {
        ClientSettings c = BedwarsQol.config;
        return c != null && row(c.oaSmoothSneak);
    }

    public static boolean hearts() {
        ClientSettings c = BedwarsQol.config;
        return c != null && row(c.oaHearts);
    }

    public static boolean redArmor() {
        ClientSettings c = BedwarsQol.config;
        return c != null && row(c.oaRedArmor);
    }

    // ---- Block Hit -----------------------------------------------------------------------------------

    /** Feature 25, start: called at the head of {@code sendClickBlockToController}. */
    public static void blockHitSwing(Minecraft mc, boolean attackHeld, int leftClickCounter) {
        if (!attackHeld || !blockHit()) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || !player.isUsingItem()) return;
        boolean onBlock = aimingAtBlock(mc);
        if (OldAnimRules.blockHitSwing(true, true, leftClickCounter, true, onBlock, restartable(player))) {
            startLocalSwing(player);
        }
    }

    /** Feature 47: called at the head of {@code clickMouse}, which returns right after while the miss delay runs. */
    public static void missSwing(Minecraft mc, int leftClickCounter) {
        if (!blockHit()) return;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return;
        if (OldAnimRules.missSwing(true, leftClickCounter, restartable(player))) startLocalSwing(player);
    }

    /** Feature 25, show: the swing progress for a using branch of {@code renderItemInFirstPerson}. */
    public static float usingSwing(float vanilla, float partialTicks) {
        if (!blockHit()) return vanilla;
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return vanilla;
        return OldAnimRules.usingSwingProgress(vanilla, player.getSwingProgress(partialTicks), true);
    }

    private static boolean aimingAtBlock(Minecraft mc) {
        MovingObjectPosition hit = mc.objectMouseOver;
        WorldClient world = mc.theWorld;
        if (hit == null || world == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return false;
        return world.getBlockState(hit.getBlockPos()).getBlock().getMaterial() != Material.air;
    }

    private static boolean restartable(EntityLivingBase entity) {
        int end = ((EntityLivingBaseSwingAccessor) entity).bedwarsqol$swingEnd();
        return OldAnimRules.swingRestartable(entity.isSwingInProgress, entity.swingProgressInt, end);
    }

    /** The local swing: the two fields vanilla's own swing start sets, and nothing else. */
    private static void startLocalSwing(EntityLivingBase entity) {
        EntityLivingBaseSwingAccessor swing = (EntityLivingBaseSwingAccessor) entity;
        swing.bedwarsqol$setSwingProgressInt(OldAnimRules.SWING_START);
        swing.bedwarsqol$setSwingInProgress(true);
    }

    // ---- Item Position (and Block Hit's held-item adjust) ---------------------------------------------------

    /**
     * Feature 21 for the idle/swing pose: called at the end of {@code transformFirstPersonItem}, before Hand
     * Position's scale. The using poses are handled at the draw call instead, after their own transforms.
     */
    public static void firstPersonIdle(ItemStack stack) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null || player.getItemInUseCount() > 0) return;
        firstPerson(stack);
    }

    /** Feature 21 for the eat/drink, block and bow poses: called just before the first-person draw. */
    public static void firstPersonUsing(ItemStack stack) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null || player.getItemInUseCount() <= 0) return;
        firstPerson(stack);
    }

    private static void firstPerson(ItemStack stack) {
        if (stack == null || !itemPosition()) return;
        Item item = stack.getItem();
        IBakedModel model = model(stack);
        if (item == null || model == null) return;
        boolean gui3d = model.isGui3d();
        boolean around = item.shouldRotateAroundWhenRendering();
        int flags = (gui3d ? 1 : 0) | (around ? 2 : 0);
        float[][] cached = cache(FIRST_OPS, model, 4);
        float[] ops = cached[flags];
        if (ops == null) {
            ops = OldAnimRules.firstPersonItemOps(model.isBuiltInRenderer(), gui3d, around,
                    transform(model, true));
            cached[flags] = ops;
        }
        apply(ops);
    }

    /** Features 22 and 24: called just before {@code LayerHeldItem} draws the held item. */
    public static void thirdPerson(EntityLivingBase entity, ItemStack stack) {
        if (stack == null || !(entity instanceof EntityPlayer)) return;
        boolean position = itemPosition();
        boolean hit = blockHit();
        if (!position && !hit) return;
        boolean blocking = hit && ((EntityPlayer) entity).getItemInUseCount() > 0
                && stack.getItemUseAction() == EnumAction.BLOCK;
        if (!position && !blocking) return;
        Item item = stack.getItem();
        IBakedModel model = model(stack);
        if (item == null || model == null) return;
        int kind = OldAnimRules.thirdPersonKind(model.isGui3d(), item == Items.bow, item.isFull3D());
        boolean around = item.shouldRotateAroundWhenRendering();
        int flags = kind | (around ? 4 : 0) | (blocking ? 8 : 0) | (position ? 16 : 0);
        float[][] cached = cache(THIRD_OPS, model, 32);
        float[] ops = cached[flags];
        if (ops == null) {
            ops = OldAnimRules.thirdPersonItemOps(position, model.isBuiltInRenderer(), kind, around, blocking,
                    transform(model, false));
            cached[flags] = ops;
        }
        apply(ops);
    }

    /** Feature 27: whether the item stays up although the selected stack is a different, unequal object. */
    public static boolean keepEquipped(ItemStack held, ItemStack current, boolean sameSlot) {
        if (held == null || current == null || !itemPosition()) return false;
        Item item = held.getItem();
        boolean sameItem = item != null && item == current.getItem();
        boolean sameMeta = held.getItemDamage() == current.getItemDamage();
        boolean subtypes = item != null && item.getHasSubtypes();
        return OldAnimRules.keepEquipped(true, false, sameItem, sameMeta, subtypes, sameSlot);
    }

    private static IBakedModel model(ItemStack stack) {
        return Minecraft.getMinecraft().getRenderItem().getItemModelMesher().getItemModel(stack);
    }

    private static float[][] cache(Map<IBakedModel, float[][]> map, IBakedModel model, int slots) {
        float[][] cached = map.get(model);
        if (cached == null) {
            if (map.size() >= OPS_CACHE_LIMIT) map.clear();
            cached = new float[slots][];
            map.put(model, cached);
        }
        return cached;
    }

    /** The model's first- or third-person transform as {rotX, rotY, rotZ, transX, transY, transZ, scale XYZ}. */
    private static float[] transform(IBakedModel model, boolean firstPerson) {
        ItemCameraTransforms transforms = model.getItemCameraTransforms();
        ItemTransformVec3f t = transforms == null ? ItemTransformVec3f.DEFAULT
                : firstPerson ? transforms.firstPerson : transforms.thirdPerson;
        return new float[]{t.rotation.x, t.rotation.y, t.rotation.z, t.translation.x, t.translation.y,
                t.translation.z, t.scale.x, t.scale.y, t.scale.z};
    }

    private static void apply(float[] ops) {
        for (int i = 0; i < ops.length; i += OldAnimRules.OP_STRIDE) {
            switch ((int) ops[i]) {
                case OldAnimRules.OP_TRANSLATE:
                    GlStateManager.translate(ops[i + 1], ops[i + 2], ops[i + 3]);
                    break;
                case OldAnimRules.OP_ROTATE:
                    GlStateManager.rotate(ops[i + 1], ops[i + 2], ops[i + 3], ops[i + 4]);
                    break;
                default:
                    GlStateManager.scale(ops[i + 1], ops[i + 2], ops[i + 3]);
                    break;
            }
        }
    }

    // ---- Smooth Sneak -------------------------------------------------------------------------------

    /** Once per client tick (end of {@code runTick}): advances the local player's eased sneak drop. */
    public static void tick(Minecraft mc) {
        EntityPlayerSP player = mc.thePlayer;
        if (player != sneakPlayer) {
            SNEAK.reset();
            sneakPlayer = player;
        }
        if (player != null) SNEAK.tick(player.isSneaking());
    }

    /** Features 28 and 29: the eye height {@code orientCamera} places the camera at. Camera only. */
    public static float cameraEyeHeight(Entity viewer, float vanillaEye, float partialTicks) {
        if (!smoothSneak()) return vanillaEye;
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (viewer == null || viewer != player) return vanillaEye;
        return OldAnimRules.cameraEyeHeight(vanillaEye, player.isSneaking(), player.isPlayerSleeping(),
                SNEAK.offset(partialTicks), true);
    }

    /** Feature 28: whether a vanilla sneak translate of the model, a clothing layer, the worn head or held item runs. */
    public static boolean vanillaSneakTranslate() {
        return OldAnimRules.vanillaSneakTranslate(smoothSneak());
    }

    /** Feature 28: the Y the local player's model is drawn at. */
    public static double playerModelY(Entity entity, double y, float partialTicks) {
        if (!smoothSneak()) return y;
        boolean local = entity instanceof EntityPlayerSP;
        boolean guiPreview = !Minecraft.getMinecraft().getRenderManager().isRenderShadow();
        return OldAnimRules.localSneakY(y, true, local, guiPreview, local ? SNEAK.offset(partialTicks) : 0f);
    }

    // ---- Hearts --------------------------------------------------------------------------------------

    /** Feature 39: whether a health rise (from {@code healthBefore}) starts the heart blink. */
    public static boolean heartsBlinkOnRise(int healthBefore) {
        if (!hearts()) return true;
        Entity viewer = Minecraft.getMinecraft().getRenderViewEntity();
        if (!(viewer instanceof EntityPlayer)) return true;
        int now = MathHelper.ceiling_float_int(((EntityPlayer) viewer).getHealth());
        return OldAnimRules.heartsBlink(now, healthBefore, true);
    }
}
