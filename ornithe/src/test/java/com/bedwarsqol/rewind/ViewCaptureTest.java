package com.bedwarsqol.rewind;

import com.bedwarsqol.gui.SettingsGui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSleepMP;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiFurnace;
import net.minecraft.client.gui.inventory.GuiInventory;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A8 (PLAN Phase 3): VIEW, MOUSE, CHAT_INPUT, FOV and INPUT are written by the per-frame capture only when they
 * change, MOUSE only while a screen is open and at most every 8 ms, CHAT_INPUT only while chat is open, FOV read at
 * most every 4 ms; TAP is written for each mouse press the Keystrokes HUD is told about, when it is processed (round 2
 * I1); a segment reset writes the view state again; the entry points are inert with no sink and survive a throwing
 * one (A14).
 */
public class ViewCaptureTest {

    private static final long MS = 1_000_000L;

    @After
    public void uninstall() {
        ViewCapture.install(null);
    }

    // ------------------------------------------------------------------ the recording sink used by the P3 tests

    /** One appended record: tag, sink clock at append, body (without its length prefix). */
    static final class Rec {
        final int tag;
        final long time;
        final byte[] body;

        Rec(int tag, long time, byte[] body) {
            this.tag = tag;
            this.time = time;
            this.body = body;
        }
    }

    /** Records every append with the test clock {@link #now}; fails on demand. */
    static final class Recorder implements StateCapture.Sink {
        final List<Rec> recs = new ArrayList<Rec>();
        final List<Throwable> failures = new ArrayList<Throwable>();
        long now;
        boolean throwOnAppend;

        @Override
        public boolean append(int tag, byte[] buf, int len) {
            if (throwOnAppend) {
                throw new IllegalStateException("sink broke");
            }
            RewindFormat.Cursor c = new RewindFormat.Cursor(buf, 0, len);
            int bodyLen = c.varInt();
            assertEquals("record length prefix matches", len - c.pos(), bodyLen);
            byte[] body = new byte[bodyLen];
            System.arraycopy(buf, c.pos(), body, 0, bodyLen);
            recs.add(new Rec(tag, now, body));
            return true;
        }

        @Override
        public void failed(String where, Throwable t) {
            failures.add(t);
        }

        List<Rec> of(int tag) {
            List<Rec> out = new ArrayList<Rec>();
            for (Rec r : recs) {
                if (r.tag == tag) {
                    out.add(r);
                }
            }
            return out;
        }

