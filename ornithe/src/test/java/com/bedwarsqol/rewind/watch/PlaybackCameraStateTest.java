package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S20PacketEntityProperties;
import net.minecraft.world.WorldSettings;
import org.junit.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Code review 1 on the playback camera's recorded state:
 * <ul>
 *   <li>I2: the recorded player's {@code generic.maxHealth} (S20 for the recorded id, which vanilla routes to the
 *       puppet) reaches the camera, so S20(40) + S06(40) shows twenty hearts, in either order;</li>
 *   <li>O1: hotbar pickup animations ({@code animationsToGo}) count down each playback tick;</li>
 *   <li>O2: the free camera never tilts, also not vanilla's separate death branch at recorded health 0.</li>
 * </ul>
 * The camera stand-in is a plain {@link EntityLivingBase} (the camera and the HUD read the same attribute and
 * health); every packet is vanilla's own class.
 */
public class PlaybackCameraStateTest {

    private static final int RECORDED_ID = 4242;

    /** A living entity with no world: vanilla's attribute map, health clamp and data watcher. */
    static final class Living extends EntityLivingBase {
        Living() {
            super(null);
        }

        @Override public ItemStack getHeldItem() { return null; }
        @Override public ItemStack getEquipmentInSlot(int slot) { return null; }
        @Override public ItemStack getCurrentArmor(int slot) { return null; }
        @Override public void setCurrentItemOrArmor(int slot, ItemStack stack) { }
        @Override public ItemStack[] getInventory() { return new ItemStack[5]; }
        @Override public void readEntityFromNBT(NBTTagCompound tag) { }
        @Override public void writeEntityToNBT(NBTTagCompound tag) { }
    }

    /** Vanilla's S20 for {@code id} carrying {@code maxHealth} as base value plus {@code bonus} (operation 0). */
    private static S20PacketEntityProperties maxHealth(int id, double base, double bonus) {
        Living src = new Living();
        IAttributeInstance a = src.getEntityAttribute(SharedMonsterAttributes.maxHealth);
        a.setBaseValue(base);
        if (bonus != 0) {
            a.applyModifier(new AttributeModifier(UUID.fromString("5d6f0ba2-1186-46ac-b896-c61c5cee99cc"), "test",
                    bonus, 0));
        }
        return new S20PacketEntityProperties(id, Collections.singletonList(a));
    }

    /** What vanilla's {@code handleUpdateHealth} does to the client player: set (clamped) health. */
    private static void vanillaS06(PlaybackSink.SelfHealth h, Living cam, float health) {
        S06PacketUpdateHealth p = new S06PacketUpdateHealth(health, 20, 5f);
        cam.setHealth(p.getHealth());
        h.applied(p, RECORDED_ID, cam);
    }

    // ------------------------------------------------------------------ I2

    @Test
    public void s20ThenS06ShowsTwentyHearts() {
        PlaybackSink.SelfHealth h = new PlaybackSink.SelfHealth();
        Living cam = new Living();
        h.applied(maxHealth(RECORDED_ID, 40, 0), RECORDED_ID, cam);
        vanillaS06(h, cam, 40f);
        assertEquals(40.0, cam.getEntityAttribute(SharedMonsterAttributes.maxHealth).getAttributeValue(), 0);
        assertEquals("not clamped to the default 20", 40f, cam.getHealth(), 0f);
    }

    @Test
    public void s06BeforeS20IsReappliedWhenTheMaximumRises() {
        PlaybackSink.SelfHealth h = new PlaybackSink.SelfHealth();
        Living cam = new Living();
        vanillaS06(h, cam, 40f);
        assertEquals("vanilla clamps to the old maximum", 20f, cam.getHealth(), 0f);
        h.applied(maxHealth(RECORDED_ID, 40, 0), RECORDED_ID, cam);
        assertEquals(40f, cam.getMaxHealth(), 0f);
        assertEquals("the recorded health once the maximum allows it", 40f, cam.getHealth(), 0f);
    }

