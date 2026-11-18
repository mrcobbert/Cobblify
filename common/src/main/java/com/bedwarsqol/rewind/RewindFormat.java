package com.bedwarsqol.rewind;

import java.nio.charset.StandardCharsets;

/**
 * Constants and primitive codecs of the Rewind disk format ({@code .cbr}). This is R2's contract:
 * change nothing here without bumping {@link #FORMAT_VERSION}.
 *
 * <p><b>Format v2</b> is v1 plus additive TRACK records: every block, NET record, HEADER, INDEX, END and every v1
 * TRACK tag (0x01-0x07) is byte for byte what v1 writes; tags at or above {@link #EXT_TAG_MIN} are length-prefixed
 * extension records (see "format v2: extension records" below). A reader accepts versions 1 and 2
 * ({@link #supportedVersion}); a writer always writes {@link #FORMAT_VERSION}; recovery finalizes a prefix in the
 * version its header names.
 *
 * <p>Encoding: big-endian; protocol varints (LEB128, at most 5 bytes for a varint, 10 for a varint64);
 * strings are a varint byte length then UTF-8.
 *
 * <p><b>File header</b> (28 B): magic {@code 43 42 52 57 4E 44 31 0A}, u16 formatVersion (1 or 2), u16 flags=0,
 * u32 protocol=47, i64 baseEpochMs (wall clock at timeUs 0), u32 CRC32 of bytes 0-23.
 *
 * <p><b>Block</b> (32 B header + payload): "RWB1" | u8 type | u8 codec (0 stored, 1 raw deflate) |
 * u16 reserved | u32 segment | i64 firstTimeUs | u32 rawLen | u32 storedLen (at most 8 MiB) | u32 CRC32
 * over header bytes 0-27 and the stored payload. NET and TRACK blocks never straddle segments.
 *
 * <p><b>Record times.</b> In NET and TRACK payloads every record starts with an unsigned 32-bit varint
 * {@code dtUs} measured from the previous record; the first record of a block is measured from the
 * block's {@code firstTimeUs}. So record 0 is at {@code firstTimeUs + dt0} and record i at
 * {@code t(i-1) + dt_i}. A writer that starts a block mid-chunk sets {@code firstTimeUs} to the time
 * of the record just before it, which lets it copy chunk bytes unchanged. No {@code dtUs} is ever larger
 * than {@link #MAX_DT_US}: a record that comes later than that after the previous one (an idle of about
 * 71 minutes) starts a new block whose {@code firstTimeUs} is the record's time and whose first
 * {@code dtUs} is 0 (the producer starts a chunk there, and blocks never span chunks). Times are never
 * clamped. This is a writer guarantee, not an encoding change: every v1 reader already handles it.
 *
 * <p><b>Segment invariant.</b> The first NET record of every segment is that segment's own S01, or its
 * dimension-changing S07.
 */
public final class RewindFormat {

    private RewindFormat() {
    }

    // ---- file header ----
    private static final byte[] FILE_MAGIC = {0x43, 0x42, 0x52, 0x57, 0x4E, 0x44, 0x31, 0x0A};
    /** The version this build writes: v1 + extension records. */
    public static final int FORMAT_VERSION = 2;
    public static final int PROTOCOL = 47;
    public static final int FILE_HEADER_LENGTH = 28;

    // ---- blocks ----
    /** "RWB1" as a big-endian u32. */
    public static final int BLOCK_MAGIC = 0x52574231;
    public static final int BLOCK_HEADER_LENGTH = 32;
    /** Largest stored payload a block may carry; the reader rejects more without reading it. */
    public static final int MAX_STORED_LENGTH = 8 << 20;
    /** Largest inflated payload; the writer never exceeds a chunk (2 MiB + 16), the reader caps allocation. */
    public static final int MAX_RAW_LENGTH = 8 << 20;

    public static final int BLOCK_NET = 1;
    public static final int BLOCK_TRACK = 2;
    public static final int BLOCK_HEADER = 3;
    public static final int BLOCK_INDEX = 4;
    public static final int BLOCK_END = 5;

