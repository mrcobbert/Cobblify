package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.DiagLog;
import com.bedwarsqol.mixin.GuiIngameAccessor;
import com.bedwarsqol.mixin.GuiNewChatAccessor;
import com.bedwarsqol.mixin.MinecraftTimerAccessor;
import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RecordingStore;
import com.bedwarsqol.rewind.Rewind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.boss.BossStatus;
import net.minecraft.stats.StatFileWriter;
import net.minecraft.util.Timer;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Rewind playback in the Ornithe client (PLAN section 3): the one {@link PlaybackSession}, its {@link ClientOps} made
 * of Minecraft calls, and the static entry points the mixins and screens use.
 *
 * <p><b>Entry.</b> {@link #open(String)} plays a recording by id, from the gallery or the pause menu's Watch Last Game
 * row: it disconnects (when connected), settles R1's recorder, waits for the file to close, and copes with a session
 * already running (that one ends first). Call it on the client thread.
 *
 * <p><b>Screens.</b> The loading card and the viewer come from {@link Screens} ({@link #setScreens}), the gallery a
 * failure ends in from {@link #setFailureGallery}; until the screen phases register theirs, minimal built-in screens
 * stand in and a failure lands on the title screen. A screen opened while a recording plays must be a Rewind screen (a class in this
 * package) or be opened through {@link #display}; anything else is ignored (recorded screens never open).
 *
 * <p><b>Hooks</b> ({@code MinecraftRewindPlaybackMixin}, {@code EntityRendererRewindPlaybackMixin},
 * {@code PlayerControllerMPRewindMixin}, {@code EffectRendererRewindMixin}, {@code SoundHandlerRewindMixin}) return at
 * once when no recording plays and never throw.
 *
 * <p>Diagnostics go to DiagLog with counts and timings only.
 */
public final class RewindPlayback implements ClientOps {

    /** The screens a session opens itself (registered by the viewer-screens phase). */
    public interface Screens {
        /** The loading card (G49). */
        GuiScreen loading(PlaybackSession session);

        /** The viewer (G36). */
        GuiScreen viewer(PlaybackSession session);
    }

    private static final String PACKAGE = RewindPlayback.class.getName().substring(0,
            RewindPlayback.class.getName().lastIndexOf('.') + 1);

    private static final Screens MINIMAL = new Screens() {
        @Override
        public GuiScreen loading(PlaybackSession session) {
            return new MinimalLoading(session);
        }

        @Override
        public GuiScreen viewer(PlaybackSession session) {
            return new MinimalViewer(session);
        }
    };

    private static final RewindPlayback INSTANCE = new RewindPlayback();
    private static volatile Screens screens = MINIMAL;
    private static volatile Supplier<GuiScreen> failureGallery;

    private final PlaybackSession session = new PlaybackSession(this, PlaybackGate.global());
    private PlaybackConnection connection;
    private PlaybackSink sink;
    /** The server the last pause-menu entry left, for Rejoin; kept across sessions. */
    private ServerData leftServer;

    private RewindPlayback() {
    }

    // ================================================================== public API

    /** Plays recording {@code recordingId} (client thread). Never throws. */
    public static void open(String recordingId) {
        try {
            INSTANCE.session.open(recordingId);
        } catch (Throwable t) {
            DiagLog.log("rewind-watch: open failed " + t.getClass().getSimpleName());
        }
    }

    /** The session (state, timeline, commands) for Rewind's screens. */
    public static PlaybackSession session() {
        return INSTANCE.session;
    }

    /** A recording is playing or opening. */
    public static boolean active() {
        return INSTANCE.session.active();
    }

    /** Opens {@code screen} as a session screen (the screen policy lets it through). Client thread. */
    public static void display(GuiScreen screen) {
        INSTANCE.session.approve(screen);
        Minecraft.getMinecraft().displayGuiScreen(screen);
    }

    /** Registers the loading card and viewer (null: the minimal built-in ones). */
    public static void setScreens(Screens s) {
        screens = s == null ? MINIMAL : s;
    }

    /**
     * Registers the gallery a failed session ends in, whose Done leads to the title screen (e.g.
     * {@code () -> RewindGalleryScreen.fromTitle(new GuiMainMenu())}). Null, or a supplier that returns null: the
     * title screen.
     */
    public static void setFailureGallery(Supplier<GuiScreen> gallery) {
        failureGallery = gallery;
    }

    /** The registered loading card and viewer. */
    static Screens screens() {
        return screens;
    }

    /** The registered failure gallery, or null. */
    static Supplier<GuiScreen> failureGallery() {
        return failureGallery;
    }

    /** The current connection's sink (the recorded entity id and game mode), or null. */
    public static PlaybackSink currentSink() {
        return INSTANCE.sink;
    }

    // ================================================================== hooks (mixins)

    public static void onFrameStart() {
        PlaybackSession s = INSTANCE.session;
        if (s.active()) {
            try {
                s.frameStart();
            } catch (Throwable ignored) {
                // the session handles its own failures
            }
        }
    }

    public static void onPauseWritten() {
        PlaybackSession s = INSTANCE.session;
        if (s.active()) {
            try {
                s.afterPauseWrite();
            } catch (Throwable ignored) {
                // the session handles its own failures
            }
        }
    }

    public static void onTickHead() {
        PlaybackSession s = INSTANCE.session;
        if (s.active()) {
            try {
                s.tickHead();
            } catch (Throwable ignored) {
                // the session handles its own failures
            }
        }
    }

    public static void onTickEnd() {
        PlaybackSession s = INSTANCE.session;
        if (s.active()) {
            try {
                s.tickEnd();
            } catch (Throwable ignored) {
                // the session handles its own failures
            }
        }
    }

    /** {@code displayGuiScreen} HEAD: false to ignore the screen. */
    public static boolean allowScreen(GuiScreen screen) {
        PlaybackSession s = INSTANCE.session;
        if (!s.active()) {
            return true;
        }
        return s.allowScreen(screen, isRewindScreen(screen));
    }

    /** Rewind's own screens: {@link RewindScreen}s and any screen class in this package (the gallery). */
    static boolean isRewindScreen(GuiScreen screen) {
        return screen instanceof RewindScreen || screen != null && screen.getClass().getName().startsWith(PACKAGE);
    }

    /** {@code renderWorld} HEAD: true to skip drawing the world this frame; otherwise the camera takes its pose. */
    public static boolean onRenderWorld(float partialTicks) {
        PlaybackSession s = INSTANCE.session;
        if (!s.active()) {
            return false;
        }
        try {
            if (s.skipWorldDraw()) {
                return true;
            }
            if (s.playingWorld()) {
                INSTANCE.poseCamera(partialTicks);
            }
        } catch (Throwable ignored) {
            // a frame is never broken by playback
        }
        return false;
    }

    public static boolean soundsMuted() {
        PlaybackSession s = INSTANCE.session;
        return s.active() && s.soundsMuted();
    }

    public static boolean particlesMuted() {
        PlaybackSession s = INSTANCE.session;
        return s.active() && s.particlesMuted();
    }

    /** The client player for a playback world: the camera during a session, else null (vanilla's player). */
    public static EntityPlayerSP createCamera(Minecraft mc, World world, NetHandlerPlayClient handler,
                                              StatFileWriter stats) {
        if (!INSTANCE.session.active()) {
            return null;
        }
        return new RewindCamera(mc, world, handler, stats);
    }

    /** This frame's camera pose: your view or the free camera ({@link CameraController}). */
    private void poseCamera(float partialTicks) {
        CameraController.onRender(session, partialTicks);
    }

    // ================================================================== ClientOps

    private static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    private static Timer timer() {
        return ((MinecraftTimerAccessor) mc()).bedwarsqol$getTimer();
    }

    /** The recorder's own store ({@link Rewind#store()}): it knows which file is still being written here. */
    static RecordingStore store() {
        return Rewind.store();
    }

    @Override
    public RecordingMeta readMeta(String id) {
        return store().readMeta(id);
    }

    @Override
    public boolean connected() {
        return mc().theWorld != null;
    }

    @Override
    public void disconnect() {
        Minecraft mc = mc();
        ServerData sd = mc.getCurrentServerData();
        if (sd != null && sd.serverIP != null && !sd.serverIP.trim().isEmpty()) {
            leftServer = new ServerData(sd.serverName, sd.serverIP, false);
        }
        if (mc.theWorld != null) {
            mc.theWorld.sendQuittingDisconnectingPacket();
        }
        mc.loadWorld(null);
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.chatKeepHistory) {
            mc.ingameGUI.getChatGUI().clearChatMessages(); // what the title screen would have done
        }
    }

    @Override
    public void settleRecorder() {
        Rewind.settleDisconnect();
    }

    @Override
    public void showLoading() {
        display(screens.loading(session));
    }

    @Override
    public void showViewer() {
        display(screens.viewer(session));
    }

    @Override
    public void startLoad(final String id, final PlaybackSession.Load load) {
        final RecordingStore store = store();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean closed = PlaybackSession.awaitClosed(() -> load.cancelled() || isClosed(store, id),
                            () -> System.nanoTime() / 1_000_000L, Thread::sleep, PlaybackSession.CLOSE_WAIT_MS,
                            PlaybackSession.CLOSE_POLL_MS);
                    if (load.cancelled()) {
                        load.fail(PlaybackSession.Failure.LOAD_ERROR, "cancelled");
                        return;
                    }
                    if (!closed) {
                        load.fail(PlaybackSession.Failure.LOAD_TIMEOUT, null);
                        return;
                    }
                    LoadedRecording r = LoadedRecording.load(store.cbrFile(id), store.jsonFile(id));
                    if (r.problem() != null) {
                        r.close();
                        load.fail(PlaybackSession.Failure.UNPLAYABLE, r.problem().name());
                        return;
                    }
                    load.succeed(r);
                } catch (Throwable e) {
                    load.fail(PlaybackSession.Failure.LOAD_ERROR, e.getClass().getSimpleName());
                }
            }
        }, "Cobblify Rewind Load");
        t.setDaemon(true);
        t.start();
    }

    /**
     * R1 has closed {@code id}: its {@code .json} says complete (R1 writes it only after releasing the file's lock), and
     * the lock can be taken. The {@code .cbr} is never opened while the {@code .json} is incomplete: closing a second
     * channel on a file the writer holds would drop the writer's lock on POSIX systems.
     */
    static boolean isClosed(RecordingStore store, String id) {
        RecordingMeta m = store.readMeta(id);
        return m != null && m.complete && store.lockable(id);
    }

    @Override
    public void resetGui() {
        GuiIngame gui = mc().ingameGUI;
        gui.getChatGUI().clearChatMessages();
        clearTitles(gui);
        GuiIngameAccessor a = (GuiIngameAccessor) gui;
        a.bedwarsqol$setUpdateCounter(0);
        a.bedwarsqol$setRemainingHighlightTicks(0);
        gui.resetPlayersOverlayFooterHeader();
        clearBossBar();
    }

    private static void clearTitles(GuiIngame gui) {
        gui.displayTitle(null, null, -1, -1, -1); // vanilla's title clear: both texts "" and the timer 0
        gui.setDefaultTitlesTimes();
        GuiIngameAccessor a = (GuiIngameAccessor) gui;
        a.bedwarsqol$setTitlesTimer(0);
        a.bedwarsqol$setDisplayedTitle("");
        a.bedwarsqol$setDisplayedSubTitle("");
        a.bedwarsqol$setRecordPlaying("");
        a.bedwarsqol$setRecordPlayingUpFor(0);
        a.bedwarsqol$setRecordIsPlaying(false);
    }

    private static void clearBossBar() {
        BossStatus.bossName = null;
        BossStatus.statusBarTime = 0;
        BossStatus.healthScale = 0f;
        BossStatus.hasColorModifier = false;
    }

    @Override
    public void newConnection() {
        Minecraft mc = mc();
        if (mc.theWorld != null) {
            mc.loadWorld(null);
        }
        closeConnection();
        connection = PlaybackConnection.open(mc, mc.getSession().getProfile());
        sink = new PlaybackSink(mc, connection);
    }

    private void closeConnection() {
        PlaybackConnection c = connection;
        connection = null;
        sink = null;
        if (c != null) {
            c.close();
        }
    }

    @Override
    public PacketSink sink() {
        return sink;
    }

    @Override
    public boolean worldPresent() {
        return mc().theWorld != null;
    }

    @Override
    public boolean gamePaused() {
        return mc().isGamePaused();
    }

    @Override
    public int elapsedTicks() {
        return timer().elapsedTicks;
    }

    @Override
    public void setElapsedTicks(int ticks) {
        timer().elapsedTicks = ticks;
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }

    @Override
    public void teardown() {
        Minecraft mc = mc();
        try {
            if (mc.theWorld != null || mc.thePlayer != null) {
                mc.loadWorld(null);
            }
        } finally {
            closeConnection();
        }
    }

    @Override
    public void clearTitlesAndBossBar() {
        clearTitles(mc().ingameGUI);
        clearBossBar();
    }

    @Override
    public void openTitle() {
        mc().displayGuiScreen(new GuiMainMenu());
    }

    @Override
    public void openRejoin() {
        Minecraft mc = mc();
        RejoinTarget.Server left = leftServer == null ? null
                : new RejoinTarget.Server(leftServer.serverName, leftServer.serverIP);
        List<RejoinTarget.Server> list = new ArrayList<RejoinTarget.Server>();
        try {
            ServerList servers = new ServerList(mc);
            servers.loadServerList();
            for (int i = 0; i < servers.countServers(); i++) {
                ServerData d = servers.getServerData(i);
                if (d != null) {
                    list.add(new RejoinTarget.Server(d.serverName, d.serverIP));
                }
            }
        } catch (Throwable ignored) {
            // no server list: the default address
        }
        RejoinTarget.Server target = RejoinTarget.choose(left, list);
        mc.displayGuiScreen(new GuiConnecting(new GuiMainMenu(), mc,
                new ServerData(target.name, target.address, false)));
    }

    @Override
    public void openGallery() {
        Supplier<GuiScreen> source = failureGallery;
        GuiScreen g = source == null ? null : source.get();
        mc().displayGuiScreen(g != null ? g : new GuiMainMenu());
    }

    @Override
    public void log(String line) {
        DiagLog.log(line);
    }

    // ---------------------------------------------------------------- PlaybackRestore.Client

    @Override
    public float timerSpeed() {
        return timer().timerSpeed;
    }

    @Override
    public void setTimerSpeed(float speed) {
        timer().timerSpeed = speed;
    }

    @Override
    public int thirdPersonView() {
        return mc().gameSettings.thirdPersonView;
    }

    @Override
    public void setThirdPersonView(int view) {
        mc().gameSettings.thirdPersonView = view;
    }

    @Override
    public int difficulty() {
        EnumDifficulty d = mc().gameSettings.difficulty;
        return d == null ? EnumDifficulty.NORMAL.getDifficultyId() : d.getDifficultyId();
    }

    @Override
    public void setDifficulty(int id) {
        mc().gameSettings.difficulty = EnumDifficulty.getDifficultyEnum(id);
    }

    @Override
    public void setGamePaused(boolean paused) {
        ((MinecraftTimerAccessor) mc()).bedwarsqol$setGamePaused(paused);
    }

    @Override
    public Object copyChat() {
        GuiNewChatAccessor a = (GuiNewChatAccessor) mc().ingameGUI.getChatGUI();
        return new Object[]{new ArrayList<ChatLine>(a.bedwarsqol$getChatLines()),
                new ArrayList<ChatLine>(a.bedwarsqol$getDrawnChatLines()),
                new ArrayList<String>(a.bedwarsqol$getSentMessages())};
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restoreChat(Object saved) {
        Object[] s = (Object[]) saved;
        GuiNewChat chat = mc().ingameGUI.getChatGUI();
        GuiNewChatAccessor a = (GuiNewChatAccessor) chat;
        replace(a.bedwarsqol$getChatLines(), (List<ChatLine>) s[0]);
        replace(a.bedwarsqol$getDrawnChatLines(), (List<ChatLine>) s[1]);
        replace(a.bedwarsqol$getSentMessages(), (List<String>) s[2]);
        chat.resetScroll();
    }

    private static <T> void replace(List<T> live, List<T> saved) {
        live.clear();
        live.addAll(saved);
    }

    @Override
    public int updateCounter() {
        return ((GuiIngameAccessor) mc().ingameGUI).bedwarsqol$getUpdateCounter();
    }

    @Override
    public void setUpdateCounter(int counter) {
        ((GuiIngameAccessor) mc().ingameGUI).bedwarsqol$setUpdateCounter(counter);
    }

    // ================================================================== minimal screens (until the screen phases)

    /** A dark card with a key that fills by the catch-up's progress; Esc gives up (title screen). */
    static final class MinimalLoading extends RewindScreen {
        private final PlaybackSession session;

        MinimalLoading(PlaybackSession session) {
            this.session = session;
        }

        @Override
        protected void drawRewind(int mouseX, int mouseY, float partialTicks) {
            Gui.drawRect(0, 0, width, height, 0xFF0E0F12);
            int w = 120;
            int x = (width - w) / 2;
            int y = height / 2 - 2;
            Gui.drawRect(x, y, x + w, y + 4, 0xFF2A2D33);
            int fill = Math.round(w * session.loadingProgress());
            if (fill > 0) {
                Gui.drawRect(x, y, x + fill, y + 4, 0xFFC0DCFF);
            }
        }

        @Override
        protected void keyTyped(char typedChar, int keyCode) {
            if (keyCode == ViewerKeys.KEY_ESCAPE) {
                session.requestExit(PlaybackSession.Exit.TITLE);
            }
        }
    }

    /** The world with a thin progress line; G45's play, seek and speed keys; Esc quits to the title screen. */
    static final class MinimalViewer extends RewindScreen {
        private final PlaybackSession session;

        MinimalViewer(PlaybackSession session) {
            this.session = session;
        }

        @Override
        protected void drawRewind(int mouseX, int mouseY, float partialTicks) {
            Timeline t = session.timeline();
            if (t == null) {
                return;
            }
            long start = t.startTick();
            long span = Math.max(1, t.endTick() - start);
            float played = Math.max(0f, Math.min(1f, (session.playheadTick() - start) / (float) span));
            Gui.drawRect(0, height - 2, width, height, 0x80000000);
            Gui.drawRect(0, height - 2, Math.round(width * played), height, 0xFFC0DCFF);
        }

        @Override
        protected void keyTyped(char typedChar, int keyCode) {
            ViewerKeys.Action a = ViewerKeys.action(keyCode, isShiftKeyDown(), false);
            switch (a) {
                case TOGGLE_PLAY:
                    session.togglePlay();
                    break;
                case BACK_SHORT:
                case FORWARD_SHORT:
                case BACK_LONG:
                case FORWARD_LONG:
                    session.seekBy(ViewerKeys.seekDeltaUs(a));
                    break;
                case SLOWER:
                    session.slower();
                    break;
                case FASTER:
                    session.faster();
                    break;
                case ESC_MENU:
                    session.requestExit(PlaybackSession.Exit.TITLE);
                    break;
                default:
                    break;
            }
        }
    }
}