    @Test
    public void modifiersCountAndALowerMaximumClampsTheRecordedHealth() {
        PlaybackSink.SelfHealth h = new PlaybackSink.SelfHealth();
        Living cam = new Living();
        h.applied(maxHealth(RECORDED_ID, 20, 20), RECORDED_ID, cam);
        vanillaS06(h, cam, 36f);
        assertEquals(40f, cam.getMaxHealth(), 0f);
        assertEquals(36f, cam.getHealth(), 0f);
        h.applied(maxHealth(RECORDED_ID, 20, 0), RECORDED_ID, cam);
        assertEquals(20f, cam.getMaxHealth(), 0f);
        assertEquals(20f, cam.getHealth(), 0f);
    }

    @Test
    public void anotherEntitysPropertiesLeaveTheCameraAlone() {
        PlaybackSink.SelfHealth h = new PlaybackSink.SelfHealth();
        Living cam = new Living();
        h.applied(maxHealth(RECORDED_ID + 1, 40, 0), RECORDED_ID, cam);
        assertEquals(20f, cam.getMaxHealth(), 0f);
    }

    @Test
    public void aNewWorldForgetsTheRecordedHealth() {
        PlaybackSink.SelfHealth h = new PlaybackSink.SelfHealth();
        Living cam = new Living();
        vanillaS06(h, cam, 0f);
        h.reset(); // S01/S07: a new camera, vanilla's defaults until the server says otherwise
        Living fresh = new Living();
        h.applied(maxHealth(RECORDED_ID, 40, 0), RECORDED_ID, fresh);
        assertEquals("the old world's 0 is not put on the new camera", 20f, fresh.getHealth(), 0f);
    }

    @Test
    public void theSinkPassesEveryAppliedPacketToItsHealthState() throws Exception {
        String text = source("com/bedwarsqol/rewind/watch/PlaybackSink.java");
        String apply = body(text, "boolean\\s+apply\\s*\\(");
        assertTrue("apply hands the processed packet over: " + apply,
                apply.matches("(?s).*health\\.applied\\s*\\(\\s*p\\s*,\\s*recordedEntityId\\s*,.*"));
        String boundary = body(text, "void\\s+boundary\\s*\\(");
        assertTrue("S01/S07 reset it: " + boundary, boundary.contains("health.reset()"));
    }

    /**
     * Code review 2, I2: every applied window-0 inventory packet (S30, S2F) reaches HandState with its record time, so a
     * fresh camera's full copy only restores EQUIP stacks newer than the server's inventory.
     */
    @Test
    public void theSinkReportsEveryAppliedInventoryPacketToHandState() throws Exception {
        String text = source("com/bedwarsqol/rewind/watch/PlaybackSink.java");
        String apply = body(text, "boolean\\s+apply\\s*\\(");
        assertTrue("apply reports the processed packet's bytes and time: " + apply, apply.matches(
                "(?s).*p\\.processPacket\\(handler\\);.*hand\\.inventoryPacket\\s*\\(\\s*buf\\s*,\\s*off\\s*,\\s*len\\s*,"
                        + "\\s*feedTimeUs\\(\\)\\s*\\).*"));
    }

    // ------------------------------------------------------------------ O1

    @Test
    public void pickupAnimationsCountDownToZero() {
        ItemStack picked = new ItemStack(new Item());
        picked.animationsToGo = 5;
        ItemStack still = new ItemStack(new Item());
        ItemStack[] hotbar = new ItemStack[36];
        hotbar[1] = picked;
        hotbar[2] = still;
        for (int tick = 0; tick < 5; tick++) {
            RewindCamera.countDownAnimations(hotbar);
        }
        assertEquals(0, picked.animationsToGo);
        RewindCamera.countDownAnimations(hotbar);
        assertEquals("never below zero", 0, picked.animationsToGo);
        assertEquals(0, still.animationsToGo);
    }

    @Test
    public void theCameraCountsDownInItsTick() throws Exception {
        String text = source("com/bedwarsqol/rewind/watch/RewindCamera.java");
        String onUpdate = body(text, "void\\s+onUpdate\\s*\\(");
        assertTrue(onUpdate, onUpdate.contains("countDownAnimations(inventory.mainInventory)"));
    }

    // ------------------------------------------------------------------ O2

