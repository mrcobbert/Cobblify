package cobbench.forge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/**
 * Forge 1.8.9 twin of tools/perfbench/mod (cobbench.Bench), run mode only: same world, scenes, timings and
 * output files. One call per frame from RenderTickEvent START (fired once per frame in runGameLoop).
 * Minecraft members are reached by their SRG names (MCP stable_22), so this compiles against the Forge
 * universal jar alone and runs in a production Forge instance.
 */
@Mod(modid = "cobbench", name = "cobbench (benchmark only)", version = "1.0.0", acceptableRemoteVersions = "*")
public class ForgeBench {
    private static final String MODE = System.getProperty("cobbench.mode", "off");
    private static final File OUT = new File(System.getProperty("cobbench.out", "cobbench-out"));
    private static final String LABEL = System.getProperty("cobbench.label", "run");
    private static final double WARMUP_S = dbl("cobbench.warmup", 25);
    private static final double MEASURE_S = dbl("cobbench.measure", 30);
    private static final double SETTLE_S = dbl("cobbench.settle", 6);
    private static final float[] YAW = {0f, 180f, 180f};
    private static final float[] PITCH = {15f, 14f, 14f};
    private static final String[] SCENE = {"A", "B", "C"};
    private static boolean chatOn;
    private static long nextChat;

    private enum S { BOOT, LOADING, WARMUP, MEASURE, SETTLE, DONE, EXIT }

    private static S state = S.BOOT;
    private static long bootFrames, tBoot, tState, tInWorld, focusSince;
    private static int scene;
    private static long firstFrameMs = -1, worldReadyMs = -1;
    private static final long[] buf = new long[2_000_000];
    private static int n, activeSamples, activeTrue;
    private static long last, gcCount0, gcTime0;
    private static float maxRotDrift;
    private static double posX0, posY0, posZ0;
    private static final List<String> sceneJson = new ArrayList<>();
    private static final List<String> notes = new ArrayList<>();
    private static String glInfo = "?";

    // Reflection (SRG names).
    private static Object mc;
    private static Field fWorld, fPlayer, fScreen, fDisplayW, fDisplayH, fSettings;
    private static Field fYaw, fPitch, fPrevYaw, fPrevPitch, fPosX, fPosY, fPosZ, fEntities;
    private static Field fRenderDist, fLimitFps, fVsync, fFancy, fGuiScale;
    private static Method mLaunch, mDisplayGui, mShutdown, mDebugFps, mFramebuffer, mIsFull, mShot;
    private static Method mNetHandler, mHandleChat, mGetScoreboard, mAddObjective, mSetDisplayName, mSetSlot,
            mCreateTeam, mPrefix, mSuffix, mAddToTeam, mGetScore, mSetPoints;
    private static java.lang.reflect.Constructor<?> cPacketChat, cChatText;
    private static Object dummyCriteria;

