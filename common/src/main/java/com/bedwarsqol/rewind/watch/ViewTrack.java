package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;

/**
 * The recorded view at playback time (PLAN "C. Playback of v2 data": {@code ViewPart}, {@code FovPart}; Phase 12;
 * TASK C4-C7): what a format-v2 recording's VIEW, MOUSE, CHAT_INPUT and FOV records say was on screen at time T.
 * These records are time-scoped and never replayed by a seek, so every answer here is read <b>by time</b> from the
 * {@link ExtTrack} ({@link #at}), and straight play, a forward seek and a backward seek all land on the same state.
 *
 * <ul>
 *   <li><b>VIEW</b> ({@link #apply}): the latest VIEW at T feeds {@link ViewState#recorded} (perspective, F1, F3, Tab,
 *       screen kind); your view follows it unless F5 or the clean view took over (Q2).</li>
 *   <li><b>MOUSE</b> ({@link #mouseShown}): the recorder stores the cursor only while a screen is open, and on the
 *       first frame of each open screen at once, so the cursor is the latest MOUSE stamped since the open screen
 *       opened ({@link #screenSinceUs}: the start of the run of VIEW records with a screen open); none while no screen
 *       is open, and never one an earlier screen left.</li>
 *   <li><b>CHAT_INPUT</b> ({@link #chatShown}): stored only while chat is open, measured from the empty bar a new
 *       chat starts with, so the text is the latest CHAT_INPUT since this chat opened ({@link #kindSinceUs}), else
 *       the empty bar.</li>
 *   <li><b>FOV</b> ({@link #fovAt}): the recorded final FOV, interpolated between two samples at most
 *       {@link #FOV_LERP_MAX_US} apart (the recorder samples it at most every 4 ms while it changes; a longer gap is a
 *       value that held, then changed).</li>
 * </ul>
 *
 * A {@code segment} is a position in {@code LoadedRecording.segments()} ({@code TickTable.segment}); the recorder
 * restates VIEW and FOV at each segment's start, so a lookup stays inside its segment. -1 (unknown, e.g. a
 * continuation tick past the table's ends) reads the latest segment whose records of the tag began at or before T.
 * Decoding is cached by record, so a frame whose records did not change allocates nothing. Pure; one thread.
 */
public final class ViewTrack {

    /** Two FOV samples at most this far apart are interpolated; further apart, the earlier one holds. */
    public static final long FOV_LERP_MAX_US = 50_000L;
    /** A bound on the walk back over one screen's VIEW records (flags rarely change while a screen is open). */
    private static final int RUN_SCAN_MAX = 4096;

    private final ExtCodec.View view = new ExtCodec.View();
    private final ExtCodec.View older = new ExtCodec.View();
    private final ExtCodec.Mouse mouse = new ExtCodec.Mouse();
    private final ExtCodec.ChatInput chat = new ExtCodec.ChatInput();

    private ExtTrack track;
    private int viewSegment = -1;
    private int viewRecord = -1;
    private boolean hasView;
    private int perspective;
    private int flags;
    private int screenKind = RewindFormat.SCREEN_NONE;
    private long screenSinceUs;
    private long kindSinceUs;

    private int mouseRecord = -1;
    private boolean mouseShown;
    private int mouseDx;
    private int mouseDy;

    private int chatRecord = -1;
    private boolean chatShown;
    private String chatText = "";
    private int chatCursor;

    /** Forgets everything (a new recording, a restart). */
    public void reset() {
        track = null;
        viewSegment = -1;
        viewRecord = -1;
        hasView = false;
        perspective = 0;
        flags = 0;
        screenKind = RewindFormat.SCREEN_NONE;
        screenSinceUs = 0;
        kindSinceUs = 0;
        mouseRecord = -1;
        mouseShown = false;
        mouseDx = 0;
        mouseDy = 0;
        chatRecord = -1;
        chatShown = false;
        chatText = "";
        chatCursor = 0;
    }

