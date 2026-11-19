package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.feature.DiagLog;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S20PacketEntityProperties;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.world.WorldSettings;

import java.io.IOException;

/**
 * Where {@link TickFeeder} hands a recording's packets in the client (PLAN section 2-3): each record is decoded with
 * vanilla's own readers ({@link VanillaPacketDecoder}) and processed by the playback connection's
 * {@link NetHandlerPlayClient} on the client thread, where {@code PacketThreadUtil} runs it inline. Packets never pass
 * through the channel, so no other mod's pipeline hook sees them, and nothing is written anywhere.
 *
 * <p><b>Boundary hook</b> (after every S01 and S07 that applied): S01 remembers the recorded entity id and gives the
 * camera {@link RewindCamera#RESERVED_ID}; both make the packet's game type the recorded game mode and put the
 * controller in the view's game type ({@link ViewGameType}: the recorded mode in your view, SPECTATOR in the free
 * camera); then the recorded player's puppet ({@link RewindSelfPuppet}) is added with the recorded id if the world
 * lacks it, and the new camera takes the current view's pose at once ({@link CameraController#restorePose}).
 *
 * <p>{@link #sessionHook} (S2B reason 3, never passed to vanilla): the recorded game mode changes and the controller
 * takes the view's game type.
 *
 * <p>{@link #sample} (each world tick, after its records and its local phase): the puppet takes tick k's end state
 * from the self track ({@link PuppetState} on the recording's {@link TickTable}), and is re-added if something removed
 * it; then the camera takes your hand, held item and hotbar ({@link HandState}, Rev 3 amendment 1), in both views;
 * then the playback parts' {@code tick}. {@link #viewChanged} applies a view switch at once.
 *
 * <p>Parts (PLAN "B-seam"): {@link #local} and {@link #timed} hand extension records to them, {@link #boundary} and
 * {@link #viewChanged} tell them. {@link #window} processes window packets only when a recorded-window holder takes
 * them ({@link RecordedScreenHolder#takesWindows}); until Phase 9 none does and they reach nothing.
 */
public final class PlaybackSink implements PacketSink {

    private final Minecraft mc;
    private final NetHandlerPlayClient handler;
    private Packet<INetHandlerPlayClient> last;
    private int recordedEntityId = Integer.MIN_VALUE;
    private WorldSettings.GameType recordedGameType = WorldSettings.GameType.NOT_SET;
    private long lastSampleTick = -1;
    private long lastSampleUs;
    private final PuppetState puppetState = new PuppetState();
    private RewindSelfPuppet puppet;
    private boolean puppetFailureLogged;
    private final ViewGameType viewType = new ViewGameType();
    private final HandState hand = new HandState();
    /** The camera that has HandState's full state (a new one after an S01/S07 takes it all). */
    private RewindCamera handCamera;
    private boolean handFailureLogged;
    private final SelfHealth health = new SelfHealth();

    public PlaybackSink(Minecraft mc, PlaybackConnection connection) {
        this.mc = mc;
        this.handler = connection.handler();
        CameraController.forRecording(RewindPlayback.session().recording()); // before the headers' game type
    }

    @Override
    public boolean apply(int packetId, byte[] buf, int off, int len) {
        last = null;
        Packet<INetHandlerPlayClient> p;
        try {
            p = VanillaPacketDecoder.decode(buf, off, len);
        } catch (IOException e) {
            return false;
        }
        p.processPacket(handler);
        last = p;
        try {
            health.applied(p, recordedEntityId, camera());
            // A window-0 S30/S2F replaces older EQUIP history (code review 2, I2): a fresh camera's full copy
            // then never puts an obsolete stack over the inventory the server just sent.
            hand.inventoryPacket(buf, off, len, feedTimeUs());
        } catch (RuntimeException e) {
            handFailed(e);
        }
        return true;
    }

    /** Window traffic (S2D, S2E, container S2F/S30): vanilla processes it only for a holder that keeps windows. */
    @Override
    public boolean window(int packetId, byte[] buf, int off, int len) {
        if (!RewindCamera.screenHolder().takesWindows()) {
            return true;
        }
        return apply(packetId, buf, off, len);
    }

    @Override
    public void local(int tag, long timeUs, byte[] buf, int off, int len) {
        RewindPlayback.partsLocal(tag, timeUs, buf, off, len);
    }

    @Override
    public void timed(int tag, long timeUs, byte[] buf, int off, int len) {
        RewindPlayback.partsTimed(tag, timeUs, buf, off, len);
    }

