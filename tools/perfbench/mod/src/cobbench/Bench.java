package cobbench;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.ChatComponentText;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/**
 * Drives one benchmark run from the frame loop (main thread only).
 *
 * <p>-Dcobbench.mode=prepare: create the world "bench" from a fixed seed, build the scene (time, weather,
 * no mob spawning, a platform with 48 armoured, name-tagged NoAI pigmen to the north), save, quit.
 * -Dcobbench.mode=run: load "bench", hold the camera on scene A (terrain, yaw 0) then scene B (the
 * entities, yaw 180), record every frame time, write CSVs + meta JSON to -Dcobbench.out, quit.
 * Without the property the mod does nothing.
 */
public final class Bench {
    private static final String MODE = System.getProperty("cobbench.mode", "off");
    private static final File OUT = new File(System.getProperty("cobbench.out", "cobbench-out"));
    private static final String LABEL = System.getProperty("cobbench.label", "run");
    private static final long SEED = Long.getLong("cobbench.seed", 20261114L);
    private static final double WARMUP_S = dbl("cobbench.warmup", 25);
    private static final double MEASURE_S = dbl("cobbench.measure", 30);
    private static final double SETTLE_S = dbl("cobbench.settle", 6);
    private static final String WORLD = "bench";

    private static final float[] YAW = {0f, 180f, 180f};
    private static final float[] PITCH = {15f, 14f, 14f};
    /** A terrain, B entities, C entities + Hypixel-like sidebar and chat (HypixelLoad). */
    private static final String[] SCENE = {"A", "B", "C"};
    private static boolean chatOn;
    private static long nextChat;

    private enum S { BOOT, LOADING, WARMUP, MEASURE, SETTLE, DONE, PREP_WAIT, PREP_BUILT, PREP_SAVED, EXIT }

    private static S state = S.BOOT;
    private static long bootFrames;
    private static long tBoot;
    private static long tState;
    private static long tInWorld;
    private static int scene;
    private static long firstFrameMs = -1;
    private static long worldReadyMs = -1;

    private static final long[] buf = new long[2_000_000];
    private static int n;
    private static long last;
    private static int activeSamples;
    private static int activeTrue;
    private static float maxRotDrift;
    private static long gcCount0;
    private static long gcTime0;
    private static double posX0, posY0, posZ0;

    private static final List<String> sceneJson = new ArrayList<>();
    private static final List<String> notes = new ArrayList<>();
    private static String glRenderer = "?";
    private static String glVersion = "?";
    private static String glVendor = "?";

    private Bench() {}

