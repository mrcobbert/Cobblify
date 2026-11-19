package com.bedwarsqol.rewind.watch;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * TASK D3 (PLAN "R3 Watch full", Phase 14): another player's view is first person from their eyes with their held
 * item, swing and use animation. Vanilla's first-person hand always draws {@code thePlayer} (the camera), so while a
 * player is ridden the camera holds their item (an empty hand: no item, so the bare arm), copies their four swing
 * fields and their item use; the camera's own stack comes back when the view leaves, also when the recording changed
 * that slot meanwhile. The camera is posed at the ridden player's interpolated eye and is not drawn there, and the
 * ridden player becomes the render view entity, which vanilla skips in first person
 * ({@code RenderGlobal.renderEntities} @907-937, javap-verified). Nothing of it sends: the camera's connection is the
 * playback connection and it counts no write.
 *
 * <p>Real vanilla entities on a world built without a constructor (no level, no client), fake profiles only.
 */
public class RiddenHandTest {

    private static final GameProfile VIEWER = new GameProfile(new UUID(1L, 2L), "FakeViewer");

    private PlaybackConnection connection;
    private WorldClient world;
    private RewindCamera cam;
    private EntityOtherPlayerMP ridden;
    private ItemStack stone, bow, dirt;

    @BeforeClass
    public static void boot() {
        Bootstrap.register();
    }

    @Before
    public void entities() throws Exception {
        connection = PlaybackConnection.open(null, VIEWER);
        world = (WorldClient) allocate(WorldClient.class);
        set(World.class, world, "provider", new WorldProviderSurface());
        set(World.class, world, "worldInfo", allocate(WorldInfo.class));
        set(World.class, world, "worldBorder", new WorldBorder());
        cam = new RewindCamera(null, world, connection.handler(), null);
        ridden = new EntityOtherPlayerMP(world, new GameProfile(new UUID(3L, 4L), "FakeOther"));
        stone = new ItemStack(Blocks.stone, 12);
        dirt = new ItemStack(Blocks.dirt, 3);
        bow = new ItemStack(Items.bow);
        cam.inventory.currentItem = 2;
        cam.inventory.mainInventory[2] = stone;
        ridden.inventory.currentItem = 0;
        ridden.inventory.mainInventory[0] = bow;
    }

    @After
    public void close() {
        connection.close();
    }

    private void swing(EntityOtherPlayerMP p, int progressInt, float prev, float now) {
        p.swingProgressInt = progressInt;
        p.isSwingInProgress = progressInt > 0;
        p.prevSwingProgress = prev;
        p.swingProgress = now;
    }

    @Test
    public void theCameraHoldsTheRiddenPlayersItemSwingAndUse() {
        RiddenView.Hand hand = new RiddenView.Hand();
        swing(ridden, 3, 0.33f, 0.5f);
        ridden.setItemInUse(bow, 71990);
        hand.apply(cam, ridden);
        assertSame("their held item", bow, cam.getHeldItem());
        assertEquals("your selected slot is kept", 2, cam.inventory.currentItem);
        assertEquals(3, cam.swingProgressInt);
        assertTrue(cam.isSwingInProgress);
        assertEquals(0.33f, cam.prevSwingProgress, 0f);
        assertEquals(0.5f, cam.swingProgress, 0f);
        assertEquals("the swing the hand draws", ridden.getSwingProgress(0.4f), cam.getSwingProgress(0.4f), 0f);
        assertSame("their bow drawn", bow, cam.getItemInUse());
        assertEquals(71990, cam.getItemInUseCount());

        // the next frame: the draw goes on, the swing ends
        ridden.clearItemInUse();
        ridden.setItemInUse(bow, 71985);
        swing(ridden, 0, 0.9f, 0f);
        hand.apply(cam, ridden);
        assertEquals(71985, cam.getItemInUseCount());
        assertFalse(cam.isSwingInProgress);
        ridden.clearItemInUse();
        hand.apply(cam, ridden);
        assertFalse("they let go: the camera stops too", cam.isUsingItem());

        hand.restore(cam);
        assertSame("your own stack is back", stone, cam.getHeldItem());
        assertEquals(2, cam.inventory.currentItem);
        assertFalse(cam.isUsingItem());
        assertFalse("the hand at rest", cam.isSwingInProgress);
        assertEquals(0f, cam.swingProgress, 0f);
    }

