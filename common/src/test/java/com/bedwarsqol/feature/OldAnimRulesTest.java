package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 1.7 Animations: off by default with every row on; a row only counts while the module is on. */
public class OldAnimRulesTest {

    private static final float EPS = 1e-6f;

    @Test
    public void moduleOffEveryRowOnByDefault() {
        assertFalse(OldAnimRules.DEFAULT_ENABLED);
        assertTrue(OldAnimRules.DEFAULT_BLOCK_HIT);
        assertTrue(OldAnimRules.DEFAULT_ITEM_POSITION);
        assertTrue(OldAnimRules.DEFAULT_SMOOTH_SNEAK);
        assertTrue(OldAnimRules.DEFAULT_HEARTS);
        assertTrue(OldAnimRules.DEFAULT_RED_ARMOR);
    }

    @Test
    public void aRowIsEffectiveOnlyWhileTheModuleIsOn() {
        assertTrue(OldAnimRules.effective(true, true));
        assertFalse(OldAnimRules.effective(true, false));
        assertFalse(OldAnimRules.effective(false, true));
        assertFalse(OldAnimRules.effective(false, false));
    }

    // ---- Block Hit: local swing start (25a, 47) -------------------------------------------------------

    @Test
    public void aSwingRestartsAsVanillaSwingItemAllows() {
        assertTrue(OldAnimRules.swingRestartable(false, 0, 6));
        assertTrue(OldAnimRules.swingRestartable(true, -1, 6));
        assertFalse(OldAnimRules.swingRestartable(true, 0, 6));
        assertFalse(OldAnimRules.swingRestartable(true, 2, 6));
        assertTrue(OldAnimRules.swingRestartable(true, 3, 6));
        assertTrue(OldAnimRules.swingRestartable(true, 5, 6));
        assertEquals(-1, OldAnimRules.SWING_START);
    }

    @Test
    public void blockHitSwingsOnlyWhileUsingAndAttackingABlock() {
        assertTrue(OldAnimRules.blockHitSwing(true, true, 0, true, true, true));
        assertTrue(OldAnimRules.blockHitSwing(true, true, -3, true, true, true));
        assertFalse("row not effective", OldAnimRules.blockHitSwing(false, true, 0, true, true, true));
        assertFalse("attack not held", OldAnimRules.blockHitSwing(true, false, 0, true, true, true));
        assertFalse("miss delay", OldAnimRules.blockHitSwing(true, true, 1, true, true, true));
        assertFalse("not using: vanilla swings itself", OldAnimRules.blockHitSwing(true, true, 0, false, true, true));
        assertFalse("not on a block", OldAnimRules.blockHitSwing(true, true, 0, true, false, true));
        assertFalse("swing in its first half", OldAnimRules.blockHitSwing(true, true, 0, true, true, false));
    }

    @Test
    public void missSwingOnlyInsideTheTenTickMissDelay() {
        assertEquals(10, OldAnimRules.MISS_DELAY_TICKS);
        for (int c = 1; c <= 10; c++) assertTrue("counter " + c, OldAnimRules.missSwing(true, c, true));
        assertFalse("no delay: vanilla swings", OldAnimRules.missSwing(true, 0, true));
        assertFalse(OldAnimRules.missSwing(true, 11, true));
        assertFalse("screen focus lock", OldAnimRules.missSwing(true, 10000, true));
        assertFalse("row not effective", OldAnimRules.missSwing(false, 5, true));
        assertFalse("swing in its first half", OldAnimRules.missSwing(true, 5, false));
    }

    @Test
    public void usingBranchesGetTheRealSwingOnlyWhenEffective() {
        assertEquals(0.4f, OldAnimRules.usingSwingProgress(0f, 0.4f, true), EPS);
        assertEquals(0f, OldAnimRules.usingSwingProgress(0f, 0.4f, false), EPS);
    }

    // ---- Block Hit: third-person blocking pose (24) ---------------------------------------------------

    @Test
    public void blockingArmHasNoYawWhenEffective() {
        assertEquals(0f, OldAnimRules.blockingArmYaw(-0.5235988f, true), EPS);
        assertEquals(-0.5235988f, OldAnimRules.blockingArmYaw(-0.5235988f, false), EPS);
    }

