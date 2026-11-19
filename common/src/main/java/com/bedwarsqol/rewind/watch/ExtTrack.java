package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.hud.HudModel;
import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;

/**
 * The format-v2 extension records of a loaded recording as the v2 playback parts read them (PLAN "C. Playback of v2
 * data", Phase 10): per tag and per segment, time-ordered lists of indices into one {@link ExtRecords}. Built once by
 * {@link LoadedRecording} (a v1 recording has {@link #EMPTY}); immutable; every read below allocates nothing.
 *
 * <p>Indices: {@code j} is a position in the list of one (tag, segment), {@code 0 <= j < count(tag, segment)}; a
 * <b>record index</b> ({@link #record}, {@link #stateAt}, {@link #keyedAt}) is a position in {@link #records()},
 * whose {@code buffer()/offset()/length()} give the body without its length prefix. {@link #read} decodes one record
 * into a caller-owned {@code ExtCodec} decoder (a part keeps one per tag, so decoding allocates nothing but strings).
 * {@code segment} is a position in {@link LoadedRecording#segments()} ({@link NetCursor#segmentIndex},
 * {@link TickTable#segment}).
 *
 * <p>Three ways to read:
 * <ul>
 *   <li><b>By time</b>: {@link #indexAt} (last at or before t), {@link #firstAfter}, {@link #firstAtOrAfter}.</li>
 *   <li><b>By tick</b>: {@link #firstInTick} to {@link #endOfTick} are the records of a tag stamped in tick k's
 *       interval {@code [T[k], T[k+1])}, the rule of the feeder's local phase; tick 0 also holds every record stamped
 *       before it (tick 0 is the first tick fed).</li>
 *   <li><b>State at time T</b>, for a part that must show the state after a seek or a restart without replaying
 *       (time-scoped records are never replayed on a seek; {@link PacketSink#timed}): {@link #stateAt} is the last
 *       record of a tag at or before T in that segment (VIEW, FOV, MOUSE, CHAT_INPUT, INPUT, USE, IDENTITY);
 *       {@link #keyedAt} the last one that set one key: an INV slot (0-40), a HUD module id, or a SETTINGS key (a
 *       SETTINGS record carries only the keys that changed, so each key is looked up on its own). The recorder
 *       restates all of these at the start of each segment, so a lookup never reaches into an earlier segment.</li>
 * </ul>
 */
public final class ExtTrack {

    /** Key range of {@link #keyedAt} (u8 keys: INV slot, HUD module, SETTINGS key). */
    private static final int KEYS = 256;
    /**
     * Segments indexed: a record in a later segment is skipped (review code-2 O11: per-segment index arrays are sized
     * by the highest segment named, so one record in segment 19,999 of a corrupt file cost about 99 MB). Far more than
     * a recorded game has.
     */
    public static final int MAX_SEGMENTS = 1024;
    private static final int KEYED_INV = 0;
    private static final int KEYED_HUD = 1;
    private static final int KEYED_SETTINGS = 2;

    /** No extension records (every v1 recording). */
    public static final ExtTrack EMPTY = new ExtTrack(ExtRecords.EMPTY, 0, new int[KEYS][][], new int[KEYS],
            new int[3][][][]);

    /** What {@link Checker#check} found in one body. */
    public enum Check {
        /** A known tag whose body its decoder reads. */
        OK,
        /** A tag {@code >= 0x20} this build does not know (a later build's record): skipped. */
        UNKNOWN,
        /** A known tag whose body does not decode: skipped and counted. */
        MALFORMED
    }

    private final ExtRecords records;
    private final int segments;
    /** {@code lists[tag][segment]}: record indices in time order; null when there are none. */
    private final int[][][] lists;
    private final int[] totals;
    /** {@code keyed[kind][key][segment]}: record indices in time order; null when there are none. */
    private final int[][][][] keyed;

    private ExtTrack(ExtRecords records, int segments, int[][][] lists, int[] totals, int[][][][] keyed) {
        this.records = records;
        this.segments = segments;
        this.lists = lists;
        this.totals = totals;
        this.keyed = keyed;
    }