    @Test
    public void anEmptyHandShowsTheBareArmWithoutTouchingAnySlot() {
        RiddenView.Hand hand = new RiddenView.Hand();
        ridden.inventory.mainInventory[0] = null;
        hand.apply(cam, ridden);
        assertNull("no item: vanilla draws the bare arm", cam.getHeldItem());
        assertSame("the slot itself is untouched", stone, cam.inventory.mainInventory[2]);
        assertEquals(RiddenView.Hand.EMPTY_HAND_SLOT, cam.inventory.currentItem);

        // they pick the bow up again
        ridden.inventory.mainInventory[0] = bow;
        hand.apply(cam, ridden);
        assertSame(bow, cam.getHeldItem());
        assertEquals(2, cam.inventory.currentItem);
        ridden.inventory.mainInventory[0] = null;
        hand.apply(cam, ridden);
        assertNull(cam.getHeldItem());
        assertSame("putting the hand down put your stack back", stone, cam.inventory.mainInventory[2]);

        hand.restore(cam);
        assertEquals("your selected slot is back", 2, cam.inventory.currentItem);
        assertSame(stone, cam.getHeldItem());
    }

    @Test
    public void whatTheRecordingWritesMeanwhileWinsOverTheRememberedStack() {
        RiddenView.Hand hand = new RiddenView.Hand();
        hand.apply(cam, ridden);
        assertSame(bow, cam.getHeldItem());

        // an S2F (or the sink's own hand) writes your slot 2 while you watch them
        cam.inventory.mainInventory[2] = dirt;
        hand.apply(cam, ridden);
        assertSame(bow, cam.getHeldItem());
        hand.restore(cam);
        assertSame("the newer stack, not the one from before", dirt, cam.inventory.mainInventory[2]);

        // your recorded selection moves to slot 4 (the sink sets currentItem every tick): slot 2 is yours again
        cam.inventory.mainInventory[2] = stone;
        hand.apply(cam, ridden);
        cam.inventory.currentItem = 4;
        hand.apply(cam, ridden);
        assertSame(stone, cam.inventory.mainInventory[2]);
        assertSame(bow, cam.inventory.mainInventory[4]);
        assertEquals(4, cam.inventory.currentItem);

        // they hold nothing while the sink keeps your selection at 4; then the empty slot 4 gets dirt
        ridden.inventory.mainInventory[0] = null;
        hand.apply(cam, ridden);
        assertNull(cam.inventory.mainInventory[4]);
        cam.inventory.currentItem = 4; // the sink's next tick
        cam.inventory.mainInventory[4] = dirt;
        hand.apply(cam, ridden);
        hand.restore(cam);
        assertSame(dirt, cam.inventory.mainInventory[4]);
        assertEquals(4, cam.inventory.currentItem);

        // a new camera (S01/S07) is a new object: nothing of the old one is put on it
        RewindCamera fresh = new RewindCamera(null, world, connection.handler(), null);
        fresh.inventory.currentItem = 1;
        hand.apply(cam, ridden);
        hand.apply(fresh, ridden);
        hand.restore(fresh);
        assertEquals(1, fresh.inventory.currentItem);
        assertNull(fresh.getHeldItem());
    }