    public static final int CODEC_STORED = 0;
    public static final int CODEC_DEFLATE = 1;

    /** HEADER source 0: the client-thread snapshot, starting with the synthetic S01; holds no S47. */
    public static final int SOURCE_CLIENT_SNAPSHOT = 0;
    /** HEADER source 1: the capture handler's S47 frozen at the boundary, in stream order. */
    public static final int SOURCE_CAPTURE_CARRY = 1;

    // ---- chunks ----
    /** Largest packet vanilla 1.8.9 accepts (2,097,152 B). */
    public static final int MAX_PACKET_LENGTH = 2_097_152;
    /** A chunk holds the largest packet with its record prefix: 2 MiB + 16 B. */
    public static final int CHUNK_CAPACITY = MAX_PACKET_LENGTH + 16;
    /** Largest record prefix: varint dtUs (5) + varint len (5), or varint dtUs (5) + u8 tag (1). */
    public static final int MAX_RECORD_PREFIX = 10;
    /** Largest dtUs a record can carry (unsigned 32-bit varint); a longer gap starts a new block instead. */
    public static final long MAX_DT_US = 0xFFFFFFFFL;

    // ---- TRACK tags ----
    public static final int TAG_TICK = 0x01;
    public static final int TAG_CAMERA = 0x02;
    public static final int TAG_EQUIP = 0x03;
    public static final int TAG_MARKER = 0x04;
    /**
     * Body: u8 key ({@code INFO_*}) + string. Written when the game opens for every value then known, and again
     * whenever a value is learned or changes later in the game; the latest record of a key wins (code review 2, I4).
     */
    public static final int TAG_INFO = 0x05;
    /**
     * Body: varint64 {@code backUs}. The record is written when the game is opened; the logical game start
     * (visit start, or the Bed Wars active edge) is the record's time minus {@code backUs} (code review 1, I6).
     */
    public static final int TAG_GAME_START = 0x06;
    public static final int TAG_GAME_END = 0x07;

    /** TICK mask bits, in body order. */
    public static final int TICK_POS = 1;        // x, y, z f64
    public static final int TICK_ROT = 1 << 1;   // yaw, pitch f32
    public static final int TICK_HEAD_YAW = 1 << 2; // f32
    public static final int TICK_FLAGS = 1 << 3; // u8
    public static final int TICK_HELD_SLOT = 1 << 4; // u8
    public static final int TICK_SWING = 1 << 5; // u8 swingProgressInt + 1
    public static final int TICK_HURT = 1 << 6;  // u8 hurtTime
    public static final int TICK_ALL = 0x7F;

    /** TICK flags byte bits. */
    public static final int FLAG_ON_GROUND = 1;
    public static final int FLAG_SNEAKING = 1 << 1;
    public static final int FLAG_SPRINTING = 1 << 2;
    public static final int FLAG_USING_ITEM = 1 << 3;
    public static final int FLAG_INVISIBLE = 1 << 4;
    public static final int FLAG_BURNING = 1 << 5;
    public static final int FLAG_RIDING = 1 << 6;

    /** EQUIP slots: 0 held, 1-4 boots to helmet. */
    public static final int EQUIP_HELD = 0;
    public static final int EQUIP_BOOTS = 1;
    public static final int EQUIP_HELMET = 4;

    public static final int MARKER_KEY = 1;
    public static final int MARKER_DEATH = 2;
    public static final int MARKER_FINAL_KILL = 3;
    public static final int MARKER_BED_BROKEN = 4;
    public static final int MARKER_BED_LOST = 5;

    public static final int INFO_MODE = 1;
    public static final int INFO_MAP = 2;
    public static final int INFO_MODE_LABEL = 3;

    public static final int GAME_END_UNKNOWN = 0;
    public static final int GAME_END_WIN = 1;
    public static final int GAME_END_LOSS = 2;

    /** Longest string (in UTF-8 bytes) any TRACK INFO body may hold. */
    public static final int MAX_STRING_BYTES = 32767 * 3;