    @Test
    public void blockingItemAdjustAloneWhenItemPositionIsOff() {
        double[][] m = compose(OldAnimRules.thirdPersonItemOps(false, false, OldAnimRules.KIND_FULL3D, false, true,
                SWORD_THIRD));
        double[][] expected = compose(new float[]{
                0, 0.05f, 0f, -0.1f, 0,
                1, -50f, 0, 1, 0,
                1, -10f, 1, 0, 0,
                1, -60f, 0, 0, 1});
        assertMatrix(expected, m);
        assertEquals(0, OldAnimRules.thirdPersonItemOps(false, false, OldAnimRules.KIND_FULL3D, false, false,
                SWORD_THIRD).length);
    }

    // ---- Item Position: 1.7 item transforms (21, 22) --------------------------------------------------

    /** Vanilla 1.8.9 item JSON transforms {rotX, rotY, rotZ, transX, transY, transZ, scaleX, scaleY, scaleZ}. */
    private static final float[] GENERATED_FIRST = {0, -135, 25, 0, 4 / 16f, 2 / 16f, 1.7f, 1.7f, 1.7f};
    private static final float[] ROD_FIRST = {0, 45, 25, 0, 4 / 16f, 2 / 16f, 1.7f, 1.7f, 1.7f};
    private static final float[] SWORD_THIRD = {0, 90, -35, 0, 1.25f / 16f, -3.5f / 16f, 0.85f, 0.85f, 0.85f};
    private static final float[] APPLE_THIRD = {-90, 0, 0, 0, 1 / 16f, -3 / 16f, 0.55f, 0.55f, 0.55f};
    private static final float[] BOW_THIRD = {5, 80, -45, 0.75f / 16f, 0, 0.25f / 16f, 1, 1, 1};
    private static final float[] BLOCK_THIRD = {10, -45, 170, 0, 1.5f / 16f, -2.75f / 16f, 0.375f, 0.375f, 0.375f};
    private static final float[] IDENTITY = {0, 0, 0, 0, 0, 0, 1, 1, 1};
    private static final float[] ODD = {12, -70, 33, 0.1f, -0.2f, 0.05f, 1.3f, 0.8f, 2.1f};

    /** What vanilla 1.8 draws after our ops: S(2), the model's transform, S(0.5), T(-0.5). */
    private static float[] vanillaChain(float[] b) {
        return new float[]{
                2, 2, 2, 2, 0,
                0, b[3], b[4], b[5], 0,
                1, b[1], 0, 1, 0,
                1, b[0], 1, 0, 0,
                1, b[2], 0, 0, 1,
                2, b[6], b[7], b[8], 0,
                2, 0.5f, 0.5f, 0.5f, 0,
                0, -0.5f, -0.5f, -0.5f, 0};
    }

    /** 1.7's 2D item draw, then the map from 1.8's generated-item geometry onto 1.7's (written out independently). */
    private static final float[] ITEM_2D_17 = {
            0, 0f, -0.3f, 0f, 0,
            2, 1.5f, 1.5f, 1.5f, 0,
            1, 50f, 0, 1, 0,
            1, 335f, 0, 0, 1,
            0, -0.9375f, -0.0625f, 0f, 0};

    private static double[][] flat17() {
        // 1.8 texel (u, v) on the +Z face sits at (u, 1-v, 8.5/16); 1.7 draws it at (1-u, 1-v, -1/16).
        double[][] p = mul(translation(1, 0, 7.5 / 16), rotation(180, 0, 1, 0));
        return mul(compose(ITEM_2D_17), p);
    }

    @Test
    public void firstPersonFlatItemEndsAt17Placement() {
        for (float[] b : new float[][]{GENERATED_FIRST, ODD}) {
            double[][] got = mul(compose(OldAnimRules.firstPersonItemOps(false, false, false, b)), compose(vanillaChain(b)));
            assertMatrix(flat17(), got);
        }
    }

    @Test
    public void firstPersonRodIsTurnedAroundAs17Did() {
        double[][] got = mul(compose(OldAnimRules.firstPersonItemOps(false, false, true, ROD_FIRST)),
                compose(vanillaChain(ROD_FIRST)));
        assertMatrix(mul(rotation(180, 0, 1, 0), flat17()), got);
    }