    @Test
    public void onlyTheFreeCameraOfAPlayingRecordingSkipsTheHurtTilt() {
        assertTrue(CameraController.cancelsHurtTilt(true, true));
        assertFalse("your view keeps vanilla", CameraController.cancelsHurtTilt(true, false));
        assertFalse("no recording: vanilla", CameraController.cancelsHurtTilt(false, true));
        assertFalse(CameraController.cancelsHurtTilt(false, false));
    }

    @Test
    public void theMixinCancelsHurtCameraEffectAtHeadWithRequireOne() throws Exception {
        Method target = EntityRenderer.class.getDeclaredMethod("hurtCameraEffect", float.class);
        assertEquals(void.class, target.getReturnType());
        Class<?> mixin = Class.forName("com.bedwarsqol.mixin.EntityRendererRewindPlaybackMixin");
        List<Method> found = new ArrayList<Method>();
        for (Method m : mixin.getDeclaredMethods()) {
            Inject inject = m.getAnnotation(Inject.class);
            if (inject == null) continue;
            for (String t : inject.method()) {
                if (t.equals("hurtCameraEffect") || t.equals("hurtCameraEffect(F)V")) {
                    found.add(m);
                    assertEquals(1, inject.at().length);
                    At at = inject.at()[0];
                    assertEquals("HEAD", at.value());
                    assertTrue(inject.cancellable());
                    assertEquals(1, inject.require());
                    assertEquals(2, m.getParameterTypes().length);
                    assertEquals(float.class, m.getParameterTypes()[0]);
                    assertEquals(CallbackInfo.class, m.getParameterTypes()[1]);
                }
            }
        }
        assertEquals("one injector on hurtCameraEffect", 1, found.size());
        String text = source("com/bedwarsqol/mixin/EntityRendererRewindPlaybackMixin.java");
        String b = body(text, "void\\s+" + Pattern.quote(found.get(0).getName()) + "\\s*\\(");
        assertTrue(b, b.contains("CameraController.cancelsHurtTilt(RewindPlayback.active(), CameraController.freeCamera())"));
        assertTrue(b, b.matches("(?s).*\\.cancel\\s*\\(\\s*\\).*"));
    }

    // ------------------------------------------------------------------ Phase 7: camera state (B1, B4-B6, B8)

    private static final int GROUND = RewindFormat.FLAG_ON_GROUND;

    /**
     * Amendment 1: vanilla's {@code Entity.isInvisibleToPlayer(viewer)} reads only the viewer's {@code isSpectator()}
     * (javap: {@code aload_1; invokevirtual EntityPlayer.isSpectator}), and {@code RendererLivingEntity} draws an
     * invisible entity translucent exactly when it returns false. With the real camera as the viewer, an invisible
     * armour stand is translucent only in your view while the recorded game mode is spectator.
     */
    @Test
    public void invisibleEntitiesAreTranslucentOnlyInYourViewOfARecordedSpectator() throws Exception {
        RewindCamera cam = allocate(RewindCamera.class);
        EntityArmorStand stand = new EntityArmorStand(null);
        stand.setInvisible(true);
        WorldSettings.GameType[] modes = {WorldSettings.GameType.NOT_SET, WorldSettings.GameType.SURVIVAL,
                WorldSettings.GameType.CREATIVE, WorldSettings.GameType.ADVENTURE, WorldSettings.GameType.SPECTATOR};
        Object playback = staticField(RewindPlayback.class, "INSTANCE");
        Field sinkField = RewindPlayback.class.getDeclaredField("sink");
        sinkField.setAccessible(true);
        Object before = sinkField.get(playback);
        ViewState v = RewindPlayback.view();
        try {
            PlaybackSink sink = allocate(PlaybackSink.class);
            sinkField.set(playback, sink);
            for (int view = 0; view < 3; view++) {
                if (view == 0) {
                    v.selectYou();
                } else if (view == 1) {
                    v.selectFree();
                } else {
                    v.ride(77);
                }
                for (WorldSettings.GameType mode : modes) {
                    setField(PlaybackSink.class, sink, "recordedGameType", mode);
                    boolean ghost = view == 0 && mode == WorldSettings.GameType.SPECTATOR;
                    String at = v.view() + " / " + mode;
                    assertEquals(at, ghost, cam.isSpectator());
                    assertEquals("translucent at " + at, ghost, !stand.isInvisibleToPlayer(cam));
                }
            }
            sinkField.set(playback, null);
            v.selectYou();
            assertFalse("no sink yet: not a spectator", cam.isSpectator());
        } finally {
            sinkField.set(playback, before);
            v.reset();
        }
    }

