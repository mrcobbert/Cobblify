package com.bedwarsqol.rewind.watch;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S20PacketEntityProperties;
import org.junit.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
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
