package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.rewind.RewindFormat;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.storage.WorldInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Rewind parity Phase 16: the rules Phases 7, 12, 14 and 15 each own, checked together across the three views
 * (your view, the free camera, another player's view) with the real {@link ViewState}, {@link RewindCamera},
 * {@link RiddenView}, {@link CameraStatePart}, {@link FovPart} and {@link ViewGameType}:
 * <ul>
 *   <li>coordinator amendment 1: the camera is a spectator, so invisible entities are translucent, only in your view
 *       while the recorded game mode is spectator; an empty hand shows the bare arm in your view (unless the recorded
 *       player is invisible or a spectator) and in another player's view, never in the free camera;</li>
 *   <li>FOV: exactly one source per view: your view of a v1 recording the derived FOV (Phase 7), your view of a v2
 *       recording the recorded FOV (Phase 12), the free camera neither (the FOV setting), another player's view
 *       vanilla's own on the ridden player (the render view entity);</li>
 *   <li>recorded F1 and the clean view (Phases 12 and 15): one rule hides the HUD and the hand, the user's F5 or Clean
 *       View Key override it until key 1, a recorded screen keeps the overlay as live, and the viewer bar is
 *       independent of all of it.</li>
 * </ul>
 */
public class ViewParityTest {

    private static final GameProfile VIEWER = new GameProfile(new UUID(1L, 2L), "FakeViewer");
    private static final int RIDDEN_ID = 77;

    private PlaybackConnection connection;
    private WorldClient world;
    private RewindCamera cam;
    private EntityOtherPlayerMP ridden;
    private Minecraft mc;
    private Field theMinecraft;
    private Object previousMc;
    private Field sinkField;
    private Object previousSink;
    private PlaybackSink sink;
    private ClientSettings savedConfig;

    @BeforeClass
    public static void boot() {
        Bootstrap.register();
    }

    @Before
    public void client() throws Exception {
        connection = PlaybackConnection.open(null, VIEWER);
        world = (WorldClient) allocate(WorldClient.class);
        set(World.class, world, "provider", new WorldProviderSurface());
        set(World.class, world, "worldInfo", allocate(WorldInfo.class));
        set(World.class, world, "worldBorder", new WorldBorder());
        cam = new RewindCamera(null, world, connection.handler(), null);
        ridden = new EntityOtherPlayerMP(world, new GameProfile(new UUID(3L, 4L), "FakeOther"));
        ridden.setEntityId(RIDDEN_ID);
        mc = (Minecraft) allocate(Minecraft.class);
        mc.entityRenderer = (EntityRenderer) allocate(EntityRenderer.class);
        mc.thePlayer = cam;
        mc.theWorld = world;
        mc.setRenderViewEntity(cam);
        theMinecraft = Minecraft.class.getDeclaredField("theMinecraft");
        theMinecraft.setAccessible(true);
        previousMc = theMinecraft.get(null);
        theMinecraft.set(null, mc);
        mc.playerController = new PlayerControllerMP(mc, connection.handler());
        Field instance = RewindPlayback.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        sinkField = RewindPlayback.class.getDeclaredField("sink");
        sinkField.setAccessible(true);
        previousSink = sinkField.get(instance.get(null));
        sink = (PlaybackSink) allocate(PlaybackSink.class);
        sinkField.set(instance.get(null), sink);
        savedConfig = BedwarsQol.config;
        BedwarsQol.config = null; // vanilla's FOV modifier (Dynamic FOV is Cobblify's option)
    }

    @After
    public void restore() throws Exception {
        Field instance = RewindPlayback.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        sinkField.set(instance.get(null), previousSink);
        theMinecraft.set(null, previousMc);
        RewindPlayback.view().reset();
        BedwarsQol.config = savedConfig;
        connection.close();
    }

    private enum V { YOU, FREE, RIDDEN }

    /** The view as the viewer's keys and the ridden view set it, on the session's own view state. */
    private void select(V v, RiddenView.Hand hand) {
        ViewState s = RewindPlayback.view();
        RiddenView.hide(mc, cam, hand);
        if (v == V.YOU) {
            s.selectYou();
        } else if (v == V.FREE) {
            s.selectFree();
        } else {
            s.ride(RIDDEN_ID);
            RiddenView.show(mc, cam, ridden, hand, 1f);
        }
    }

    /** The recorded game mode as the sink keeps it, from an S01 (or none). */
    private ViewGameType recordedMode(WorldSettings.GameType mode) throws Exception {
        ViewGameType t = new ViewGameType();
        if (mode != WorldSettings.GameType.NOT_SET) {
            byte[] s01 = RecordingFixture.joinGame(0);
            s01[5] = (byte) mode.getID();
            assertTrue(t.onPacket(s01, 0, s01.length));
        }
        set(PlaybackSink.class, sink, "recordedGameType", WorldSettings.GameType.getByID(t.recorded()));
        return t;
    }

    // ------------------------------------------------------------------ amendment 1

    @Test
    public void spectatorTranslucencyAndTheBareArmByViewAndRecordedMode() throws Exception {
        WorldSettings.GameType[] modes = {WorldSettings.GameType.NOT_SET, WorldSettings.GameType.SURVIVAL,
                WorldSettings.GameType.CREATIVE, WorldSettings.GameType.ADVENTURE, WorldSettings.GameType.SPECTATOR};
        EntityArmorStand stand = new EntityArmorStand(null);
        stand.setInvisible(true);
        RiddenView.Hand hand = new RiddenView.Hand();
        cam.inventory.currentItem = 0;
        cam.inventory.mainInventory[0] = null; // your recorded hand is empty
        ridden.inventory.currentItem = 0;
        ridden.inventory.mainInventory[0] = null; // and so is theirs
        for (V v : V.values()) {
            for (WorldSettings.GameType mode : modes) {
                for (boolean invisible : new boolean[]{false, true}) {
                    String at = v + " / " + mode + (invisible ? " / recorded invisible" : "");
                    ViewGameType type = recordedMode(mode);
                    select(v, hand);
                    ViewState s = RewindPlayback.view();
                    mc.playerController.setGameType(WorldSettings.GameType.getByID(type.forView(s.view())));
                    CameraStatePart part = new CameraStatePart();
                    part.step(0, 64, 0, RewindFormat.FLAG_ON_GROUND | (invisible ? RewindFormat.FLAG_INVISIBLE : 0),
                            true, -1, 1f);
                    part.apply(cam, s.yourView());

                    boolean spectator = v == V.YOU && mode == WorldSettings.GameType.SPECTATOR;
                    assertEquals(at, spectator, cam.isSpectator());
                    assertEquals("translucent invisibles at " + at, spectator, !stand.isInvisibleToPlayer(cam));
                    boolean controllerSpectator = v == V.FREE || spectator;
                    assertEquals("controller at " + at, controllerSpectator, mc.playerController.isSpectator());
                    // EntityRenderer.renderHand runs for a non-spectator controller; ItemRenderer draws the bare arm
                    // for an empty hand of a visible camera; RenderPlayer shows its arm unless the camera is a
                    // spectator.
                    boolean bareArm = !mc.playerController.isSpectator() && cam.getHeldItem() == null
                            && !cam.isInvisible() && !cam.isSpectator();
                    boolean expected = v == V.RIDDEN || v == V.YOU && !spectator && !invisible;
                    assertEquals("bare arm at " + at, expected, bareArm);
                }
            }
        }
    }

    // ------------------------------------------------------------------ FOV

    @Test
    public void exactlyOneFovSourcePerView() throws Exception {
        final float derived = 1.15f; // sprinting: what DerivedFov gives
        final float recorded = 95.5f;
        final float vanilla = 70f;
        RiddenView.Hand hand = new RiddenView.Hand();
        for (int version = 1; version <= 2; version++) {
            boolean v2 = version == 2;
            for (V v : V.values()) {
                String at = "v" + version + " " + v;
                select(v, hand);
                ViewState s = RewindPlayback.view();
                // CameraStatePart.tick: the derived value only when the recording holds no FOV records
                CameraStatePart part = new CameraStatePart();
                part.step(0, 64, 0, RewindFormat.FLAG_ON_GROUND, true, -1, v2 ? 1f : derived);
                part.apply(cam, s.yourView());
                // FovPart.frame: the recorded value in your view of a recording with FOV records, else none
                float fromRecording = v2 && s.yourView() ? recorded : Float.NaN;
                float world = FovPart.choose(vanilla, true, true, s.yourView(), fromRecording);

                boolean renderedFromCamera = mc.getRenderViewEntity() == cam;
                boolean derivedApplies = renderedFromCamera && cam.getFovModifier() != 1f;
                boolean recordedApplies = world != vanilla;
                boolean riddenOwn = mc.getRenderViewEntity() == ridden;
                int sources = (derivedApplies ? 1 : 0) + (recordedApplies ? 1 : 0) + (riddenOwn ? 1 : 0);
                if (v == V.FREE) {
                    assertEquals("the free camera: the FOV setting only, " + at, 0, sources);
                    assertEquals(1f, cam.getFovModifier(), 0f);
                    assertEquals(vanilla, world, 0f);
                } else {
                    assertEquals("exactly one FOV source at " + at, 1, sources);
                }
                if (v == V.YOU) {
                    assertEquals(at, !v2, derivedApplies);
                    assertEquals(at, v2, recordedApplies);
                    if (v2) {
                        assertEquals(recorded, world, 0f);
                    } else {
                        assertEquals(derived, cam.getFovModifier(), 1e-6f);
                    }
                }
                if (v == V.RIDDEN) {
                    assertTrue("vanilla reads the ridden player's own modifier", riddenOwn);
                    assertEquals("the camera's modifier stays neutral", 1f, cam.getFovModifier(), 0f);
                }
                // the hand pass (useFOVSetting false) is never replaced
                assertEquals(vanilla, FovPart.choose(vanilla, false, true, s.yourView(), recorded), 0f);
            }
        }
        // FovPart's own state: a view switch away from your view drops the recorded value at once
        FovPart fov = new FovPart();
        ExtTrack none = ExtTrack.EMPTY;
        fov.update(none, 0, 0L, false);
        assertTrue(Float.isNaN(fov.recorded()));
    }

    // ------------------------------------------------------------------ the free camera has no body (review code-2 O5)

    /**
     * Review code-2 O5: since amendment 1 the free camera is no spectator (so invisible entities stay invisible), but
     * it must not take part in client-side physics either: arrows, snowballs and fish hooks test
     * {@code canBeCollidedWith} after vanilla's spectator filter (javap: {@code EntityArrow/EntityThrowable.onUpdate}),
     * and an XP orb's target ({@code World.getClosestPlayerToEntity}) skips the free camera for the next closest
     * player. Your view and another player's view keep vanilla's answers.
     */
    @Test
    public void theFreeCameraIsNotHitByProjectilesNorAttractsOrbsButStaysNoSpectator() throws Exception {
        set(World.class, world, "playerEntities", new java.util.ArrayList<Object>(java.util.Arrays.asList(cam, ridden)));
        set(PlaybackSink.class, sink, "recordedGameType", WorldSettings.GameType.SURVIVAL);
        net.minecraft.entity.item.EntityXPOrb orb = new net.minecraft.entity.item.EntityXPOrb(world, 1, 64, 0, 3);
        ViewState v = RewindPlayback.view();
        for (V view : V.values()) {
            select(view, new RiddenView.Hand());
            cam.setPosition(0, 64, 0);
            ridden.setPosition(5, 64, 0);
            boolean free = view == V.FREE;
            assertEquals(view + ": collides", !free, cam.canBeCollidedWith());
            assertFalse(view + ": never a spectator for the invisible-entity rule outside a recorded spectator",
                    cam.isSpectator());
            assertSame(view + ": the orb's target", free ? ridden : cam,
                    RewindCamera.orbTarget(world, orb, 8.0, cam));
            assertSame("another player picked by vanilla stays", ridden,
                    RewindCamera.orbTarget(world, orb, 8.0, ridden));
        }
        select(V.FREE, new RiddenView.Hand());
        ridden.setPosition(20, 64, 0);
        assertSame("no other player in range: no target", null, RewindCamera.orbTarget(world, orb, 8.0, cam));
        assertSame(null, RewindCamera.orbTarget(world, orb, 8.0, null));
        v.selectYou();
    }

    // ------------------------------------------------------------------ recorded F1 and the clean view

    @Test
    public void recordedF1AndTheCleanViewHideTheHudAndHandAndTheUserOverridesUntilKeyOne() throws Exception {
        ViewState v = new ViewState();
        v.recorded(0, RewindFormat.VIEW_HIDE_GUI, RewindFormat.SCREEN_NONE);
        assertTrue("recorded F1: the hand is hidden (vanilla's hideGUI)", v.hideGui());
        assertTrue("and the whole overlay is skipped, as live with no screen open", v.hidesEveryHud());

        v.recorded(0, RewindFormat.VIEW_HIDE_GUI, RewindFormat.SCREEN_CHEST);
        assertTrue("a recorded chest under F1: still no hand", v.hideGui());
        assertFalse("the overlay is drawn, as vanilla drew it with a screen open", v.hidesEveryHud());
        assertEquals(RewindFormat.SCREEN_CHEST, v.screenKind());

        v.recorded(0, RewindFormat.VIEW_HIDE_GUI, RewindFormat.SCREEN_NONE);
        assertTrue(v.cyclePerspective());
        assertFalse("F5 overrides the recorded F1 (Q2)", v.hideGui());
        assertFalse(v.hidesEveryHud());
        assertEquals(1, v.perspective());

        v.toggleClean();
        assertTrue("the Clean View Key hides everything", v.hideGui() && v.hidesEveryHud());
        assertFalse("and the recorded HUD", v.recordedHud());
        v.toggleClean();
        assertFalse("pressed again: shown, the recorded F1 stays overridden until key 1", v.hideGui());

        v.selectYou();
        assertTrue("key 1 follows the recording again", v.hideGui() && v.hidesEveryHud());
        assertEquals(0, v.perspective());

        v.selectFree();
        assertFalse("the free camera follows no recorded F1", v.hideGui());
        v.toggleClean();
        assertTrue("the clean view works in every view", v.hidesEveryHud());

        // the viewer bar is the viewer screen's own: a mouse move shows it whatever the view hides
        BarFade fade = new BarFade();
        fade.update(1_000L, false, false);
        assertFalse(fade.visible());
        fade.mouseMoved(1_000L);
        fade.update(1_000L + BarFade.FADE_IN_MS, false, false);
        assertTrue(fade.visible());
        String viewer = source("ornithe/src/main/java/com/bedwarsqol/rewind/watch/RewindViewerScreen.java");
        assertFalse("the bar never asks whether the HUD is hidden", viewer.contains("hidesEveryHud")
                || viewer.contains("hideGui"));
        String hud = source("ornithe/src/main/java/com/bedwarsqol/mixin/GuiIngameHudMixin.java");
        assertTrue("one rule skips the overlay: the clean view or recorded F1 with no recorded screen",
                hud.contains("if (HudPlayback.skipsOverlay(Minecraft.getMinecraft()))"));
        assertNotEquals(-1, source("common/src/main/java/com/bedwarsqol/rewind/watch/PlaybackSession.java")
                .indexOf("ops.setHideGui(view.hideGui());"));
    }

    @Test
    public void theSessionsViewIsTheOneTheCameraAsks() {
        assertSame(RewindPlayback.session().view(), RewindPlayback.view());
    }

    // ------------------------------------------------------------------ helpers

    static String source(String path) throws Exception {
        File dir = new File("").getAbsoluteFile();
        while (dir != null && !new File(dir, "common").isDirectory()) dir = dir.getParentFile();
        return new String(Files.readAllBytes(new File(dir, path).toPath()), StandardCharsets.UTF_8);
    }

    static void set(Class<?> owner, Object target, String field, Object value) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    static Object allocate(Class<?> type) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Field f = u.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return u.getMethod("allocateInstance", Class.class).invoke(f.get(null), type);
    }
}