    /** The track of {@code records}; {@link #EMPTY} for null or no records. */
    public static ExtTrack of(ExtRecords records) {
        if (records == null || records.isEmpty()) {
            return EMPTY;
        }
        int n = records.size();
        int segs = 0;
        for (int i = 0; i < n; i++) {
            segs = Math.max(segs, Math.min(MAX_SEGMENTS, records.segmentIndex(i) + 1));
        }
        int[][] count = new int[KEYS][segs];
        int[] totals = new int[KEYS];
        int[] keyOf = new int[n];
        int[][][] keyCount = new int[3][KEYS][];
        ExtCodec.Inv inv = new ExtCodec.Inv();
        ExtCodec.Hud hud = new ExtCodec.Hud();
        ExtCodec.Settings settings = new ExtCodec.Settings();
        for (int i = 0; i < n; i++) {
            int s = records.segmentIndex(i);
            keyOf[i] = -1;
            if (s < 0 || s >= segs) {
                continue;
            }
            int tag = records.tag(i) & 0xFF;
            count[tag][s]++;
            totals[tag]++;
            int kind = keyedKind(tag);
            if (kind < 0) {
                continue;
            }
            try {
                if (kind == KEYED_SETTINGS) {
                    settings.read(records.buffer(), records.offset(i), records.length(i));
                    for (int e = 0; e < settings.count; e++) {
                        bump(keyCount[kind], settings.keys[e] & 0xFF, s, segs);
                    }
                } else {
                    int key = kind == KEYED_INV
                            ? inv.read(records.buffer(), records.offset(i), records.length(i)).slot
                            : hud.read(records.buffer(), records.offset(i), records.length(i)).module;
                    keyOf[i] = key & 0xFF;
                    bump(keyCount[kind], key & 0xFF, s, segs);
                }
            } catch (RewindFormat.FormatException e) {
                // a body the loader would have skipped: listed by tag, no key state
            }
        }
        int[][][] lists = new int[KEYS][][];
        for (int tag = 0; tag < KEYS; tag++) {
            if (totals[tag] == 0) {
                continue;
            }
            lists[tag] = new int[segs][];
            for (int s = 0; s < segs; s++) {
                if (count[tag][s] > 0) {
                    lists[tag][s] = new int[count[tag][s]];
                }
            }
        }
        int[][][][] keyed = new int[3][][][];
        for (int kind = 0; kind < 3; kind++) {
            keyed[kind] = new int[KEYS][][];
            for (int key = 0; key < KEYS; key++) {
                int[] c = keyCount[kind][key];
                if (c == null) {
                    continue;
                }
                keyed[kind][key] = new int[segs][];
                for (int s = 0; s < segs; s++) {
                    if (c[s] > 0) {
                        keyed[kind][key][s] = new int[c[s]];
                    }
                }
            }
        }
        int[][] fill = new int[KEYS][segs];
        int[][][] keyFill = new int[3][KEYS][segs];
        for (int i = 0; i < n; i++) {
            int s = records.segmentIndex(i);
            if (s < 0 || s >= segs) {
                continue;
            }
            int tag = records.tag(i) & 0xFF;
            lists[tag][s][fill[tag][s]++] = i;
            int kind = keyedKind(tag);
            if (kind == KEYED_SETTINGS) {
                try {
                    settings.read(records.buffer(), records.offset(i), records.length(i));
                } catch (RewindFormat.FormatException e) {
                    continue;
                }
                for (int e = 0; e < settings.count; e++) {
                    int key = settings.keys[e] & 0xFF;
                    keyed[kind][key][s][keyFill[kind][key][s]++] = i;
                }
            } else if (kind >= 0 && keyOf[i] >= 0) {
                int key = keyOf[i];
                keyed[kind][key][s][keyFill[kind][key][s]++] = i;
            }
        }
        return new ExtTrack(records, segs, lists, totals, keyed);
    }

    private static void bump(int[][] counts, int key, int segment, int segs) {
        if (counts[key] == null) {
            counts[key] = new int[segs];
        }
        counts[key][segment]++;
    }