    // ---- format v2: extension records ----
    //
    // A TRACK tag at or above EXT_TAG_MIN is an extension record: varint dtUs | u8 tag | varint len | body[len].
    // The walker sizes any such tag from its length, known or not, so a later build can add a tag without a version
    // bump and this build skips it; tags 0x08-0x1F stay invalid (no length to skip by). Every body is produced and
    // decoded by ExtCodec; decoders ignore bytes after the fields they know, so a body may also grow at its end.
    // Time: the TRACK clock, read when the event happens on the client thread. Sizes in comments are body bytes.

    /** The original format: every v1 record. Still read; never written by this build. */
    public static final int FORMAT_VERSION_1 = 1;
    /** Lowest extension tag. */
    public static final int EXT_TAG_MIN = 0x20;
    /** Largest extension body; the producer's chunk (at most {@link #CHUNK_CAPACITY}) is the real bound. */
    public static final int MAX_EXT_BODY_LENGTH = MAX_PACKET_LENGTH;

    /**
     * Body: varint {@code handled} (how many more of this segment's stored, client-thread-handled NET records had
     * their handler run since the previous TICK_HEAD), then an optional u8 of {@code TICK_HEAD_*} flags, present only
     * when a flag is set. Written at {@code Minecraft.runTick} HEAD, every tick of a recorded segment.
     */
    public static final int TAG_TICK_HEAD = 0x20;
    /** Body: a serverbound packet as vanilla writes it, varint id + {@code writePacketData} bytes (C02 C07 C08 C09 C0A C0B C0D C0E C16). */
    public static final int TAG_OUT = 0x21;
    /** Body: u8 flags ({@link #DIG_HITTING}), i64 block position (protocol long), u8 face, i8 crack stage (-1 none, 0-9). */
    public static final int TAG_DIG = 0x22;
    /** Body: i64 position, varint old state id, varint new state id, u8 cause ({@code BLOCK_CAUSE_*}). */
    public static final int TAG_BLOCK = 0x23;
    /** Body: string name, f32 x, y, z, f32 volume, f32 pitch. */
    public static final int TAG_SOUND = 0x24;
    /** Body: u8 perspective (0-2), u8 {@code VIEW_*} flags, u8 screen kind ({@code SCREEN_*}). */
    public static final int TAG_VIEW = 0x25;
    /** Body: i16 dx, i16 dy: the cursor's offset from the screen centre in quarter GUI pixels. */
    public static final int TAG_MOUSE = 0x26;
    /** Body: string text, varint cursor. */
    public static final int TAG_CHAT_INPUT = 0x27;
    /** Body: f32 final world FOV in degrees. */
    public static final int TAG_FOV = 0x28;
    /** Body: varint {@code itemInUseCount} (0-72000; a bow draw or sword block starts at 72000). */
    public static final int TAG_USE = 0x29;
    /** Body: u8 slot ({@code INV_*}: 0-35 main, 36-39 armour boots to helmet, 40 cursor) + protocol-47 slot bytes. */
    public static final int TAG_INV = 0x2A;
    /** Body: varint n, then n x (u8 key ({@code SETTING_*}), u8 type ({@code SETTING_TYPE_*}), value). */
    public static final int TAG_SETTINGS = 0x2B;
    /** Body: i64 uuidMost, i64 uuidLeast, string name, string skin value, string skin signature (empty when none). */
    public static final int TAG_IDENTITY = 0x2C;
    /** Body: u8 {@code INPUT_*} bits. */
    public static final int TAG_INPUT = 0x2D;
    /** Body: u8 module id ({@code HUD_*}), u8 {@code HUD_FLAG_*} flags, then the module's values (HudModel). */
    public static final int TAG_HUD = 0x2E;
    /** Body: u8 window id, varint slot, protocol-47 slot bytes: one slot of the open window that is not the inventory. */
    public static final int TAG_CONT = 0x2F;
    /** Body: u8 button ({@link #TAP_LMB}, {@link #TAP_RMB}): a mouse press the Keystrokes HUD was told about. */
    public static final int TAG_TAP = 0x30;