    /** Reads the recorded VIEW, MOUSE and CHAT_INPUT state at {@code tUs} in {@code segment} (-1 unknown). */
    public void at(ExtTrack t, int segment, long tUs) {
        if (t != track) {
            reset();
            track = t;
        }
        if (t == null) {
            return;
        }
        readView(t, segment, tUs);
        readMouse(t, tUs);
        readChat(t, tUs);
    }

    /** Puts the recorded VIEW on {@code v} ({@link ViewState#recorded}); nothing when none is recorded yet. */
    public void apply(ViewState v) {
        if (hasView && v != null) {
            v.recorded(perspective, flags, screenKind);
        }
    }

    private void readView(ExtTrack t, int segment, long tUs) {
        int s = segmentFor(t, RewindFormat.TAG_VIEW, segment, tUs);
        int j = s < 0 ? -1 : t.indexAt(RewindFormat.TAG_VIEW, s, tUs);
        if (j < 0) {
            viewSegment = s;
            viewRecord = -1;
            hasView = false;
            screenKind = RewindFormat.SCREEN_NONE;
            return;
        }
        int rec = t.record(RewindFormat.TAG_VIEW, s, j);
        if (rec == viewRecord && s == viewSegment) {
            return;
        }
        try {
            t.read(rec, view);
        } catch (RuntimeException e) {
            return; // the loader skips malformed bodies; keep what was shown
        }
        viewSegment = s;
        viewRecord = rec;
        hasView = true;
        perspective = view.perspective;
        flags = view.flags;
        screenKind = view.screenKind;
        long since = t.timeUs(RewindFormat.TAG_VIEW, s, j);
        screenSinceUs = since;
        kindSinceUs = since;
        if (screenKind == RewindFormat.SCREEN_NONE) {
            return;
        }
        boolean sameKind = true;
        for (int i = j - 1, n = 0; i >= 0 && n < RUN_SCAN_MAX; i--, n++) {
            int kind;
            try {
                kind = t.read(t.record(RewindFormat.TAG_VIEW, s, i), older).screenKind;
            } catch (RuntimeException e) {
                break;
            }
            if (kind == RewindFormat.SCREEN_NONE) {
                break;
            }
            screenSinceUs = t.timeUs(RewindFormat.TAG_VIEW, s, i);
            if (sameKind && kind == screenKind) {
                kindSinceUs = screenSinceUs;
            } else {
                sameKind = false;
            }
        }
    }

    private void readMouse(ExtTrack t, long tUs) {
        mouseShown = false;
        if (!hasView || screenKind == RewindFormat.SCREEN_NONE) {
            return;
        }
        int s = viewSegment;
        int j = t.indexAt(RewindFormat.TAG_MOUSE, s, tUs);
        if (j < 0 || t.timeUs(RewindFormat.TAG_MOUSE, s, j) < screenSinceUs) {
            return;
        }
        int rec = t.record(RewindFormat.TAG_MOUSE, s, j);
        if (rec != mouseRecord) {
            try {
                t.read(rec, mouse);
            } catch (RuntimeException e) {
                return;
            }
            mouseRecord = rec;
            mouseDx = mouse.dx;
            mouseDy = mouse.dy;
        }
        mouseShown = true;
    }

    private void readChat(ExtTrack t, long tUs) {
        chatShown = hasView && screenKind == RewindFormat.SCREEN_CHAT;
        if (!chatShown) {
            clearChat();
            return;
        }
        int s = viewSegment;
        int j = t.indexAt(RewindFormat.TAG_CHAT_INPUT, s, tUs);
        if (j < 0 || t.timeUs(RewindFormat.TAG_CHAT_INPUT, s, j) < kindSinceUs) {
            clearChat(); // the empty bar a new chat opens with
            return;
        }
        int rec = t.record(RewindFormat.TAG_CHAT_INPUT, s, j);
        if (rec != chatRecord) {
            try {
                t.read(rec, chat);
            } catch (RuntimeException e) {
                return;
            }
            chatRecord = rec;
            chatText = chat.text == null ? "" : chat.text;
            chatCursor = chat.cursor;
        }
    }

    private void clearChat() {
        chatRecord = -1;
        chatText = "";
        chatCursor = 0;
    }

    // ================================================================== answers