    private static double dbl(String key, double def) {
        try {
            return Double.parseDouble(System.getProperty(key, Double.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        if ("off".equals(MODE)) return;
        try {
            Class<?> mcC = Class.forName("net.minecraft.client.Minecraft");
            Class<?> entity = Class.forName("net.minecraft.entity.Entity");
            Class<?> world = Class.forName("net.minecraft.world.World");
            Class<?> settings = Class.forName("net.minecraft.client.settings.GameSettings");
            Class<?> ws = Class.forName("net.minecraft.world.WorldSettings");
            Class<?> screen = Class.forName("net.minecraft.client.gui.GuiScreen");
            Class<?> fb = Class.forName("net.minecraft.client.shader.Framebuffer");
            mc = mcC.getMethod("func_71410_x").invoke(null);
            fWorld = f(mcC, "field_71441_e");
            fPlayer = f(mcC, "field_71439_g");
            fScreen = f(mcC, "field_71462_r");
            fDisplayW = f(mcC, "field_71443_c");
            fDisplayH = f(mcC, "field_71440_d");
            fSettings = f(mcC, "field_71474_y");
            fYaw = f(entity, "field_70177_z");
            fPitch = f(entity, "field_70125_A");
            fPrevYaw = f(entity, "field_70126_B");
            fPrevPitch = f(entity, "field_70127_C");
            fPosX = f(entity, "field_70165_t");
            fPosY = f(entity, "field_70163_u");
            fPosZ = f(entity, "field_70161_v");
            fEntities = f(world, "field_72996_f");
            fRenderDist = f(settings, "field_151451_c");
            fLimitFps = f(settings, "field_74350_i");
            fVsync = f(settings, "field_74352_v");
            fFancy = f(settings, "field_74347_j");
            fGuiScale = f(settings, "field_74335_Z");
            mLaunch = m(mcC, "func_71371_a", String.class, String.class, ws);
            mDisplayGui = m(mcC, "func_147108_a", screen);
            mShutdown = m(mcC, "func_71400_g");
            mDebugFps = m(mcC, "func_175610_ah");
            mFramebuffer = m(mcC, "func_147110_a");
            mIsFull = m(mcC, "func_71372_G");
            mShot = m(Class.forName("net.minecraft.util.ScreenShotHelper"), "func_148259_a", File.class, String.class,
                    int.class, int.class, fb);
            Class<?> packet = Class.forName("net.minecraft.network.play.server.S02PacketChat");
            Class<?> component = Class.forName("net.minecraft.util.IChatComponent");
            Class<?> sbC = Class.forName("net.minecraft.scoreboard.Scoreboard");
            Class<?> objC = Class.forName("net.minecraft.scoreboard.ScoreObjective");
            Class<?> crit = Class.forName("net.minecraft.scoreboard.IScoreObjectiveCriteria");
            Class<?> teamC = Class.forName("net.minecraft.scoreboard.ScorePlayerTeam");
            cPacketChat = packet.getConstructor(component);
            cChatText = Class.forName("net.minecraft.util.ChatComponentText").getConstructor(String.class);
            mNetHandler = m(mcC, "func_147114_u");
            mHandleChat = Class.forName("net.minecraft.network.play.INetHandlerPlayClient").getMethod("func_147251_a", packet);
            mGetScoreboard = m(world, "func_96441_U");
            mAddObjective = m(sbC, "func_96535_a", String.class, crit);
            mSetDisplayName = m(objC, "func_96681_a", String.class);
            mSetSlot = m(sbC, "func_96530_a", int.class, objC);
            mCreateTeam = m(sbC, "func_96527_f", String.class);
            mPrefix = m(teamC, "func_96666_b", String.class);
            mSuffix = m(teamC, "func_96662_c", String.class);
            mAddToTeam = m(sbC, "func_151392_a", String.class, String.class);
            mGetScore = m(sbC, "func_96529_a", String.class, objC);
            mSetPoints = m(Class.forName("net.minecraft.scoreboard.Score"), "func_96647_c", int.class);
            dummyCriteria = f(crit, "field_96641_b").get(null);
        } catch (Throwable t) {
            notes.add("reflection setup failed: " + t);
            System.err.println("[cobbench] reflection setup failed: " + t);
        }
        FMLCommonHandler.instance().bus().register(this);
    }

    private static Field f(Class<?> c, String name) throws NoSuchFieldException {
        Field x = c.getDeclaredField(name);
        x.setAccessible(true);
        return x;
    }

    private static Method m(Class<?> c, String name, Class<?>... args) throws NoSuchMethodException {
        Method x = c.getDeclaredMethod(name, args);
        x.setAccessible(true);
        return x;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        try {
            frame();
        } catch (Throwable t) {
            notes.add("frame failed: " + t);
            System.err.println("[cobbench] frame failed: " + t);
            t.printStackTrace();
            finish();
        }
    }

    private static double secs(long from, long to) {
        return (to - from) / 1e9;
    }

    private static void frame() throws Exception {
        long now = System.nanoTime();
        if (chatOn && now >= nextChat) {
            nextChat += HypixelLoad.CHAT_INTERVAL_NS;
            mHandleChat.invoke(mNetHandler.invoke(mc), cPacketChat.newInstance(cChatText.newInstance(HypixelLoad.nextLine())));
        }
        if (state == S.MEASURE) {
            buf[n++] = now - last;
            last = now;
            if ((n & 63) == 0) {
                activeSamples++;
                if (Display.isActive()) activeTrue++;
            }
            hold();
            if (secs(tState, now) >= MEASURE_S || n >= buf.length) endScene(now);
            return;
        }
        switch (state) {
            case BOOT:
                if (bootFrames++ == 0) {
                    tBoot = now;
                    firstFrameMs = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime();
                }
                if (bootFrames > 120 && secs(tBoot, now) > 3 && fWorld.get(mc) == null) {
                    glInfo = GL11.glGetString(GL11.GL_VENDOR) + " | " + GL11.glGetString(GL11.GL_RENDERER) + " | "
                            + GL11.glGetString(GL11.GL_VERSION);
                    Object scr = fScreen.get(mc);
                    notes.add("screen at launch: " + (scr == null ? "none" : scr.getClass().getName()));
                    mLaunch.invoke(mc, "bench", "bench", null);
                    to(S.LOADING, now);
                }
                return;
            case LOADING:
                if (secs(tState, now) > 240) {
                    notes.add("timed out loading the world");
                    finish();
                    return;
                }
                if (fWorld.get(mc) == null || fPlayer.get(mc) == null) return;
                if (tInWorld == 0) tInWorld = now;
                Object scr = fScreen.get(mc);
                if (scr != null) {
                    if (secs(tInWorld, now) > 15) {
                        notes.add("closed screen in world: " + scr.getClass().getName());
                        mDisplayGui.invoke(mc, (Object) null);
                    }
                    return;
                }
                worldReadyMs = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime();
                to(S.WARMUP, now);
                return;
            case WARMUP:
                hold();
                if (secs(tState, now) >= WARMUP_S && focused(now, WARMUP_S)) startScene();
                return;
            case SETTLE:
                hold();
                if (secs(tState, now) >= SETTLE_S && focused(now, SETTLE_S)) startScene();
                return;
            case DONE:
                finish();
                return;
            default:
        }
    }

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

    private static void hold() throws IllegalAccessException {
        Object p = fPlayer.get(mc);
        if (p == null) return;
        float yaw = YAW[scene];
        float pitch = PITCH[scene];
        if (state == S.MEASURE) {
            float d = Math.max(Math.abs(fYaw.getFloat(p) - yaw), Math.abs(fPitch.getFloat(p) - pitch));
            if (d > maxRotDrift) maxRotDrift = d;
        }
        fYaw.setFloat(p, yaw);
        fPrevYaw.setFloat(p, yaw);
        fPitch.setFloat(p, pitch);
        fPrevPitch.setFloat(p, pitch);
    }

    private static void startScene() throws IllegalAccessException {
        n = 0;
        activeSamples = 0;
        activeTrue = 0;
        maxRotDrift = 0;
        long[] gc = gc();
        gcCount0 = gc[0];
        gcTime0 = gc[1];
        Object p = fPlayer.get(mc);
        posX0 = fPosX.getDouble(p);
        posY0 = fPosY.getDouble(p);
        posZ0 = fPosZ.getDouble(p);
        last = System.nanoTime();
        to(S.MEASURE, last);
    }

    private static void endScene(long now) throws Exception {
        long[] gc = gc();
        Object p = fPlayer.get(mc);
        double dx = fPosX.getDouble(p) - posX0, dy = fPosY.getDouble(p) - posY0, dz = fPosZ.getDouble(p) - posZ0;
        String name = SCENE[scene];
        try (Writer w = writer(new File(OUT, LABEL + "-frames-" + name + ".csv"))) {
            StringBuilder sb = new StringBuilder(n * 9);
            for (int k = 0; k < n; k++) sb.append(buf[k]).append('\n');
            w.write(sb.toString());
        }
        int ents = ((List<?>) fEntities.get(fWorld.get(mc))).size();
        sceneJson.add(String.format(Locale.ROOT,
                "{\"scene\":\"%s\",\"frames\":%d,\"seconds\":%.3f,\"gcCount\":%d,\"gcMs\":%d,"
                        + "\"activeSamples\":%d,\"activeTrue\":%d,\"maxRotDrift\":%.3f,\"moved\":%.4f,"
                        + "\"entities\":%d,\"debugFps\":%d}",
                name, n, secs(tState, now), gc[0] - gcCount0, gc[1] - gcTime0, activeSamples, activeTrue,
                maxRotDrift, Math.sqrt(dx * dx + dy * dy + dz * dz), ents, (Integer) mDebugFps.invoke(null)));
        try {
            mShot.invoke(null, OUT, LABEL + "-" + name + ".png", fDisplayW.getInt(mc), fDisplayH.getInt(mc),
                    mFramebuffer.invoke(mc));
        } catch (Throwable t) {
            notes.add("screenshot failed: " + t);
        }
        scene++;
        if (scene < SCENE.length) {
            if ("C".equals(SCENE[scene])) {
                sidebar(mGetScoreboard.invoke(fWorld.get(mc)));
                chatOn = true;
                nextChat = System.nanoTime();
            }
            hold();
            to(S.SETTLE, System.nanoTime());
        } else {
            to(S.DONE, System.nanoTime());
        }
    }

    private static void sidebar(Object sb) throws Exception {
        Object o = mAddObjective.invoke(sb, "cobbench", dummyCriteria);
        mSetDisplayName.invoke(o, HypixelLoad.TITLE);
        mSetSlot.invoke(sb, 1, o);
        String[] lines = HypixelLoad.SIDEBAR;
        for (int i = 0; i < lines.length; i++) {
            String holder = HypixelLoad.holder(i);
            Object t = mCreateTeam.invoke(sb, "cobbench" + i);
            mPrefix.invoke(t, HypixelLoad.prefix(lines[i]));
            mSuffix.invoke(t, HypixelLoad.suffix(lines[i]));
            mAddToTeam.invoke(sb, holder, "cobbench" + i);
            mSetPoints.invoke(mGetScore.invoke(sb, holder, o), lines.length - i);
        }
    }

    private static long[] gc() {
        long c = 0, t = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            c += Math.max(0, b.getCollectionCount());
            t += Math.max(0, b.getCollectionTime());
        }
        return new long[] {c, t};
    }

    private static void finish() {
        if (state == S.EXIT) return;
        state = S.EXIT;
        writeMeta();
        try {
            mShutdown.invoke(mc);
        } catch (Throwable t) {
            System.exit(0);
        }
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

    private static String join(List<String> items) {
        StringBuilder b = new StringBuilder();
        for (String s : items) {
            if (b.length() > 0) b.append(',');
            b.append(s);
        }
        return b.toString();
    }

    private static void writeMeta() {
        List<String> mods = new ArrayList<>();
        for (ModContainer m : Loader.instance().getActiveModList()) mods.add(q(m.getModId() + " " + m.getVersion()));
        List<String> args = new ArrayList<>();
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) args.add(q(a));
        List<String> gcs = new ArrayList<>();
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) gcs.add(q(b.getName()));
        List<String> nts = new ArrayList<>();
        for (String s : notes) nts.add(q(s));
        String display = "?", settings = "";
        try {
            display = fDisplayW.getInt(mc) + "x" + fDisplayH.getInt(mc);
            Object gs = fSettings.get(mc);
            settings = " \"fullscreen\":" + mIsFull.invoke(mc) + ",\n \"renderDistance\":" + fRenderDist.getInt(gs)
                    + ",\n \"limitFramerate\":" + fLimitFps.getInt(gs) + ",\n \"vsync\":" + fVsync.getBoolean(gs)
                    + ",\n \"fancy\":" + fFancy.getBoolean(gs) + ",\n \"guiScale\":" + fGuiScale.getInt(gs) + ",\n";
        } catch (Throwable t) {
            nts.add(q("settings read failed: " + t));
        }
        String json = "{\n \"label\":" + q(LABEL) + ",\n \"mode\":" + q(MODE) + ",\n"
                + " \"scenes\":[" + join(sceneJson) + "],\n"
                + " \"firstFrameMs\":" + firstFrameMs + ",\n \"worldReadyMs\":" + worldReadyMs + ",\n"
                + " \"java\":" + q(System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version")) + ",\n"
                + " \"jvmArgs\":[" + join(args) + "],\n \"gc\":[" + join(gcs) + "],\n"
                + " \"maxHeapMb\":" + (Runtime.getRuntime().maxMemory() >> 20) + ",\n"
                + " \"cpus\":" + Runtime.getRuntime().availableProcessors() + ",\n"
                + " \"gl\":" + q(glInfo) + ",\n \"display\":\"" + display + "\",\n" + settings
                + " \"mods\":[" + join(mods) + "],\n \"notes\":[" + join(nts) + "]\n}\n";
        try (Writer w = writer(new File(OUT, LABEL + "-meta.json"))) {
            w.write(json);
        } catch (IOException e) {
            System.err.println("[cobbench] meta write failed: " + e);
        }
    }
}