    /** Every extension tag this build writes, in tag order. */
    public static final int[] EXT_TAGS = {TAG_TICK_HEAD, TAG_OUT, TAG_DIG, TAG_BLOCK, TAG_SOUND, TAG_VIEW, TAG_MOUSE,
            TAG_CHAT_INPUT, TAG_FOV, TAG_USE, TAG_INV, TAG_SETTINGS, TAG_IDENTITY, TAG_INPUT, TAG_HUD, TAG_CONT, TAG_TAP};

    private static final String[] EXT_NAMES = {"TickHead", "Out", "Dig", "Block", "Sound", "View", "Mouse",
            "ChatInput", "Fov", "Use", "Inv", "Settings", "Identity", "Input", "Hud", "Cont", "Tap"};

    /** TICK_HEAD flag: the handled order is unknown at this head (ring overflow or a resync); use the arrival rule. */
    public static final int TICK_HEAD_ORDER_UNKNOWN = 1;

    /** DIG flags. */
    public static final int DIG_HITTING = 1;

    /** BLOCK causes. */
    public static final int BLOCK_CAUSE_OTHER = 0;
    /** The change happened while {@code PlayerControllerMP.onPlayerDestroyBlock} was on the stack. */
    public static final int BLOCK_CAUSE_OWN_BREAK = 1;

    /** VIEW flags. */
    public static final int VIEW_HIDE_GUI = 1;
    public static final int VIEW_F3 = 1 << 1;
    /** The tab list is showing (vanilla's rule, {@code TabHudGate}'s answer). */
    public static final int VIEW_TAB = 1 << 2;
    public static final int VIEW_SMOOTH_CAMERA = 1 << 3;

    /** VIEW screen kinds. */
    public static final int SCREEN_NONE = 0;
    public static final int SCREEN_CHAT = 1;
    public static final int SCREEN_INVENTORY = 2;
    /** A chest or generic container. */
    public static final int SCREEN_CHEST = 3;
    public static final int SCREEN_OTHER_CONTAINER = 4;
    public static final int SCREEN_DEATH = 5;
    public static final int SCREEN_ESC_MENU = 6;
    public static final int SCREEN_COBBLIFY = 7;
    public static final int SCREEN_OTHER = 8;

    /** MOUSE units per GUI pixel. */
    public static final int MOUSE_UNITS_PER_GUI_PX = 4;

    /** INV slots: 0-35 main inventory (vanilla's {@code mainInventory} order), 36-39 armour, 40 the cursor stack. */
    public static final int INV_MAIN_SLOTS = 36;
    public static final int INV_ARMOR_FIRST = 36;
    public static final int INV_CURSOR = 40;
    public static final int INV_SLOTS = 41;

    /** INPUT bits. */
    public static final int INPUT_W = 1;
    public static final int INPUT_A = 1 << 1;
    public static final int INPUT_S = 1 << 2;
    public static final int INPUT_D = 1 << 3;
    public static final int INPUT_JUMP = 1 << 4;
    public static final int INPUT_LMB = 1 << 5;
    public static final int INPUT_RMB = 1 << 6;

    /** TAP buttons (the INPUT bit index of the button). */
    public static final int TAP_LMB = 5;
    public static final int TAP_RMB = 6;

    /** HUD flags. */
    public static final int HUD_FLAG_ON_SCREEN = 1;

    /**
     * HUD module ids, in {@code HudModules.ids()} order (1-12), then the three vanilla overlays placed as HUD items
     * (13-15, SETTINGS only: vanilla draws them). 0 is never an id.
     */
    public static final int HUD_POTION = 1;
    public static final int HUD_ARMOR = 2;
    public static final int HUD_INVENTORY = 3;
    public static final int HUD_DIAMOND_TIMER = 4;
    public static final int HUD_EMERALD_TIMER = 5;
    public static final int HUD_KEYSTROKES = 6;
    public static final int HUD_SESSION_STATS = 7;
    public static final int HUD_HEIGHT_LIMIT = 8;
    public static final int HUD_TEAM_UPGRADES = 9;
    public static final int HUD_FPS = 10;
    public static final int HUD_CPS = 11;
    public static final int HUD_PING = 12;
    public static final int HUD_ITEM_SCOREBOARD = 13;
    public static final int HUD_ITEM_CHAT = 14;
    public static final int HUD_ITEM_TAB_LIST = 15;

