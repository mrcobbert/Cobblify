package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The feed half of the seek equivalence contract (PLAN "Seek equivalence contract", check 1).
 *
 * <p><b>Trace:</b> straight play with frames of 0-10 random ticks, random pauses and speed changes, a forward seek and
 * a backward seek, all to the same tick K, record (tick, records fed, puppet sample time) since the last restart; the
 * traces are equal element by element.
 *
 * <p><b>Pure client model:</b> a small stand-in for the vanilla client driven by the same feed and ticks: an
 * interpolating living entity (3-tick targets, as {@code EntityOtherPlayerMP}), a world clock that ticks, chat lines
 * aged by a GUI counter that grows per tick, a title timer, chunks whose unload removes the entities listed in them
 * (membership changes only in ticks), spawns refused in unloaded chunks, and a GUI reset at every restart. Its state
 * is equal for all three ways of reaching K, including the reviewer's S14 case and the chat lines at 5 s and 15 s
 * with a backward seek from 20 s to 10 s.
 *
 * <p><b>Group 7 (Rev 3 amendment 1):</b> the model also keeps the recorded player's state on the camera: health,
 * food and XP (S06, S1F), the selected slot (S09, then TICK {@code heldSlot} through {@link HandState}), the player
 * inventory (S2F for window 0; a container window is dropped by the policy) and EQUIP stacks, armour, the swing, the
 * item in use, {@code hurtTime}, and the recorded game mode (S01, S07, S2B reason 3 through the session hook into
 * {@link ViewGameType}). A camera made anew by an S01/S07 starts empty and takes HandState's full state.
 *
 * <p><b>Group 7, format v2</b> (Phase 13, C9): on a v2 recording the model also does what {@code HandV2Part} does
 * after the sink's hand: a second {@link HandState} reads the extension records, so the swing starts on the tick of an
 * OUT C0A, the item-use count is the recorded USE count, and all 41 slots (36 main, armour, the cursor) are the INV
 * records', over S2F and EQUIP. Equal after every seek.
 */