    private static int keyedKind(int tag) {
        switch (tag) {
            case RewindFormat.TAG_INV:
                return KEYED_INV;
            case RewindFormat.TAG_HUD:
                return KEYED_HUD;
            case RewindFormat.TAG_SETTINGS:
                return KEYED_SETTINGS;
            default:
                return -1;
        }
    }

    /** The records this track indexes (the recording's {@link LoadedRecording#ext()}). */
    public ExtRecords records() {
        return records;
    }

    /** True if the recording holds at least one record of {@code tag} (a v2 part checks this before acting). */
    public boolean has(int tag) {
        return tag >= 0 && tag < KEYS && totals[tag] > 0;
    }

    /** One more than the highest segment that holds a record (0 for {@link #EMPTY}). */
    public int segmentCount() {
        return segments;
    }

    /** Records of {@code tag} over all segments. */
    public int count(int tag) {
        return tag >= 0 && tag < KEYS ? totals[tag] : 0;
    }

    private int[] list(int tag, int segment) {
        if (tag < 0 || tag >= KEYS || segment < 0 || segment >= segments) {
            return null;
        }
        int[][] l = lists[tag];
        return l == null ? null : l[segment];
    }

    /** Records of {@code tag} in {@code segment}. */
    public int count(int tag, int segment) {
        int[] l = list(tag, segment);
        return l == null ? 0 : l.length;
    }

    /** The record index of the j-th record of {@code tag} in {@code segment} (time order). */
    public int record(int tag, int segment, int j) {
        return list(tag, segment)[j];
    }

    /** The stamp of the j-th record of {@code tag} in {@code segment}. */
    public long timeUs(int tag, int segment, int j) {
        return records.timeUs(list(tag, segment)[j]);
    }

    /** The last j whose stamp is at or before {@code tUs} (of equal stamps the last in file order), or -1. */
    public int indexAt(int tag, int segment, long tUs) {
        return firstAfter(list(tag, segment), tUs) - 1;
    }

    /** The first j whose stamp is after {@code tUs}; {@code count(tag, segment)} when there is none. */
    public int firstAfter(int tag, int segment, long tUs) {
        return firstAfter(list(tag, segment), tUs);
    }

    /** The first j whose stamp is at or after {@code tUs}; {@code count(tag, segment)} when there is none. */
    public int firstAtOrAfter(int tag, int segment, long tUs) {
        return tUs == Long.MIN_VALUE ? 0 : firstAfter(list(tag, segment), tUs - 1);
    }

    /** The first j of tick {@code k}'s records ({@code [T[k], T[k+1])}; tick 0 from the first record). */
    public int firstInTick(int tag, int segment, TickTable table, long k) {
        return k <= 0 ? 0 : firstAtOrAfter(tag, segment, table.timeOf(k));
    }

    /** One past the last j of tick {@code k}'s records; equal to {@link #firstInTick} when it has none. */
    public int endOfTick(int tag, int segment, TickTable table, long k) {
        return k < 0 ? 0 : firstAtOrAfter(tag, segment, table.timeOf(k + 1));
    }

    /** The record index of the last record of {@code tag} in {@code segment} stamped at or before {@code tUs}, or -1. */
    public int stateAt(int tag, int segment, long tUs) {
        int[] l = list(tag, segment);
        int j = firstAfter(l, tUs) - 1;
        return j < 0 ? -1 : l[j];
    }

    /**
     * The record index of the last record in {@code segment}, stamped at or before {@code tUs}, that set {@code key}
     * of {@code tag} (INV: the slot, 0-40; HUD: the module id; SETTINGS: the key; the record may set other SETTINGS
     * keys too: find the entry with {@code Settings.indexOf(key)}), or -1. Any other tag: -1.
     */
    public int keyedAt(int tag, int key, int segment, long tUs) {
        int kind = keyedKind(tag);
        if (kind < 0 || key < 0 || key >= KEYS || segment < 0 || segment >= segments) {
            return -1;
        }
        int[][] k = keyed[kind] == null ? null : keyed[kind][key];
        int[] l = k == null ? null : k[segment];
        int j = firstAfter(l, tUs) - 1;
        return j < 0 ? -1 : l[j];
    }