    @Test
    public void the18FrontFaceLandsOnThe17FaceShownInFirstPerson() {
        double[][] got = mul(compose(OldAnimRules.firstPersonItemOps(false, false, false, GENERATED_FIRST)),
                compose(vanillaChain(GENERATED_FIRST)));
        double[][] item17 = compose(ITEM_2D_17);
        double[][] uvs = {{0, 0}, {1, 0}, {0.25, 0.75}, {1, 1}};
        for (double[] uv : uvs) {
            double[] p18 = {uv[0], 1 - uv[1], 8.5 / 16};
            double[] p17 = {1 - uv[0], 1 - uv[1], -1 / 16.0};
            assertArrayEquals(apply(item17, p17), apply(got, p18), 1e-5);
        }
    }

    @Test
    public void firstPersonBlockWithoutAModelTransformIsUnchanged() {
        double[][] got = compose(OldAnimRules.firstPersonItemOps(false, true, false, IDENTITY));
        assertMatrix(identity(), got);
    }

    @Test
    public void firstPersonBlockIsCentredAs17() {
        double[][] got = mul(compose(OldAnimRules.firstPersonItemOps(false, true, false, ODD)), compose(vanillaChain(ODD)));
        assertMatrix(translation(-0.5, -0.5, -0.5), got);
    }

    @Test
    public void builtInOrDegenerateModelsGetNoOps() {
        assertEquals(0, OldAnimRules.firstPersonItemOps(true, false, false, GENERATED_FIRST).length);
        assertEquals(0, OldAnimRules.firstPersonItemOps(false, false, false,
                new float[]{0, 0, 0, 0, 0, 0, 1, 0, 1}).length);
        assertEquals(0, OldAnimRules.thirdPersonItemOps(true, true, OldAnimRules.KIND_FLAT, false, false, APPLE_THIRD).length);
        assertEquals(0, OldAnimRules.thirdPersonItemOps(true, true, OldAnimRules.KIND_FULL3D, false, true, SWORD_THIRD).length);
    }

    @Test
    public void thirdPersonKindsFollow17Order() {
        assertEquals(OldAnimRules.KIND_BLOCK, OldAnimRules.thirdPersonKind(true, true, true));
        assertEquals(OldAnimRules.KIND_BOW, OldAnimRules.thirdPersonKind(false, true, true));
        assertEquals(OldAnimRules.KIND_FULL3D, OldAnimRules.thirdPersonKind(false, false, true));
        assertEquals(OldAnimRules.KIND_FLAT, OldAnimRules.thirdPersonKind(false, false, false));
    }

    private static final float[] K_BLOCK = {
            0, 0f, 0.1875f, -0.3125f, 0,
            1, 20f, 1, 0, 0,
            1, 45f, 0, 1, 0,
            2, -0.375f, -0.375f, 0.375f, 0};
    private static final float[] K_BOW = {
            0, 0f, 0.125f, 0.3125f, 0,
            1, -20f, 0, 1, 0,
            2, 0.625f, -0.625f, 0.625f, 0,
            1, -100f, 1, 0, 0,
            1, 45f, 0, 1, 0};
    private static final float[] K_FULL3D = {
            0, 0f, 0.1875f, 0f, 0,
            2, 0.625f, -0.625f, 0.625f, 0,
            1, -100f, 1, 0, 0,
            1, 45f, 0, 1, 0};
    private static final float[] K_ROTATE_AROUND = {
            1, 180f, 0, 0, 1,
            0, 0f, -0.125f, 0f, 0};
    private static final float[] K_BLOCKING = {
            0, 0.05f, 0f, -0.1f, 0,
            1, -50f, 0, 1, 0,
            1, -10f, 1, 0, 0,
            1, -60f, 0, 0, 1};
    private static final float[] K_FLAT = {
            0, 0.25f, 0.1875f, -0.1875f, 0,
            2, 0.375f, 0.375f, 0.375f, 0,
            1, 60f, 0, 0, 1,
            1, -90f, 1, 0, 0,
            1, 20f, 0, 0, 1};

