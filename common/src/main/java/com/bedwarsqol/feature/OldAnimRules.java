package com.bedwarsqol.feature;

/**
 * 1.7 Animations, a Visuals module of the Ornithe build: off by default, with each of its rows (Block Hit, Item
 * Position, Smooth Sneak, Hearts, Red Armor) on, so switching the module on gives every 1.7 animation.
 *
 * <p>Every number and decision of the module lives here; the Ornithe hooks only read the game state, ask these
 * rules, and apply the answer. Numbers marked "1.8.9" are read from the vanilla 1.8.9 bytecode or its bundled
 * item models; numbers marked "1.7" are Minecraft 1.7.10's own constants for the same draw.
 */
public final class OldAnimRules {

    public static final boolean DEFAULT_ENABLED = false;
    public static final boolean DEFAULT_BLOCK_HIT = true;
    public static final boolean DEFAULT_ITEM_POSITION = true;
    public static final boolean DEFAULT_SMOOTH_SNEAK = true;
    public static final boolean DEFAULT_HEARTS = true;
    public static final boolean DEFAULT_RED_ARMOR = true;

    private OldAnimRules() {
    }

    /** Whether a row applies: only while its module is on. */
    public static boolean effective(boolean module, boolean row) {
        return module && row;
    }

    // ---- Block Hit: local swing start (25, 47) ------------------------------------------------------------

    /** {@code swingProgressInt} right after a swing starts, as {@code EntityLivingBase.swingItem} sets it (1.8.9). */
    public static final int SWING_START = -1;

    /** The miss delay {@code Minecraft.clickMouse} sets after a missed hit, in ticks (1.8.9 {@code bipush 10}). */
    public static final int MISS_DELAY_TICKS = 10;

    /**
     * Whether a new swing may start now: {@code EntityLivingBase.swingItem}'s own guard (1.8.9) - no swing, or the
     * current one is past its first half, or it has not begun. 1.7 swung through {@code swingItem} every tick a
     * block was hit, so a held block hit re-swings at this pace.
     */
    public static boolean swingRestartable(boolean swingInProgress, int swingProgressInt, int swingEnd) {
        return !swingInProgress || swingProgressInt >= swingEnd / 2 || swingProgressInt < 0;
    }

    /**
     * Feature 25: start a local swing where vanilla 1.8 returns from {@code sendClickBlockToController} because an
     * item is in use, while attack is held on a block outside the miss delay.
     */
    public static boolean blockHitSwing(boolean effective, boolean attackHeld, int leftClickCounter, boolean usingItem,
                                        boolean aimingAtBlock, boolean restartable) {
        return effective && attackHeld && leftClickCounter <= 0 && usingItem && aimingAtBlock && restartable;
    }

    /**
     * Feature 47: start a local swing for a click swallowed by the miss delay. Only counters a miss sets
     * (1..{@link #MISS_DELAY_TICKS}); the 10000 lock vanilla sets while a screen holds focus is left alone.
     */
    public static boolean missSwing(boolean effective, int leftClickCounter, boolean restartable) {
        return effective && leftClickCounter > 0 && leftClickCounter <= MISS_DELAY_TICKS && restartable;
    }

    /** Feature 25: the swing progress the eat/drink, block and bow branches pass on (vanilla passes 0). */
    public static float usingSwingProgress(float vanilla, float real, boolean effective) {
        return effective ? real : vanilla;
    }

    // ---- Block Hit: third-person blocking pose (24) -------------------------------------------------------

    /** Feature 24: the blocking arm's yaw. 1.8.9 sets {@code -0.5235988F}; 1.7 left it at 0. */
    public static float blockingArmYaw(float vanillaYaw, boolean effective) {
        return effective ? 0f : vanillaYaw;
    }

    // ---- Transform ops ----------------------------------------------------------------------------------

    /** One op is {@link #OP_STRIDE} floats: {op, a, b, c, d}. */
    public static final int OP_STRIDE = 5;
    /** translate(a, b, c). */
    public static final int OP_TRANSLATE = 0;
    /** rotate(a degrees, about axis b, c, d). */
    public static final int OP_ROTATE = 1;
    /** scale(a, b, c). */
    public static final int OP_SCALE = 2;

    private static final float[] NO_OPS = new float[0];

    /** Smallest model-transform scale that can be inverted; below it the item is left vanilla. */
    private static final float MIN_SCALE = 1e-4f;

    /** Generated item geometry depth in 1.8.9 ({@code ItemModelGenerator}: layer from z 7.5 to 8.5 of 16). */
    private static final float GENERATED_BACK_Z = 7.5f / 16f;

