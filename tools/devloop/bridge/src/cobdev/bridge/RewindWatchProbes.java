package cobdev.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.PotionEffect;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Step RWW's probes (cycle 2026-11-17-rewind-watch, PLAN Phase 7): open a recording of cobdev's own folder, read the
 * session's state, seek, play to an exact tick, take the seek-equivalence snapshot, quit. Cobblify's playback classes
 * are reached by reflection on their own (unremapped) names; Minecraft through its MCP names, compile-checked. Every
 * reply is counts, ticks, flags and hashes: no recording id, player name, chat line, map or position leaves the game.
 *
 * <p>Rewind parity (cycle 2026-11-18-rewind-parity, Phase 16): the snapshot adds four groups (the recorded view,
 * the open window, the dig state, the recorded Cobblify HUD), {@code rewind-watch-state} names the ticks of the first
 * recorded F5 and the first recorded chest window, and {@code rewind-watch-window} reports the held recorded screen.
 * The actions RW records for them are {@link RewindActions}.
 *
 * <p>Refusals: never on a multiplayer server ({@link Bridge#requireLocal}, which lets a Rewind recording through:
 * its world has no server behind it); open and stage only with cobdev's own Rewind folder; seek, play-to and quit
 * only while a recording plays.
 */
final class RewindWatchProbes {

    static final String PLAYBACK = "com.bedwarsqol.rewind.watch.RewindPlayback";
    static final String CAMERA = "com.bedwarsqol.rewind.watch.CameraController";
    static final String GATE = "com.bedwarsqol.rewind.watch.PlaybackGate";
    static final String SESSION = "com.bedwarsqol.rewind.watch.PlaybackSession";
    static final String PUPPET_STATE = "com.bedwarsqol.rewind.watch.PuppetState";
    static final String PLANNER = "com.bedwarsqol.rewind.watch.SeekPlanner";
    static final String TIMER_ACCESSOR = "com.bedwarsqol.mixin.MinecraftTimerAccessor";
    static final String CHAT_ACCESSOR = "com.bedwarsqol.mixin.GuiNewChatAccessor";
    static final String REWIND_CAMERA = "com.bedwarsqol.rewind.watch.RewindCamera";
    static final String DIG_PART = "com.bedwarsqol.rewind.watch.DigPart";
    static final String HUD_PLAYBACK = "com.bedwarsqol.rewind.watch.HudPlayback";
    static final String FORMAT = "com.bedwarsqol.rewind.RewindFormat";
    static final String VIEW_DECODER = "com.bedwarsqol.rewind.ExtCodec$View";
    /** The longest a play-to may wait for its tick before the caller gives up (it can always quit). */
    private static final long TICK_US = 50_000L;

    /** The last command (open, seek, play-to) and when it was given; the watcher marks when it landed. */
    private static volatile long commandNanos;
    private static volatile String command = "";
    private static volatile long commandTarget = -1;
    private static volatile long landedMs = -1;
    private static volatile boolean watcherRunning;
    /** play-to: the tick to pause at (-1 none), and the ticks run when the pause was set. */
    private static volatile long stopAt = -1;
    private static volatile long stoppedAt = -1;
    private static volatile boolean tickHookRegistered;

    private RewindWatchProbes() {
    }

    static Object run(String name, List<String> args) throws Exception {
        if ("rewind-watch-swing".equals(name)) return swing(args);
        if ("rewind-watch-stage".equals(name)) return stage(args);
        if ("rewind-watch-actions".equals(name)) return RewindActions.run(args);
        return Bridge.onMain(() -> {
            Bridge.requireLocal();
            switch (name) {
                case "rewind-watch-open":
                    return open();
                case "rewind-watch-state":
                    return state();
                case "rewind-watch-seek":
                    return seek(args);
                case "rewind-watch-play-to":
                    return playTo(args);
                case "rewind-watch-snapshot":
                    return snapshot();
                case "rewind-watch-quit":
                    return quit();
                case "rewind-watch-window":
                    return window();
                default:
                    throw new Refused("unknown probe " + name);
            }
        });
    }

    // ------------------------------------------------------------------ commands

    /**
     * {@code rewind-watch-swing ITEM}, in the singleplayer world before RW records: hotbar slot 2 (index 1) holds ITEM
     * on the server, the client selects it, and the client player swings once.
     */
    private static Map<String, Object> swing(List<String> args) throws Exception {
        String id = Policy.rewindSwingItem(args);
        Bridge.onMain(() -> {
            Bridge.requireSingleplayerWorld();
            return null;
        });
        Item item = Item.getByNameOrId(id);
        if (item == null) throw new Refused("unknown item " + id);
        Minecraft mc = Bridge.mc();
        net.minecraft.server.integrated.IntegratedServer server = mc.getIntegratedServer();
        java.util.concurrent.FutureTask<Object> put = new java.util.concurrent.FutureTask<>(() -> {
            EntityPlayerMP p = server.getConfigurationManager().getPlayerByUUID(mc.thePlayer.getUniqueID());
            if (p == null) throw new Refused("the integrated server has no player for this client yet");
            p.inventory.setInventorySlotContents(1, new ItemStack(item));
            p.inventoryContainer.detectAndSendChanges();
            return null;
        });
        server.addScheduledTask(put);
        Bridge.await(put, 10_000, "the integrated server");
        Thread.sleep(200); // the slot reaches the client
        return Bridge.onMain(() -> {
            EntityPlayerSP p = mc.thePlayer;
            p.inventory.currentItem = 1;
            p.swingItem();
            Map<String, Object> out = Bridge.ok();
            out.put("slot", p.inventory.currentItem);
            out.put("held", stack(p.getHeldItem()) != null);
            out.put("swinging", p.isSwingInProgress);
            return out;
        });
    }

    /** {@code rewind-watch-open}: the newest closed recording (by id) of cobdev's own Rewind folder. */
    private static Map<String, Object> open() throws Exception {
        if (active()) throw new Refused("a recording is already playing (rewind-watch-quit first)");
        File dir = ownFolder("rewind-watch-open");
        List<String> ids = closedIds(dir);
        if (ids.isEmpty()) throw new Refused("cobdev's Rewind folder has no closed recording");
        markCommand("open", -1);
        call(cls(PLAYBACK), "open", ids.get(ids.size() - 1));
        Map<String, Object> out = Bridge.ok();
        out.put("closed", ids.size());
        return out;
    }

    /** {@code rewind-watch-seek TICK} or {@code by MS}: the session's own seek; the reply has the target tick. */
    private static Map<String, Object> seek(List<String> args) throws Exception {
        long[] s = Policy.rewindSeek(args);
        Object session = requirePlaying();
        // The target as the session computes it (SeekPlanner), then the session's own seek, applied at the next frame.
        Class<?> planner = cls(PLANNER);
        Object timeline = call(session, "timeline");
        long tick = s[0] == 0 ? s[1] : ((Number) call(planner, "relative", timeline,
                ((Number) call(session, "playheadTick")).longValue(), s[1])).longValue();
        long target = ((Number) call(planner, "clamp", timeline, tick)).longValue();
        call(session, "seekTo", target);
        markCommand("seek", target);
        Map<String, Object> out = Bridge.ok();
        out.put("target", target);
        return out;
    }

    /**
     * {@code rewind-watch-play-to K}: plays at 4x and pauses exactly when K ticks have run (a game-thread hook after
     * every client tick). K must be ahead of the playhead.
     */
    private static Map<String, Object> playTo(List<String> args) throws Exception {
        long k = Policy.rewindPlayTo(args);
        Object session = requirePlaying();
        Object ticks = call(session, "ticks");
        long run = ((Number) call(ticks, "ticksRun")).longValue();
        if (k <= run) throw new Refused("play-to " + k + " is not ahead of the playhead (" + run + ")");
        registerTickHook();
        for (int i = 0; i < 8; i++) call(session, "faster");
        stoppedAt = -1;
        stopAt = k;
        markCommand("play-to", k);
        call(session, "setPaused", false);
        Map<String, Object> out = Bridge.ok();
        out.put("target", k);
        out.put("speed", call(ticks, "speed"));
        return out;
    }

    /** {@code rewind-watch-quit}: Quit to Title (the session's own exit at the next frame). */
    private static Map<String, Object> quit() throws Exception {
        Object session = requirePlaying();
        Class<?> exit = cls(SESSION + "$Exit");
        call(session, "requestExit", enumValue(exit, "TITLE"));
        stopAt = -1;
        markCommand("quit", -1);
        return Bridge.ok();
    }

    /**
     * {@code rewind-watch-stage DIR}: copies the recordings (and their {@code .json}) from cobdev's staging folder into
     * its own Rewind folder, for step 5's timings on a real game. The step deletes both copies afterwards.
     */
    private static Map<String, Object> stage(List<String> args) throws Exception {
        Refused.check(Policy.rewindStageDir(args));
        Bridge.onMain(() -> {
            Bridge.requireLocal();
            return null;
        });
        File to = ownFolder("rewind-watch-stage");
        File from = new File(args.get(0));
        String[] names = from.list();
        if (names == null) throw new Refused("no staging folder");
        if (!to.isDirectory() && !to.mkdirs()) throw new Refused("cannot create cobdev's Rewind folder");
        int copied = 0;
        for (String n : names) {
            File f = new File(from, n);
            if (!Policy.rewindStageFile(n) || !Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
            Files.copy(f.toPath(), new File(to, n).toPath(), StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }
        Map<String, Object> out = Bridge.ok();
        out.put("copied", copied);
        return out;
    }

    // ------------------------------------------------------------------ state

    /** {@code rewind-watch-state}: the session, the cameras, the gate and what playback must restore. */
    private static Map<String, Object> state() throws Exception {
        Minecraft mc = Bridge.mc();
        Map<String, Object> out = Bridge.ok();
        Object session = call(cls(PLAYBACK), "session");
        boolean active = active();
        out.put("active", active);
        out.put("state", String.valueOf(call(session, "state")));
        out.put("muted", call(cls(GATE), "muted"));
        out.put("camera", ((Number) call(cls(CAMERA), "cameraIndex")).intValue() == 1 ? "FREE" : "YOU");
        out.put("lastFailure", String.valueOf(call(session, "lastFailure")));
        out.put("lastExit", String.valueOf(call(session, "lastExit")));
        out.put("command", command);
        out.put("commandTarget", commandTarget);
        out.put("sinceCommandMs", commandNanos == 0 ? -1 : (System.nanoTime() - commandNanos) / 1_000_000L);
        out.put("landedMs", landedMs);
        out.put("stopAt", stopAt);
        out.put("stoppedAt", stoppedAt);
        Object timer = timer(mc);
        out.put("timerSpeed", timer == null ? null : timer.getClass().getField("timerSpeed").get(timer));
        out.put("thirdPersonView", mc.gameSettings.thirdPersonView);
        out.put("chatLines", chatLines(mc).size());
        out.put("updateCounter", mc.ingameGUI.getUpdateCounter());
        out.put("screen", mc.currentScreen == null ? null : mc.currentScreen.getClass().getSimpleName());
        EntityPlayerSP p = mc.thePlayer;
        out.put("pose", p == null ? null : hash(p.posX + "," + p.posY + "," + p.posZ + "," + p.rotationYaw + ","
                + p.rotationPitch)); // a fingerprint: whether the camera moved, never where it is
        Object timeline = call(session, "timeline");
        Object ticks = call(session, "ticks");
        Object feeder = call(session, "feeder");
        if (timeline != null && ticks != null) {
            out.put("startTick", call(timeline, "startTick"));
            out.put("endTick", call(timeline, "endTick"));
            out.put("markers", ((Collection<?>) call(timeline, "markers")).size());
            out.put("playhead", call(session, "playheadTick"));
            out.put("ticksRun", call(ticks, "ticksRun"));
            out.put("paused", call(ticks, "paused"));
            out.put("catchingUp", call(ticks, "catchingUp"));
            out.put("speed", call(ticks, "speed"));
        }
        if (feeder != null) {
            out.put("failures", call(feeder, "failures"));
            out.put("applied", call(feeder, "applied"));
        }
        Object connection = field(cls(PLAYBACK), staticInstance(), "connection");
        out.put("discarded", connection == null ? null : call(connection, "discarded"));
        if (active && timeline != null) {
            out.put("firstSwingTick", firstSwingTick(session, timeline));
            long[] view = viewTicks(session, timeline);
            out.put("perspectiveTick", view[0]);
            out.put("windowOpenTick", view[1]);
            out.put("windowCloseTick", view[2]);
        }
        hand(mc, out);
        return out;
    }

    /** Your view's hand against the recording (Rev 3 amendment 1): camera vs puppet (recorded EQUIP) vs TICK. */
    private static void hand(Minecraft mc, Map<String, Object> out) throws Exception {
        EntityPlayerSP cam = mc.thePlayer;
        Object sink = call(cls(PLAYBACK), "currentSink");
        if (cam == null || sink == null) return;
        out.put("cameraSlot", cam.inventory.currentItem);
        out.put("cameraHeld", hash(String.valueOf(stack(cam.inventory.getCurrentItem()))));
        out.put("cameraSwinging", cam.isSwingInProgress);
        out.put("cameraSwingInt", cam.swingProgressInt);
        out.put("cameraHurtTime", cam.hurtTime);
        out.put("controllerGameType", mc.playerController == null ? null
                : String.valueOf(mc.playerController.getCurrentGameType()));
        out.put("recordedGameType", String.valueOf(call(sink, "recordedGameType")));
        Object puppet = call(sink, "puppet");
        out.put("recordedHeld", puppet == null ? null
                : hash(String.valueOf(stack(((EntityLivingBase) puppet).getHeldItem()))));
        Object session = call(cls(PLAYBACK), "session");
        Object rec = call(session, "recording");
        Object track = rec == null ? null : call(rec, "selfTrack");
        Object timeline = call(session, "timeline");
        if (track != null && timeline != null) {
            long t = ((Number) call(timeline, "timeOfTick", ((Number) call(session, "playheadTick")).longValue()))
                    .longValue();
            int seg = ((Number) call(track, "segmentIndexAt", t)).intValue();
            Object segment = seg < 0 ? null : call(track, "segment", seg);
            int tick = segment == null ? -1 : ((Number) call(segment, "tickIndexAt", t)).intValue();
            if (tick >= 0) {
                out.put("recordedSlot", call(segment, "heldSlot", tick));
            }
        }
    }

    /** The recording {@link #firstSwingTick} was found for (weak) and its answer. */
    private static java.lang.ref.WeakReference<Object> swingFor = new java.lang.ref.WeakReference<>(null);
    private static long swingTick = -1;

    /** The first tick whose world tick starts a recorded swing (PuppetState's own rule), or -1; once per recording. */
    private static long firstSwingTick(Object session, Object timeline) throws Exception {
        Object rec = call(session, "recording");
        if (rec != null && rec == swingFor.get()) return swingTick;
        swingTick = findSwingTick(rec, timeline);
        swingFor = new java.lang.ref.WeakReference<>(rec);
        return swingTick;
    }

    private static long findSwingTick(Object rec, Object timeline) throws Exception {
        Object track = rec == null ? null : call(rec, "selfTrack");
        if (track == null) return -1;
        long start = ((Number) call(timeline, "startTick")).longValue();
        long end = ((Number) call(timeline, "endTick")).longValue();
        Method swingStartIn = null;
        for (Method m : cls(PUPPET_STATE).getDeclaredMethods()) {
            if (m.getName().equals("swingStartIn") && m.getParameterCount() == 3) swingStartIn = m;
        }
        if (swingStartIn == null) return -1;
        swingStartIn.setAccessible(true);
        for (long k = Math.max(1, start); k <= end; k++) {
            long t = ((Number) call(timeline, "timeOfTick", k)).longValue();
            int si = ((Number) call(track, "segmentIndexAt", t)).intValue();
            if (si < 0) continue;
            Object seg = call(track, "segment", si);
            if (Boolean.TRUE.equals(swingStartIn.invoke(null, seg, t - TICK_US, t))) return k;
        }
        return -1;
    }

    /** The recording {@link #viewTicks} was found for (weak) and its answer. */
    private static java.lang.ref.WeakReference<Object> viewFor = new java.lang.ref.WeakReference<>(null);
    private static long[] viewTicksFound = {-1, -1, -1};

    /**
     * From the recording's VIEW records (format v2; -1 each on v1): the first tick at or after the first recorded
     * F5 (perspective 1 or 2), the first tick after a chest window opened (screen kind 3), and the first tick after
     * that window's screen ended. Once per recording.
     */
    private static long[] viewTicks(Object session, Object timeline) throws Exception {
        Object rec = call(session, "recording");
        if (rec == null) return new long[]{-1, -1, -1};
        if (rec == viewFor.get()) return viewTicksFound;
        long[] out = {-1, -1, -1};
        Object ext = call(rec, "ext");
        int tagView = cls(FORMAT).getField("TAG_VIEW").getInt(null);
        int chest = cls(FORMAT).getField("SCREEN_CHEST").getInt(null);
        Object decoder = cls(VIEW_DECODER).getConstructor().newInstance();
        byte[] buf = (byte[]) call(ext, "buffer");
        int n = ((Number) call(ext, "size")).intValue();
        for (int i = 0; i < n; i++) {
            if (((Number) call(ext, "tag", i)).intValue() != tagView) continue;
            call(decoder, "read", buf, ((Number) call(ext, "offset", i)).intValue(),
                    ((Number) call(ext, "length", i)).intValue());
            int persp = decoder.getClass().getField("perspective").getInt(decoder);
            int kind = decoder.getClass().getField("screenKind").getInt(decoder);
            long t = ((Number) call(ext, "timeUs", i)).longValue();
            long tick = ((Number) call(timeline, "tickOf", t)).longValue();
            if (out[0] < 0 && persp != 0) out[0] = tick;
            if (out[1] < 0 && kind == chest) out[1] = tick + 1;
            else if (out[1] >= 0 && out[2] < 0 && kind != chest) out[2] = tick + 1;
        }
        viewTicksFound = out;
        viewFor = new java.lang.ref.WeakReference<>(rec);
        return out;
    }

    /**
     * {@code rewind-watch-window}: the held recorded screen (chest, inventory, death, chat or none), the camera's open
     * window (its slots, how many of its own slots hold a stack) and a hash of the camera's own inventory.
     */
    private static Map<String, Object> window() throws Exception {
        requirePlaying();
        Minecraft mc = Bridge.mc();
        EntityPlayerSP cam = mc.thePlayer;
        if (cam == null) throw new Refused("no playback camera yet");
        Map<String, Object> out = Bridge.ok();
        Object held = call(call(cls(REWIND_CAMERA), "screenHolder"), "held");
        out.put("held", heldKind(held));
        net.minecraft.inventory.Container c = cam.openContainer;
        boolean open = c != null && c != cam.inventoryContainer;
        out.put("windowOpen", open);
        int slots = open ? c.inventorySlots.size() : 0;
        int own = Math.max(0, slots - 36);
        int filled = 0;
        for (int i = 0; i < own; i++) {
            if (c.getSlot(i).getHasStack()) filled++;
        }
        out.put("slots", slots);
        out.put("ownSlots", own);
        out.put("ownFilled", filled);
        out.put("inventory", hash(inventory(cam)));
        return out;
    }

    private static String heldKind(Object held) {
        if (held == null) return "none";
        if (held instanceof net.minecraft.client.gui.inventory.GuiChest) return "chest";
        if (held instanceof net.minecraft.client.gui.inventory.GuiInventory) return "inventory";
        if (held instanceof net.minecraft.client.gui.GuiGameOver) return "death";
        return "other";
    }

    private static String inventory(EntityPlayerSP c) {
        StringBuilder b = new StringBuilder();
        for (ItemStack s : c.inventory.mainInventory) b.append(stack(s)).append(';');
        b.append('|');
        for (ItemStack s : c.inventory.armorInventory) b.append(stack(s)).append(';');
        return b.toString();
    }

    // ------------------------------------------------------------------ snapshot

    /**
     * {@code rewind-watch-snapshot}: one hash per group of PLAN's seek-equivalence contract, and counts. The camera
     * itself is excluded (rule b) from groups 1 and 2; non-living entities' client positions are excluded (rule c) and
     * only counted.
     */
    private static Map<String, Object> snapshot() throws Exception {
        Object session = requirePlaying();
        Minecraft mc = Bridge.mc();
        WorldClient world = mc.theWorld;
        if (world == null) throw new Refused("no playback world yet");
        Map<String, Object> groups = new TreeMap<>();
        Map<String, Object> counts = new TreeMap<>();
        List<String> missing = new ArrayList<>();

        List<Entity> entities = new ArrayList<>(world.loadedEntityList);
        entities.sort((a, b) -> Integer.compare(a.getEntityId(), b.getEntityId()));
        StringBuilder living = new StringBuilder();
        StringBuilder all = new StringBuilder();
        int nLiving = 0, nAll = 0, excluded = 0;
        for (Entity e : entities) {
            if (e == mc.thePlayer) continue;
            nAll++;
            all.append(e.getEntityId()).append('|').append(e.getClass().getName()).append('|').append(e.serverPosX)
                    .append(',').append(e.serverPosY).append(',').append(e.serverPosZ).append('|')
                    .append(watched(e)).append('\n');
            if (e instanceof EntityLivingBase) {
                nLiving++;
                EntityLivingBase l = (EntityLivingBase) e;
                living.append(l.getEntityId()).append('|').append(l.getClass().getName()).append('|')
                        .append(Double.doubleToLongBits(l.posX)).append(',').append(Double.doubleToLongBits(l.posY))
                        .append(',').append(Double.doubleToLongBits(l.posZ)).append('|')
                        .append(Float.floatToIntBits(l.rotationYaw)).append(',')
                        .append(Float.floatToIntBits(l.rotationPitch)).append(',')
                        .append(Float.floatToIntBits(l.rotationYawHead)).append('|').append(watched(l)).append('|');
                for (int i = 0; i < 5; i++) living.append(stack(l.getEquipmentInSlot(i))).append(';');
                living.append('|').append(effects(l.getActivePotionEffects())).append('|')
                        .append(l.ridingEntity == null ? -1 : l.ridingEntity.getEntityId()).append('\n');
            } else {
                excluded++;
            }
        }
        groups.put("1-living", hash(living.toString()));
        groups.put("2-entities", hash(all.toString()));
        counts.put("living", nLiving);
        counts.put("entities", nAll);
        counts.put("excludedNonLiving", excluded);

        StringBuilder chunks = new StringBuilder();
        List<Chunk> loaded = loadedChunks(world, missing);
        loaded.sort((a, b) -> a.xPosition != b.xPosition ? Integer.compare(a.xPosition, b.xPosition)
                : Integer.compare(a.zPosition, b.zPosition));
        int tiles = 0;
        for (Chunk c : loaded) {
            chunks.append(c.xPosition).append(',').append(c.zPosition).append(':');
            for (ExtendedBlockStorage s : c.getBlockStorageArray()) {
                if (s == null) {
                    chunks.append('-');
                    continue;
                }
                chunks.append(s.getYLocation()).append('=').append(hash(new String(s.getData()))).append(';');
            }
            Map<BlockPos, TileEntity> te = new TreeMap<>((a, b) -> Long.compare(a.toLong(), b.toLong()));
            te.putAll(c.getTileEntityMap());
            for (Map.Entry<BlockPos, TileEntity> t : te.entrySet()) {
                NBTTagCompound tag = new NBTTagCompound();
                t.getValue().writeToNBT(tag);
                chunks.append(t.getKey().toLong()).append('=').append(tag).append(';');
                tiles++;
            }
            chunks.append('\n');
        }
        groups.put("3-chunks", hash(chunks.toString()));
        counts.put("chunks", loaded.size());
        counts.put("tileEntities", tiles);

        groups.put("4-time", hash(world.getWorldTime() + "|" + world.getTotalWorldTime()));

        groups.put("5-scoreboard", hash(scoreboard(world.getScoreboard()) + "\n" + tab(mc, missing)));

        StringBuilder chat = new StringBuilder();
        int counter = mc.ingameGUI.getUpdateCounter();
        List<?> lines = chatLines(mc);
        for (Object o : lines) {
            net.minecraft.client.gui.ChatLine l = (net.minecraft.client.gui.ChatLine) o;
            chat.append(l.getChatComponent().getFormattedText()).append('|').append(l.getChatLineID()).append('|')
                    .append(counter - l.getUpdatedCounter()).append('\n');
        }
        chat.append(titles(mc.ingameGUI, missing));
        groups.put("6-chat-titles", hash(chat.toString()));
        counts.put("chatLines", lines.size());

        groups.put("7-camera", hash(camera(mc, session)));
        counts.put("tick", call(session, "playheadTick"));

        // Rewind parity (Phase 16): what the parts put on the playback client.
        groups.put("8-view", hash(view(mc)));
        EntityPlayerSP cam = mc.thePlayer;
        StringBuilder win = new StringBuilder();
        int windowSlots = 0;
        if (cam != null && cam.openContainer != null && cam.openContainer != cam.inventoryContainer) {
            net.minecraft.inventory.Container c = cam.openContainer;
            windowSlots = c.inventorySlots.size();
            win.append(c.windowId).append('|').append(windowSlots).append('|');
            for (int i = 0; i < windowSlots; i++) win.append(stack(c.getSlot(i).getStack())).append(';');
        }
        win.append('|').append(heldKind(call(call(cls(REWIND_CAMERA), "screenHolder"), "held")));
        groups.put("9-window", hash(win.toString()));
        counts.put("windowSlots", windowSlots);
        Object dig = call(call(cls(DIG_PART), "instance"), "state");
        groups.put("10-dig", hash(call(dig, "hitting") + "|" + call(dig, "pos") + "|" + call(dig, "stage") + "|"
                + call(dig, "shown")));
        int[] shown = new int[1];
        groups.put("11-hud", hash(hud(mc, shown)));
        counts.put("hudItemsShown", shown[0]);

        Map<String, Object> out = Bridge.ok();
        out.put("groups", groups);
        out.put("counts", counts);
        out.put("missing", missing);
        return out;
    }

    private static String watched(Entity e) {
        List<DataWatcher.WatchableObject> list = e.getDataWatcher().getAllWatched();
        if (list == null) return "";
        List<String> parts = new ArrayList<>();
        for (DataWatcher.WatchableObject w : list) {
            Object v = w.getObject();
            parts.add(w.getDataValueId() + ":" + w.getObjectType() + ":"
                    + (v instanceof ItemStack ? stack((ItemStack) v) : String.valueOf(v)));
        }
        Collections.sort(parts);
        return String.join(",", parts);
    }

    private static String effects(Collection<PotionEffect> effects) {
        List<String> parts = new ArrayList<>();
        for (PotionEffect p : effects) {
            parts.add(p.getPotionID() + ":" + p.getAmplifier() + ":" + p.getDuration() + ":" + p.getIsAmbient() + ":"
                    + p.getIsShowParticles());
        }
        Collections.sort(parts);
        return String.join(",", parts);
    }

    static String stack(ItemStack s) {
        if (s == null) return null;
        return Item.itemRegistry.getNameForObject(s.getItem()) + ":" + s.getMetadata() + "x" + s.stackSize + ":"
                + s.getTagCompound();
    }

    /** ChunkProviderClient keeps its loaded chunks in its one private List field (MCP {@code chunkListing}). */
    private static List<Chunk> loadedChunks(WorldClient world, List<String> missing) throws Exception {
        Object provider = world.getChunkProvider();
        for (Class<?> k = provider.getClass(); k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType()) && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    List<Chunk> out = new ArrayList<>();
                    for (Object o : (List<?>) f.get(provider)) out.add((Chunk) o);
                    return out;
                }
            }
        }
        missing.add("chunks");
        return new ArrayList<>();
    }

    private static String scoreboard(Scoreboard sb) {
        List<String> parts = new ArrayList<>();
        for (ScoreObjective o : sb.getScoreObjectives()) {
            parts.add("o|" + o.getName() + "|" + o.getDisplayName() + "|" + o.getCriteria().getName() + "|"
                    + o.getRenderType());
        }
        for (Score s : sb.getScores()) {
            parts.add("s|" + s.getPlayerName() + "|" + s.getObjective().getName() + "|" + s.getScorePoints());
        }
        for (ScorePlayerTeam t : sb.getTeams()) {
            List<String> members = new ArrayList<>(t.getMembershipCollection());
            Collections.sort(members);
            parts.add("t|" + t.getRegisteredName() + "|" + t.getTeamName() + "|" + t.getColorPrefix() + "|"
                    + t.getColorSuffix() + "|" + t.getAllowFriendlyFire() + "|" + t.getSeeFriendlyInvisiblesEnabled()
                    + "|" + t.getNameTagVisibility() + "|" + t.getDeathMessageVisibility() + "|" + members);
        }
        for (int i = 0; i < 19; i++) {
            ScoreObjective o = sb.getObjectiveInDisplaySlot(i);
            parts.add("d|" + i + "|" + (o == null ? "" : o.getName()));
        }
        Collections.sort(parts);
        return String.join("\n", parts);
    }

    private static String tab(Minecraft mc, List<String> missing) throws Exception {
        List<String> parts = new ArrayList<>();
        if (mc.getNetHandler() != null) {
            for (NetworkPlayerInfo i : mc.getNetHandler().getPlayerInfoMap()) {
                GameProfile p = i.getGameProfile();
                IChatComponent d = i.getDisplayName();
                parts.add(p.getId() + "|" + p.getName() + "|" + i.getGameType() + "|" + i.getResponseTime() + "|"
                        + (d == null ? "" : d.getFormattedText()));
            }
        }
        Collections.sort(parts);
        GuiPlayerTabOverlay overlay = mc.ingameGUI.getTabList();
        int found = 0;
        for (Field f : GuiPlayerTabOverlay.class.getDeclaredFields()) {
            if (IChatComponent.class.isAssignableFrom(f.getType()) && !Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                IChatComponent c = (IChatComponent) f.get(overlay);
                parts.add("hf|" + f.getName() + "|" + (c == null ? "" : c.getFormattedText()));
                found++;
            }
        }
        if (found != 2) missing.add("tab header/footer (" + found + " fields)");
        return String.join("\n", parts);
    }

    /** Title, subtitle and action bar with their timers (GuiIngame's private fields, MCP names: dev mode). */
    private static String titles(GuiIngame gui, List<String> missing) {
        StringBuilder b = new StringBuilder();
        for (String n : new String[]{"displayedTitle", "displayedSubTitle", "titlesTimer", "titleFadeIn",
                "titleDisplayTime", "titleFadeOut", "recordPlaying", "recordPlayingUpFor", "recordIsPlaying"}) {
            Object v = Mods.plain(raw(gui, n));
            if (v == null && !hasField(gui.getClass(), n)) missing.add("GuiIngame." + n);
            b.append(n).append('=').append(v).append('\n');
        }
        return b.toString();
    }

    /** Group 8: the view state the session writes (F5, F1, F3) and the recorded view it follows. */
    private static String view(Minecraft mc) throws Exception {
        Object v = call(cls(PLAYBACK), "view");
        return mc.gameSettings.thirdPersonView + "|" + mc.gameSettings.hideGUI + "|" + mc.gameSettings.showDebugInfo
                + "|" + call(v, "view") + "|" + call(v, "perspective") + "|" + call(v, "hideGui") + "|"
                + call(v, "showDebug") + "|" + call(v, "tabList") + "|" + call(v, "screenKind") + "|"
                + call(v, "clean");
    }

    /** Group 11: what the recorded Cobblify HUD shows (each item's on-screen flag, the text items' runs, the keys). */
    private static String hud(Minecraft mc, int[] shown) throws Exception {
        Field f = cls(HUD_PLAYBACK).getDeclaredField("INSTANCE");
        f.setAccessible(true);
        Object source = call(f.get(null), "source");
        StringBuilder b = new StringBuilder();
        for (int id = 1; id <= 12; id++) {
            Object name = call(cls(FORMAT), "hudModuleName", id);
            if (name == null) continue;
            boolean on = Boolean.TRUE.equals(call(source, "shows", name));
            if (on) shown[0]++;
            b.append(name).append('=').append(on).append(':');
            for (Object seg : (List<?>) call(source, "textRuns", mc, name)) {
                b.append(seg.getClass().getField("text").get(seg)).append(',');
            }
            b.append(';');
        }
        b.append(Arrays.toString((boolean[]) call(source, "keyStates", mc)));
        return b.toString();
    }

    /** Group 7: the recorded-player state on the camera (amendment 1), both views. */
    private static String camera(Minecraft mc, Object session) throws Exception {
        EntityPlayerSP c = mc.thePlayer;
        if (c == null) return "";
        StringBuilder b = new StringBuilder();
        for (ItemStack s : c.inventory.mainInventory) b.append(stack(s)).append(';');
        b.append('|');
        for (ItemStack s : c.inventory.armorInventory) b.append(stack(s)).append(';');
        b.append('|').append(c.inventory.currentItem).append('|').append(Float.floatToIntBits(c.getHealth()))
                .append('|').append(c.getFoodStats().getFoodLevel()).append('|')
                .append(Float.floatToIntBits(c.getFoodStats().getSaturationLevel())).append('|')
                .append(Float.floatToIntBits(c.experience)).append(',').append(c.experienceLevel).append(',')
                .append(c.experienceTotal).append('|').append(Float.floatToIntBits(c.getAbsorptionAmount()))
                .append('|').append(effects(c.getActivePotionEffects())).append('|').append(stack(c.getItemInUse()))
                .append(',').append(c.getItemInUseCount()).append('|').append(c.isSwingInProgress).append(',')
                .append(c.swingProgressInt).append('|').append(c.hurtTime).append('|');
        Object sink = call(cls(PLAYBACK), "currentSink");
        b.append(sink == null ? null : call(sink, "recordedGameType"));
        return b.toString();
    }

    // ------------------------------------------------------------------ the landing watcher and the tick hook

    private static void markCommand(String kind, long target) {
        command = kind;
        commandTarget = target;
        landedMs = -1;
        commandNanos = System.nanoTime();
        startWatcher();
    }

    /** Once a frame (a game-thread task) until the command landed: WATCHING, not catching up, on its target. */
    private static synchronized void startWatcher() {
        if (watcherRunning) return;
        watcherRunning = true;
        Thread t = new Thread(() -> {
            try {
                while (landedMs < 0 && System.nanoTime() - commandNanos < 120_000_000_000L) {
                    Minecraft m = Minecraft.getMinecraft();
                    if (m == null) break;
                    m.addScheduledTask(RewindWatchProbes::checkLanded).get(5, java.util.concurrent.TimeUnit.SECONDS);
                    Thread.sleep(1);
                }
            } catch (Throwable ignored) {
                // the state probe still reports sinceCommandMs
            } finally {
                watcherRunning = false;
            }
        }, "cobdev-rewind-watch");
        t.setDaemon(true);
        t.start();
    }

    private static void checkLanded() {
        try {
            if (landedMs >= 0) return;
            Object session = call(cls(PLAYBACK), "session");
            String state = String.valueOf(call(session, "state"));
            if ("quit".equals(command)) {
                if ("IDLE".equals(state)) landedMs = (System.nanoTime() - commandNanos) / 1_000_000L;
                return;
            }
            Object ticks = call(session, "ticks");
            if (!"WATCHING".equals(state) || ticks == null || Boolean.TRUE.equals(call(ticks, "catchingUp"))) return;
            long playhead = ((Number) call(session, "playheadTick")).longValue();
            if ("play-to".equals(command)) {
                if (stoppedAt < 0) return;
            } else if (commandTarget >= 0 && playhead != commandTarget) {
                return;
            }
            landedMs = (System.nanoTime() - commandNanos) / 1_000_000L;
        } catch (Exception ignored) {
            // the next frame tries again
        }
    }

    /** After every client tick: a play-to pauses as soon as its tick count is reached. */
    private static synchronized void registerTickHook() {
        if (tickHookRegistered) return;
        try {
            net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents.TICK_END.register(mc -> onTickEnd());
        } catch (LinkageError e) {
            throw new Refused("play-to needs OSL's client tick event: " + e);
        }
        tickHookRegistered = true;
    }

    private static void onTickEnd() {
        if (stopAt < 0) return;
        try {
            Object session = call(cls(PLAYBACK), "session");
            if (!"WATCHING".equals(String.valueOf(call(session, "state")))) return;
            Object ticks = call(session, "ticks");
            if (ticks == null || Boolean.TRUE.equals(call(ticks, "catchingUp"))) return;
            long run = ((Number) call(ticks, "ticksRun")).longValue();
            if (run >= stopAt) {
                call(session, "setPaused", true);
                stoppedAt = run;
                stopAt = -1;
            }
        } catch (Exception ignored) {
            // the step sees stoppedAt stay -1 and fails
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean active() throws Exception {
        return Boolean.TRUE.equals(call(cls(PLAYBACK), "active"));
    }

    private static Object requirePlaying() throws Exception {
        Object session = call(cls(PLAYBACK), "session");
        String state = String.valueOf(call(session, "state"));
        if (!"WATCHING".equals(state) && !"CATCHING_UP".equals(state)) {
            throw new Refused("no recording is playing (state " + state + ")");
        }
        return session;
    }

    private static File ownFolder(String probe) throws Exception {
        Class<?> c = cls(Probes.REWIND_DIR);
        File dir = (File) c.getMethod("get").invoke(null);
        Refused.check(Policy.rewindOwnFolder(probe, System.getProperty(Policy.REWIND_DIR_PROPERTY),
                dir.getAbsolutePath(), System.getProperty("user.home")));
        return dir;
    }

    /** Closed recordings' ids (their {@code .json} says complete), oldest first. */
    private static List<String> closedIds(File dir) {
        List<String> out = new ArrayList<>();
        String[] names = dir.list();
        if (names == null) return out;
        Arrays.sort(names);
        for (String n : names) {
            if (!n.endsWith(".cbr") || !Policy.rewindFile(n)) continue;
            String id = n.substring(0, n.length() - 4);
            File json = new File(dir, id + ".json");
            if (!Files.isRegularFile(json.toPath(), LinkOption.NOFOLLOW_LINKS)) continue;
            try (Reader r = new InputStreamReader(new FileInputStream(json), StandardCharsets.UTF_8)) {
                JsonElement e = new JsonParser().parse(r);
                JsonElement complete = e.isJsonObject() ? e.getAsJsonObject().get("complete") : null;
                if (complete != null && complete.isJsonPrimitive() && complete.getAsBoolean()) out.add(id);
            } catch (Exception ignored) {
                // being written or broken
            }
        }
        return out;
    }

    private static List<?> chatLines(Minecraft mc) throws Exception {
        Object chat = mc.ingameGUI.getChatGUI();
        Method m = cls(CHAT_ACCESSOR).getMethod("bedwarsqol$getChatLines");
        return (List<?>) m.invoke(chat);
    }

    private static Object timer(Minecraft mc) {
        try {
            return cls(TIMER_ACCESSOR).getMethod("bedwarsqol$getTimer").invoke(mc);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static Object staticInstance() throws Exception {
        Field f = cls(PLAYBACK).getDeclaredField("INSTANCE");
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object field(Class<?> c, Object target, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static boolean hasField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                k.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException ignored) {
                // up
            }
        }
        return false;
    }

    private static Object raw(Object o, String name) {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // up
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    static Class<?> cls(String name) {
        try {
            return Class.forName(name, true, RewindWatchProbes.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new Refused("this build has no " + name + " (Rewind playback)");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    /** Calls {@code name} with {@code args} on {@code target} (a Class for a static method), any visibility. */
    static Object call(Object target, String name, Object... args) throws Exception {
        Class<?> start = target instanceof Class ? (Class<?>) target : target.getClass();
        Object self = target instanceof Class ? null : target;
        for (Class<?> k = start; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                if (!fits(m.getParameterTypes(), args)) continue;
                m.setAccessible(true);
                try {
                    return m.invoke(self, args);
                } catch (InvocationTargetException e) {
                    Throwable c = e.getCause();
                    if (c instanceof Exception) throw (Exception) c;
                    if (c instanceof Error) throw (Error) c;
                    throw e;
                }
            }
        }
        throw new Refused(start.getName() + " has no " + name + " with " + args.length + " arguments");
    }

    private static boolean fits(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            Object a = args[i];
            if (a == null) {
                if (t.isPrimitive()) return false;
                continue;
            }
            if (t == long.class) t = Long.class;
            else if (t == int.class) t = Integer.class;
            else if (t == boolean.class) t = Boolean.class;
            else if (t == float.class) t = Float.class;
            else if (t == double.class) t = Double.class;
            if (!t.isInstance(a)) return false;
        }
        return true;
    }

    /** First 16 hex digits of SHA-256: a group's fingerprint, never its content. */
    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
