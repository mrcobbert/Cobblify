package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.mixin.EntityRendererFovInvoker;
import com.bedwarsqol.mixin.MinecraftTimerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ResourceLocation;

/**
 * Another player's view (PLAN "R3 Watch full", Phase 14; TASK D1-D4; G8, G37, G44): first person from their eyes,
 * with their held item, swing and item use, their name in their team's colour at the top (drawn by the viewer), and
 * no hearts, hunger, XP bar, hotbar, recorded screens or Cobblify HUD.
 *
 * <p><b>How.</b> Vanilla's spectate path: the ridden player becomes the render view entity
 * ({@code Minecraft.setRenderViewEntity}), so vanilla takes the eye, the head turn and the interpolation from them and
 * does not draw them in first person ({@code RenderGlobal.renderEntities} skips the render view entity when
 * {@code thirdPersonView == 0}, javap-verified; {@link ViewState#perspective()} is 0 outside your view). Each tick and
 * frame the camera ({@code thePlayer}) is posed at their interpolated eye, so the sound listener, the hand's lighting
 * and turn follow them; the camera itself is never drawn ({@code RewindCamera.isInRangeToRender3d}), so it shows
 * nowhere around their eye.
 * The first-person hand always draws {@code thePlayer}, so the camera holds their item and copies their swing and use
 * ({@link Hand}). The controller's game type is CREATIVE in this view ({@link ViewGameType#forView(ViewState.View)}:
 * hand, but no hearts, hunger or XP bar, javap-verified) and {@code GuiIngameRewindMixin} cancels the hotbar
 * ({@link #hidesHotbar()}). Recorded screens are your view's only ({@link ViewState#screenKind()}).
 *
 * <p><b>Who.</b> {@link PlayerListModel}, filled from the playback world: players with a tab entry, minus the camera
 * and the recorded player's puppet, by team then name. Key 3 / Shift+3 ({@link #cyclePlayer}), a camera menu row
 * ({@link #ridePlayer}) or a click in the free camera ({@link #pick}, {@link PickRay}) ride one.
 *
 * <p><b>Leaving.</b> Key 1 or 2, a menu row, or the ridden player leaving the world: once playback is settled (not
 * rebuilding or catching up after a seek, when they may not be spawned yet) the view becomes the free camera where
 * the view was ({@link PlayerListModel#follow}). A new world (S01/S07) makes a new camera and vanilla points the
 * render view back at it; the view rides again as soon as the player is in the new world.
 *
 * <p><b>Isolation.</b> Nothing here sends or opens a screen: riding is a local change of the render view entity, the
 * camera's pose, hand fields and inventory slot (put back on leaving), never vanilla's spectate packet. With nothing
 * playing every entry point returns at once. Register once with {@link #register()} (Phase 16 calls it, before any
 * part that answers {@link #playerInfo} for the camera, so the ridden player's skin and arm model win in this view).
 */
public final class RiddenView implements PlaybackPart {

    private static final RiddenView INSTANCE = new RiddenView();

    private final PlayerListModel players = new PlayerListModel();
    private final Hand hand = new Hand();
    private final double[] pose = new double[5];
    private final double[] dir = new double[3];
    /** The player the view shows now (the render view entity), or null. */
    private EntityPlayer shown;
    /** The camera whose pose and hand this view changed, or null. */
    private RewindCamera shownOn;

    private RiddenView() {
    }

    /** Registers the ridden view as a playback part (once; client thread). */
    public static void register() {
        RewindPlayback.register(INSTANCE);
    }

    // ================================================================== viewer API (client thread)

    /** {@code GuiIngameRewindMixin}: the hotbar is not drawn while a recording plays in another player's view. */
    public static boolean hidesHotbar() {
        return RewindPlayback.active() && RewindPlayback.view().riddenEntityId() >= 0;
    }

    /** The players the camera menu lists, refreshed from the playback world now. */
    static PlayerListModel players() {
        INSTANCE.refresh();
        return INSTANCE.players;
    }

    /** The menu index of the ridden player in the last refreshed list, or -1. */
    static int ridingIndex() {
        int id = RewindPlayback.active() ? RewindPlayback.view().riddenEntityId() : -1;
        return id < 0 ? -1 : INSTANCE.players.indexOf(id);
    }

    /** A camera menu row: rides player {@code index} of the last refreshed list. @return true if one was ridden */
    static boolean ridePlayer(int index) {
        if (!RewindPlayback.active() || index < 0 || index >= INSTANCE.players.size()) {
            return false;
        }
        INSTANCE.ride(INSTANCE.players.get(index).entityId);
        return true;
    }