    /** 1.7's 2D item draw in {@code ItemRenderer.renderItem}, before {@code renderItemIn2D} (1.7). */
    private static final float[] ITEM_2D_17 = {
            OP_TRANSLATE, 0f, -0.3f, 0f, 0,
            OP_SCALE, 1.5f, 1.5f, 1.5f, 0,
            OP_ROTATE, 50f, 0, 1, 0,
            OP_ROTATE, 335f, 0, 0, 1,
            OP_TRANSLATE, -0.9375f, -0.0625f, 0f, 0};

    /**
     * Moves 1.8's generated-item geometry (x, y in 0..1, z 7.5/16..8.5/16, texture upright on the +Z face) onto
     * 1.7's {@code renderItemIn2D} geometry (z -1/16..0, texture upright on the -Z face): turn half round about Y,
     * then shift. The +Z face of 1.8 becomes the face 1.7 shows in first person.
     */
    private static final float[] GENERATED_TO_2D_17 = {
            OP_TRANSLATE, 1f, 0f, GENERATED_BACK_Z, 0,
            OP_ROTATE, 180f, 0, 1, 0};

    /** 1.7 renders a block item centred on the origin ({@code RenderBlocks.renderBlockAsItem}). */
    private static final float[] BLOCK_CENTRE_17 = {OP_TRANSLATE, -0.5f, -0.5f, -0.5f, 0};

    /** 1.7 turns fishing rods and carrot sticks half round ({@code shouldRotateAroundWhenRendering}), first person. */
    private static final float[] ROTATE_AROUND_FIRST_17 = {OP_ROTATE, 180f, 0, 1, 0};

    /** Third-person kinds, in the order 1.7's {@code RenderPlayer.renderEquippedItems} tested them. */
    public static final int KIND_BLOCK = 0;
    public static final int KIND_BOW = 1;
    public static final int KIND_FULL3D = 2;
    public static final int KIND_FLAT = 3;

    /** The third-person kind 1.7 drew an item as: 3D block, bow, full-3D (tools, swords, rods), or plain flat. */
    public static int thirdPersonKind(boolean gui3d, boolean bow, boolean full3d) {
        if (gui3d) return KIND_BLOCK;
        if (bow) return KIND_BOW;
        if (full3d) return KIND_FULL3D;
        return KIND_FLAT;
    }

    /** 1.7 third person, 3D block (also 1.8.9's {@code LayerHeldItem} branch for render type 2). */
    private static final float[] THIRD_BLOCK_17 = {
            OP_TRANSLATE, 0f, 0.1875f, -0.3125f, 0,
            OP_ROTATE, 20f, 1, 0, 0,
            OP_ROTATE, 45f, 0, 1, 0,
            OP_SCALE, -0.375f, -0.375f, 0.375f, 0};

    /** 1.7 third person, bow. */
    private static final float[] THIRD_BOW_17 = {
            OP_TRANSLATE, 0f, 0.125f, 0.3125f, 0,
            OP_ROTATE, -20f, 0, 1, 0,
            OP_SCALE, 0.625f, -0.625f, 0.625f, 0,
            OP_ROTATE, -100f, 1, 0, 0,
            OP_ROTATE, 45f, 0, 1, 0};

    /** 1.7 third person, full-3D item: rod/carrot stick turn-around (before the blocking adjust). */
    private static final float[] THIRD_ROTATE_AROUND_17 = {
            OP_ROTATE, 180f, 0, 0, 1,
            OP_TRANSLATE, 0f, -0.125f, 0f, 0};

    /** 1.7 third person, full-3D item while blocking (feature 24's held-item adjustment). */
    private static final float[] THIRD_BLOCKING_17 = {
            OP_TRANSLATE, 0.05f, 0f, -0.1f, 0,
            OP_ROTATE, -50f, 0, 1, 0,
            OP_ROTATE, -10f, 1, 0, 0,
            OP_ROTATE, -60f, 0, 0, 1};

    /** 1.7 third person, full-3D item placement. */
    private static final float[] THIRD_FULL3D_17 = {
            OP_TRANSLATE, 0f, 0.1875f, 0f, 0,
            OP_SCALE, 0.625f, -0.625f, 0.625f, 0,
            OP_ROTATE, -100f, 1, 0, 0,
            OP_ROTATE, 45f, 0, 1, 0};