    /** The HUD item string ids ({@code BedwarsHudRenderer.*_HUD}), index = id - 1. */
    private static final String[] HUD_NAMES = {"potion", "armor", "inventory", "diamondtimer", "emeraldtimer",
            "keystrokes", "sessionstats", "heightlimit", "teamupgrades", "fps", "cps", "ping", "scoreboard", "chat",
            "tablist"};

    /** SETTINGS value types. */
    public static final int SETTING_TYPE_VARINT = 0;
    public static final int SETTING_TYPE_F32 = 1;
    public static final int SETTING_TYPE_STRING = 2;

    // SETTINGS keys, below SETTING_HUD_BASE. Booleans are varint 0/1. Vanilla GameSettings and screen size:
    public static final int SETTING_FOV = 1;               // f32 fovSetting
    public static final int SETTING_GUI_SCALE = 2;         // guiScale
    public static final int SETTING_VIEW_BOBBING = 3;
    public static final int SETTING_SCALED_WIDTH = 4;      // ScaledResolution
    public static final int SETTING_SCALED_HEIGHT = 5;
    public static final int SETTING_DISPLAY_WIDTH = 6;     // displayWidth, displayHeight
    public static final int SETTING_DISPLAY_HEIGHT = 7;
    public static final int SETTING_PARTICLES_VANILLA = 8; // particleSetting
    public static final int SETTING_GAMMA = 9;             // f32 gammaSetting
    // Cobblify (ClientSettings):
    public static final int SETTING_GUI_FONT = 16;         // string guiFont
    public static final int SETTING_GUI_ACCENT = 17;       // string guiAccent
    public static final int SETTING_HUD_TEXT_SIZE = 18;    // defaultTextSize
    public static final int SETTING_OLD_ANIMATIONS = 19;
    public static final int SETTING_OA_BLOCK_HIT = 20;
    public static final int SETTING_OA_ITEM_POSITION = 21;
    public static final int SETTING_OA_SMOOTH_SNEAK = 22;
    public static final int SETTING_OA_HEARTS = 23;
    public static final int SETTING_OA_RED_ARMOR = 24;
    public static final int SETTING_HAND_POSITION = 25;    // handPositionEnabled
    public static final int SETTING_HAND_POS_X = 26;       // f32
    public static final int SETTING_HAND_POS_Y = 27;       // f32
    public static final int SETTING_HAND_POS_Z = 28;       // f32
    public static final int SETTING_HAND_SCALE = 29;       // f32
    public static final int SETTING_NO_HURT_CAM = 30;
    public static final int SETTING_DYNAMIC_FOV = 31;
    public static final int SETTING_ZOOM = 32;             // zoomEnabled
    public static final int SETTING_FULLBRIGHT = 33;
    public static final int SETTING_MOTION_BLUR = 34;
    public static final int SETTING_MOTION_BLUR_STRENGTH = 35; // mbStrength
    public static final int SETTING_ITEM_PHYSICS = 36;
    public static final int SETTING_PARTICLES = 37;        // Cobblify's Particles module
    public static final int SETTING_PT_ALWAYS_CRIT = 38;
    public static final int SETTING_PT_HIDE_OWN_POTION = 39;
    public static final int SETTING_PT_HIDE_BLOCK_BREAK = 40;
    public static final int SETTING_PT_MULTIPLIER = 41;

    private static final int[] SETTING_F32_KEYS = {SETTING_FOV, SETTING_GAMMA, SETTING_HAND_POS_X, SETTING_HAND_POS_Y,
            SETTING_HAND_POS_Z, SETTING_HAND_SCALE};
    private static final int SETTING_LAST_FIXED = SETTING_PT_MULTIPLIER;