    @Override
    public void boundary(int packetId, byte[] buf, int off, int len) {
        Packet<INetHandlerPlayClient> p = last;
        last = null;
        health.reset(); // S01 and S07 make a new camera with vanilla's default health and maximum
        if (p instanceof S01PacketJoinGame) {
            recordedEntityId = ((S01PacketJoinGame) p).getEntityId();
            if (mc.thePlayer != null) {
                mc.thePlayer.setEntityId(RewindCamera.RESERVED_ID);
            }
        }
        if (viewType.onPacket(buf, off, len)) {
            recordedGameType = WorldSettings.GameType.getByID(viewType.recorded());
        }
        applyViewGameType();
        try {
            ensurePuppet(Math.max(0, feedTick()));
        } catch (RuntimeException e) {
            puppetFailed(e);
        }
        CameraController.restorePose();
        RewindPlayback.partsBoundary();
    }

    @Override
    public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
        if (!viewType.onPacket(buf, off, len)) {
            return false;
        }
        recordedGameType = WorldSettings.GameType.getByID(viewType.recorded());
        applyViewGameType();
        return true;
    }

    /** The controller takes the current view's game type (what vanilla's HUD and hand read). */
    private void applyViewGameType() {
        if (mc.playerController != null && mc.thePlayer != null) {
            mc.playerController.setGameType(
                    WorldSettings.GameType.getByID(viewType.forView(CameraController.yourView())));
        }
    }

    /**
     * A view switch ({@link CameraController}): the controller's game type and the camera's view-dependent state
     * (hurtTime, absorption, air, effects) change at once, not at the next world tick (playback may be paused).
     */
    public void viewChanged() {
        applyViewGameType();
        RewindCamera cam = camera();
        if (cam != null) {
            try {
                cam.applyView(hand, CameraController.yourView(), puppet);
            } catch (RuntimeException e) {
                handFailed(e);
            }
        }
        RewindPlayback.partsViewChanged();
    }

    @Override
    public void sample(long tick, long timeUs) {
        lastSampleTick = tick;
        lastSampleUs = timeUs;
        try {
            if (!ensurePuppet(tick)) { // a new puppet took this tick's sample already
                SelfTrack track = track();
                TickTable table = table();
                if (puppet != null && track != null && table != null && puppetState.sample(track, table, tick)) {
                    puppet.follow();
                }
            }
        } catch (RuntimeException e) {
            puppetFailed(e);
        }
        try {
            applyHand(tick);
        } catch (RuntimeException e) {
            handFailed(e);
        }
        RewindPlayback.partsTick(tick, timeUs);
    }

    /** HandState for this tick (every tick, whatever the camera), then onto the camera. */
    private void applyHand(long tick) {
        SelfTrack track = track();
        TickTable table = table();
        if (track == null || table == null || !hand.sample(track, table, tick, swingEnd(puppet))) {
            return;
        }
        RewindCamera cam = camera();
        if (cam == null) {
            return;
        }
        boolean full = cam != handCamera;
        handCamera = cam;
        cam.applyHand(hand, full, CameraController.yourView(), puppet);
    }

    /** The swing's length from the recorded player's haste and mining fatigue (on the puppet, where S1D lands). */
    private static int swingEnd(RewindSelfPuppet p) {
        if (p == null) {
            return HandState.SWING_END;
        }
        PotionEffect haste = p.getActivePotionEffect(Potion.digSpeed);
        PotionEffect fatigue = p.getActivePotionEffect(Potion.digSlowdown);
        return HandState.swingEnd(haste == null ? -1 : haste.getAmplifier(),
                fatigue == null ? -1 : fatigue.getAmplifier());
    }

    private RewindCamera camera() {
        return mc.thePlayer instanceof RewindCamera ? (RewindCamera) mc.thePlayer : null;
    }

    private void handFailed(RuntimeException e) {
        if (!handFailureLogged) {
            handFailureLogged = true;
            DiagLog.log("rewind-watch: hand failed " + e.getClass().getSimpleName());
        }
    }

    /** The puppet is a picture of you, not the world's state: a failure is logged once and playback goes on. */
    private void puppetFailed(RuntimeException e) {
        if (!puppetFailureLogged) {
            puppetFailureLogged = true;
            DiagLog.log("rewind-watch: puppet failed " + e.getClass().getSimpleName());
        }
    }

    /**
     * Adds the puppet with the recorded id when the current world lacks it (a new world after S01/S07, or something
     * removed it): a fresh {@link PuppetState} sampled for tick {@code tick}, so it stands at the recorded pose wearing
     * everything recorded so far. The camera must already have its reserved id (an id clash would remove it).
     *
     * @return true if a puppet was added
     */
    private boolean ensurePuppet(long tick) {
        WorldClient world = mc.theWorld;
        if (world == null || recordedEntityId == Integer.MIN_VALUE || recordedEntityId == RewindCamera.RESERVED_ID) {
            return false;
        }
        if (puppet != null && puppet.worldObj == world && world.getEntityByID(recordedEntityId) == puppet) {
            return false;
        }
        SelfTrack track = track();
        TickTable table = table();
        if (track == null || table == null) {
            return false;
        }
        puppetState.reset();
        if (!puppetState.sample(track, table, tick)) {
            return false;
        }
        RewindSelfPuppet p = new RewindSelfPuppet(world, puppetProfile(), puppetState);
        p.place();
        p.follow();
        world.addEntityToWorld(recordedEntityId, p);
        puppet = p;
        return true;
    }

    /**
     * The tab entry's profile for the session's account (its skin), else the session profile (format gap 1). Phase 13
     * moves this to {@code Identity} (the recorded profile when the recording has one).
     */
    private GameProfile puppetProfile() {
        GameProfile own = mc.getSession().getProfile();
        NetworkPlayerInfo info = own.getId() == null ? null : handler.getPlayerInfo(own.getId());
        return info != null && info.getGameProfile() != null ? info.getGameProfile() : own;
    }

    private static SelfTrack track() {
        LoadedRecording r = RewindPlayback.session().recording();
        return r == null ? null : r.selfTrack();
    }

    /** The recording's tick table, or null. */
    private static TickTable table() {
        Timeline t = RewindPlayback.session().timeline();
        return t == null ? null : t.ticks();
    }

    /** The time of the record being fed (the origin for headers). */
    private static long feedTimeUs() {
        TickFeeder f = RewindPlayback.session().feeder();
        return f == null ? 0 : f.recordTimeUs();
    }

    /** The tick being fed (-1 while the headers are). */
    private static long feedTick() {
        TickFeeder f = RewindPlayback.session().feeder();
        return f == null ? 0 : f.currentTick();
    }

    /** The recorded player's stand-in in the current world, or null. */
    public RewindSelfPuppet puppet() {
        return puppet;
    }

    /** The recorded player's entity id from the last S01, or {@link Integer#MIN_VALUE}. */
    public int recordedEntityId() {
        return recordedEntityId;
    }

    /** The recorded game mode from the last S01, S07 or S2B reason 3. */
    public WorldSettings.GameType recordedGameType() {
        return recordedGameType;
    }

    public long lastSampleTick() {
        return lastSampleTick;
    }

    public long lastSampleUs() {
        return lastSampleUs;
    }

    /**
     * The recorded player's health on the camera (code review 1, I2). Vanilla routes S20 by entity id, so the recorded
     * player's properties land on the puppet, while S06 lands on the camera and {@code setHealth} clamps it to the
     * camera's own {@code generic.maxHealth} (20 by default). So an S20 for the recorded id also sets the camera's
     * maximum health, as vanilla's {@code handleEntityProperties} would on the real player (base value, then the
     * modifiers), and the last S06 health is applied again whenever the maximum changes, so S06 before S20 shows the
     * recorded hearts too. A new world (S01/S07: a new camera) starts from vanilla's defaults again.
     */
    static final class SelfHealth {
        private static final String MAX_HEALTH = "generic.maxHealth";
        private float recordedHealth = Float.NaN;

        void applied(Packet<?> p, int recordedId, EntityLivingBase camera) {
            if (p instanceof S06PacketUpdateHealth) {
                recordedHealth = ((S06PacketUpdateHealth) p).getHealth();
                return;
            }
            if (!(p instanceof S20PacketEntityProperties) || camera == null) {
                return;
            }
            S20PacketEntityProperties props = (S20PacketEntityProperties) p;
            if (props.getEntityId() != recordedId || recordedId == RewindCamera.RESERVED_ID) {
                return;
            }
            for (S20PacketEntityProperties.Snapshot snap : props.m_57321779()) {
                if (!MAX_HEALTH.equals(snap.m_23449231())) {
                    continue;
                }
                IAttributeInstance a = camera.getEntityAttribute(SharedMonsterAttributes.maxHealth);
                double before = a.getAttributeValue();
                a.setBaseValue(snap.m_16455010());
                a.removeAllModifiers();
                for (AttributeModifier m : snap.m_41315742()) {
                    a.applyModifier(m);
                }
                if (a.getAttributeValue() != before && !Float.isNaN(recordedHealth)) {
                    camera.setHealth(recordedHealth); // clamped to the new maximum
                }
            }
        }

        void reset() {
            recordedHealth = Float.NaN;
        }
    }
}