    @Test
    public void theCameraIsPosedAtTheRiddenEyeAndNotDrawnWhileTheyAreShown() throws Exception {
        Minecraft mc = fakeClient();
        RiddenView.Hand hand = new RiddenView.Hand();
        ridden.prevPosX = 10;
        ridden.posX = 11;
        ridden.prevPosY = 64;
        ridden.posY = 65;
        ridden.prevPosZ = -4;
        ridden.posZ = -2;
        ridden.prevRotationYaw = 80;
        ridden.rotationYaw = 100;
        ridden.prevRotationPitch = 10;
        ridden.rotationPitch = 20;
        assertTrue("drawn before", cam.isInRangeToRender3d(cam.posX + 1, cam.posY, cam.posZ));

        RiddenView.show(mc, cam, ridden, hand, 0.25f);
        assertSame("vanilla views from them", ridden, mc.getRenderViewEntity());
        assertEquals(10.25, cam.posX, 1e-9);
        assertEquals(64.25 + ridden.getEyeHeight(), cam.posY + cam.getEyeHeight(), 1e-9);
        assertEquals(-3.5, cam.posZ, 1e-9);
        assertEquals(85f, cam.rotationYaw, 1e-4f);
        assertEquals(12.5f, cam.rotationPitch, 1e-4f);
        assertEquals("no partial-tick drift", cam.posX, cam.prevPosX, 0);
        assertFalse("the camera is not drawn at their eye",
                cam.isInRangeToRender3d(cam.posX + 0.5, cam.posY, cam.posZ));
        assertSame(bow, cam.getHeldItem());

        RiddenView.hide(mc, cam, hand);
        assertSame("back to the camera", cam, mc.getRenderViewEntity());
        assertSame(stone, cam.getHeldItem());
        assertTrue(cam.isInRangeToRender3d(cam.posX + 1, cam.posY, cam.posZ));
    }

    /** The plan's isolation rule for Phase 14: selecting and riding a player writes nothing to the playback channel. */
    @Test
    public void ridingAPlayerWritesNothingToThePlaybackChannel() throws Exception {
        Minecraft mc = fakeClient();
        RiddenView.Hand hand = new RiddenView.Hand();
        for (int frame = 0; frame < 20; frame++) {
            swing(ridden, frame % 6, frame / 20f, (frame + 1) / 20f);
            if (frame == 5) ridden.setItemInUse(bow, 72000);
            if (frame == 12) ridden.clearItemInUse();
            if (frame == 15) ridden.inventory.mainInventory[0] = null;
            RiddenView.show(mc, cam, ridden, hand, frame / 20f);
        }
        RiddenView.hide(mc, cam, hand);
        assertEquals("nothing reached the playback channel", 0, connection.discarded());
        assertTrue(connection.channel().outboundMessages().isEmpty());
    }

    @Test
    public void theRiddenViewsSourcesNeverSend() throws Exception {
        String[] files = {"ornithe/src/main/java/com/bedwarsqol/rewind/watch/RiddenView.java",
                "ornithe/src/main/java/com/bedwarsqol/mixin/GuiIngameRewindMixin.java",
                "common/src/main/java/com/bedwarsqol/rewind/watch/PlayerListModel.java",
                "common/src/main/java/com/bedwarsqol/rewind/watch/PickRay.java"};
        for (String f : files) {
            File file = repoFile(f);
            assertTrue(f, file.isFile());
            String src = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertFalse(f + " sends", src.matches("(?s).*(sendPacket|addToSendQueue|writeAndFlush|sendQueue"
                    + "|C18PacketSpectate|C02PacketUseEntity|displayGuiScreen|swingItem\\s*\\().*"));
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** A client with no window: an entity renderer (shaders off, as before GL starts) and the camera as player. */
    private Minecraft fakeClient() throws Exception {
        Minecraft mc = (Minecraft) allocate(Minecraft.class);
        mc.entityRenderer = (EntityRenderer) allocate(EntityRenderer.class);
        mc.thePlayer = cam;
        mc.theWorld = world;
        mc.setRenderViewEntity(cam);
        return mc;
    }

    private static File repoFile(String path) {
        File dir = new File("").getAbsoluteFile();
        while (dir != null && !new File(dir, "common").isDirectory()) dir = dir.getParentFile();
        return new File(dir, path);
    }

    private static void set(Class<?> owner, Object target, String field, Object value) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** An instance of {@code type} with no constructor run ({@code sun.misc.Unsafe.allocateInstance}). */
    private static Object allocate(Class<?> type) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Field f = u.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return u.getMethod("allocateInstance", Class.class).invoke(f.get(null), type);
    }
}
