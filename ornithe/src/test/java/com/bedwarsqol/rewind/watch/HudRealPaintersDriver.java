package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.hud.HudModel;
import com.bedwarsqol.hud.HudSource;
import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.HudCapture;
import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.init.Bootstrap;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs inside {@link NoGlClassLoader} (its own copies of Minecraft, Cobblify and LWJGL, OpenGL stubbed): the real
 * recorded-HUD path, {@link HudPlayback#draw} with the live painters ({@link HudPlayback#LIVE_PAINTERS}:
 * {@code BedwarsHudRenderer.renderRecorded} and {@code renderRecordedPost}), on a v2 recording's synthetic FPS, CPS,
 * Ping, generator timer and Session Stats records, with a font that writes down what it is asked to draw. Returns, in
 * plain JDK types: the drawn strings, the live trackers' picture before and after, the records handed to the recorder
 * and the first painter failure (null when none).
 */
public final class HudRealPaintersDriver {

    private static final long MS = 1_000L;

    /**
     * {@code BedwarsHudRenderer}'s default-layout memo: worked out from the example sizes, the screen and the placement
     * settings only, and stored with that key, so the live HUD's next draw (another key) works its own out again. Not
     * a tracker: left out of the before/after picture.
     */
    static final java.util.Set<String> MEMOS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "layoutKey", "layoutCache", "layoutTeamScale"));

    private HudRealPaintersDriver() {
    }

    /** A client with no window or GL; {@code isUnicode} false. */
    static final class Mc extends Minecraft {
        Mc() {
            super(null); // never run: allocated
        }

        @Override
        public boolean isUnicode() {
            return false;
        }
    }

    /** Vanilla's font with every glyph 6 px wide, noting each string it is asked to draw. */
    static final class RecordingFont extends FontRenderer {
        List<String> drawn;

        RecordingFont() {
            super(null, null, null, false); // never run: allocated
        }

        @Override
        public int getStringWidth(String text) {
            return text == null ? 0 : text.length() * 6;
        }

        @Override
        public int getCharWidth(char c) {
            return 6;
        }

        @Override
        public int drawString(String text, float x, float y, int color, boolean dropShadow) {
            if (drawn == null) drawn = new ArrayList<String>();
            drawn.add(text);
            return getStringWidth(text);
        }
    }

    private static int hk(int item, int field) {
        return RewindFormat.settingHudKey(item, field);
    }

    /** {drawn strings (List), trackers before (String), after (String), records handed over (Integer), failure}. */
    public static Object[] run() throws Exception {
        PrintStream out = System.out;
        Bootstrap.register();
        System.setOut(out);

        Minecraft mc = (Minecraft) allocate(Mc.class);
        mc.displayWidth = 854;
        mc.displayHeight = 480;
        mc.gameSettings = (GameSettings) allocate(GameSettings.class);
        mc.gameSettings.guiScale = 2;
        RecordingFont font = (RecordingFont) allocate(RecordingFont.class);
        font.FONT_HEIGHT = 9;
        mc.fontRendererObj = font;
        Field i18n = net.minecraft.client.resources.I18n.class.getDeclaredField("i18nLocale");
        i18n.setAccessible(true);
        i18n.set(null, new net.minecraft.client.resources.Locale()); // no language loaded: keys draw as themselves
        Field instance = Minecraft.class.getDeclaredField("theMinecraft");
        instance.setAccessible(true);
        instance.set(null, mc);
        ClientSettings live = new ClientSettings();
        live.guiFont = GuiTheme.FONT_MODERN; // today's font differs from the recorded one
        BedwarsQol.config = live;

        final int[] appended = {0};
        HudCapture.shared().setSink(new HudCapture.Sink() {
            @Override
            public boolean append(int tag, byte[] buf, int len) {
                appended[0]++;
                return true;
            }
        });
        HudCapture.shared().tickHead();
        HudCapture.shared().frame();
        String before = HudPlaybackTest.trackers(MEMOS);

        ExtCodec c = new ExtCodec();
        ExtRecords.Builder b = new ExtRecords.Builder();
        c.beginSettings();
        c.settingString(RewindFormat.SETTING_GUI_FONT, GuiTheme.FONT_MINECRAFT);
        c.settingString(RewindFormat.SETTING_GUI_ACCENT, "pale-green");
        for (int item : new int[]{RewindFormat.HUD_FPS, RewindFormat.HUD_CPS, RewindFormat.HUD_PING,
                RewindFormat.HUD_DIAMOND_TIMER, RewindFormat.HUD_EMERALD_TIMER, RewindFormat.HUD_SESSION_STATS}) {
            c.settingInt(hk(item, RewindFormat.HUD_FIELD_ENABLED), 1);
        }
        b.addEncoded(0, 10 * MS, RewindFormat.TAG_SETTINGS, c.buffer(), c.endSettings());
        int on = RewindFormat.HUD_FLAG_ON_SCREEN;
        b.addEncoded(0, 20 * MS, RewindFormat.TAG_HUD, c.buffer(), c.beginHud(RewindFormat.HUD_FPS, on).putVarInt(144).end());
        b.addEncoded(0, 20 * MS, RewindFormat.TAG_HUD, c.buffer(),
                c.beginHud(RewindFormat.HUD_CPS, on).putVarInt(7).putVarInt(3).end());
        b.addEncoded(0, 20 * MS, RewindFormat.TAG_HUD, c.buffer(), c.beginHud(RewindFormat.HUD_PING, on).putString("27").end());
        b.addEncoded(0, 20 * MS, RewindFormat.TAG_HUD, c.buffer(),
                c.beginHud(RewindFormat.HUD_DIAMOND_TIMER, on).putVarInt(23).end());
        b.addEncoded(0, 20 * MS, RewindFormat.TAG_HUD, c.buffer(),
                c.beginHud(RewindFormat.HUD_EMERALD_TIMER, on).putVarInt(-1).end());
        c.beginHud(RewindFormat.HUD_SESSION_STATS, on);
        HudModel.putLines(c, new String[]{"Game", "Kills: 3"}, new int[]{HudModel.LINE_HEADER, HudModel.LINE_ROW}, 2);
        b.addEncoded(0, 20 * MS, RewindFormat.TAG_HUD, c.buffer(), c.end());
        b.addEncoded(0, 300 * MS, RewindFormat.TAG_HUD, c.buffer(), c.beginHud(RewindFormat.HUD_FPS, on).putVarInt(120).end());
        ExtTrack track = ExtTrack.of(b.build());

        final Throwable[] failure = {null};
        HudPlayback hp = new HudPlayback(new HudPlayback.Painters() {
            @Override
            public void text(Minecraft m, ClientSettings s, HudSource src) {
                try {
                    HudPlayback.LIVE_PAINTERS.text(m, s, src);
                } catch (RuntimeException | Error t) {
                    if (failure[0] == null) failure[0] = t;
                    throw t;
                }
            }

            @Override
            public void post(Minecraft m, ClientSettings s, HudSource src) {
                try {
                    HudPlayback.LIVE_PAINTERS.post(m, s, src);
                } catch (RuntimeException | Error t) {
                    if (failure[0] == null) failure[0] = t;
                    throw t;
                }
            }
        });
        ViewState view = new ViewState();
        List<String> drawn = new ArrayList<String>();
        for (long ms : new long[]{100, 316}) {
            font.drawn = null;
            hp.draw(mc, false, view, track, 0, ms * MS);
            hp.draw(mc, true, view, track, 0, ms * MS);
            drawn.add("frame " + ms);
            if (font.drawn != null) drawn.addAll(font.drawn);
        }
        hp.restart();
        hp.exit();
        String after = HudPlaybackTest.trackers(MEMOS);
        HudCapture.shared().setSink(null);
        return new Object[]{drawn, before, after, appended[0], failure[0] == null ? null : stack(failure[0])};
    }

    private static String stack(Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Field f = u.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return u.getMethod("allocateInstance", Class.class).invoke(f.get(null), type);
    }
}