    /**
     * HUD item keys: {@code SETTING_HUD_BASE + (item - 1) x HUD_FIELDS + field} for item {@link #HUD_POTION} to
     * {@link #HUD_ITEM_TAB_LIST}; the last key is 0xE4. A field an item does not have is not written.
     */
    public static final int SETTING_HUD_BASE = 0x40;
    public static final int HUD_FIELDS = 11;
    public static final int HUD_FIELD_ENABLED = 0;       // ...Enabled (scoreboard: scoreboardHudEnabled)
    public static final int HUD_FIELD_IN_GAME_ONLY = 1;  // ...InGameOnly
    public static final int HUD_FIELD_X = 2;             // ...HudX
    public static final int HUD_FIELD_Y = 3;             // ...HudY
    public static final int HUD_FIELD_ANCHOR = 4;        // ...HudAnchor
    public static final int HUD_FIELD_SCALE = 5;         // f32 ...HudScale
    public static final int HUD_FIELD_BACKGROUND = 6;    // hudBackgrounds entry (absent = on)
    public static final int HUD_FIELD_SUB_X = 7;         // f32 hudSubPixel[0]
    public static final int HUD_FIELD_SUB_Y = 8;         // f32 hudSubPixel[1]
    /** fps/cps/ping: brackets; teamupgrades: layout; sessionstats: hold key. */
    public static final int HUD_FIELD_OPTION_A = 9;
    /** fps/cps/ping: label; sessionstats: key code. */
    public static final int HUD_FIELD_OPTION_B = 10;

    /** True for the versions this build reads (and so may recover or prune): 1 and 2. */
    public static boolean supportedVersion(int version) {
        return version == FORMAT_VERSION_1 || version == FORMAT_VERSION;
    }

    /** True for a TRACK tag framed as an extension record (sized by its length, known or not). */
    public static boolean isExtensionTag(int tag) {
        return tag >= EXT_TAG_MIN && tag <= 0xFF;
    }

    /** Short name of a known extension tag ({@code RewindCheck} counts), or null. */
    public static String extName(int tag) {
        int i = tag - TAG_TICK_HEAD;
        return i >= 0 && i < EXT_NAMES.length ? EXT_NAMES[i] : null;
    }

    /** True for the nine serverbound ids an OUT record may hold: C02, C07, C08, C09, C0A, C0B, C0D, C0E, C16. */
    public static boolean isStoredOutbound(int packetId) {
        switch (packetId) {
            case 0x02:
            case 0x07:
            case 0x08:
            case 0x09:
            case 0x0A:
            case 0x0B:
            case 0x0D:
            case 0x0E:
            case 0x16:
                return true;
            default:
                return false;
        }
    }

    /** The SETTINGS key of HUD {@code item}'s {@code field}. */
    public static int settingHudKey(int item, int field) {
        if (item < HUD_POTION || item > HUD_ITEM_TAB_LIST || field < 0 || field >= HUD_FIELDS) {
            throw new IllegalArgumentException("hud item " + item + " field " + field);
        }
        return SETTING_HUD_BASE + (item - 1) * HUD_FIELDS + field;
    }

    /** The HUD item of a HUD settings key, or -1 for any other key. */
    public static int settingHudItem(int key) {
        int i = key - SETTING_HUD_BASE;
        return i >= 0 && i < HUD_ITEM_TAB_LIST * HUD_FIELDS ? i / HUD_FIELDS + 1 : -1;
    }

    /** The field of a HUD settings key, or -1 for any other key. */
    public static int settingHudField(int key) {
        int i = key - SETTING_HUD_BASE;
        return i >= 0 && i < HUD_ITEM_TAB_LIST * HUD_FIELDS ? i % HUD_FIELDS : -1;
    }