    @Test
    public void thirdPersonEachKindEndsAt17Placement() {
        double[][] got = mul(compose(OldAnimRules.thirdPersonItemOps(true, false, OldAnimRules.KIND_FLAT, false, false,
                APPLE_THIRD)), compose(vanillaChain(APPLE_THIRD)));
        assertMatrix(mul(compose(K_FLAT), flat17()), got);

        got = mul(compose(OldAnimRules.thirdPersonItemOps(true, false, OldAnimRules.KIND_BOW, false, false, BOW_THIRD)),
                compose(vanillaChain(BOW_THIRD)));
        assertMatrix(mul(compose(K_BOW), flat17()), got);

        got = mul(compose(OldAnimRules.thirdPersonItemOps(true, false, OldAnimRules.KIND_FULL3D, false, false,
                SWORD_THIRD)), compose(vanillaChain(SWORD_THIRD)));
        assertMatrix(mul(compose(K_FULL3D), flat17()), got);

        got = mul(compose(OldAnimRules.thirdPersonItemOps(true, false, OldAnimRules.KIND_BLOCK, false, false,
                BLOCK_THIRD)), compose(vanillaChain(BLOCK_THIRD)));
        assertMatrix(mul(compose(K_BLOCK), translation(-0.5, -0.5, -0.5)), got);
    }

    @Test
    public void thirdPersonFull3DTurnsAroundThenBlocksThenPlaces() {
        double[][] got = mul(compose(OldAnimRules.thirdPersonItemOps(true, false, OldAnimRules.KIND_FULL3D, true, true,
                SWORD_THIRD)), compose(vanillaChain(SWORD_THIRD)));
        double[][] expected = mul(mul(mul(compose(K_ROTATE_AROUND), compose(K_BLOCKING)), compose(K_FULL3D)), flat17());
        assertMatrix(expected, got);
    }

    @Test
    public void thirdPersonBlockingOnlyAppliesToFull3DItems() {
        double[][] got = mul(compose(OldAnimRules.thirdPersonItemOps(true, false, OldAnimRules.KIND_FLAT, false, true,
                APPLE_THIRD)), compose(vanillaChain(APPLE_THIRD)));
        assertMatrix(mul(compose(K_FLAT), flat17()), got);
        assertEquals(0, OldAnimRules.thirdPersonItemOps(false, false, OldAnimRules.KIND_FLAT, false, true,
                APPLE_THIRD).length);
    }

    @Test
    public void opsUseOnlyTranslateRotateScale() {
        float[] ops = OldAnimRules.thirdPersonItemOps(true, false, OldAnimRules.KIND_FULL3D, true, true, SWORD_THIRD);
        assertEquals(0, ops.length % OldAnimRules.OP_STRIDE);
        for (int i = 0; i < ops.length; i += OldAnimRules.OP_STRIDE) {
            int op = (int) ops[i];
            assertTrue(op == OldAnimRules.OP_TRANSLATE || op == OldAnimRules.OP_ROTATE || op == OldAnimRules.OP_SCALE);
        }
    }

    // ---- Item Position: item switch (27) ---------------------------------------------------------------

    @Test
    public void sameItemAndMetaNeverDipsWhenEffective() {
        assertTrue(OldAnimRules.keepEquipped(true, false, true, true, true, false));
        assertTrue("vanilla equal always keeps", OldAnimRules.keepEquipped(false, true, false, false, false, false));
        assertFalse("not effective: vanilla dips", OldAnimRules.keepEquipped(false, false, true, true, true, true));
    }

    @Test
    public void damageChangeOnTheHeldStackKeepsOnlyForItemsWithoutSubtypes() {
        assertTrue("held sword lost durability", OldAnimRules.keepEquipped(true, false, true, false, false, true));
        assertFalse("other slot, other durability", OldAnimRules.keepEquipped(true, false, true, false, false, false));
        assertFalse("wool colour changed", OldAnimRules.keepEquipped(true, false, true, false, true, true));
        assertFalse("different item", OldAnimRules.keepEquipped(true, false, false, true, false, true));
    }

    // ---- Smooth Sneak (28, 29) -------------------------------------------------------------------------