    private static double dbl(String key, double def) {
        try {
            return Double.parseDouble(System.getProperty(key, Double.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double secs(long from, long to) {
        return (to - from) / 1e9;
    }

    public static void frame(Minecraft mc) {
        if ("off".equals(MODE)) return;
        long now = System.nanoTime();

        // Hot path first: one store per frame while measuring.
        if (chatOn && now >= nextChat) {
            nextChat += HypixelLoad.CHAT_INTERVAL_NS;
            mc.getNetHandler().handleChat(new S02PacketChat(new ChatComponentText(HypixelLoad.nextLine())));
        }
        if (state == S.MEASURE) {
            buf[n++] = now - last;
            last = now;
            if ((n & 63) == 0) {
                activeSamples++;
                if (Display.isActive()) activeTrue++;
            }
            hold(mc);
            if (secs(tState, now) >= MEASURE_S || n >= buf.length) endScene(mc, now);
            return;
        }

        switch (state) {
            case BOOT:
                if (bootFrames++ == 0) {
                    tBoot = now;
                    firstFrameMs = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime();
                }
                if (bootFrames > 120 && secs(tBoot, now) > 3 && mc.theWorld == null) {
                    glRenderer = GL11.glGetString(GL11.GL_RENDERER);
                    glVersion = GL11.glGetString(GL11.GL_VERSION);
                    glVendor = GL11.glGetString(GL11.GL_VENDOR);
                    WorldSettings settings = null;
                    if ("prepare".equals(MODE)) {
                        settings = new WorldSettings(SEED, WorldSettings.GameType.CREATIVE, true, false, WorldType.DEFAULT)
                                .enableCommands();
                    }
                    notes.add("screen at launch: " + (mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getName()));
                    mc.launchIntegratedServer(WORLD, WORLD, settings);
                    to(S.LOADING, now);
                }
                return;
            case LOADING:
                if (secs(tState, now) > 240) {
                    notes.add("timed out loading the world");
                    finish(mc);
                    return;
                }
                if (mc.theWorld == null || mc.thePlayer == null) return;
                if (tInWorld == 0) tInWorld = now;
                if (mc.currentScreen != null) {
                    if (secs(tInWorld, now) > 15) {
                        notes.add("closed screen in world: " + mc.currentScreen.getClass().getName());
                        mc.displayGuiScreen(null);
                    }
                    return;
                }
                worldReadyMs = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime();
                to("prepare".equals(MODE) ? S.PREP_WAIT : S.WARMUP, now);
                return;
            case WARMUP:
                hold(mc);
                if (secs(tState, now) >= WARMUP_S && focused(now, WARMUP_S)) startScene(mc, now);
                return;
            case SETTLE:
                hold(mc);
                if (secs(tState, now) >= SETTLE_S && focused(now, SETTLE_S)) startScene(mc, now);
                return;
            case PREP_WAIT:
                if (secs(tState, now) >= 10) {
                    buildScene(mc);
                    to(S.PREP_BUILT, now);
                }
                return;
            case PREP_BUILT:
                hold(mc);
                if (secs(tState, now) >= 25) {
                    MinecraftServer s = mc.getIntegratedServer();
                    s.addScheduledTask(() -> s.getCommandManager().executeCommand(s, "/save-all"));
                    to(S.PREP_SAVED, now);
                }
                return;
            case PREP_SAVED:
                hold(mc);
                if (secs(tState, now) >= 8) {
                    shot(mc, "prepare");
                    finish(mc);
                }
                return;
            case DONE:
                finish(mc);
                return;
            default:
        }
    }

    /**
     * A scene starts only with the window focused (Dynamic FPS and similar mods throttle an unfocused
     * window), held for 2 s; after 90 s extra waiting it starts anyway and says so.
     */
    private static long focusSince;

    private static boolean focused(long now, double nominal) {
        if (Display.isActive()) {
            if (focusSince == 0) focusSince = now;
            if (secs(focusSince, now) >= 2) return true;
        } else {
            focusSince = 0;
        }
        if (secs(tState, now) >= nominal + 90) {
            notes.add("scene " + SCENE[scene] + " started without focus");
            return true;
        }
        return false;
    }

    private static void to(S next, long now) {
        state = next;
        tState = now;
    }

    /** Fixed camera; reports how far anything else had moved it since the last frame. */
    private static void hold(Minecraft mc) {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null) return;
        int i = state == S.PREP_BUILT || state == S.PREP_SAVED ? 0 : scene;
        float yaw = YAW[i];
        float pitch = PITCH[i];
        if (state == S.MEASURE) {
            float d = Math.max(Math.abs(p.rotationYaw - yaw), Math.abs(p.rotationPitch - pitch));
            if (d > maxRotDrift) maxRotDrift = d;
        }
        p.rotationYaw = p.prevRotationYaw = yaw;
        p.rotationPitch = p.prevRotationPitch = pitch;
    }

    private static void startScene(Minecraft mc, long now) {
        n = 0;
        activeSamples = 0;
        activeTrue = 0;
        maxRotDrift = 0;
        long[] gc = gc();
        gcCount0 = gc[0];
        gcTime0 = gc[1];
        posX0 = mc.thePlayer.posX;
        posY0 = mc.thePlayer.posY;
        posZ0 = mc.thePlayer.posZ;
        last = System.nanoTime();
        to(S.MEASURE, last);
    }

    private static void endScene(Minecraft mc, long now) {
        long[] gc = gc();
        double moved = Math.sqrt(sq(mc.thePlayer.posX - posX0) + sq(mc.thePlayer.posY - posY0) + sq(mc.thePlayer.posZ - posZ0));
        String name = SCENE[scene];
        File csv = new File(OUT, LABEL + "-frames-" + name + ".csv");
        try (Writer w = writer(csv)) {
            StringBuilder sb = new StringBuilder(n * 9);
            for (int k = 0; k < n; k++) sb.append(buf[k]).append('\n');
            w.write(sb.toString());
        } catch (IOException e) {
            notes.add("csv write failed: " + e);
        }
        sceneJson.add(String.format(Locale.ROOT,
                "{\"scene\":\"%s\",\"frames\":%d,\"seconds\":%.3f,\"gcCount\":%d,\"gcMs\":%d,"
                        + "\"activeSamples\":%d,\"activeTrue\":%d,\"maxRotDrift\":%.3f,\"moved\":%.4f,"
                        + "\"entities\":%d,\"debugFps\":%d}",
                name, n, secs(tState, now), gc[0] - gcCount0, gc[1] - gcTime0, activeSamples, activeTrue,
                maxRotDrift, moved, mc.theWorld.loadedEntityList.size(), Minecraft.getDebugFPS()));
        shot(mc, name);
        scene++;
        if (scene < SCENE.length) {
            if ("C".equals(SCENE[scene])) {
                sidebar(mc.theWorld.getScoreboard());
                chatOn = true;
                nextChat = System.nanoTime();
            }
            hold(mc);
            to(S.SETTLE, System.nanoTime());
        } else {
            to(S.DONE, System.nanoTime());
        }
    }

    /** A Hypixel Bed Wars sidebar, built client-side the way Hypixel's packets build it (team prefix/suffix). */
    private static void sidebar(Scoreboard sb) {
        ScoreObjective o = sb.addScoreObjective("cobbench", IScoreObjectiveCriteria.DUMMY);
        o.setDisplayName(HypixelLoad.TITLE);
        sb.setObjectiveInDisplaySlot(1, o);
        String[] lines = HypixelLoad.SIDEBAR;
        for (int i = 0; i < lines.length; i++) {
            String holder = HypixelLoad.holder(i);
            ScorePlayerTeam t = sb.createTeam("cobbench" + i);
            t.setNamePrefix(HypixelLoad.prefix(lines[i]));
            t.setNameSuffix(HypixelLoad.suffix(lines[i]));
            sb.addPlayerToTeam(holder, t.getRegisteredName());
            sb.getValueFromObjective(holder, o).setScorePoints(lines.length - i);
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private static long[] gc() {
        long c = 0;
        long t = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            c += Math.max(0, b.getCollectionCount());
            t += Math.max(0, b.getCollectionTime());
        }
        return new long[] {c, t};
    }

    private static void shot(Minecraft mc, String name) {
        try {
            ScreenShotHelper.saveScreenshot(OUT, LABEL + "-" + name + ".png", mc.displayWidth, mc.displayHeight,
                    mc.getFramebuffer());
        } catch (Throwable t) {
            notes.add("screenshot failed: " + t);
        }
    }

    /** Prepare mode: runs on the integrated server's thread; the server is the command sender. */
    private static void buildScene(Minecraft mc) {
        final MinecraftServer s = mc.getIntegratedServer();
        final String player = mc.thePlayer.getName();
        s.addScheduledTask(() -> {
            EntityPlayerMP p = s.getConfigurationManager().getPlayerByUsername(player);
            WorldServer w = s.worldServerForDimension(0);
            int x = MathHelper.floor_double(p.posX);
            int z = MathHelper.floor_double(p.posZ);
            int ground = w.getHeight(new BlockPos(x, 0, z)).getY();
            int y = Math.max(ground + 20, 95);
            p.capabilities.allowFlying = true;
            p.capabilities.isFlying = true;
            p.sendPlayerAbilities();
            String[] cmds = {
                    "/gamerule doDaylightCycle false",
                    "/time set 6000",
                    "/weather clear 1000000",
                    "/gamerule doMobSpawning false",
                    "/gamerule randomTickSpeed 0",
                    "/gamerule doFireTick false",
                    "/kill @e[type=!Player]",
                    "/tp " + player + " " + (x + 0.5) + " " + y + " " + (z + 0.5) + " 0 15",
                    "/fill " + (x - 9) + " " + (y - 4) + " " + (z - 22) + " " + (x + 9) + " " + (y - 4) + " " + (z - 8)
                            + " minecraft:stone",
            };
            for (String c : cmds) s.getCommandManager().executeCommand(s, c);
            int k = 0;
            for (int dz = -10; dz >= -20; dz -= 2) {
                for (int dx = -7; dx <= 7; dx += 2) {
                    String nbt = "{NoAI:1b,Silent:1b,PersistenceRequired:1b,CustomName:\"Bench" + (k++)
                            + "\",CustomNameVisible:1b,Equipment:[{id:\"minecraft:diamond_sword\",Count:1b},"
                            + "{id:\"minecraft:diamond_boots\",Count:1b},{id:\"minecraft:diamond_leggings\",Count:1b},"
                            + "{id:\"minecraft:diamond_chestplate\",Count:1b},{id:\"minecraft:diamond_helmet\",Count:1b}]}";
                    s.getCommandManager().executeCommand(s, "/summon PigZombie " + (x + dx + 0.5) + " " + (y - 3) + " "
                            + (z + dz + 0.5) + " " + nbt);
                }
            }
            notes.add("scene at " + x + " " + y + " " + z + " (ground " + ground + "), " + k + " entities");
        });
        mc.thePlayer.capabilities.allowFlying = true;
        mc.thePlayer.capabilities.isFlying = true;
    }

    private static void finish(Minecraft mc) {
        if (state == S.EXIT) return;
        state = S.EXIT;
        writeMeta(mc);
        mc.shutdown();
    }

    private static Writer writer(File f) throws IOException {
        f.getParentFile().mkdirs();
        return new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
    }

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private static void writeMeta(Minecraft mc) {
        StringBuilder mods = new StringBuilder();
        for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
            if (mods.length() > 0) mods.append(',');
            mods.append(q(m.getMetadata().getId() + " " + m.getMetadata().getVersion().getFriendlyString()));
        }
        StringBuilder args = new StringBuilder();
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (args.length() > 0) args.append(',');
            args.append(q(a));
        }
        StringBuilder gcs = new StringBuilder();
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gcs.length() > 0) gcs.append(',');
            gcs.append(q(b.getName()));
        }
        StringBuilder nts = new StringBuilder();
        for (String s : notes) {
            if (nts.length() > 0) nts.append(',');
            nts.append(q(s));
        }
        String json = "{\n"
                + " \"label\":" + q(LABEL) + ",\n \"mode\":" + q(MODE) + ",\n"
                + " \"scenes\":[" + String.join(",", sceneJson) + "],\n"
                + " \"firstFrameMs\":" + firstFrameMs + ",\n \"worldReadyMs\":" + worldReadyMs + ",\n"
                + " \"java\":" + q(System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version")) + ",\n"
                + " \"jvmArgs\":[" + args + "],\n \"gc\":[" + gcs + "],\n"
                + " \"maxHeapMb\":" + (Runtime.getRuntime().maxMemory() >> 20) + ",\n"
                + " \"cpus\":" + Runtime.getRuntime().availableProcessors() + ",\n"
                + " \"gl\":" + q(glVendor + " | " + glRenderer + " | " + glVersion) + ",\n"
                + " \"display\":\"" + mc.displayWidth + "x" + mc.displayHeight + "\",\n"
                + " \"fullscreen\":" + mc.isFullScreen() + ",\n"
                + " \"renderDistance\":" + mc.gameSettings.renderDistanceChunks + ",\n"
                + " \"limitFramerate\":" + mc.gameSettings.limitFramerate + ",\n"
                + " \"vsync\":" + mc.gameSettings.enableVsync + ",\n"
                + " \"fancy\":" + mc.gameSettings.fancyGraphics + ",\n"
                + " \"guiScale\":" + mc.gameSettings.guiScale + ",\n"
                + " \"mods\":[" + mods + "],\n"
                + " \"notes\":[" + nts + "]\n}\n";
        try (Writer w = writer(new File(OUT, LABEL + "-meta.json"))) {
            w.write(json);
        } catch (IOException e) {
            System.err.println("[cobbench] meta write failed: " + e);
        }
    }
}