    /** A VIEW record is in effect at the time read. */
    public boolean hasView() {
        return hasView;
    }

    /** {@code gameSettings.thirdPersonView} as recorded (0-2). */
    public int perspective() {
        return perspective;
    }

    /** {@code RewindFormat.VIEW_*} flags as recorded. */
    public int flags() {
        return flags;
    }

    /** The recorded open screen ({@code RewindFormat.SCREEN_*}); {@code SCREEN_NONE} before any VIEW. */
    public int screenKind() {
        return screenKind;
    }

    /** When the screen open now was opened (the first VIEW of the run with a screen open). */
    public long screenSinceUs() {
        return screenSinceUs;
    }

    /** When the screen kind shown now began (the first VIEW of the run of this kind). */
    public long kindSinceUs() {
        return kindSinceUs;
    }

    /** The open screen has a recorded cursor ({@link #mouseDx}, {@link #mouseDy}). */
    public boolean mouseShown() {
        return mouseShown;
    }

    /** The cursor's x offset from the screen centre, quarter GUI pixels ({@code ViewCapture.mouseDx}). */
    public int mouseDx() {
        return mouseDx;
    }

    /** The cursor's y offset from the screen centre, downwards, quarter GUI pixels. */
    public int mouseDy() {
        return mouseDy;
    }

    /** Chat is open in the recording now. */
    public boolean chatShown() {
        return chatShown;
    }

    /** The recorded chat input text (empty before the first keystroke of this chat). Private player data. */
    public String chatText() {
        return chatText;
    }

    /** The recorded chat input cursor. */
    public int chatCursor() {
        return chatCursor;
    }

    // ================================================================== static reads

    /**
     * {@code segment} when it is known (>= 0); else the latest segment whose first record of {@code tag} is at or
     * before {@code tUs}, or -1.
     */
    public static int segmentFor(ExtTrack t, int tag, int segment, long tUs) {
        if (segment >= 0 || t == null) {
            return segment;
        }
        for (int s = t.segmentCount() - 1; s >= 0; s--) {
            if (t.count(tag, s) > 0 && t.timeUs(tag, s, 0) <= tUs) {
                return s;
            }
        }
        return -1;
    }

    /**
     * The recorded final world FOV at {@code tUs} in degrees, interpolated between two samples at most
     * {@link #FOV_LERP_MAX_US} apart; NaN before the first sample or without FOV records. Allocates nothing.
     */
    public static float fovAt(ExtTrack t, int segment, long tUs, ExtCodec.Fov decoder) {
        if (t == null || !t.has(RewindFormat.TAG_FOV)) {
            return Float.NaN;
        }
        int s = segmentFor(t, RewindFormat.TAG_FOV, segment, tUs);
        int j = s < 0 ? -1 : t.indexAt(RewindFormat.TAG_FOV, s, tUs);
        if (j < 0) {
            return Float.NaN;
        }
        float a;
        try {
            a = t.read(t.record(RewindFormat.TAG_FOV, s, j), decoder).degrees;
        } catch (RuntimeException e) {
            return Float.NaN;
        }
        if (j + 1 >= t.count(RewindFormat.TAG_FOV, s)) {
            return a;
        }
        long t0 = t.timeUs(RewindFormat.TAG_FOV, s, j);
        long t1 = t.timeUs(RewindFormat.TAG_FOV, s, j + 1);
        if (t1 <= t0 || t1 - t0 > FOV_LERP_MAX_US) {
            return a;
        }
        float b;
        try {
            b = t.read(t.record(RewindFormat.TAG_FOV, s, j + 1), decoder).degrees;
        } catch (RuntimeException e) {
            return a;
        }
        return a + (b - a) * (float) ((tUs - t0) / (double) (t1 - t0));
    }

    /** World ticks whose recorded head lies in {@code (fromUs, toUs]} (vanilla's per-tick screen counters). */
    public static long ticksBetween(TickTable table, long fromUs, long toUs) {
        if (table == null || toUs <= fromUs) {
            return 0;
        }
        return table.tickOf(toUs + 1) - table.tickOf(fromUs + 1);
    }
}