    @Test
    public void sneakDropsInOneTickAndGlidesBackUp() {
        OldAnimRules.SneakState s = new OldAnimRules.SneakState();
        assertEquals(0f, s.offset(0.5f), EPS);
        s.tick(true);
        assertEquals(0f, s.offset(0f), EPS);
        assertEquals(OldAnimRules.SNEAK_EYE_DROP * 0.5f, s.offset(0.5f), EPS);
        assertEquals(OldAnimRules.SNEAK_EYE_DROP, s.offset(1f), EPS);
        s.tick(true);
        assertEquals(OldAnimRules.SNEAK_EYE_DROP, s.offset(0.3f), EPS);

        int ticks = 0;
        float last = OldAnimRules.SNEAK_EYE_DROP;
        while (s.offset(1f) > 0f) {
            s.tick(false);
            ticks++;
            float now = s.offset(1f);
            assertTrue("monotone", now < last);
            last = now;
            assertTrue("ends", ticks < 50);
        }
        assertTrue("longer than the one-tick drop", ticks >= 4);
        assertEquals(OldAnimRules.SNEAK_EYE_DROP * OldAnimRules.UNSNEAK_RETAIN, firstGlideStep(), EPS);
    }

    private static float firstGlideStep() {
        OldAnimRules.SneakState s = new OldAnimRules.SneakState();
        s.tick(true);
        s.tick(false);
        return s.offset(1f);
    }

    @Test
    public void sneakStateResets() {
        OldAnimRules.SneakState s = new OldAnimRules.SneakState();
        s.tick(true);
        s.reset();
        assertEquals(0f, s.offset(1f), EPS);
        assertEquals(0f, s.offset(0f), EPS);
    }

    @Test
    public void vanillaSneakDropMatchesEntityPlayer() {
        assertEquals(0.08f, OldAnimRules.SNEAK_EYE_DROP, EPS);
        assertEquals(0.4f, OldAnimRules.UNSNEAK_RETAIN, EPS);
    }

    @Test
    public void cameraEyeHeightIsVanillaAtRestAndEasedBetween() {
        // At rest it is exactly vanilla.
        assertEquals(1.54f, OldAnimRules.cameraEyeHeight(1.54f, true, false, 0.08f, true), EPS);
        assertEquals(1.62f, OldAnimRules.cameraEyeHeight(1.62f, false, false, 0f, true), EPS);
        // Just pressed: vanilla dropped already, the camera has not.
        assertEquals(1.62f, OldAnimRules.cameraEyeHeight(1.54f, true, false, 0f, true), EPS);
        // Just released: vanilla is back up, the camera is still down.
        assertEquals(1.54f, OldAnimRules.cameraEyeHeight(1.62f, false, false, 0.08f, true), EPS);
        // Not effective or sleeping: vanilla.
        assertEquals(1.54f, OldAnimRules.cameraEyeHeight(1.54f, true, false, 0f, false), EPS);
        assertEquals(0.2f, OldAnimRules.cameraEyeHeight(0.2f, false, true, 0.08f, true), EPS);
    }

    @Test
    public void sneakModelTranslatesStayVanillaUnlessEffective() {
        assertTrue(OldAnimRules.vanillaSneakTranslate(false));
        assertFalse(OldAnimRules.vanillaSneakTranslate(true));
    }

    @Test
    public void localPlayerModelSinksWithTheCameraOnlyInTheWorld() {
        assertEquals(63.92, OldAnimRules.localSneakY(64.0, true, true, false, 0.08f), 1e-6);
        assertEquals(64.0, OldAnimRules.localSneakY(64.0, false, true, false, 0.08f), 1e-6);
        assertEquals("remote players keep vanilla's offset", 64.0,
                OldAnimRules.localSneakY(64.0, true, false, false, 0.08f), 1e-6);
        assertEquals("inventory preview", 64.0, OldAnimRules.localSneakY(64.0, true, true, true, 0.08f), 1e-6);
    }

    // ---- Hearts (39) and Red Armor (33) ----------------------------------------------------------------

    @Test
    public void heartsBlinkOnlyWhenHealthDropped() {
        assertTrue(OldAnimRules.heartsBlink(10, 14, true));
        assertTrue(OldAnimRules.heartsBlink(10, 14, false));
        assertFalse("regeneration", OldAnimRules.heartsBlink(15, 14, true));
        assertTrue("vanilla blinks on regeneration", OldAnimRules.heartsBlink(15, 14, false));
        assertFalse(OldAnimRules.heartsBlink(14, 14, true));
        assertFalse(OldAnimRules.heartsBlink(14, 14, false));
    }