    /** The type a known SETTINGS key is written with, or -1 for a key this build does not number. */
    public static int settingType(int key) {
        int field = settingHudField(key);
        if (field >= 0) {
            return field == HUD_FIELD_SCALE || field == HUD_FIELD_SUB_X || field == HUD_FIELD_SUB_Y
                    ? SETTING_TYPE_F32 : SETTING_TYPE_VARINT;
        }
        if (key == SETTING_GUI_FONT || key == SETTING_GUI_ACCENT) {
            return SETTING_TYPE_STRING;
        }
        for (int k : SETTING_F32_KEYS) {
            if (k == key) {
                return SETTING_TYPE_F32;
            }
        }
        if ((key >= SETTING_FOV && key <= SETTING_GAMMA) || (key >= SETTING_GUI_FONT && key <= SETTING_LAST_FIXED)) {
            return SETTING_TYPE_VARINT;
        }
        return -1;
    }

    /** The HUD module id of a HUD item string id, or 0 if it is not one. */
    public static int hudModuleId(String name) {
        for (int i = 0; i < HUD_NAMES.length; i++) {
            if (HUD_NAMES[i].equals(name)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** The HUD item string id of a module id, or null. */
    public static String hudModuleName(int id) {
        return id >= 1 && id <= HUD_NAMES.length ? HUD_NAMES[id - 1] : null;
    }

    // ---- protocol 47 play-state clientbound ids ----
    public static final int PACKET_KEEP_ALIVE = 0x00;
    public static final int PACKET_JOIN_GAME = 0x01;
    public static final int PACKET_RESPAWN = 0x07;
    public static final int PACKET_CUSTOM_PAYLOAD = 0x3F;
    public static final int PACKET_SET_COMPRESSION = 0x46;
    public static final int PACKET_PLAYER_LIST_HEADER_FOOTER = 0x47;

    public static byte[] fileMagic() {
        return FILE_MAGIC.clone();
    }

    /** True for the packets a NET block never stores: S00 keep-alive, S3F custom payload, S46 compression. */
    public static boolean isSkipped(int packetId) {
        return packetId == PACKET_KEEP_ALIVE || packetId == PACKET_CUSTOM_PAYLOAD || packetId == PACKET_SET_COMPRESSION;
    }

    /** {@code .json} marker kind for a TRACK MARKER code, or null for an unknown code. */
    public static String markerName(int kind) {
        switch (kind) {
            case MARKER_KEY:
                return "KEY";
            case MARKER_DEATH:
                return "DEATH";
            case MARKER_FINAL_KILL:
                return "FINAL_KILL";
            case MARKER_BED_BROKEN:
                return "BED_BROKEN";
            case MARKER_BED_LOST:
                return "BED_LOST";
            default:
                return null;
        }
    }

    /** Leading varint packet id of a stored packet, or -1 if the bytes do not start with one. */
    public static int packetId(byte[] b, int off, int len) {
        int value = 0;
        int end = off + Math.min(len, 5);
        for (int i = off, shift = 0; i < end; i++, shift += 7) {
            int x = b[i];
            value |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                return value;
            }
        }
        return -1;
    }

    // ---- writing primitives (return the offset after the written bytes) ----

    public static int varIntSize(int v) {
        if ((v & 0xFFFFFF80) == 0) {
            return 1;
        }
        if ((v & 0xFFFFC000) == 0) {
            return 2;
        }
        if ((v & 0xFFE00000) == 0) {
            return 3;
        }
        if ((v & 0xF0000000) == 0) {
            return 4;
        }
        return 5;
    }

    public static int putVarInt(byte[] b, int off, int v) {
        while ((v & 0xFFFFFF80) != 0) {
            b[off++] = (byte) (v & 0x7F | 0x80);
            v >>>= 7;
        }
        b[off++] = (byte) v;
        return off;
    }

    public static int putVarLong(byte[] b, int off, long v) {
        while ((v & 0xFFFFFFFFFFFFFF80L) != 0) {
            b[off++] = (byte) (v & 0x7F | 0x80);
            v >>>= 7;
        }
        b[off++] = (byte) v;
        return off;
    }

    public static int putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
        return off + 2;
    }

    public static int putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
        return off + 4;
    }

    public static int putLong(byte[] b, int off, long v) {
        putInt(b, off, (int) (v >>> 32));
        return putInt(b, off + 4, (int) v);
    }

    public static int putFloat(byte[] b, int off, float v) {
        return putInt(b, off, Float.floatToRawIntBits(v));
    }