    @Test
    public void theCameraItselfIsNeverRendered() throws Exception {
        RewindCamera cam = allocate(RewindCamera.class);
        assertFalse("F5 shows the puppet, never the camera", cam.isInRangeToRender3d(0, 0, 0));
        assertFalse(cam.isInRangeToRender3d(cam.posX + 1, cam.posY, cam.posZ));
    }

    /** The free camera keeps today's look: no underwater overlay although it is no spectator any more. */
    @Test
    public void theFreeCameraDrawsNoWaterOverlay() throws Exception {
        RewindCamera cam = allocate(RewindCamera.class);
        ViewState v = RewindPlayback.view();
        try {
            v.selectFree();
            assertFalse(cam.isInsideOfMaterial(Material.water));
        } finally {
            v.reset();
        }
    }

    /**
     * B1, B5, B8: bob, sway, nausea, the derived FOV and the recorded invisible and burning flags reach the camera in
     * your view only; the free camera gets none of them, and they come back at once in your view.
     */
    @Test
    public void cameraStateReachesTheCameraOnlyInYourView() throws Exception {
        ClientSettings saved = BedwarsQol.config;
        BedwarsQol.config = null; // vanilla's modifier (Dynamic FOV is Cobblify's option, pinned below)
        try {
            cameraStateInEachView();
        } finally {
            BedwarsQol.config = saved;
        }
    }

    /** B6 with Cobblify's Dynamic FOV off: the derived widening is not shown, as it was not live. */
    @Test
    public void dynamicFovOffKeepsTheViewSteady() throws Exception {
        ClientSettings saved = BedwarsQol.config;
        try {
            RewindCamera cam = cameraWithDataWatcher();
            CameraStatePart part = new CameraStatePart();
            part.step(0, 64, 0, GROUND, true, -1, 1.15f);
            part.apply(cam, true);
            ClientSettings cfg = new ClientSettings();
            cfg.dynamicFov = true;
            BedwarsQol.config = cfg;
            assertEquals(1.15f, cam.getFovModifier(), 0f);
            cfg.dynamicFov = false;
            assertEquals(1f, cam.getFovModifier(), 0f);
        } finally {
            BedwarsQol.config = saved;
        }
    }