    /** Player {@code index}'s skin (the camera menu's face), or null for the default one. */
    static ResourceLocation skin(int index) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || index < 0 || index >= INSTANCE.players.size()) {
            return null;
        }
        Entity e = mc.theWorld.getEntityByID(INSTANCE.players.get(index).entityId);
        return e instanceof AbstractClientPlayer ? ((AbstractClientPlayer) e).getLocationSkin() : null;
    }

    /**
     * Key 2 or the menu's free camera while riding: the free camera starts at the ridden view's eye, as it would start
     * at your view's. @return false when not riding (the caller selects the free camera as usual)
     */
    static boolean freeHere() {
        if (!RewindPlayback.active()) {
            return false;
        }
        int id = RewindPlayback.view().riddenEntityId();
        if (id < 0) {
            return false;
        }
        if (!INSTANCE.players.lastPose(id, INSTANCE.pose)) {
            return false;
        }
        INSTANCE.toFree(INSTANCE.pose);
        return true;
    }

    /**
     * A click (not a drag) in the free camera at ({@code x}, {@code y}) of a {@code width} x {@code height} screen:
     * rides the nearest player under the cursor within {@link PickRay#REACH} blocks. @return true if one was ridden
     */
    static boolean pick(float x, float y, float width, float height) {
        if (!RewindPlayback.active() || !RewindPlayback.view().freeCamera()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        RewindCamera cam = camera(mc);
        if (cam == null || mc.theWorld == null) {
            return false;
        }
        INSTANCE.refresh();
        PlayerListModel list = INSTANCE.players;
        int n = list.size();
        if (n == 0) {
            return false;
        }
        double[] boxes = new double[n * 6];
        int[] ids = new int[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            Entity e = mc.theWorld.getEntityByID(list.get(i).entityId);
            if (e == null) continue;
            AxisAlignedBB bb = e.getEntityBoundingBox();
            double b = PickRay.BORDER;
            int o = count * 6;
            boxes[o] = bb.minX - b;
            boxes[o + 1] = bb.minY - b;
            boxes[o + 2] = bb.minZ - b;
            boxes[o + 3] = bb.maxX + b;
            boxes[o + 4] = bb.maxY + b;
            boxes[o + 5] = bb.maxZ + b;
            ids[count++] = e.getEntityId();
        }
        float partial = ((MinecraftTimerAccessor) mc).bedwarsqol$getTimer().renderPartialTicks;
        float fov = ((EntityRendererFovInvoker) mc.entityRenderer).bedwarsqol$getFOVModifier(partial, true);
        PickRay.direction(cam.rotationYaw, cam.rotationPitch, fov, width, height, x, y, INSTANCE.dir);
        int hit = PickRay.nearest(cam.posX, cam.posY + cam.getEyeHeight(), cam.posZ, INSTANCE.dir, boxes, count,
                PickRay.REACH);
        if (hit < 0) {
            return false;
        }
        INSTANCE.ride(ids[hit]);
        return true;
    }

    // ================================================================== PlaybackPart

    /** Keys 3 / Shift+3: the next or previous player in the list. */
    @Override
    public boolean cyclePlayer(int direction) {
        refresh();
        int id = players.cycle(RewindPlayback.view().riddenEntityId(), direction);
        if (id < 0) {
            return false;
        }
        ride(id);
        return true;
    }

    @Override
    public void restart() {
        forget();
    }

    /** A new world and camera: vanilla points the render view at the new camera; ride again once they are there. */
    @Override
    public void boundary() {
        forget();
        follow(1f, false);
    }

    @Override
    public void tick(long tick, long timeUs) {
        follow(1f, false);
    }

    @Override
    public void frame(long timeUs, float partial) {
        follow(partial, true);
    }

    @Override
    public void viewChanged() {
        if (RewindPlayback.view().riddenEntityId() < 0) {
            leave();
        } else {
            follow(1f, false);
        }
    }

    /** The camera's tab entry while a player is shown: theirs, so the bare arm has their skin and arm model. */
    @Override
    public NetworkPlayerInfo playerInfo(AbstractClientPlayer player) {
        EntityPlayer e = shown;
        if (e == null || !(player instanceof RewindCamera)) {
            return null;
        }
        NetHandlerPlayClient h = Minecraft.getMinecraft().getNetHandler();
        return h == null ? null : h.getPlayerInfo(e.getUniqueID());
    }

    @Override
    public void exit() {
        leave();
        players.clear();
        players.absent(-1, true);
    }

    // ================================================================== the view

    private void ride(int entityId) {
        RewindPlayback.view().ride(entityId);
        RewindPlayback.viewStateChanged(); // game type, the camera's view state, then the parts (viewChanged)
    }

    /**
     * The ridden view now: shows the ridden player when they are in the world; when they are gone, waits while
     * playback is not settled, else ({@code mayLeave}, a drawn frame) becomes the free camera where the view was.
     */
    private void follow(float partial, boolean mayLeave) {
        int id = RewindPlayback.view().riddenEntityId();
        if (id < 0) {
            if (shownOn != null) {
                leave();
            }
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        RewindCamera cam = camera(mc);
        if (cam == null) {
            return;
        }
        EntityPlayer e = ridden(mc.theWorld, id);
        boolean settled = mayLeave && RewindPlayback.session().state() == PlaybackSession.State.WATCHING;
        if (e != null) {
            if (shownOn != null && shownOn != cam) {
                forget();
            }
            show(mc, cam, e, hand, partial);
            shown = e;
            shownOn = cam;
            players.follow(id, true, settled, cam.posX, cam.posY + cam.getEyeHeight(), cam.posZ, cam.rotationYaw,
                    cam.rotationPitch);
            return;
        }
        if (shown != null) {
            hide(mc, cam, hand); // not on a removed entity's last frame while waiting
            shown = null;
            shownOn = null;
        }
        if (players.absent(id, settled) == PlayerListModel.Step.LEAVE) {
            if (!players.lastPose(id, pose)) {
                pose[0] = cam.posX;
                pose[1] = cam.posY + cam.getEyeHeight();
                pose[2] = cam.posZ;
                pose[3] = cam.rotationYaw;
                pose[4] = cam.rotationPitch;
            }
            toFree(pose);
        }
    }

    /** The free camera at {pose}, and the camera there at once (this frame is drawn from it). */
    private void toFree(double[] p) {
        CameraController.selectFreeCameraAt(p[0], p[1], p[2], (float) p[3], (float) p[4]);
        RewindCamera cam = camera(Minecraft.getMinecraft());
        if (cam != null) {
            cam.pose(p[0], p[1], p[2], (float) p[3], (float) p[4]);
        }
    }

    /** Back to the camera's own view (key 1 or 2, the player gone, the session ending). */
    private void leave() {
        if (shownOn != null) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer == shownOn) {
                hide(mc, shownOn, hand);
            }
        }
        shown = null;
        shownOn = null;
        hand.reset();
    }

    /** A new connection or world: the old camera and entities are gone; nothing of them is put back. */
    private void forget() {
        shown = null;
        shownOn = null;
        hand.reset();
    }

    /** The playback world's players another view can ride, by team then name. */
    private void refresh() {
        players.clear();
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc == null ? null : mc.theWorld;
        NetHandlerPlayClient handler = mc == null ? null : mc.getNetHandler();
        if (world == null || handler == null || !RewindPlayback.active()) {
            return;
        }
        Scoreboard board = world.getScoreboard();
        for (EntityPlayer p : world.playerEntities) {
            if (!rideable(p) || handler.getPlayerInfo(p.getUniqueID()) == null) {
                continue;
            }
            String name = p.getName();
            ScorePlayerTeam team = board == null ? null : board.getPlayersTeam(name);
            players.add(p.getEntityId(), name, team == null ? null : team.getRegisteredName(),
                    team == null ? null : ScorePlayerTeam.formatPlayerName(team, name));
        }
    }

    /** Another player: not the camera, not the recorded player's puppet, alive. */
    private static boolean rideable(Entity e) {
        return e instanceof EntityOtherPlayerMP && !(e instanceof RewindSelfPuppet) && !e.isDead;
    }

    private static EntityPlayer ridden(WorldClient world, int id) {
        Entity e = world == null ? null : world.getEntityByID(id);
        return rideable(e) ? (EntityPlayer) e : null;
    }

    private static RewindCamera camera(Minecraft mc) {
        return mc != null && mc.thePlayer instanceof RewindCamera ? (RewindCamera) mc.thePlayer : null;
    }

    /**
     * Shows {@code ridden} for this tick or frame: vanilla views from them, the camera stands at their eye (at
     * {@code partial}, as vanilla interpolates the render view entity; the camera is never drawn) and holds their hand.
     */
    static void show(Minecraft mc, RewindCamera cam, EntityPlayer ridden, Hand hand, float partial) {
        if (mc.getRenderViewEntity() != ridden) {
            mc.setRenderViewEntity(ridden);
        }
        double x = ridden.prevPosX + (ridden.posX - ridden.prevPosX) * partial;
        double y = ridden.prevPosY + (ridden.posY - ridden.prevPosY) * partial + ridden.getEyeHeight();
        double z = ridden.prevPosZ + (ridden.posZ - ridden.prevPosZ) * partial;
        float yaw = ridden.prevRotationYaw + (ridden.rotationYaw - ridden.prevRotationYaw) * partial;
        float pitch = ridden.prevRotationPitch + (ridden.rotationPitch - ridden.prevRotationPitch) * partial;
        cam.pose(x, y, z, yaw, pitch);
        hand.apply(cam, ridden);
    }

    /** The camera's own view again: the render view and its own hand. */
    static void hide(Minecraft mc, RewindCamera cam, Hand hand) {
        if (mc.getRenderViewEntity() != cam) {
            mc.setRenderViewEntity(cam);
        }
        hand.restore(cam);
    }

    /**
     * The first-person hand of another player's view on the camera: their held item in the camera's selected slot
     * (the camera's own stack is remembered and put back), their four swing fields and their item use. An empty hand
     * selects {@link #EMPTY_HAND_SLOT}, outside the hotbar, so {@code getCurrentItem()} is null and vanilla draws the
     * bare arm without any slot being written. A slot the recording writes meanwhile (the sink's hand, S2F, a new
     * selected slot) is the camera's newer own stack and is what comes back. A different camera object starts afresh.
     */
    static final class Hand {
        /** {@code InventoryPlayer.currentItem} for an empty hand: past the hotbar, so no stack is held. */
        static final int EMPTY_HAND_SLOT = 9;
        private static final int HOTBAR = InventoryPlayer.getHotbarSize();

        private EntityPlayer on;
        /** The slot holding their item, or -1; the camera's own stack there; the stack put there. */
        private int slot = -1;
        private ItemStack own;
        private ItemStack put;
        /** The camera's own selected slot while {@link #EMPTY_HAND_SLOT} is selected, else -1. */
        private int ownSelected = -1;
        private boolean using;

        void apply(EntityPlayer cam, EntityPlayer ridden) {
            if (on != cam) {
                reset();
                on = cam;
            }
            InventoryPlayer inv = cam.inventory;
            int sel = inv.currentItem;
            if (ownSelected >= 0 && sel == EMPTY_HAND_SLOT) {
                sel = ownSelected; // our own write, not the recording's
            }
            if (sel < 0 || sel >= HOTBAR) {
                sel = 0;
            }
            ItemStack held = ridden.getHeldItem();
            if (slot >= 0 && (held == null || slot != sel)) {
                restoreSlot(inv);
            }
            if (held == null) {
                ownSelected = sel;
                inv.currentItem = EMPTY_HAND_SLOT;
            } else {
                ownSelected = -1;
                inv.currentItem = sel;
                ItemStack cur = inv.mainInventory[sel];
                if (slot != sel || cur != put) {
                    own = cur; // first use of this slot, or the recording wrote it since
                }
                inv.mainInventory[sel] = held;
                slot = sel;
                put = held;
            }
            cam.swingProgressInt = ridden.swingProgressInt;
            cam.isSwingInProgress = ridden.isSwingInProgress;
            cam.prevSwingProgress = ridden.prevSwingProgress;
            cam.swingProgress = ridden.swingProgress;
            if (held != null && ridden.isUsingItem()) {
                int count = ridden.getItemInUseCount();
                if (cam.getItemInUse() != held || cam.getItemInUseCount() != count) {
                    cam.clearItemInUse(); // setItemInUse only takes a new stack object
                    cam.setItemInUse(held, count);
                }
                using = true;
            } else {
                if (cam.isUsingItem()) {
                    cam.clearItemInUse(); // your own use from this tick's hand is not theirs
                }
                using = false;
            }
        }

        /** The camera's own hand again: its stack, its selected slot, no item in use and the arm at rest. */
        void restore(EntityPlayer cam) {
            if (on == cam) {
                InventoryPlayer inv = cam.inventory;
                if (slot >= 0) {
                    restoreSlot(inv);
                }
                if (ownSelected >= 0 && inv.currentItem == EMPTY_HAND_SLOT) {
                    inv.currentItem = ownSelected;
                }
                if (using) {
                    cam.clearItemInUse();
                }
                cam.swingProgressInt = 0;
                cam.isSwingInProgress = false;
                cam.prevSwingProgress = 0f;
                cam.swingProgress = 0f;
            }
            reset();
        }

        void reset() {
            on = null;
            slot = -1;
            own = null;
            put = null;
            ownSelected = -1;
            using = false;
        }

        private void restoreSlot(InventoryPlayer inv) {
            if (inv.mainInventory[slot] == put) {
                inv.mainInventory[slot] = own;
            }
            slot = -1;
            own = null;
            put = null;
        }
    }
}