    /** 1.7 third person, any other (flat) item. */
    private static final float[] THIRD_FLAT_17 = {
            OP_TRANSLATE, 0.25f, 0.1875f, -0.1875f, 0,
            OP_SCALE, 0.375f, 0.375f, 0.375f, 0,
            OP_ROTATE, 60f, 0, 0, 1,
            OP_ROTATE, -90f, 1, 0, 0,
            OP_ROTATE, 20f, 0, 0, 1};

    /**
     * Feature 21: ops to apply just before 1.8 draws a held item in first person, so the item ends where 1.7 drew
     * it. {@code modelTransform} is the item model's first-person transform {rotX, rotY, rotZ, transX, transY,
     * transZ, scaleX, scaleY, scaleZ}. Vanilla then applies scale 2, that transform, scale 0.5 and translate -0.5;
     * the ops undo exactly that and put 1.7's draw in its place. Empty for built-in renderers and transforms that
     * cannot be undone.
     */
    public static float[] firstPersonItemOps(boolean builtIn, boolean gui3d, boolean rotateAround,
                                             float[] modelTransform) {
        if (builtIn || !invertible(modelTransform)) return NO_OPS;
        Ops ops = new Ops();
        if (rotateAround && !gui3d) ops.add(ROTATE_AROUND_FIRST_17);
        placeAndUndo(ops, gui3d, modelTransform);
        return ops.toArray();
    }

    /**
     * Features 22 and 24: ops to apply just before 1.8 draws a held item in third person. With Item Position the
     * item ends where 1.7 drew it for its {@code kind} (including the blocking adjust when {@code blocking});
     * without it only the blocking adjust is added, on top of 1.8's placement. Empty when neither applies.
     */
    public static float[] thirdPersonItemOps(boolean itemPosition, boolean builtIn, int kind, boolean rotateAround,
                                             boolean blocking, float[] modelTransform) {
        boolean block = blocking && kind == KIND_FULL3D;
        if (!itemPosition || builtIn || !invertible(modelTransform)) {
            return block && !builtIn ? THIRD_BLOCKING_17.clone() : NO_OPS;
        }
        Ops ops = new Ops();
        switch (kind) {
            case KIND_BLOCK:
                ops.add(THIRD_BLOCK_17);
                break;
            case KIND_BOW:
                ops.add(THIRD_BOW_17);
                break;
            case KIND_FULL3D:
                if (rotateAround) ops.add(THIRD_ROTATE_AROUND_17);
                if (block) ops.add(THIRD_BLOCKING_17);
                ops.add(THIRD_FULL3D_17);
                break;
            default:
                ops.add(THIRD_FLAT_17);
                break;
        }
        placeAndUndo(ops, kind == KIND_BLOCK, modelTransform);
        return ops.toArray();
    }

    /** 1.7's draw for the geometry, then the inverse of 1.8's S(2), model transform, S(0.5), T(-0.5). */
    private static void placeAndUndo(Ops ops, boolean gui3d, float[] b) {
        if (gui3d) {
            ops.add(BLOCK_CENTRE_17);
        } else {
            ops.add(ITEM_2D_17);
            ops.add(GENERATED_TO_2D_17);
        }
        ops.op(OP_TRANSLATE, 0.5f, 0.5f, 0.5f, 0);
        ops.op(OP_SCALE, 2f, 2f, 2f, 0);
        // Inverse of applyTransform: T(t) Ry Rx Rz S(s), undone in reverse order.
        ops.op(OP_SCALE, 1f / b[6], 1f / b[7], 1f / b[8], 0);
        ops.op(OP_ROTATE, -b[2], 0, 0, 1);
        ops.op(OP_ROTATE, -b[0], 1, 0, 0);
        ops.op(OP_ROTATE, -b[1], 0, 1, 0);
        ops.op(OP_TRANSLATE, -b[3], -b[4], -b[5], 0);
        ops.op(OP_SCALE, 0.5f, 0.5f, 0.5f, 0);
    }

    private static boolean invertible(float[] b) {
        return b != null && b.length >= 9
                && Math.abs(b[6]) >= MIN_SCALE && Math.abs(b[7]) >= MIN_SCALE && Math.abs(b[8]) >= MIN_SCALE;
    }

    /** A small growable op list. */
    private static final class Ops {
        private float[] data = new float[OP_STRIDE * 24];
        private int size;