    public static int putDouble(byte[] b, int off, double v) {
        return putLong(b, off, Double.doubleToRawLongBits(v));
    }

    // ---- reading primitives (caller checks bounds) ----

    public static int getU16(byte[] b, int off) {
        return (b[off] & 0xFF) << 8 | b[off + 1] & 0xFF;
    }

    public static int getInt(byte[] b, int off) {
        return (b[off] & 0xFF) << 24 | (b[off + 1] & 0xFF) << 16 | (b[off + 2] & 0xFF) << 8 | b[off + 3] & 0xFF;
    }

    public static long getLong(byte[] b, int off) {
        return (long) getInt(b, off) << 32 | getInt(b, off + 4) & 0xFFFFFFFFL;
    }

    public static float getFloat(byte[] b, int off) {
        return Float.intBitsToFloat(getInt(b, off));
    }

    public static double getDouble(byte[] b, int off) {
        return Double.longBitsToDouble(getLong(b, off));
    }

    /** A payload that breaks the format: never an index exception, always this. */
    public static final class FormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public FormatException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Bounds-checked reader over {@code [pos, end)} of a byte array. Reusable through {@link #reset} so
     * a hot caller allocates nothing; every overrun throws {@link FormatException}.
     */
    public static final class Cursor {
        private byte[] buf;
        private int pos;
        private int end;

        public Cursor(byte[] buf, int off, int end) {
            reset(buf, off, end);
        }

        public Cursor reset(byte[] buf, int off, int end) {
            if (off < 0 || end > buf.length || off > end) {
                throw new FormatException("bad range");
            }
            this.buf = buf;
            this.pos = off;
            this.end = end;
            return this;
        }

        public int pos() {
            return pos;
        }

        public int end() {
            return end;
        }

        public boolean hasMore() {
            return pos < end;
        }

        public int remaining() {
            return end - pos;
        }

        private void need(int n) {
            if (n < 0 || n > end - pos) {
                throw new FormatException("short");
            }
        }

        public void skip(int n) {
            need(n);
            pos += n;
        }

        public int u8() {
            need(1);
            return buf[pos++] & 0xFF;
        }

        public int i8() {
            need(1);
            return buf[pos++];
        }

        public int u16() {
            need(2);
            int v = getU16(buf, pos);
            pos += 2;
            return v;
        }

        public short i16() {
            return (short) u16();
        }

        public int i32() {
            need(4);
            int v = getInt(buf, pos);
            pos += 4;
            return v;
        }

        public long i64() {
            need(8);
            long v = getLong(buf, pos);
            pos += 8;
            return v;
        }

        public float f32() {
            return Float.intBitsToFloat(i32());
        }

        public double f64() {
            return Double.longBitsToDouble(i64());
        }

        /** Protocol varint (up to 5 bytes, signed int as the protocol defines it). */
        public int varInt() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int x = u8();
                value |= (x & 0x7F) << shift;
                if ((x & 0x80) == 0) {
                    return value;
                }
            }
            throw new FormatException("varint too long");
        }

        /** A varint read as an unsigned 32-bit value (record {@code dtUs}). */
        public long uvarInt() {
            return varInt() & 0xFFFFFFFFL;
        }

        /** A varint that must be a length in {@code [0, max]}. */
        public int length(int max) {
            int v = varInt();
            if (v < 0 || v > max) {
                throw new FormatException("bad length");
            }
            return v;
        }

        public long varLong() {
            long value = 0;
            for (int shift = 0; shift < 70; shift += 7) {
                int x = u8();
                value |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) {
                    return value;
                }
            }
            throw new FormatException("varlong too long");
        }

        public byte[] bytes(int n) {
            need(n);
            byte[] out = new byte[n];
            System.arraycopy(buf, pos, out, 0, n);
            pos += n;
            return out;
        }

        public String string(int maxBytes) {
            int n = length(maxBytes);
            need(n);
            String s = new String(buf, pos, n, StandardCharsets.UTF_8);
            pos += n;
            return s;
        }
    }
}
