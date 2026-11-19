package com.bedwarsqol.hud;

import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.KeyFade;
import com.bedwarsqol.feature.TeamUpgradeRows;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.watch.ExtTrack;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A format-v2 recording as the HUD painters' {@link HudSource} (PLAN "Cobblify HUD as recorded", Phase 15; TASK D5):
 * what {@link BedwarsHudRenderer#renderRecorded} draws in your view of a recording, at the playback time
 * {@link #at} points it to. Recorded versus recomputed, per item:
 * <ul>
 *   <li><b>Recorded</b> (the latest HUD record of the item at that time, {@code ExtTrack.keyedAt}): FPS, CPS, Ping,
 *       the generator timers, Map Info, Session Stats and Upgrades &amp; Traps values, and whether each item was on
 *       screen ({@link #shows}). In Game Only, Map Info's and Session Stats' own visibility rules passed when it was
 *       recorded, so they pass here.</li>
 *   <li><b>From the camera</b> ({@code mc.thePlayer} in your view: the recorded effects, armour and inventory): Potion,
 *       Armor and Inventory.</li>
 *   <li><b>Keystrokes</b>: held keys from the INPUT record at that time; each cap's fade is this source's own
 *       {@link KeyFade}, flashed by TAP records at their stamps ({@link #tap}) and run on playback time
 *       ({@link #now}), so a click shorter than a frame flashes as it did live.</li>
 *   <li><b>Settings</b> ({@link #settings}): a {@link ClientSettings} copy built from the SETTINGS records in effect
 *       (font, accent, HUD Size and every item's place, size, background and options); the caller draws inside it.</li>
 * </ul>
 * Reads only the recording and the camera: no live tracker, no lookup, nothing sent. Decodes a record once, when the
 * one in effect changes. Render thread.
 */
public final class RecordedHudSource implements HudSource {

    /** Keystrokes' fade after a release, as the live caps ({@code BedwarsHudRenderer.KS_FADE_MS}; pinned by a test). */
    static final long FADE_MS = 120L;
    private static final int CAPS = 7;
    private static final int CAP_LMB = 5, CAP_RMB = 6;
    private static final int MODULES = RewindFormat.HUD_PING + 1;
    private static final int[] INPUT_BITS = {RewindFormat.INPUT_W, RewindFormat.INPUT_A, RewindFormat.INPUT_S,
            RewindFormat.INPUT_D, RewindFormat.INPUT_JUMP, RewindFormat.INPUT_LMB, RewindFormat.INPUT_RMB};

    private final KeyFade[] fades = new KeyFade[CAPS];
    private final boolean[] held = new boolean[CAPS];
    private final ExtCodec.Hud hudDecoder = new ExtCodec.Hud();
    private final ExtCodec.Settings settingsDecoder = new ExtCodec.Settings();
    private final ExtCodec.Input inputDecoder = new ExtCodec.Input();

    private ExtTrack track;
    private long timeUs;
    private boolean placed;

    /** By module: the record decoded into {@link #values} (-1 none) and what the painters get from it. */
    private final int[] valueRecord = new int[MODULES];
    private final HudModel.Values[] values = new HudModel.Values[MODULES];
    private final List<?>[] runs = new List<?>[MODULES];
    private List<BedwarsHudRenderer.Line> lines = Collections.emptyList();

    private int settingsRecord = -1;
    private ExtTrack settingsTrack;
    private ClientSettings settings;
    private int inputRecord = -1;

    public RecordedHudSource() {
        reset();
    }

    /** Forgets everything: the Keystrokes flashes and every decoded record (a new or restarted playback). */
    public void reset() {
        for (int i = 0; i < CAPS; i++) {
            fades[i] = new KeyFade(FADE_MS);
            held[i] = false;
        }
        for (int m = 0; m < MODULES; m++) {
            valueRecord[m] = -1;
            values[m] = null;
            runs[m] = null;
        }
        lines = Collections.emptyList();
        track = null;
        settingsTrack = null;
        settingsRecord = -1;
        settings = null;
        inputRecord = -1;
        placed = false;
        timeUs = 0L;
    }

    /**
     * Points the source at recording time {@code timeUs} of {@code segment} in {@code track}. Time moving back (a
     * seek) forgets the flashes, so none outlives its own time.
     */
    public void at(ExtTrack track, int segment, long timeUs) {
        if (placed && timeUs < this.timeUs) {
            for (int i = 0; i < CAPS; i++) {
                fades[i] = new KeyFade(FADE_MS);
            }
        }
        if (track != this.track) {
            for (int m = 0; m < MODULES; m++) valueRecord[m] = -1;
            inputRecord = -1;
        }
        this.track = track;
        this.timeUs = timeUs;
        placed = true;
        if (track == null) {
            settings = null;
            return;
        }
        for (int m = RewindFormat.HUD_POTION; m < MODULES; m++) {
            int rec = track.keyedAt(RewindFormat.TAG_HUD, m, segment, timeUs);
            if (rec != valueRecord[m]) {
                valueRecord[m] = rec;
                values[m] = rec < 0 ? null : decode(track, rec);
                derive(m);
            }
        }
        int input = track.stateAt(RewindFormat.TAG_INPUT, segment, timeUs);
        if (input != inputRecord) {
            inputRecord = input;
            int keys = input < 0 ? 0 : readInput(track, input);
            for (int i = 0; i < CAPS; i++) held[i] = (keys & INPUT_BITS[i]) != 0;
        }
        int s = track.stateAt(RewindFormat.TAG_SETTINGS, segment, timeUs);
        if (s != settingsRecord || track != settingsTrack) {
            settingsRecord = s;
            settingsTrack = track;
            settings = s < 0 ? null : build(track, segment, timeUs);
        }
    }

    /**
     * The recorded HUD settings at the time {@link #at} pointed to, or null before the segment's SETTINGS snapshot. A
     * copy built for the recording: never the player's own.
     */
    public ClientSettings settings() {
        return settings;
    }

    /** A TAP record at {@code timeUs}: the Keystrokes mouse cap ({@code RewindFormat.TAP_LMB/RMB}) flashes from then. */
    public void tap(int button, long timeUs) {
        int cap = button == RewindFormat.TAP_LMB ? CAP_LMB : button == RewindFormat.TAP_RMB ? CAP_RMB : -1;
        if (cap >= 0) fades[cap].tap(Math.floorDiv(timeUs, 1000L));
    }

    // ------------------------------------------------------------------ HudSource

    @Override
    public boolean shows(String id) {
        int m = RewindFormat.hudModuleId(id);
        HudModel.Values v = m > 0 && m < MODULES ? values[m] : null;
        return v != null && v.onScreen;
    }

    /** It drew, so In Game Only passed when it was recorded. */
    @Override
    public boolean inGame() {
        return true;
    }

    @Override
    public boolean heightLimitVisible(ClientSettings cfg, boolean preview) {
        return shows(BedwarsHudRenderer.HEIGHT_LIMIT_HUD);
    }

    @Override
    public boolean sessionVisible(ClientSettings cfg, boolean preview) {
        return shows(BedwarsHudRenderer.SESSION_HUD);
    }

    /** The camera's effects (the recorded ones, in your view). */
    @Override
    public List<BedwarsHudRenderer.PotionEntry> potionEntries(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) return Collections.emptyList();
        Collection<PotionEffect> active = mc.thePlayer.getActivePotionEffects();
        if (active.isEmpty()) return Collections.emptyList();
        List<BedwarsHudRenderer.PotionEntry> entries = new ArrayList<>(active.size());
        for (PotionEffect eff : active) {
            int id = eff.getPotionID();
            if (id < 0 || id >= Potion.potionTypes.length || Potion.potionTypes[id] == null) continue;
            entries.add(new BedwarsHudRenderer.PotionEntry(Potion.potionTypes[id], eff.getAmplifier(),
                    eff.getDuration(), eff.getIsPotionDurationMax()));
        }
        return entries;
    }

    /** The camera's leggings (the recorded armour). */
    @Override
    public ItemStack currentLeggings(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) return null;
        return mc.thePlayer.inventory.armorInventory[1];
    }

    /** The camera's storage rows (main slots 9-35: the recorded inventory). */
    @Override
    public ItemStack[] miniInventorySlots(Minecraft mc) {
        ItemStack[] out = new ItemStack[BedwarsHudRenderer.MINI_INVENTORY_SLOTS];
        if (mc == null || mc.thePlayer == null) return out;
        ItemStack[] main = mc.thePlayer.inventory.mainInventory;
        for (int i = 0; i < out.length; i++) out[i] = main[9 + i];
        return out;
    }

    @Override
    public int timerValue(boolean diamond) {
        HudModel.Values v = values[diamond ? RewindFormat.HUD_DIAMOND_TIMER : RewindFormat.HUD_EMERALD_TIMER];
        return v != null && v.onScreen ? v.a : -1;
    }

    @Override
    public List<String> heightLimitRows(Minecraft mc) {
        HudModel.Values v = values[RewindFormat.HUD_HEIGHT_LIMIT];
        return v == null ? Collections.<String>emptyList() : v.rows;
    }

    /** The rows as recorded, in the layout they were recorded in. */
    @Override
    public List<TeamUpgradeRows.Row> teamUpgradesRows(TeamUpgradeRows.Layout layout) {
        HudModel.Values v = values[RewindFormat.HUD_TEAM_UPGRADES];
        return v == null ? Collections.<TeamUpgradeRows.Row>emptyList() : v.team;
    }

    @Override
    public List<BedwarsHudRenderer.Line> sessionLines() {
        return lines;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<TextModules.Seg> textRuns(Minecraft mc, String id) {
        int m = RewindFormat.hudModuleId(id);
        List<?> r = m > 0 && m < MODULES ? runs[m] : null;
        return r == null ? Collections.<TextModules.Seg>emptyList() : (List<TextModules.Seg>) r;
    }

    /** INPUT at the time {@link #at} pointed to, in cap order (W, A, S, D, Space, LMB, RMB). */
    @Override
    public boolean[] keyStates(Minecraft mc) {
        return held;
    }

    @Override
    public KeyFade keyFade(int cap) {
        return fades[cap];
    }

    /** Playback time in ms: the fades and the potion blink run on it. */
    @Override
    public long now() {
        return Math.floorDiv(timeUs, 1000L);
    }

    // ------------------------------------------------------------------ records

    private HudModel.Values decode(ExtTrack t, int rec) {
        try {
            return HudModel.decode(t.read(rec, hudDecoder));
        } catch (RewindFormat.FormatException e) {
            return null; // the loader counted it; nothing shows
        }
    }

    private int readInput(ExtTrack t, int rec) {
        try {
            return t.read(rec, inputDecoder).keys;
        } catch (RewindFormat.FormatException e) {
            return 0;
        }
    }

    /** What the painters read from module {@code m}'s values, built once per record. */
    private void derive(int m) {
        HudModel.Values v = values[m];
        boolean on = v != null && v.onScreen;
        switch (m) {
            case RewindFormat.HUD_FPS:
                runs[m] = on ? TextModules.fps(v.a) : null;
                break;
            case RewindFormat.HUD_CPS:
                runs[m] = on ? TextModules.cps(v.a, v.b) : null;
                break;
            case RewindFormat.HUD_PING:
                runs[m] = on ? TextModules.ping(v.text) : null;
                break;
            case RewindFormat.HUD_SESSION_STATS: {
                if (!on) {
                    lines = Collections.emptyList();
                    break;
                }
                List<BedwarsHudRenderer.Line> out = new ArrayList<>(v.lines.size());
                for (HudModel.Line l : v.lines) {
                    // as drawSessionHud draws them: headers SemiBold, rows Regular
                    out.add(new BedwarsHudRenderer.Line(l.text,
                            l.header() ? BedwarsQolFont.Weight.BOLD : BedwarsQolFont.Weight.REGULAR));
                }
                lines = out;
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ settings

    /**
     * A fresh {@link ClientSettings} with every HUD key the SETTINGS records set by {@code timeUs} in {@code segment}
     * (each key's latest value; the recorder states all of them at the start of each segment), sanitized. Keys that do
     * not place or style the HUD are left at their defaults. The Background keys go last, straight to each item, so
     * the shared gen-timer write of {@link HudModules#set} cannot carry one timer's value to the other.
     */
    private ClientSettings build(ExtTrack t, int segment, long timeUs) {
        ClientSettings c = new ClientSettings();
        c.sanitize(); // now at the current settings version: the sanitize below migrates nothing recorded
        int decoded = -1;
        boolean any = false;
        for (int pass = 0; pass < 2; pass++) {
            for (int key = 1; key <= 0xFF; key++) {
                int type = RewindFormat.settingType(key);
                if (type < 0) continue;
                boolean background = RewindFormat.settingHudField(key) == RewindFormat.HUD_FIELD_BACKGROUND;
                if (background != (pass == 1)) continue;
                int rec = t.keyedAt(RewindFormat.TAG_SETTINGS, key, segment, timeUs);
                if (rec < 0) continue;
                if (rec != decoded) {
                    try {
                        t.read(rec, settingsDecoder);
                    } catch (RewindFormat.FormatException e) {
                        decoded = -1;
                        continue;
                    }
                    decoded = rec;
                }
                int e = settingsDecoder.indexOf(key);
                if (e < 0 || settingsDecoder.types[e] != type) continue;
                apply(c, key, settingsDecoder.ints[e], settingsDecoder.floats[e], settingsDecoder.strings[e]);
                any = true;
            }
        }
        if (!any) return null;
        c.sanitize();
        return c;
    }

    private static void apply(ClientSettings c, int key, int i, float f, String s) {
        switch (key) {
            case RewindFormat.SETTING_GUI_FONT:
                c.guiFont = s;
                return;
            case RewindFormat.SETTING_GUI_ACCENT:
                c.guiAccent = s;
                return;
            case RewindFormat.SETTING_HUD_TEXT_SIZE:
                c.defaultTextSize = i;
                return;
            default:
                break;
        }
        int item = RewindFormat.settingHudItem(key);
        String id = item < 0 ? null : RewindFormat.hudModuleName(item);
        HudModuleState st = id == null ? null : HudModules.get(c, id);
        if (st == null) return;
        boolean b = i != 0;
        switch (RewindFormat.settingHudField(key)) {
            case RewindFormat.HUD_FIELD_ENABLED:
                HudModules.set(c, id, st.withEnabled(b));
                break;
            case RewindFormat.HUD_FIELD_X:
                HudModules.set(c, id, new HudModuleState(i, st.y, st.anchor, st.scale, st.enabled, st.background,
                        st.fracX, st.fracY));
                break;
            case RewindFormat.HUD_FIELD_Y:
                HudModules.set(c, id, new HudModuleState(st.x, i, st.anchor, st.scale, st.enabled, st.background,
                        st.fracX, st.fracY));
                break;
            case RewindFormat.HUD_FIELD_ANCHOR:
                HudModules.set(c, id, new HudModuleState(st.x, st.y, i, st.scale, st.enabled, st.background,
                        st.fracX, st.fracY));
                break;
            case RewindFormat.HUD_FIELD_SCALE:
                // review code-2 O1: only a scale the live settings allow (a corrupt one keeps the default)
                if (f >= CornerResize.MIN_SCALE && f <= CornerResize.MAX_SCALE) HudModules.set(c, id, st.withScale(f));
                break;
            case RewindFormat.HUD_FIELD_SUB_X:
                if (f >= -0.5f && f < 0.5f) HudModules.set(c, id, st.withFraction(f, st.fracY));
                break;
            case RewindFormat.HUD_FIELD_SUB_Y:
                if (f >= -0.5f && f < 0.5f) HudModules.set(c, id, st.withFraction(st.fracX, f));
                break;
            case RewindFormat.HUD_FIELD_BACKGROUND:
                c.setHudBackground(id, b);
                break;
            case RewindFormat.HUD_FIELD_IN_GAME_ONLY:
                inGameOnly(c, item, b);
                break;
            case RewindFormat.HUD_FIELD_OPTION_A:
                optionA(c, item, i);
                break;
            case RewindFormat.HUD_FIELD_OPTION_B:
                optionB(c, item, i);
                break;
            default:
                break;
        }
    }

    private static void inGameOnly(ClientSettings c, int item, boolean b) {
        switch (item) {
            case RewindFormat.HUD_POTION: c.potionInGameOnly = b; break;
            case RewindFormat.HUD_ARMOR: c.armorInGameOnly = b; break;
            case RewindFormat.HUD_INVENTORY: c.inventoryInGameOnly = b; break;
            case RewindFormat.HUD_DIAMOND_TIMER:
            case RewindFormat.HUD_EMERALD_TIMER: c.genTimersInGameOnly = b; break;
            case RewindFormat.HUD_KEYSTROKES: c.keystrokesInGameOnly = b; break;
            case RewindFormat.HUD_HEIGHT_LIMIT: c.heightLimitInGameOnly = b; break;
            default: break;
        }
    }

    /** Session Stats: Hold Key; Upgrades &amp; Traps: layout; FPS, CPS, Ping: brackets. */
    private static void optionA(ClientSettings c, int item, int i) {
        switch (item) {
            case RewindFormat.HUD_SESSION_STATS: c.sessionStatsHoldKey = i != 0; break;
            case RewindFormat.HUD_TEAM_UPGRADES: c.teamUpgradesLayout = i; break;
            case RewindFormat.HUD_FPS: c.fpsBrackets = i != 0; break;
            case RewindFormat.HUD_CPS: c.cpsBrackets = i != 0; break;
            case RewindFormat.HUD_PING: c.pingBrackets = i != 0; break;
            default: break;
        }
    }

    /** Session Stats: its key; FPS, CPS, Ping: label. */
    private static void optionB(ClientSettings c, int item, int i) {
        switch (item) {
            case RewindFormat.HUD_SESSION_STATS: c.sessionStatsKeyCode = i; break;
            case RewindFormat.HUD_FPS: c.fpsLabel = i; break;
            case RewindFormat.HUD_CPS: c.cpsLabel = i; break;
            case RewindFormat.HUD_PING: c.pingLabel = i; break;
            default: break;
        }
    }
}