        void op(float op, float a, float b, float c, float d) {
            if (size + OP_STRIDE > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[size++] = op;
            data[size++] = a;
            data[size++] = b;
            data[size++] = c;
            data[size++] = d;
        }

        void add(float[] ops) {
            for (int i = 0; i < ops.length; i += OP_STRIDE) op(ops[i], ops[i + 1], ops[i + 2], ops[i + 3], ops[i + 4]);
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    // ---- Item Position: item switch (27) -------------------------------------------------------------------

    /**
     * Feature 27: whether the held item stays up (no re-equip dip) when the selected stack is a different object.
     * Vanilla 1.8 keeps it only when the stacks are fully equal. 1.7 also kept it for the same item with the same
     * metadata (count and tags may differ). For an item without subtypes the metadata is only its damage, so a
     * damage change of the stack in the same slot keeps it up too.
     */
    public static boolean keepEquipped(boolean effective, boolean vanillaEqual, boolean sameItem, boolean sameMeta,
                                       boolean hasSubtypes, boolean sameSlot) {
        if (vanillaEqual) return true;
        return effective && sameItem && (sameMeta || (sameSlot && !hasSubtypes));
    }

    // ---- Smooth Sneak (28, 29) -------------------------------------------------------------------------------

    /** How far the eye drops while sneaking ({@code EntityPlayer.getEyeHeight}, 1.8.9: {@code 1.62F - 0.08F}). */
    public static final float SNEAK_EYE_DROP = 0.08f;

    /**
     * Share of the drop kept each tick after un-sneaking. 1.7 lowered the local player by {@code ySize} and
     * shrank it by {@code ySize *= 0.4F} each move; the research's "0.6 per tick" stand-up factor is the same
     * glide (60% of the remaining way per tick).
     */
    public static final float UNSNEAK_RETAIN = 0.4f;

    /** Below this the glide snaps to rest. */
    private static final float SNEAK_REST = 1e-4f;

    /**
     * The local player's eased sneak drop, ticked once per client tick. The drop reaches {@link #SNEAK_EYE_DROP}
     * in the tick sneaking starts (eased across that tick by the partial tick), and on release keeps
     * {@link #UNSNEAK_RETAIN} of itself per tick.
     */
    public static final class SneakState {
        private float drop;
        private float prevDrop;

        public void tick(boolean sneaking) {
            prevDrop = drop;
            if (sneaking) {
                drop = SNEAK_EYE_DROP;
            } else {
                drop *= UNSNEAK_RETAIN;
                if (drop < SNEAK_REST) drop = 0f;
            }
        }

        /** The drop at {@code partialTicks} between the last two ticks. */
        public float offset(float partialTicks) {
            return prevDrop + (drop - prevDrop) * partialTicks;
        }

        public void reset() {
            drop = 0f;
            prevDrop = 0f;
        }
    }

    /**
     * Features 28 and 29: the camera's eye height. Vanilla's eye height already includes the instant sneak drop;
     * this swaps it for the eased drop. Camera only: the real eye height is not changed.
     */
    public static float cameraEyeHeight(float vanillaEye, boolean vanillaSneaking, boolean sleeping, float easedDrop,
                                        boolean effective) {
        if (!effective || sleeping) return vanillaEye;
        return vanillaEye + (vanillaSneaking ? SNEAK_EYE_DROP : 0f) - easedDrop;
    }

    /**
     * Feature 28, 1.7 sneak model: whether 1.8's sneak translates of the model, its clothing layers, the worn head
     * and the held item stay. 1.7 had none of them.
     */
    public static boolean vanillaSneakTranslate(boolean effective) {
        return !effective;
    }

    /**
     * Feature 28, 1.7 sneak model: where the local player's model is drawn. 1.8 lowers other sneaking players by
     * 0.125 and never the local one; 1.7 lowered the local one with its eye ({@code ySize}), which is the eased
     * drop. The inventory preview is left alone.
     */
    public static double localSneakY(double y, boolean effective, boolean localPlayer, boolean guiPreview,
                                     float easedDrop) {
        if (!effective || !localPlayer || guiPreview) return y;
        return y - easedDrop;
    }

    // ---- Hearts (39) ------------------------------------------------------------------------------------

    /** Feature 39: whether a health change starts the heart blink. 1.8 blinks on any change, 1.7 only on a drop. */
    public static boolean heartsBlink(int healthNow, int healthBefore, boolean effective) {
        if (effective) return healthNow < healthBefore;
        return healthNow != healthBefore;
    }

    // ---- Red Armor (33) ---------------------------------------------------------------------------------

    /** Feature 33: whether the armour layer takes the hurt tint. */
    public static boolean armorTint(boolean vanilla, boolean effective) {
        return vanilla || effective;
    }
}