    @Test
    public void armorTakesTheHurtTintOnlyWhenEffective() {
        assertTrue(OldAnimRules.armorTint(false, true));
        assertFalse(OldAnimRules.armorTint(false, false));
        assertTrue(OldAnimRules.armorTint(true, false));
    }

    // ---- 4x4 matrix helpers (GL conventions: each op multiplies on the right) -------------------------

    private static double[][] identity() {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) m[i][i] = 1;
        return m;
    }

    private static double[][] translation(double x, double y, double z) {
        double[][] m = identity();
        m[0][3] = x;
        m[1][3] = y;
        m[2][3] = z;
        return m;
    }

    private static double[][] scaling(double x, double y, double z) {
        double[][] m = identity();
        m[0][0] = x;
        m[1][1] = y;
        m[2][2] = z;
        return m;
    }

    private static double[][] rotation(double deg, double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        x /= len;
        y /= len;
        z /= len;
        double a = Math.toRadians(deg);
        double c = Math.cos(a);
        double s = Math.sin(a);
        double t = 1 - c;
        double[][] m = identity();
        m[0][0] = t * x * x + c;
        m[0][1] = t * x * y - s * z;
        m[0][2] = t * x * z + s * y;
        m[1][0] = t * x * y + s * z;
        m[1][1] = t * y * y + c;
        m[1][2] = t * y * z - s * x;
        m[2][0] = t * x * z - s * y;
        m[2][1] = t * y * z + s * x;
        m[2][2] = t * z * z + c;
        return m;
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[4][4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                for (int k = 0; k < 4; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }

    private static double[][] compose(float[] ops) {
        double[][] m = identity();
        for (int i = 0; i < ops.length; i += 5) {
            int op = (int) ops[i];
            double[][] step;
            if (op == 0) step = translation(ops[i + 1], ops[i + 2], ops[i + 3]);
            else if (op == 1) step = rotation(ops[i + 1], ops[i + 2], ops[i + 3], ops[i + 4]);
            else step = scaling(ops[i + 1], ops[i + 2], ops[i + 3]);
            m = mul(m, step);
        }
        return m;
    }

    private static double[] apply(double[][] m, double[] p) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) r[i] = m[i][0] * p[0] + m[i][1] * p[1] + m[i][2] * p[2] + m[i][3];
        return r;
    }

    private static void assertMatrix(double[][] expected, double[][] actual) {
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                assertEquals("m[" + i + "][" + j + "]", expected[i][j], actual[i][j], 1e-4);
    }

    @Test
    public void bowVariantFollowsVanillaDrawStages() {
        assertNull(OldAnimRules.bowVariant(0));
        assertNull(OldAnimRules.bowVariant(-5));
        assertEquals("bow_pulling_0", OldAnimRules.bowVariant(1));
        assertEquals("bow_pulling_0", OldAnimRules.bowVariant(13));
        assertEquals("bow_pulling_1", OldAnimRules.bowVariant(14));
        assertEquals("bow_pulling_1", OldAnimRules.bowVariant(17));
        assertEquals("bow_pulling_2", OldAnimRules.bowVariant(18));
        assertEquals("bow_pulling_2", OldAnimRules.bowVariant(72000));
    }

    @Test
    public void reflectedModelsStayOnTheVanillaPath() {
        // Vanilla picks the cull face from the model's scale signs; undoing a reflection would flip the faces.
        float[][] reflected = {
                {0, 0, 0, 0, 0, 0, -1, 1, 1}, {0, 0, 0, 0, 0, 0, 1, -1, 1}, {0, 0, 0, 0, 0, 0, 1, 1, -1},
                {0, 0, 0, 0, 0, 0, -1, -1, 1}, {0, 0, 0, 0, 0, 0, -1, -1, -1}};
        for (float[] t : reflected) {
            assertEquals(0, OldAnimRules.firstPersonItemOps(false, true, false, t).length);
            assertEquals(0, OldAnimRules.firstPersonItemOps(false, false, false, t).length);
            assertEquals(0, OldAnimRules.thirdPersonItemOps(true, false, OldAnimRules.KIND_FLAT, false, false, t).length);
            assertEquals(0, OldAnimRules.thirdPersonItemOps(true, false, OldAnimRules.KIND_BLOCK, false, false, t).length);
        }
    }
}