    /** Decodes record {@code record} into {@code decoder} and returns it. */
    public <B extends ExtCodec.Body> B read(int record, B decoder) {
        decoder.read(records.buffer(), records.offset(record), records.length(record));
        return decoder;
    }

    private int firstAfter(int[] l, long tUs) {
        if (l == null) {
            return 0;
        }
        int lo = 0;
        int hi = l.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (records.timeUs(l[mid]) <= tUs) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Sorts extension bodies while loading: {@link Check#UNKNOWN} for a tag this build has no decoder for, else
     * {@link Check#MALFORMED} when the tag's {@code ExtCodec} decoder (HUD: also {@link HudModel#decode}, which reads
     * the module's values) rejects the body. One per load; not thread-safe.
     */
    public static final class Checker {
        private final ExtCodec.TickHead tickHead = new ExtCodec.TickHead();
        private final ExtCodec.Out out = new ExtCodec.Out();
        private final ExtCodec.Dig dig = new ExtCodec.Dig();
        private final ExtCodec.Block block = new ExtCodec.Block();
        private final ExtCodec.Sound sound = new ExtCodec.Sound();
        private final ExtCodec.View view = new ExtCodec.View();
        private final ExtCodec.Mouse mouse = new ExtCodec.Mouse();
        private final ExtCodec.ChatInput chatInput = new ExtCodec.ChatInput();
        private final ExtCodec.Fov fov = new ExtCodec.Fov();
        private final ExtCodec.Use use = new ExtCodec.Use();
        private final ExtCodec.Inv inv = new ExtCodec.Inv();
        private final ExtCodec.Settings settings = new ExtCodec.Settings();
        private final ExtCodec.Identity identity = new ExtCodec.Identity();
        private final ExtCodec.Input input = new ExtCodec.Input();
        private final ExtCodec.Hud hud = new ExtCodec.Hud();
        private final ExtCodec.Cont cont = new ExtCodec.Cont();
        private final ExtCodec.Tap tap = new ExtCodec.Tap();

        public Check check(int tag, byte[] b, int off, int len) {
            ExtCodec.Body d = decoder(tag);
            if (d == null) {
                return Check.UNKNOWN;
            }
            try {
                d.read(b, off, len);
                if (tag == RewindFormat.TAG_OUT && out.packetId < 0) {
                    return Check.MALFORMED;
                }
                if (tag == RewindFormat.TAG_HUD) {
                    HudModel.decode(hud);
                }
                return Check.OK;
            } catch (RuntimeException e) {
                return Check.MALFORMED; // decoders throw FormatException; anything else is a malformed body too
            }
        }

        private ExtCodec.Body decoder(int tag) {
            switch (tag) {
                case RewindFormat.TAG_TICK_HEAD:
                    return tickHead;
                case RewindFormat.TAG_OUT:
                    return out;
                case RewindFormat.TAG_DIG:
                    return dig;
                case RewindFormat.TAG_BLOCK:
                    return block;
                case RewindFormat.TAG_SOUND:
                    return sound;
                case RewindFormat.TAG_VIEW:
                    return view;
                case RewindFormat.TAG_MOUSE:
                    return mouse;
                case RewindFormat.TAG_CHAT_INPUT:
                    return chatInput;
                case RewindFormat.TAG_FOV:
                    return fov;
                case RewindFormat.TAG_USE:
                    return use;
                case RewindFormat.TAG_INV:
                    return inv;
                case RewindFormat.TAG_SETTINGS:
                    return settings;
                case RewindFormat.TAG_IDENTITY:
                    return identity;
                case RewindFormat.TAG_INPUT:
                    return input;
                case RewindFormat.TAG_HUD:
                    return hud;
                case RewindFormat.TAG_CONT:
                    return cont;
                case RewindFormat.TAG_TAP:
                    return tap;
                default:
                    return null;
            }
        }
    }
}