public class TickFeedEquivalenceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long S = 1_000_000L;
    /** Origin, deliberately not a round number. */
    private static final long O = 11 * S + 12_345L;
    private static final long GAME_START = O + 3 * S;
    private static final long END = O + 30 * S;

    private static final int PLAYER = 10;
    private static final int MOB_MOVES_OUT = 11;
    private static final int MOB_REFUSED = 12;
    private static final int MOB_REFUSED_LATER = 13;

    // ---------------- model packets (every id passes the policy, except the camera teleport) ----------------

    private static byte[] pk(int id, int... ints) {
        byte[] b = new byte[1 + 4 * ints.length];
        b[0] = (byte) id;
        for (int i = 0; i < ints.length; i++) {
            RewindFormat.putInt(b, 1 + 4 * i, ints[i]);
        }
        return b;
    }

    private static byte[] chat(int line) {
        return pk(0x02, line);
    }

    private static byte[] time(int worldTime) {
        return pk(0x03, worldTime);
    }

    private static byte[] spawnPlayer(int id, int blocksX) {
        return pk(0x0C, id, blocksX * 32);
    }

    private static byte[] spawnMob(int id, int blocksX) {
        return pk(0x0F, id, blocksX * 32);
    }

    private static byte[] move(int id, int dxBlocks) {
        return pk(0x15, id, dxBlocks * 32);
    }

    private static byte[] chunk(int cx, boolean load) {
        return pk(0x21, cx, load ? 1 : 0);
    }

    private static byte[] title(int ticks) {
        return pk(0x45, ticks);
    }

    /** S08 acts on the client player: the policy drops it, so the model's camera never moves. */
    private static byte[] teleportCamera(int x) {
        return pk(0x08, x);
    }

    private static byte[] filler(int i) {
        return pk(0x04, i);
    }

    // ---------------- group 7: packets that land on the camera (amendment 1) ----------------

    private static byte[] health(int health, int food) {
        return pk(0x06, health, food);
    }

    private static byte[] experience(int level) {
        return pk(0x1F, level);
    }

    private static byte[] heldSlot(int slot) {
        return pk(0x09, slot);
    }

    /** S2F: window byte (the policy reads it), then the model's int slot and item. */
    private static byte[] setSlot(int window, int slot, int item) {
        byte[] b = new byte[10];
        b[0] = 0x2F;
        b[1] = (byte) window;
        RewindFormat.putInt(b, 2, slot);
        RewindFormat.putInt(b, 6, item);
        return b;
    }

    private static byte[] gameMode(int mode) {
        byte[] b = new byte[6];
        b[0] = 0x2B;
        b[1] = 3;
        RewindFormat.putFloat(b, 2, mode);
        return b;
    }

    /** Client ticks of the recording, 7 ms off the playback grid. */
    private static long clientTick(int i) {
        return O + 7_000L + i * Timeline.TICK_US;
    }

    static final int CLICK_1 = 100;
    static final int CLICK_RESTART = 104;
    static final int CLICK_2 = 300;
    static final int USE_FROM = 250;
    static final int USE_TO = 290;
    static final int HURT_AT = 450;

    /** The self track the model's camera follows: slots, swings (one restarted), a use, a hit, EQUIP stacks. */
    static SelfTrack selfTrack() {
        SelfTrackEncoder enc = new SelfTrackEncoder();
        SelfTrack.Builder b = new SelfTrack.Builder().segment(1);
        HandStateTest.VanillaSwing swing = new HandStateTest.VanillaSwing();
        for (int i = 0; i < 600; i++) {
            if (i == CLICK_1 || i == CLICK_RESTART || i == CLICK_2) {
                swing.swingItem(6);
            }
            swing.tick(6);
            int flags = i >= USE_FROM && i < USE_TO ? RewindFormat.FLAG_USING_ITEM : 0;
            int slot = i < 200 ? 0 : i < 400 ? 2 : 5;
            int hurt = i >= HURT_AT && i <= HURT_AT + 10 ? 10 - (i - HURT_AT) : 0;
            int n = enc.tick(i / 20, 64, 0, 0f, 0f, 0f, flags, slot, swing.progress, hurt);
            if (n > 0) {
                b.record(clientTick(i), RewindFormat.TAG_TICK, enc.buffer(), 0, n);
            }
            int[] equip = i == 0 ? new int[]{RewindFormat.EQUIP_HELD, 1, 64}
                    : i == 150 ? new int[]{RewindFormat.EQUIP_HELD, 1, 63}
                    : i == 200 ? new int[]{RewindFormat.EQUIP_HELD, 2, 1}
                    : i == 400 ? new int[]{RewindFormat.EQUIP_HELD, 3, 16}
                    : i == 420 ? new int[]{RewindFormat.EQUIP_BOOTS, 4, 1} : null;
            if (equip != null) {
                byte[] body = {(byte) equip[0], (byte) equip[1], (byte) equip[2]};
                b.record(clientTick(i), RewindFormat.TAG_EQUIP, body, 0, body.length);
            }
            if (i == 0) {
                byte[] helmet = {(byte) RewindFormat.EQUIP_HELMET, 5, 1};
                b.record(clientTick(i), RewindFormat.TAG_EQUIP, helmet, 0, helmet.length);
            }
        }
        return b.build();
    }

    private static final SelfTrack TRACK = selfTrack();

    private static final class Timed {
        final long t;
        final byte[] p;

        Timed(long t, byte[] p) {
            this.t = t;
            this.p = p;
        }
    }

    private LoadedRecording recording() throws Exception {
        List<Timed> game = new ArrayList<Timed>();
        game.add(new Timed(O + 30_000L, chunk(0, true)));
        game.add(new Timed(O + 30_000L, chunk(1, true)));
        game.add(new Timed(O + S, spawnPlayer(PLAYER, 0)));
        game.add(new Timed(O + S, spawnMob(MOB_MOVES_OUT, 17)));
        game.add(new Timed(O + S + 20_000L, spawnMob(MOB_REFUSED, 5 * 16 + 1)));
        game.add(new Timed(O + 2 * S, time(6000)));
        game.add(new Timed(O + 5 * S, chat(1)));
        game.add(new Timed(O + 9 * S, title(90)));
        game.add(new Timed(O + 12 * S + 10_000L, time(13000)));
        game.add(new Timed(O + 15 * S, chat(2)));
        game.add(new Timed(O + 17 * S + 500_000L, teleportCamera(99)));
        game.add(new Timed(O + 20 * S, move(PLAYER, 3))); // the reviewer's S14 case
        game.add(new Timed(O + 21 * S, move(MOB_MOVES_OUT, -7))); // x 17 -> 10: from chunk 1 to chunk 0
        game.add(new Timed(O + 21 * S + 100_000L, chunk(1, false))); // two ticks later
        game.add(new Timed(O + 21 * S + 110_000L, spawnMob(MOB_REFUSED_LATER, 17)));
        game.add(new Timed(O + 24 * S + 950_000L, chat(3)));
        game.add(new Timed(O + 25 * S, chat(4)));
        game.add(new Timed(O + 25 * S + 1, chat(5)));
        game.add(new Timed(O + 28 * S, chat(6)));
        for (int i = 0; i < 100; i++) {
            game.add(new Timed(O + 100_000L + i * 300_000L, filler(i)));
        }
        // Group 7: the camera's packets.
        game.add(new Timed(O + 4 * S, health(20, 20)));
        game.add(new Timed(O + 6 * S, setSlot(0, 36, 111)));
        game.add(new Timed(O + 8 * S, heldSlot(7)));
        game.add(new Timed(O + 9 * S, setSlot(0, 36, 112))); // after the last EQUIP for hotbar slot 0
        game.add(new Timed(O + 16 * S, setSlot(5, 0, 222))); // a chest: never reaches the camera
        game.add(new Timed(O + 19 * S, health(14, 18)));
        game.add(new Timed(O + 23 * S, experience(5)));
        game.add(new Timed(O + 26 * S, gameMode(ViewGameType.SPECTATOR)));
        // Stable sort by time keeps the listed order of records that share a time.
        Collections.sort(game, new Comparator<Timed>() {
            @Override
            public int compare(Timed a, Timed b) {
                return a.t < b.t ? -1 : a.t > b.t ? 1 : 0;
            }
        });
        TickFeederTest.Rec rec = new TickFeederTest.Rec()
                .segment(1, TickFeederTest.standardHeader0(), TickFeederTest.standardHeader1())
                .at(O, RecordingFixture.joinGame(0))
                // A second segment, as in the real files: a same-dimension S07 a few ms in. Its header is never fed.
                .segment(2, new byte[][]{RecordingFixture.joinGame(-1)}, TickFeederTest.standardHeader1())
                .at(O + 20_000L, RecordingFixture.respawn(0));
        for (Timed t : game) {
            rec.at(t.t, t.p);
        }
        return rec.load(tmp.newFolder(), GAME_START, END);
    }

    // ---------------- the model ----------------

    static final class Model {
        static final class Ent {
            final int id;
            final boolean player;
            double x;
            double serverX;
            double target;
            int steps;
            int listed;

            Ent(int id, boolean player, double x) {
                this.id = id;
                this.player = player;
                this.x = x;
                this.serverX = x;
                this.target = x;
                this.listed = chunkOf(x);
            }
        }

        boolean guiResetAtRestart = true;
        boolean world;
        int dimension;
        long worldTime;
        long counter;
        final TreeMap<Integer, Ent> ents = new TreeMap<Integer, Ent>();
        final TreeSet<Integer> chunks = new TreeSet<Integer>();
        final List<int[]> chat = new ArrayList<int[]>();
        int titleTimer;
        int cameraX;
        /** Group 7: the camera's recorded-player state. */
        Cam cam = new Cam();
        HandState hand = new HandState();
        ViewGameType mode = new ViewGameType();
        boolean camFull = true;
        /** Group 7, v2: the recording's extension records and HandV2Part's own hand state. */
        ExtTrack ext = ExtTrack.EMPTY;
        HandState hand2 = new HandState();

        static final class Cam {
            int health = -1;
            int food = -1;
            int xp = -1;
            int selected;
            final TreeMap<Integer, String> inventory = new TreeMap<Integer, String>();
            final String[] armour = new String[5];
            int swingInt;
            boolean swinging;
            float swing;
            float prevSwing;
            boolean inUse;
            int useCount;
            int hurt;
            String cursor;

            String snapshot() {
                return "h=" + health + " f=" + food + " xp=" + xp + " sel=" + selected + " inv=" + inventory
                        + " arm=" + Arrays.toString(armour) + " cur=" + cursor + " swing=" + swinging + "/" + swingInt
                        + "/" + swing + "/" + prevSwing + " use=" + inUse + "/" + useCount + " hurt=" + hurt;
            }
        }

        static int chunkOf(double x) {
            return (int) Math.floor(x / 16.0);
        }

        /** Local records applied since the last restart, by tag (BLOCK, CONT, ... in the model: a count). */
        final Map<Integer, Integer> locals = new java.util.TreeMap<Integer, Integer>();

        void local(int tag) {
            Integer n = locals.get(tag);
            locals.put(tag, n == null ? 1 : n + 1);
        }

        /** A restart: the world is torn down; GUI state outside the world is reset (round 2 B2). */
        void restart() {
            locals.clear();
            if (guiResetAtRestart) {
                chat.clear();
                titleTimer = 0;
                counter = 0;
            }
            world = false;
            ents.clear();
            chunks.clear();
            worldTime = 0;
            // A new connection: a new sink with its own hand state and recorded mode, and no camera yet.
            cam = new Cam();
            hand = new HandState();
            mode = new ViewGameType();
            camFull = true;
            hand2 = new HandState();
        }

        /** After an S01/S07: vanilla made a new, empty camera; the recorded mode comes from the packet. */
        void boundary(byte[] b, int off, int len) {
            cam = new Cam();
            camFull = true;
            mode.onPacket(b, off, len);
        }

        /** S2B reason 3. */
        boolean hook(byte[] b, int off, int len) {
            return mode.onPacket(b, off, len);
        }

        /** The tick's sample: the self track onto the camera (your view), as the Ornithe sink does. */
        void sample(TickTable table, long k) {
            if (!hand.sample(TRACK, table, k, 6)) {
                return;
            }
            cam.selected = hand.selectedSlot();
            for (int i = 0; i < HandState.HOTBAR; i++) {
                if ((camFull || hand.hotbarChanged(i)) && hand.hotbarItem(i) != null) {
                    cam.inventory.put(36 + i, "E" + Arrays.toString(hand.hotbarItem(i)));
                }
            }
            for (int i = 1; i < 5; i++) {
                if ((camFull || hand.armourChanged(i)) && hand.armourItem(i) != null) {
                    cam.armour[i] = Arrays.toString(hand.armourItem(i));
                }
            }
            camFull = false;
            cam.swingInt = hand.swingProgressInt();
            cam.swinging = hand.swingInProgress();
            cam.swing = hand.swingProgress();
            cam.prevSwing = hand.prevSwingProgress();
            cam.inUse = hand.inUse();
            cam.useCount = hand.inUse() ? hand.useCount(72000) : 0;
            cam.hurt = hand.hurtTime(true);
            sampleV2(table, k);
        }

        /** HandV2Part, after the sink's hand: the v2 records over what the v1 rules put on the camera. */
        private void sampleV2(TickTable table, long k) {
            if (!HandState.usesExt(ext) || !hand2.sample(TRACK, ext, table, k, 6)) {
                return;
            }
            if (hand2.extInventory()) {
                for (int slot = 0; slot < RewindFormat.INV_SLOTS; slot++) {
                    byte[] item = hand2.invItem(slot);
                    if (item == null) {
                        continue;
                    }
                    String v = "I" + Arrays.toString(item);
                    if (slot < RewindFormat.INV_MAIN_SLOTS) {
                        cam.inventory.put(slot < HandState.HOTBAR ? 36 + slot : slot, v); // container slot numbers
                    } else if (slot < RewindFormat.INV_CURSOR) {
                        cam.armour[slot - RewindFormat.INV_ARMOR_FIRST + RewindFormat.EQUIP_BOOTS] = v;
                    } else {
                        cam.cursor = v;
                    }
                }
            }
            if (hand2.extSwing()) {
                cam.swingInt = hand2.swingProgressInt();
                cam.swinging = hand2.swingInProgress();
                cam.swing = hand2.swingProgress();
                cam.prevSwing = hand2.prevSwingProgress();
            }
            if (hand2.extUse() && hand2.inUse()) {
                cam.useCount = hand2.useCount(72000);
            }
        }

        private void newWorld(int dim) {
            world = true;
            dimension = dim;
            ents.clear();
            chunks.clear();
            worldTime = 0;
        }

        void apply(int id, byte[] b, int off, int len) {
            switch (id) {
                case 0x01:
                    newWorld(b[off + 6]);
                    break;
                case 0x07: {
                    int dim = RewindFormat.getInt(b, off + 1);
                    if (!world || dim != dimension) {
                        newWorld(dim);
                    }
                    break;
                }
                case 0x02:
                    chat.add(new int[]{RewindFormat.getInt(b, off + 1), (int) counter});
                    break;
                case 0x03:
                    worldTime = RewindFormat.getInt(b, off + 1);
                    break;
                case 0x0C:
                case 0x0F: {
                    int eid = RewindFormat.getInt(b, off + 1);
                    double x = RewindFormat.getInt(b, off + 5) / 32.0;
                    boolean player = id == 0x0C;
                    if (player || chunks.contains(chunkOf(x))) {
                        ents.put(eid, new Ent(eid, player, x));
                    }
                    break;
                }
                case 0x15: {
                    Ent e = ents.get(RewindFormat.getInt(b, off + 1));
                    if (e != null) {
                        e.serverX += RewindFormat.getInt(b, off + 5) / 32.0;
                        e.target = e.serverX;
                        e.steps = 3;
                    }
                    break;
                }
                case 0x21: {
                    int cx = RewindFormat.getInt(b, off + 1);
                    if (RewindFormat.getInt(b, off + 5) != 0) {
                        chunks.add(cx);
                    } else {
                        chunks.remove(cx);
                        for (Iterator<Ent> it = ents.values().iterator(); it.hasNext(); ) {
                            if (it.next().listed == cx) {
                                it.remove();
                            }
                        }
                    }
                    break;
                }
                case 0x45:
                    titleTimer = RewindFormat.getInt(b, off + 1);
                    break;
                case 0x08:
                    cameraX = RewindFormat.getInt(b, off + 1);
                    break;
                case 0x06:
                    cam.health = RewindFormat.getInt(b, off + 1);
                    cam.food = RewindFormat.getInt(b, off + 5);
                    break;
                case 0x1F:
                    cam.xp = RewindFormat.getInt(b, off + 1);
                    break;
                case 0x09:
                    cam.selected = len >= 5 ? RewindFormat.getInt(b, off + 1) : b[off + 1];
                    break;
                case 0x2F:
                    if (b[off + 1] != 0) {
                        throw new AssertionError("a container window reached the camera");
                    }
                    cam.inventory.put(RewindFormat.getInt(b, off + 2), "S" + RewindFormat.getInt(b, off + 6));
                    break;
                default:
                    break;
            }
        }

        /** One world tick: GUI counter and title always; world clock, interpolation and chunk lists with a world. */
        void tick() {
            counter++;
            if (titleTimer > 0) {
                titleTimer--;
            }
            if (!world) {
                return;
            }
            worldTime++;
            for (Ent e : ents.values()) {
                if (e.steps > 0) {
                    e.x += (e.target - e.x) / e.steps;
                    e.steps--;
                }
                int c = chunkOf(e.x);
                if (c != e.listed && chunks.contains(c)) {
                    e.listed = c;
                }
            }
        }

        List<int[]> visibleChat() {
            List<int[]> out = new ArrayList<int[]>();
            for (int[] l : chat) {
                long age = counter - l[1];
                if (age < 200) {
                    out.add(new int[]{l[0], (int) age});
                }
            }
            return out;
        }

        String snapshot() {
            StringBuilder sb = new StringBuilder();
            sb.append("world=").append(world).append(" dim=").append(dimension).append(" time=").append(worldTime)
                    .append(" counter=").append(counter).append(" title=").append(titleTimer)
                    .append(" camera=").append(cameraX).append(" chunks=").append(chunks).append(" ents=");
            for (Map.Entry<Integer, Ent> en : ents.entrySet()) {
                Ent e = en.getValue();
                sb.append('[').append(e.id).append(e.player ? "p" : "m").append(" x=").append(e.x)
                        .append(" sx=").append(e.serverX).append(" in=").append(e.listed).append(" steps=")
                        .append(e.steps).append(']');
            }
            sb.append(" chat=");
            for (int[] l : visibleChat()) {
                sb.append('[').append(l[0]).append(" age ").append(l[1]).append(']');
            }
            sb.append(" cam=").append(cam.snapshot()).append(" mode=").append(mode.recorded());
            sb.append(" locals=").append(locals);
            return sb.toString();
        }
    }

    // ---------------- the harness: drives feeder, ticks and model as the session will ----------------

    static final class Harness implements PacketSink {
        final Timeline tl;
        final TickFeeder feeder;
        final ReplayTicks ticks;
        final Model model = new Model();
        final Random rnd;
        /** Since the last restart. */
        final List<String> trace = new ArrayList<String>();
        long tick = -1;
        int restarts;

        Harness(LoadedRecording r, long seed) {
            tl = r.timeline();
            feeder = new TickFeeder(r);
            ticks = new ReplayTicks(tl.endTick());
            rnd = new Random(seed);
            model.ext = r.extTrack();
        }

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            trace.add("R t" + tick + " #" + feeder.recordIndex() + " id" + packetId + " at" + feeder.recordTimeUs());
            model.apply(packetId, buf, off, len);
            return true;
        }

        @Override
        public void boundary(int packetId, byte[] buf, int off, int len) {
            trace.add("B t" + tick + " id" + packetId);
            model.boundary(buf, off, len);
        }

        @Override
        public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
            trace.add("H t" + tick + " id" + packetId);
            return model.hook(buf, off, len);
        }

        @Override
        public boolean window(int packetId, byte[] buf, int off, int len) {
            trace.add("W t" + tick + " #" + feeder.recordIndex() + " id" + packetId);
            return true;
        }

        @Override
        public void local(int tag, long timeUs, byte[] buf, int off, int len) {
            trace.add("L t" + tick + " tag" + tag + " at" + timeUs + " len" + len);
            model.local(tag);
        }

        @Override
        public void sample(long t, long timeUs) {
            trace.add("S t" + t + " at" + timeUs);
            model.sample(tl.ticks(), t);
        }

        /** Rebuild: GUI reset, world gone, headers before tick 0. */
        void restart() {
            restarts++;
            feeder.restart();
            model.restart();
            trace.clear();
            tick = -1;
            feeder.feedHeaders(this);
        }

        void runTick() {
            long k = ticks.beginTick();
            tick = k;
            assertEquals(TickFeeder.State.RUNNING, feeder.feedForTick(k, this));
            model.tick();
            ticks.finishTick();
        }

        void seek(SeekPlanner.Plan p) {
            if (p.restart) {
                restart();
            }
            ticks.seek(p);
            while (ticks.catchingUp()) {
                int budget = 1 + rnd.nextInt(40); // frames of a bounded catch-up
                for (int i = 0; i < budget && ticks.catchingUp(); i++) {
                    runTick();
                }
            }
        }

        Harness open() {
            seek(SeekPlanner.opening(tl));
            return this;
        }

        Harness seekTo(long target) {
            seek(SeekPlanner.plan(ticks.ticksRun(), target));
            return this;
        }

        /** Straight play to tick k with frames of 0-10 ticks, random pauses and speed changes; never past k. */
        Harness playTo(long k) {
            while (ticks.ticksRun() < k) {
                int r = rnd.nextInt(10);
                if (r == 0) {
                    ticks.setPaused(!ticks.paused());
                } else if (r == 1) {
                    if (rnd.nextBoolean()) {
                        ticks.faster();
                    } else {
                        ticks.slower();
                    }
                }
                int n = (int) Math.min(ticks.frameTicks(rnd.nextInt(11)), k - ticks.ticksRun());
                for (int i = 0; i < n; i++) {
                    runTick();
                }
                if (ticks.paused() && rnd.nextInt(3) == 0) {
                    ticks.setPaused(false);
                }
            }
            assertEquals(k, ticks.ticksRun());
            return this;
        }
    }

    private long tick(Timeline tl, long us) {
        return tl.tickOf(us);
    }

    // ---------------- tests ----------------

    @Test
    public void straightPlayForwardSeekAndBackwardSeekGiveEqualTracesAndModelState() throws Exception {
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        long start = tl.startTick();
        long end = tl.endTick();
        long[] targets = {tick(tl, O + 10 * S), tick(tl, O + 21 * S + 50_000L), tick(tl, O + 25 * S), end};
        int compared = 0;
        for (long k : targets) {
            for (long seed = 0; seed < 15; seed++) {
                Harness straight = new Harness(r, seed).open().playTo(k);
                assertEquals(1, straight.restarts);

                Random pick = new Random(seed * 31 + k);
                long j = start + (long) (pick.nextDouble() * (k - start));
                Harness forward = new Harness(r, seed + 1000).open().playTo(j);
                if (pick.nextBoolean()) {
                    forward.ticks.setPaused(true);
                }
                forward.seekTo(k);
                assertEquals("forward seeks catch up in place", 1, forward.restarts);
                assertEquals(k, forward.ticks.ticksRun());
                assertEquals("forward trace, K=" + k + " seed " + seed, straight.trace, forward.trace);
                assertEquals("forward state, K=" + k + " seed " + seed, straight.model.snapshot(),
                        forward.model.snapshot());
                compared++;

                if (k < end) {
                    long j2 = k + 1 + (long) (pick.nextDouble() * (end - k - 1));
                    Harness backward = new Harness(r, seed + 2000).open().playTo(j2);
                    backward.seekTo(k);
                    assertEquals("backward seeks restart", 2, backward.restarts);
                    assertEquals(k, backward.ticks.ticksRun());
                    assertEquals("backward trace, K=" + k + " seed " + seed, straight.trace, backward.trace);
                    assertEquals("backward state, K=" + k + " seed " + seed, straight.model.snapshot(),
                            backward.model.snapshot());
                    compared++;
                }
            }
        }
        assertEquals(15 * 4 + 15 * 3, compared);
    }

    /**
     * The same recording as format v2: a TICK_HEAD every client tick (7 ms off the 50 ms grid, a 400 ms freeze at
     * 12 s, a span marked order-unknown at 18-19 s) whose handled counts lag the arrival stamps by up to 30 ms, and
     * tick-scoped local records (BLOCK, USE, CONT) plus time-scoped ones (SOUND) that never enter the feed.
     */
    private LoadedRecording v2() throws Exception {
        return v2(false);
    }

    /** Head n of the v2 fixtures (7 ms off the grid; not inside the 12 s freeze). */
    private static long head(int n) {
        return O + 30_000L + 7_000L + n * Timeline.TICK_US;
    }

    /** A protocol-47 slot: item {@code id}, {@code count}, damage 0, no tag. */
    private static byte[] item(int id, int count) {
        return new byte[]{(byte) (id >> 8), (byte) id, (byte) count, 0, 0, 0};
    }

    static final long OUT_SWING = head(460) + 2_000L;
    static final long OUT_SWING_2 = head(464) + 2_000L;

    /**
     * {@link #v2()}; with {@code hand}, Phase 13's records instead of the generic USE ones: all 41 INV slots at the
     * first head and changes later (hotbar slot 0 after the S2F at 9 s, a main slot, the cursor, the leggings), OUT
     * C0As at 23 s (no TICK swing there), a block placement OUT, and a USE of 32 (food) where the flag rises.
     */
    private LoadedRecording v2(boolean hand) throws Exception {
        LoadedRecording r = recording();
        com.bedwarsqol.rewind.ExtCodec c = new com.bedwarsqol.rewind.ExtCodec();
        ExtRecords.Builder b = new ExtRecords.Builder();
        // Per segment: the stamps of its client-handled records, in order.
        List<List<Long>> handled = new ArrayList<List<Long>>();
        NetCursor cur = r.newCursor();
        while (!cur.atEnd()) {
            while (handled.size() <= cur.segmentIndex()) {
                handled.add(new ArrayList<Long>());
            }
            if (TickTable.clientHandled(cur.packetId())) {
                handled.get(cur.segmentIndex()).add(cur.timeUs());
            }
            cur.advance();
        }
        int seg = handled.size() - 1; // every head after the last boundary belongs to the last segment
        int count = 0;
        long lag = 0;
        Random rnd = new Random(42);
        for (long t = O + 30_000L + 7_000L; t < END; t += Timeline.TICK_US) {
            if (t > O + 12 * S && t < O + 12 * S + 400_000L) {
                continue; // a freeze
            }
            lag = rnd.nextInt(30_000);
            int now = count;
            List<Long> stamps = handled.get(seg);
            while (now < stamps.size() && stamps.get(now) <= t - lag) {
                now++;
            }
            int flags = t > O + 18 * S && t < O + 19 * S
                    ? com.bedwarsqol.rewind.RewindFormat.TICK_HEAD_ORDER_UNKNOWN : 0;
            int n = c.tickHead(now - count, flags);
            b.addEncoded(seg, t, com.bedwarsqol.rewind.RewindFormat.TAG_TICK_HEAD, c.buffer(), n);
            count = now;
            if ((t / Timeline.TICK_US) % 7 == 0) {
                n = c.block(t, 1, 2, 0);
                b.addEncoded(seg, t + 1_000L, com.bedwarsqol.rewind.RewindFormat.TAG_BLOCK, c.buffer(), n);
                n = c.sound("dig.stone", 0f, 64f, 0f, 1f, 1f);
                b.addEncoded(seg, t + 20_000L, com.bedwarsqol.rewind.RewindFormat.TAG_SOUND, c.buffer(), n);
            }
            if (!hand && (t / Timeline.TICK_US) % 11 == 0) {
                n = c.use(72000);
                b.addEncoded(seg, t + 2_000L, com.bedwarsqol.rewind.RewindFormat.TAG_USE, c.buffer(), n);
                n = c.cont(5, 3, new byte[]{(byte) 0xFF, (byte) 0xFF}, 0, 2);
                b.addEncoded(seg, t + 2_000L, com.bedwarsqol.rewind.RewindFormat.TAG_CONT, c.buffer(), n);
            }
        }
        if (hand) {
            int n;
            for (int slot = 0; slot < RewindFormat.INV_SLOTS; slot++) {
                n = c.inv(slot, item(slot + 1, 1), 0, 6);
                b.addEncoded(seg, head(0) + 2_000L, RewindFormat.TAG_INV, c.buffer(), n);
            }
            n = c.inv(0, item(150, 2), 0, 6);
            b.addEncoded(seg, O + 10 * S + 2_000L, RewindFormat.TAG_INV, c.buffer(), n);
            n = c.inv(20, item(160, 1), 0, 6);
            b.addEncoded(seg, O + 14 * S + 2_000L, RewindFormat.TAG_INV, c.buffer(), n);
            n = c.inv(RewindFormat.INV_CURSOR, item(170, 3), 0, 6);
            b.addEncoded(seg, O + 16 * S + 2_000L, RewindFormat.TAG_INV, c.buffer(), n);
            n = c.inv(37, item(180, 1), 0, 6);
            b.addEncoded(seg, O + 22 * S + 2_000L, RewindFormat.TAG_INV, c.buffer(), n);
            n = c.out(0x0A, new byte[0], 0, 0);
            b.addEncoded(seg, OUT_SWING, RewindFormat.TAG_OUT, c.buffer(), n);
            b.addEncoded(seg, OUT_SWING_2, RewindFormat.TAG_OUT, c.buffer(), n);
            n = c.out(0x08, new byte[]{1, 2, 3}, 0, 3);
            b.addEncoded(seg, head(470) + 2_000L, RewindFormat.TAG_OUT, c.buffer(), n);
            n = c.use(32);
            b.addEncoded(seg, clientTick(USE_FROM), RewindFormat.TAG_USE, c.buffer(), n);
        }
        return r.withExt(b.build());
    }

    @Test
    public void v2TracesWithHandledCountsAndLocalRecordsAreEqualForStraightPlayAndSeeks() throws Exception {
        LoadedRecording r = v2();
        Timeline tl = r.timeline();
        assertEquals(TickTable.Kind.V2, tl.ticks().kind());
        long start = tl.startTick();
        long end = tl.endTick();
        long[] targets = {tick(tl, O + 10 * S), tick(tl, O + 12 * S + 300_000L), tick(tl, O + 18 * S + 500_000L),
                tick(tl, O + 25 * S), end};
        int locals = 0;
        for (long k : targets) {
            for (long seed = 0; seed < 6; seed++) {
                Harness straight = new Harness(r, seed).open().playTo(k);
                Random pick = new Random(seed * 17 + k);
                long j = start + (long) (pick.nextDouble() * (k - start));
                Harness forward = new Harness(r, seed + 1000).open().playTo(j).seekTo(k);
                assertEquals("v2 forward trace, K=" + k + " seed " + seed, straight.trace, forward.trace);
                assertEquals(straight.model.snapshot(), forward.model.snapshot());
                if (k < end) {
                    long j2 = k + 1 + (long) (pick.nextDouble() * (end - k - 1));
                    Harness backward = new Harness(r, seed + 2000).open().playTo(j2).seekTo(k);
                    assertEquals("v2 backward trace, K=" + k + " seed " + seed, straight.trace, backward.trace);
                    assertEquals(straight.model.snapshot(), backward.model.snapshot());
                }
                for (String e : straight.trace) {
                    if (e.startsWith("L ")) {
                        locals++;
                        assertFalse("time-scoped records never enter the feed",
                                e.contains(" tag" + com.bedwarsqol.rewind.RewindFormat.TAG_SOUND + " "));
                    }
                }
            }
        }
        assertTrue("local records are in the traces", locals > 0);
        assertNotEquals("the v2 order differs from the v1 stamp order somewhere",
                new Harness(recording(), 1).open().playTo(tick(tl, O + 25 * S)).trace,
                new Harness(r, 1).open().playTo(tick(tl, O + 25 * S)).trace);
    }

    @Test
    public void theReviewersS14CaseAPausedForwardSeekRunsTheInterpolationTicks() throws Exception {
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        Harness straight = new Harness(r, 7).open().playTo(tick(tl, O + 25 * S));
        straight.ticks.setPaused(true);

        Harness seeked = new Harness(r, 8).open().playTo(tick(tl, O + 15 * S));
        seeked.ticks.setPaused(true);
        assertEquals(0.0, seeked.model.ents.get(PLAYER).x, 0.0);
        seeked.seekTo(tick(tl, O + 25 * S));
        assertTrue("still paused after the seek", seeked.ticks.paused());
        assertEquals(0, seeked.ticks.frameTicks(10));

        assertEquals("x = 3 as in straight play", 3.0, seeked.model.ents.get(PLAYER).x, 0.0);
        assertEquals(3.0, straight.model.ents.get(PLAYER).x, 0.0);
        assertEquals(straight.model.snapshot(), seeked.model.snapshot());
        assertEquals(straight.trace, seeked.trace);
    }

    @Test
    public void chatLinesAtFiveAndFifteenSecondsSeekedBackFromTwentyToTen() throws Exception {
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        long ten = tick(tl, O + 10 * S);
        Harness straight = new Harness(r, 3).open().playTo(ten);
        Harness back = new Harness(r, 4).open().playTo(tick(tl, O + 20 * S));
        assertEquals("both lines kept at 20 s", 2, back.model.chat.size());
        assertEquals("the 5 s line has faded (200 ticks)", 1, back.model.visibleChat().size());
        back.seekTo(ten);

        assertEquals("one 5 s line", 1, back.model.chat.size());
        List<int[]> lines = back.model.visibleChat();
        assertEquals(1, lines.size());
        assertEquals(1, lines.get(0)[0]);
        assertEquals("aged 5 s of ticks", 100, lines.get(0)[1]);
        List<int[]> want = straight.model.visibleChat();
        assertEquals(1, want.size());
        assertEquals("age equal to straight play", want.get(0)[1], lines.get(0)[1]);
        assertEquals(straight.model.snapshot(), back.model.snapshot());
    }

    @Test
    public void withoutTheGuiResetTheBackwardSeekWouldDiffer() throws Exception {
        // Shows the model can tell: the same backward seek with no GUI reset keeps old lines and the old counter.
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        long ten = tick(tl, O + 10 * S);
        Harness straight = new Harness(r, 3).open().playTo(ten);
        Harness back = new Harness(r, 4);
        back.model.guiResetAtRestart = false;
        back.open().playTo(tick(tl, O + 20 * S)).seekTo(ten);
        assertNotEquals(straight.model.snapshot(), back.model.snapshot());
        assertEquals(straight.trace, back.trace);
    }

    @Test
    public void anEntityThatLeftAChunkBeforeItsUnloadSurvivesEverySeek() throws Exception {
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        long k = tick(tl, O + 22 * S);
        Harness straight = new Harness(r, 5).open().playTo(k);
        Harness forward = new Harness(r, 6).open().playTo(tick(tl, O + 19 * S)).seekTo(k);
        Harness backward = new Harness(r, 9).open().playTo(tick(tl, O + 29 * S)).seekTo(k);
        for (Harness h : new Harness[]{straight, forward, backward}) {
            Model.Ent mob = h.model.ents.get(MOB_MOVES_OUT);
            assertTrue("listed in chunk 0 before chunk 1 unloaded", mob != null && mob.listed == 0);
            assertEquals(10.0, mob.x, 1e-9);
            assertNull("spawn in an unloaded chunk refused", h.model.ents.get(MOB_REFUSED));
            assertNull(h.model.ents.get(MOB_REFUSED_LATER));
            assertFalse(h.model.chunks.contains(1));
            assertEquals("the dropped camera packet never applied", 0, h.model.cameraX);
        }
        assertEquals(straight.model.snapshot(), forward.model.snapshot());
        assertEquals(straight.model.snapshot(), backward.model.snapshot());
    }

    @Test
    public void feedingWithoutTicksTheRevisionOneCatchUpWouldDiverge() throws Exception {
        // The rejected design: feed the records up to the target without running world ticks. The model catches it.
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        long k = tick(tl, O + 25 * S);
        Harness straight = new Harness(r, 1).open().playTo(k);
        Harness noTicks = new Harness(r, 2).open().playTo(tick(tl, O + 15 * S));
        while (noTicks.ticks.ticksRun() < k) {
            long t = noTicks.ticks.beginTick();
            noTicks.tick = t;
            noTicks.feeder.feedForTick(t, noTicks);
            noTicks.ticks.finishTick(); // no model.tick(): what Rev 1 did
        }
        assertEquals(straight.trace, noTicks.trace);
        assertEquals("the S14 move never interpolated", 0.0, noTicks.model.ents.get(PLAYER).x, 0.0);
        assertNull("the unload removed a mob still listed in the old chunk", noTicks.model.ents.get(MOB_MOVES_OUT));
        assertNotEquals(straight.model.snapshot(), noTicks.model.snapshot());
    }

    @Test
    public void tracesAreTakenSinceTheLastRestartAndStartWithTheHeaders() throws Exception {
        LoadedRecording r = recording();
        Harness h = new Harness(r, 11).open();
        assertTrue(h.trace.get(0).startsWith("R t-1 #-1 id1 "));
        assertEquals("B t-1 id1", h.trace.get(1));
        assertTrue(h.trace.get(2).startsWith("R t-1 #-1 id9 ")); // S09 lands on the camera (amendment 1)
        assertTrue(h.trace.get(3).startsWith("R t-1 #-1 id56 ")); // S38
        assertTrue(h.trace.get(4).startsWith("R t-1 #-1 id71 ")); // S47
        assertTrue(h.trace.get(5).startsWith("R t0 #0 id1 "));
        int samples = 0;
        for (String s : h.trace) {
            if (s.startsWith("S ")) {
                samples++;
            }
        }
        assertEquals("one sample per tick of the opening", r.timeline().startTick(), samples);
    }

    @Test
    public void groupSevenTheRecordedPlayerOnTheCameraIsEqualAfterEverySeek() throws Exception {
        LoadedRecording r = recording();
        Timeline tl = r.timeline();
        long end = tl.endTick();
        long swingTick = tick(tl, clientTick(CLICK_RESTART + 2));
        long useTick = tick(tl, clientTick(USE_FROM + 5));
        long hurtTick = tick(tl, clientTick(HURT_AT + 2));
        long late = tick(tl, O + 27 * S);
        for (long k : new long[]{swingTick, useTick, hurtTick, late}) {
            for (long seed = 0; seed < 5; seed++) {
                Harness straight = new Harness(r, seed).open().playTo(k);
                Harness forward = new Harness(r, seed + 100).open().playTo(tl.startTick() + seed).seekTo(k);
                Harness backward = new Harness(r, seed + 200).open().playTo(Math.min(end, k + 40 + seed)).seekTo(k);
                assertEquals(2, backward.restarts);
                String want = straight.model.cam.snapshot() + " mode=" + straight.model.mode.recorded();
                assertEquals("forward, K=" + k, want,
                        forward.model.cam.snapshot() + " mode=" + forward.model.mode.recorded());
                assertEquals("backward, K=" + k, want,
                        backward.model.cam.snapshot() + " mode=" + backward.model.mode.recorded());
                assertEquals(straight.model.snapshot(), backward.model.snapshot());
            }
        }

        Model.Cam swinging = new Harness(r, 1).open().playTo(swingTick).model.cam;
        assertTrue("mid-swing after the restarted click", swinging.swinging);
        assertEquals(20, swinging.health);
        assertEquals("the EQUIP held stack", "E[1, 64]", swinging.inventory.get(36));

        Model.Cam using = new Harness(r, 2).open().playTo(useTick).model.cam;
        assertTrue(using.inUse);
        assertTrue(using.useCount < 72000 && using.useCount > 72000 - 10);
        assertEquals("TICK heldSlot wins over the S09", 2, using.selected);
        assertEquals("a later S2F replaces the EQUIP stack", "S112", using.inventory.get(36));
        assertEquals("E[2, 1]", using.inventory.get(38));

        Harness hit = new Harness(r, 3).open().playTo(hurtTick);
        assertTrue("your view tilts on a recorded hit", hit.model.cam.hurt > 0);
        assertEquals("the free camera never does", 0, hit.model.hand.hurtTime(false));

        Harness last = new Harness(r, 4).open().playTo(late);
        Model.Cam cam = last.model.cam;
        assertEquals(14, cam.health);
        assertEquals(18, cam.food);
        assertEquals(5, cam.xp);
        assertEquals(5, cam.selected);
        assertEquals("E[3, 16]", cam.inventory.get(36 + 5));
        assertEquals("S112", cam.inventory.get(36));
        assertEquals("[4, 1]", cam.armour[RewindFormat.EQUIP_BOOTS]);
        assertEquals("[5, 1]", cam.armour[RewindFormat.EQUIP_HELMET]);
        assertFalse("no container slot reached the camera", cam.inventory.containsKey(0));
        assertEquals("a final death: recorded spectator", ViewGameType.SPECTATOR, last.model.mode.recorded());
        assertEquals(ViewGameType.SPECTATOR, last.model.mode.forView(true));
        assertEquals(ViewGameType.SPECTATOR, last.model.mode.forView(false));
        Harness before = new Harness(r, 5).open().playTo(tick(tl, O + 25 * S));
        assertEquals("survival from S01/S07 before it", ViewGameType.SURVIVAL, before.model.mode.forView(true));
        assertEquals(ViewGameType.SPECTATOR, before.model.mode.forView(false));
    }

    /** The tick whose interval {@code [T[k], T[k+1])} holds a record stamped {@code tUs}. */
    private static long tickHolding(Timeline tl, long tUs) {
        long k = tl.ticks().tickOf(tUs);
        return tl.ticks().timeOf(k) > tUs ? k - 1 : k;
    }

    @Test
    public void groupSevenV2RecordedSwingsUseCountAndAllInventorySlotsAreEqualAfterEverySeek() throws Exception {
        LoadedRecording r = v2(true);
        Timeline tl = r.timeline();
        assertEquals(TickTable.Kind.V2, tl.ticks().kind());
        long end = tl.endTick();
        long swingTick = tickHolding(tl, OUT_SWING);
        long v1SwingTick = tick(tl, clientTick(CLICK_RESTART + 2));
        long useStart = tickHolding(tl, clientTick(USE_FROM));
        long useTick = tick(tl, clientTick(USE_FROM + 5));
        long late = tick(tl, O + 27 * S);
        for (long k : new long[]{v1SwingTick, swingTick + 1, swingTick + 2, swingTick + 5, useTick, late}) {
            for (long seed = 0; seed < 4; seed++) {
                Harness straight = new Harness(r, seed).open().playTo(k);
                Harness forward = new Harness(r, seed + 100).open().playTo(tl.startTick() + seed).seekTo(k);
                Harness backward = new Harness(r, seed + 200).open().playTo(Math.min(end, k + 40 + seed)).seekTo(k);
                assertEquals(2, backward.restarts);
                assertEquals("v2 forward, K=" + k, straight.model.snapshot(), forward.model.snapshot());
                assertEquals("v2 backward, K=" + k, straight.model.snapshot(), backward.model.snapshot());
            }
        }

        // playTo(k) runs ticks 0 to k-1: the state shown after tick k-1.
        Model.Cam before = new Harness(r, 1).open().playTo(swingTick).model.cam;
        assertFalse(before.swinging);
        Model.Cam swing = new Harness(r, 1).open().playTo(swingTick + 1).model.cam;
        assertTrue("the swing starts on the C0A's tick", swing.swinging);
        assertEquals(0, swing.swingInt);
        Model.Cam restarted = new Harness(r, 2).open().playTo(swingTick + 5).model.cam;
        assertTrue("the second C0A, four ticks on (second half), restarts it", restarted.swinging);
        assertEquals(0, restarted.swingInt);
        assertFalse("the TICK counters no longer start swings on v2",
                new Harness(r, 3).open().playTo(v1SwingTick).model.cam.swinging);

        Model.Cam using = new Harness(r, 4).open().playTo(useTick).model.cam;
        assertTrue(using.inUse);
        assertEquals("the recorded count, counted down", 32 - (useTick - 1 - useStart), using.useCount);

        Model.Cam cam = new Harness(r, 5).open().playTo(late).model.cam;
        assertEquals("INV wins over the S2F and the EQUIP stack", "I" + Arrays.toString(item(150, 2)),
                cam.inventory.get(36));
        assertEquals("I" + Arrays.toString(item(160, 1)), cam.inventory.get(20));
        assertEquals("the cursor", "I" + Arrays.toString(item(170, 3)), cam.cursor);
        assertEquals("the leggings", "I" + Arrays.toString(item(180, 1)), cam.armour[2]);
        assertEquals("the boots", "I" + Arrays.toString(item(37, 1)), cam.armour[RewindFormat.EQUIP_BOOTS]);
        for (int slot = 0; slot < RewindFormat.INV_MAIN_SLOTS; slot++) {
            assertTrue("main slot " + slot, cam.inventory.containsKey(slot < 9 ? 36 + slot : slot));
        }
        assertFalse("no container slot reached the camera", cam.inventory.containsKey(0));

        // C10: the same recording without its extension records takes no v2 path.
        Model.Cam v1 = new Harness(recording(), 5).open().playTo(late).model.cam;
        assertNull(v1.cursor);
        assertEquals("S112", v1.inventory.get(36));
    }
}