    private static void cameraStateInEachView() throws Exception {
        RewindCamera cam = cameraWithDataWatcher();
        CameraStatePart part = new CameraStatePart();
        int both = GROUND | RewindFormat.FLAG_INVISIBLE | RewindFormat.FLAG_BURNING;
        part.step(0, 64, 0, GROUND, true, -1, 1f);
        part.step(0.25, 64, 0, both, true, 300, 1.15f);
        cam.rotationYaw = 30f;
        cam.rotationPitch = 10f;
        part.viewSwitched(cam, true);
        part.snapIfPending(cam, true); // the frame after the switch: no lag
        assertEquals(30f, cam.renderArmYaw, 0f);
        assertEquals(30f, cam.prevRenderArmYaw, 0f);
        cam.rotationYaw = 70f;
        part.turn(cam, true);
        part.apply(cam, true);

        ViewBob bob = new ViewBob();
        bob.step(0, 64, 0, GROUND, true);
        bob.nausea(-1);
        bob.step(0.25, 64, 0, both, true);
        bob.nausea(300);
        assertTrue(bob.cameraYaw() > 0f);
        assertEquals(bob.cameraYaw(), cam.cameraYaw, 0f);
        assertEquals(bob.prevCameraYaw(), cam.prevCameraYaw, 0f);
        assertEquals(bob.cameraPitch(), cam.cameraPitch, 0f);
        assertEquals(bob.distanceWalked(), cam.distanceWalkedModified, 0f);
        assertEquals(bob.prevDistanceWalked(), cam.prevDistanceWalkedModified, 0f);
        assertTrue(cam.timeInPortal > 0f);
        assertEquals(bob.timeInPortal(), cam.timeInPortal, 0f);
        assertEquals(bob.prevTimeInPortal(), cam.prevTimeInPortal, 0f);
        assertEquals("sway: half way to the turn", 50f, cam.renderArmYaw, 0f);
        assertEquals(30f, cam.prevRenderArmYaw, 0f);
        assertEquals(1.15f, cam.getFovModifier(), 0f);
        assertTrue("recorded invisible: vanilla hides the bare arm", cam.isInvisible());
        assertTrue("recorded burning: vanilla's fire overlay reads flag 0", burningFlag(cam));

        part.viewSwitched(cam, false); // the free camera
        assertFalse(cam.isInvisible());
        assertFalse(burningFlag(cam));
        assertEquals(0f, cam.cameraYaw, 0f);
        assertEquals(0f, cam.prevCameraYaw, 0f);
        assertEquals(0f, cam.cameraPitch, 0f);
        assertEquals(0f, cam.distanceWalkedModified, 0f);
        assertEquals(0f, cam.prevDistanceWalkedModified, 0f);
        assertEquals(0f, cam.timeInPortal, 0f);
        assertEquals(0f, cam.prevTimeInPortal, 0f);
        assertEquals(1f, cam.getFovModifier(), 0f);
        assertEquals("no sway", cam.rotationYaw, cam.renderArmYaw, 0f);
        assertEquals(cam.rotationYaw, cam.prevRenderArmYaw, 0f);
        assertEquals(cam.rotationPitch, cam.renderArmPitch, 0f);
        part.snapIfPending(cam, false);
        part.turn(cam, false);
        assertEquals("nothing sways outside your view", cam.rotationYaw, cam.renderArmYaw, 0f);

        cam.rotationYaw = 200f; // your view's pose differs from the free camera's
        part.viewSwitched(cam, true);
        assertTrue("back in your view at once", cam.isInvisible());
        assertEquals(bob.cameraYaw(), cam.cameraYaw, 0f);
        part.snapIfPending(cam, true);
        assertEquals("the hand starts from your view's rotation", 200f, cam.renderArmYaw, 0f);
        assertEquals(200f, cam.prevRenderArmYaw, 0f);
    }

    /** The camera's own pose no longer pins the hand's lag or the bob (holdPose leaves them to the part). */
    @Test
    public void holdPoseLeavesTheSwayAndBobAlone() throws Exception {
        String text = source("com/bedwarsqol/rewind/watch/RewindCamera.java");
        String hold = body(text, "void\\s+holdPose\\s*\\(");
        assertFalse(hold, hold.contains("renderArm"));
        assertFalse(hold, hold.contains("cameraYaw"));
        assertFalse(hold, hold.contains("distanceWalked"));
        assertFalse(hold, hold.contains("timeInPortal"));
    }

    /** B6: sprint (vanilla's setSprinting modifier) and Speed II (an S20 modifier) on the puppet widen the FOV. */
    @Test
    public void theDerivedFovReadsThePuppetsMovementSpeed() throws Exception {
        RewindCamera cam = allocate(RewindCamera.class);
        cam.capabilities = new PlayerCapabilities();
        Living puppet = new Living();
        IAttributeInstance speed = puppet.getEntityAttribute(SharedMonsterAttributes.movementSpeed);
        speed.setBaseValue(0.1f);
        assertEquals(1f, CameraStatePart.derivedFov(puppet, cam), 1e-6f);
        puppet.setSprinting(true);
        assertEquals(1.15f, CameraStatePart.derivedFov(puppet, cam), 1e-6f);
        speed.applyModifier(new AttributeModifier(UUID.fromString("91AEAA56-376B-4498-935B-2F7F68070635"),
                "speed", 0.4, 2));
        assertEquals(1.41f, CameraStatePart.derivedFov(puppet, cam), 1e-6f);
        puppet.setSprinting(false);
        assertEquals(1.2f, CameraStatePart.derivedFov(puppet, cam), 1e-6f);
        assertEquals("no puppet: walking", 1f, CameraStatePart.derivedFov(null, cam), 1e-6f);
    }