        void clear() {
            recs.clear();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ViewCapture.Frame frame(long ns) {
        ViewCapture.Frame f = new ViewCapture.Frame();
        f.nanoTime = ns;
        f.perspective = 0;
        f.flags = 0;
        f.screenKind = RewindFormat.SCREEN_NONE;
        f.keys = 0;
        f.fovRead = false;
        f.screenOpen = false;
        f.chatOpen = false;
        f.chatText = null;
        return f;
    }

    private static ExtCodec.View view(Rec r) {
        return new ExtCodec.View().read(r.body, 0, r.body.length);
    }

    // ------------------------------------------------------------------ VIEW

    @Test
    public void viewIsWrittenOnTheFirstFrameAndThenOnlyWhenItChanges() {
        ViewCapture v = new ViewCapture();
        Recorder s = new Recorder();
        ViewCapture.Frame f = frame(0);
        assertTrue(v.frame(s, f));
        assertEquals("the segment's first frame states the view", 1, s.of(RewindFormat.TAG_VIEW).size());
        for (int i = 1; i <= 50; i++) {
            f.nanoTime = i * 16 * MS;
            assertTrue(v.frame(s, f));
        }
        assertEquals("unchanged frames write nothing", 1, s.of(RewindFormat.TAG_VIEW).size());

        f.perspective = 1;
        v.frame(s, f);
        f.flags = RewindFormat.VIEW_HIDE_GUI | RewindFormat.VIEW_F3;
        v.frame(s, f);
        f.flags |= RewindFormat.VIEW_TAB | RewindFormat.VIEW_SMOOTH_CAMERA;
        v.frame(s, f);
        f.screenKind = RewindFormat.SCREEN_INVENTORY;
        f.screenOpen = true;
        v.frame(s, f);
        v.frame(s, f);
        List<Rec> views = s.of(RewindFormat.TAG_VIEW);
        assertEquals(5, views.size());
        ExtCodec.View last = view(views.get(4));
        assertEquals(1, last.perspective);
        assertEquals(RewindFormat.VIEW_HIDE_GUI | RewindFormat.VIEW_F3 | RewindFormat.VIEW_TAB
                | RewindFormat.VIEW_SMOOTH_CAMERA, last.flags);
        assertEquals(RewindFormat.SCREEN_INVENTORY, last.screenKind);
        assertEquals(0, view(views.get(0)).perspective);
    }

    @Test
    public void aSegmentResetStatesTheViewAndFovAgain() {
        ViewCapture v = new ViewCapture();
        Recorder s = new Recorder();
        ViewCapture.Frame f = frame(0);
        f.fovRead = true;
        f.fov = 70f;
        v.frame(s, f);
        f.nanoTime = 20 * MS;
        v.frame(s, f);
        assertEquals(1, s.of(RewindFormat.TAG_VIEW).size());
        assertEquals(1, s.of(RewindFormat.TAG_FOV).size());
        v.reset();
        f.nanoTime = 40 * MS;
        v.frame(s, f);
        assertEquals(2, s.of(RewindFormat.TAG_VIEW).size());
        assertEquals(2, s.of(RewindFormat.TAG_FOV).size());
    }

    @Test
    public void screenKindsFollowTheScreenClass() {
        assertEquals(RewindFormat.SCREEN_CHAT, ViewCapture.screenKind(GuiChat.class));
        assertEquals("sleeping shows the chat bar", RewindFormat.SCREEN_CHAT, ViewCapture.screenKind(GuiSleepMP.class));
        assertEquals(RewindFormat.SCREEN_INVENTORY, ViewCapture.screenKind(GuiInventory.class));
        assertEquals(RewindFormat.SCREEN_CHEST, ViewCapture.screenKind(GuiChest.class));
        assertEquals(RewindFormat.SCREEN_OTHER_CONTAINER, ViewCapture.screenKind(GuiFurnace.class));
        assertEquals(RewindFormat.SCREEN_OTHER_CONTAINER, ViewCapture.screenKind(GuiContainerCreative.class));
        assertEquals(RewindFormat.SCREEN_DEATH, ViewCapture.screenKind(GuiGameOver.class));
        assertEquals(RewindFormat.SCREEN_ESC_MENU, ViewCapture.screenKind(GuiIngameMenu.class));
        assertEquals(RewindFormat.SCREEN_COBBLIFY, ViewCapture.screenKind(SettingsGui.class));
        assertEquals(RewindFormat.SCREEN_COBBLIFY, ViewCapture.screenKind(OwnScreen.class));
        assertEquals(RewindFormat.SCREEN_OTHER, ViewCapture.screenKind(GuiOptions.class));
        assertEquals(RewindFormat.SCREEN_OTHER, ViewCapture.screenKind(GuiScreen.class));
    }

    /** A screen of this mod (package com.bedwarsqol). */
    static final class OwnScreen extends GuiScreen {
    }

    // ------------------------------------------------------------------ MOUSE

    @Test
    public void mouseIsStoredOnlyWhileAScreenIsOpenOnChangeAndAtMostEvery8Ms() {
        ViewCapture v = new ViewCapture();
        Recorder s = new Recorder();
        ViewCapture.Frame f = frame(0);
        f.mouseDx = 40;
        f.mouseDy = -12;
        for (int i = 0; i < 20; i++) {
            f.nanoTime = i * 5 * MS;
            f.mouseDx = 40 + i; // the cursor moves, but no screen is open
            v.frame(s, f);
        }
        assertEquals("no screen open: no MOUSE", 0, s.of(RewindFormat.TAG_MOUSE).size());

        long t = 200 * MS;
        f.screenOpen = true;
        f.screenKind = RewindFormat.SCREEN_CHEST;
        f.nanoTime = t;
        f.mouseDx = 100;
        f.mouseDy = -20;
        v.frame(s, f);
        assertEquals("a screen opens: the cursor is stored at once", 1, s.of(RewindFormat.TAG_MOUSE).size());
        f.nanoTime = t + 1 * MS;
        v.frame(s, f);
        f.nanoTime = t + 20 * MS;
        v.frame(s, f);
        assertEquals("unchanged: nothing", 1, s.of(RewindFormat.TAG_MOUSE).size());

        // moves every millisecond for 40 ms: at most one MOUSE per 8 ms
        long base = t + 100 * MS;
        for (int i = 0; i <= 40; i++) {
            f.nanoTime = base + i * MS;
            f.mouseDx = 100 + i;
            v.frame(s, f);
        }
        List<Rec> moves = s.of(RewindFormat.TAG_MOUSE);
        int during = moves.size() - 1;
        assertTrue("rate-limited: " + during, during >= 5 && during <= 6);
        ExtCodec.Mouse last = new ExtCodec.Mouse().read(moves.get(moves.size() - 1).body, 0,
                moves.get(moves.size() - 1).body.length);
        assertEquals(-20, last.dy);

        // a still cursor after the burst: its final position is written once the 8 ms have passed
        f.nanoTime = base + 60 * MS;
        v.frame(s, f);
        moves = s.of(RewindFormat.TAG_MOUSE);
        ExtCodec.Mouse fin = new ExtCodec.Mouse().read(moves.get(moves.size() - 1).body, 0,
                moves.get(moves.size() - 1).body.length);
        assertEquals("the resting position is stored", 140, fin.dx);
        int count = moves.size();
        f.nanoTime = base + 200 * MS;
        v.frame(s, f);
        assertEquals(count, s.of(RewindFormat.TAG_MOUSE).size());

        // closed, then reopened at the same position: stored again at once
        f.screenOpen = false;
        f.screenKind = RewindFormat.SCREEN_NONE;
        f.nanoTime = base + 300 * MS;
        v.frame(s, f);
        assertEquals(count, s.of(RewindFormat.TAG_MOUSE).size());
        f.screenOpen = true;
        f.screenKind = RewindFormat.SCREEN_INVENTORY;
        f.nanoTime = base + 301 * MS;
        v.frame(s, f);
        assertEquals(count + 1, s.of(RewindFormat.TAG_MOUSE).size());
    }

    @Test
    public void mouseOffsetsKeepVanillasGuiPixel() {
        int[][] sizes = {{1920, 1080, 480, 270}, {854, 480, 427, 240}, {1366, 768, 683, 384}, {2560, 1440, 640, 360},
                {1001, 777, 334, 259}};
        for (int[] sz : sizes) {
            int dw = sz[0], dh = sz[1], sw = sz[2], sh = sz[3];
            for (int mx = 0; mx < dw; mx += 7) {
                int dx = ViewCapture.mouseDx(mx, dw, sw);
                int vanilla = mx * sw / dw; // EntityRenderer.updateCameraAndRender's drawScreen x
                assertEquals("x " + mx + " of " + dw, vanilla, Math.floorDiv(2 * sw + dx, RewindFormat.MOUSE_UNITS_PER_GUI_PX));
            }
            for (int my = 0; my < dh; my += 5) {
                int dy = ViewCapture.mouseDy(my, dh, sh);
                int vanilla = sh - my * sh / dh - 1; // drawScreen y
                assertEquals("y " + my + " of " + dh, vanilla, Math.floorDiv(2 * sh + dy, RewindFormat.MOUSE_UNITS_PER_GUI_PX));
            }
        }
        assertEquals("the screen centre is 0", 0, ViewCapture.mouseDx(960, 1920, 480));
    }

    // ------------------------------------------------------------------ CHAT_INPUT

    @Test
    public void chatInputIsStoredOnlyWhileChatIsOpenAndOnlyWhenItChanges() {
        ViewCapture v = new ViewCapture();
        Recorder s = new Recorder();
        ViewCapture.Frame f = frame(0);
        v.frame(s, f);
        assertEquals(0, s.of(RewindFormat.TAG_CHAT_INPUT).size());

        f.screenOpen = true;
        f.screenKind = RewindFormat.SCREEN_CHAT;
        f.chatOpen = true;
        f.chatText = "";
        f.chatCursor = 0;
        f.nanoTime = 16 * MS;
        v.frame(s, f);
        assertEquals("chat opened empty: the VIEW says so, no text record", 0, s.of(RewindFormat.TAG_CHAT_INPUT).size());

        String[] typing = {"g", "gg", "gg", "gg "};
        for (int i = 0; i < typing.length; i++) {
            f.chatText = new String(typing[i]); // a fresh but maybe equal String each frame, as GuiTextField builds
            f.chatCursor = typing[i].length();
            f.nanoTime = (32 + i * 16) * MS;
            v.frame(s, f);
        }
        List<Rec> chat = s.of(RewindFormat.TAG_CHAT_INPUT);
        assertEquals("one record per change", 3, chat.size());
        ExtCodec.ChatInput last = new ExtCodec.ChatInput().read(chat.get(2).body, 0, chat.get(2).body.length);
        assertEquals("gg ", last.text);
        assertEquals(3, last.cursor);

        f.chatCursor = 1; // the cursor alone moves
        f.nanoTime = 200 * MS;
        v.frame(s, f);
        assertEquals(4, s.of(RewindFormat.TAG_CHAT_INPUT).size());

        // closed: nothing; reopened with "/": a record, measured from an empty bar
        f.chatOpen = false;
        f.chatText = null;
        f.screenOpen = false;
        f.screenKind = RewindFormat.SCREEN_NONE;
        f.nanoTime = 300 * MS;
        v.frame(s, f);
        assertEquals(4, s.of(RewindFormat.TAG_CHAT_INPUT).size());
        f.chatOpen = true;
        f.screenOpen = true;
        f.screenKind = RewindFormat.SCREEN_CHAT;
        f.chatText = "/";
        f.chatCursor = 1;
        f.nanoTime = 316 * MS;
        v.frame(s, f);
        assertEquals(5, s.of(RewindFormat.TAG_CHAT_INPUT).size());
    }

    // ------------------------------------------------------------------ FOV

    /**
     * Code review 1, I2 and coordinator amendment 3: vanilla's {@code getFOVModifier} allocates (about 144 B a call),
     * so the frame path reads it at most once per 16 ms. Driven through the frame's FOV source, the same rate limit
     * production's renderer read goes through.
     */
    @Test
    public void fovIsReadAtMostOncePer16MsAndWrittenOnChange() {
        ViewCapture v = new ViewCapture();
        Recorder s = new Recorder();
        ClientTrackCaptureTest.FakeFov fov = new ClientTrackCaptureTest.FakeFov();
        ViewCapture.Frame f = frame(0);
        f.fovSource = fov;
        // 1,000 frames 1 ms apart (1000 FPS): read at 0, 16, ..., 992 ms
        for (int i = 0; i < 1000; i++) {
            f.nanoTime = i * MS;
            assertTrue(v.frame(s, f));
            assertEquals("frame " + i + " read", i % 16 == 0, f.fovRead);
        }
        assertEquals("one read per 16 ms", 63, fov.reads);
        assertEquals("unchanged FOV: one record", 1, s.of(RewindFormat.TAG_FOV).size());

        // 144 FPS: never two reads closer than 16 ms
        fov.reads = 0;
        long last = Long.MIN_VALUE;
        for (int i = 0; i < 1440; i++) {
            f.nanoTime = 2000 * MS + i * 6_944_444L;
            v.frame(s, f);
            if (f.fovRead) {
                assertTrue("reads 16 ms apart", last == Long.MIN_VALUE || f.nanoTime - last >= 16 * MS);
                last = f.nanoTime;
            }
        }
        assertTrue("at most one read per 16 ms over 10 s: " + fov.reads, fov.reads <= 1 + 10_000 / 16);

        // written on change only
        float[] sprint = {71.5f, 73f, 74.2f, 74.2f, 74.2f, 63.1f};
        for (int i = 0; i < sprint.length; i++) {
            f.nanoTime = 20_000 * MS + 16 * i * MS;
            fov.value = sprint[i];
            v.frame(s, f);
        }
        List<Rec> recs = s.of(RewindFormat.TAG_FOV);
        assertEquals(5, recs.size());
        assertEquals(63.1f, new ExtCodec.Fov().read(recs.get(4).body, 0, 4).degrees, 0f);

        // no FOV source: a frame stating no read changes nothing
        f.fovSource = null;
        f.fovRead = false;
        f.fov = 10f;
        f.nanoTime = 30_000 * MS;
        v.frame(s, f);
        assertEquals(5, s.of(RewindFormat.TAG_FOV).size());
        assertEquals(16 * MS, ViewCapture.FOV_INTERVAL_NS);
    }

    // ------------------------------------------------------------------ INPUT and TAP

    @Test
    public void inputIsWrittenWhenTheHeldKeysChange() {
        ViewCapture v = new ViewCapture();
        Recorder s = new Recorder();
        ViewCapture.Frame f = frame(0);
        v.frame(s, f);
        assertEquals("nothing held at the start: nothing written", 0, s.of(RewindFormat.TAG_INPUT).size());
        int[] seq = {RewindFormat.INPUT_W, RewindFormat.INPUT_W, RewindFormat.INPUT_W | RewindFormat.INPUT_JUMP,
                RewindFormat.INPUT_W | RewindFormat.INPUT_LMB, RewindFormat.INPUT_W | RewindFormat.INPUT_LMB, 0};
        for (int i = 0; i < seq.length; i++) {
            f.nanoTime = (i + 1) * 16 * MS;
            f.keys = seq[i];
            v.frame(s, f);
        }
        List<Rec> in = s.of(RewindFormat.TAG_INPUT);
        assertEquals(4, in.size());
        assertEquals(RewindFormat.INPUT_W | RewindFormat.INPUT_LMB, new ExtCodec.Input().read(in.get(2).body, 0, 1).keys);
        assertEquals(0, new ExtCodec.Input().read(in.get(3).body, 0, 1).keys);
    }

    /** Round 2 I1: frames at 100 and 116 ms, press at 105, release at 110, event processed at 115. */
    @Test
    public void aClickShorterThanAFrameIsStoredAsATapWhenItIsProcessed() {
        Recorder s = new Recorder();
        ViewCapture.install(s);
        ViewCapture v = new ViewCapture();
        ViewCapture.Frame f = frame(100 * MS);
        s.now = 100;
        v.frame(s, f); // the button is up
        s.now = 115;
        ViewCapture.mousePressed(0); // BedwarsHudRenderer.onMouse hands over the press
        s.now = 116;
        f.nanoTime = 116 * MS;
        v.frame(s, f); // up again
        assertEquals("INPUT never saw the button down", 0, s.of(RewindFormat.TAG_INPUT).size());
        List<Rec> taps = s.of(RewindFormat.TAG_TAP);
        assertEquals(1, taps.size());
        assertEquals("stamped when processed", 115, taps.get(0).time);
        assertEquals(RewindFormat.TAP_LMB, new ExtCodec.Tap().read(taps.get(0).body, 0, 1).button);

        ViewCapture.mousePressed(1);
        ViewCapture.mousePressed(2); // a button Keystrokes does not show
        taps = s.of(RewindFormat.TAG_TAP);
        assertEquals(2, taps.size());
        assertEquals(RewindFormat.TAP_RMB, new ExtCodec.Tap().read(taps.get(1).body, 0, 1).button);
    }

    // ------------------------------------------------------------------ inert, failure

    @Test
    public void entryPointsAreInertWithoutASinkAndSurviveAThrowingOne() {
        ViewCapture.install(null);
        ViewCapture.mousePressed(0); // no sink: nothing, no exception
        ViewCapture.frame(null, 0f, 0L);

        Recorder s = new Recorder();
        s.throwOnAppend = true;
        ViewCapture.install(s);
        ViewCapture.mousePressed(0);
        assertEquals("the failure is reported once", 1, s.failures.size());

        ViewCapture v = new ViewCapture();
        assertFalse(v.frame(s, frame(0)));
        assertEquals(2, s.failures.size());
    }

    @Test
    public void theFovInvokerAndChatAccessorAreRegisteredMixins() throws Exception {
        File main = new File("src/main/java");
        if (!new File(main, "com/bedwarsqol/rewind/Rewind.java").isFile()) {
            main = new File("ornithe/src/main/java");
        }
        assertTrue("invoker exists", new File(main, "com/bedwarsqol/mixin/EntityRendererFovInvoker.java").isFile());
        assertTrue("accessor exists", new File(main, "com/bedwarsqol/mixin/GuiChatRewindAccessor.java").isFile());
        String inv = new String(Files.readAllBytes(new File(main, "com/bedwarsqol/mixin/EntityRendererFovInvoker.java")
                .toPath()), StandardCharsets.UTF_8);
        assertTrue(inv.contains("@Mixin(EntityRenderer.class)"));
        assertTrue(inv.contains("@Invoker(\"getFOVModifier\")"));
        String acc = new String(Files.readAllBytes(new File(main, "com/bedwarsqol/mixin/GuiChatRewindAccessor.java")
                .toPath()), StandardCharsets.UTF_8);
        assertTrue(acc.contains("@Mixin(GuiChat.class)"));
        assertTrue(acc.contains("@Accessor(\"inputField\")"));
        String json = new String(Files.readAllBytes(new File(main.getParentFile(), "resources/mixins.bedwarsqol.json")
                .toPath()), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"EntityRendererFovInvoker\""));
        assertTrue(json.contains("\"GuiChatRewindAccessor\""));
    }
}