    @Test
    public void theFreeCameraKeepsSpectatorTerrainCulling() throws Exception {
        assertTrue(CameraStatePart.terrainSpectator(true, true, false));
        assertFalse("your view: vanilla's (the live culling)", CameraStatePart.terrainSpectator(true, false, false));
        assertTrue(CameraStatePart.terrainSpectator(true, false, true));
        assertFalse("no recording: vanilla", CameraStatePart.terrainSpectator(false, true, false));
        assertTrue(CameraStatePart.terrainSpectator(false, false, true));

        Method target = null;
        for (Method m : EntityRenderer.class.getDeclaredMethods()) {
            if (m.getName().equals("renderWorldPass")) {
                target = m;
            }
        }
        assertTrue("renderWorldPass exists", target != null);
        Method setup = RenderGlobal.class.getDeclaredMethod("setupTerrain", Entity.class, double.class,
                ICamera.class, int.class, boolean.class);
        assertEquals(void.class, setup.getReturnType());
        Class<?> mixin = Class.forName("com.bedwarsqol.mixin.EntityRendererRewindPlaybackMixin");
        List<Method> found = new ArrayList<Method>();
        for (Method m : mixin.getDeclaredMethods()) {
            ModifyArg a = m.getAnnotation(ModifyArg.class);
            if (a == null) continue;
            assertEquals(1, a.method().length);
            assertEquals("renderWorldPass", a.method()[0]);
            assertEquals(1, a.at().length);
            At at = a.at()[0];
            assertEquals("INVOKE", at.value());
            assertEquals("Lnet/minecraft/client/renderer/RenderGlobal;setupTerrain(Lnet/minecraft/entity/Entity;D"
                    + "Lnet/minecraft/client/renderer/culling/ICamera;IZ)V", at.target());
            assertEquals(4, a.index());
            assertEquals(1, a.require());
            assertEquals(boolean.class, m.getReturnType());
            found.add(m);
        }
        assertEquals("one injector on setupTerrain's spectator flag", 1, found.size());
        String text = source("com/bedwarsqol/mixin/EntityRendererRewindPlaybackMixin.java");
        String b = body(text, "boolean\\s+" + Pattern.quote(found.get(0).getName()) + "\\s*\\(");
        assertTrue(b, b.contains("CameraStatePart.terrainSpectator(RewindPlayback.active(), "
                + "CameraController.freeCamera(), "));
    }

    /** The part asks the session's view and writes through the camera's one entry point. */
    @Test
    public void theCameraAnswersSpectatorFromTheViewAndTheRecordedMode() throws Exception {
        String text = source("com/bedwarsqol/rewind/watch/RewindCamera.java");
        String spectator = body(text, "boolean\\s+isSpectator\\s*\\(");
        assertTrue(spectator, spectator.contains("RewindPlayback.view().cameraSpectator("));
        String fov = body(text, "float\\s+getFovModifier\\s*\\(");
        assertTrue("the derived value through the Dynamic FOV option: " + fov,
                fov.contains("CameraOptions.fovModifier(fovModifier)"));
    }

    private static RewindCamera cameraWithDataWatcher() throws Exception {
        RewindCamera cam = allocate(RewindCamera.class);
        DataWatcher dw = new DataWatcher(cam);
        dw.addObject(0, (byte) 0); // what Entity.<init> registers: the flag byte
        setField(Entity.class, cam, "dataWatcher", dw);
        return cam;
    }

    /** Entity flag 0, which {@code isBurning()} reads on a client world. */
    private static boolean burningFlag(Entity e) throws Exception {
        Field f = Entity.class.getDeclaredField("dataWatcher");
        f.setAccessible(true);
        return (((DataWatcher) f.get(e)).getWatchableObjectByte(0) & 1) != 0;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Field f = u.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) u.getMethod("allocateInstance", Class.class).invoke(f.get(null), type);
    }

    private static Object staticField(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ------------------------------------------------------------------ helpers

    private static String source(String path) throws Exception {
        File f = new File(PlaybackIsolationScanTest.mainRoot(), path);
        return PlaybackIsolationScanTest.strip(PlaybackIsolationScanTest.read(f));
    }

    private static String body(String text, String declRegex) {
        Matcher decl = Pattern.compile(declRegex).matcher(text);
        assertTrue("declared: " + declRegex, decl.find());
        return PlaybackIsolationScanTest.block(text, text.indexOf('{', decl.end()));
    }
}
