package com.bedwarsqol.rewind;

import org.junit.Assume;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Format v2 extension bodies (PLAN section A "Records", Phase 1): each of the 17 tags encodes into the codec's reused
 * buffer as {@code varint len | body}, and decodes back to the values it was given. The byte layout of every tag is
 * pinned here, so a later phase cannot change it by accident.
 */
public class ExtCodecTest {

    /** The body of an encoded record: {@code buffer()[prefix, n)}, checking the length prefix on the way. */
    static byte[] body(ExtCodec c, int n) {
        RewindFormat.Cursor cur = new RewindFormat.Cursor(c.buffer(), 0, n);
        int len = cur.varInt();
        assertEquals("length prefix matches the record", n - cur.pos(), len);
        return Arrays.copyOfRange(c.buffer(), cur.pos(), n);
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    @Test
    public void everyExtensionTagIsDeclaredOnceAboveTheOldTags() {
        int[] tags = RewindFormat.EXT_TAGS;
        assertEquals(17, tags.length);
        int[] want = {0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E,
                0x2F, 0x30};
        int[] sorted = tags.clone();
        Arrays.sort(sorted);
        assertArrayEquals(want, sorted);
        assertEquals(0x20, RewindFormat.TAG_TICK_HEAD);
        assertEquals(0x21, RewindFormat.TAG_OUT);
        assertEquals(0x22, RewindFormat.TAG_DIG);
        assertEquals(0x23, RewindFormat.TAG_BLOCK);
        assertEquals(0x24, RewindFormat.TAG_SOUND);
        assertEquals(0x25, RewindFormat.TAG_VIEW);
        assertEquals(0x26, RewindFormat.TAG_MOUSE);
        assertEquals(0x27, RewindFormat.TAG_CHAT_INPUT);
        assertEquals(0x28, RewindFormat.TAG_FOV);
        assertEquals(0x29, RewindFormat.TAG_USE);
        assertEquals(0x2A, RewindFormat.TAG_INV);
        assertEquals(0x2B, RewindFormat.TAG_SETTINGS);
        assertEquals(0x2C, RewindFormat.TAG_IDENTITY);
        assertEquals(0x2D, RewindFormat.TAG_INPUT);
        assertEquals(0x2E, RewindFormat.TAG_HUD);
        assertEquals(0x2F, RewindFormat.TAG_CONT);
        assertEquals(0x30, RewindFormat.TAG_TAP);
        for (int t = 0; t < 256; t++) {
            assertEquals("tag " + t, t >= 0x20, RewindFormat.isExtensionTag(t));
        }
        for (int t : tags) {
            assertNotNull("named " + t, RewindFormat.extName(t));
        }
    }

    @Test
    public void tickHeadRoundTripsZeroOneAndSeventyThousand() {
        ExtCodec c = new ExtCodec();
        ExtCodec.TickHead d = new ExtCodec.TickHead();
        for (int handled : new int[]{0, 1, 127, 128, 70_000}) {
            int n = c.tickHead(handled);
            byte[] b = body(c, n);
            assertEquals(RewindFormat.varIntSize(handled), b.length);
            d.read(b, 0, b.length);
            assertEquals(handled, d.handled);
            assertEquals(0, d.flags);
        }
        // Layout: 70,000 = varint F0 A2 04, prefixed by its length 3.
        int n = c.tickHead(70_000);
        assertArrayEquals(bytes(3, 0xF0, 0xA2, 0x04), Arrays.copyOf(c.buffer(), n));
        // The optional flags byte only appears when a flag is set.
        n = c.tickHead(5, RewindFormat.TICK_HEAD_ORDER_UNKNOWN);
        assertArrayEquals(bytes(2, 5, RewindFormat.TICK_HEAD_ORDER_UNKNOWN), Arrays.copyOf(c.buffer(), n));
        byte[] b = body(c, n);
        d.read(b, 0, b.length);
        assertEquals(5, d.handled);
        assertEquals(RewindFormat.TICK_HEAD_ORDER_UNKNOWN, d.flags);
        assertTrue(d.orderUnknown());
    }

    @Test
    public void useRoundTripsTheFullBowCount() {
        ExtCodec c = new ExtCodec();
        ExtCodec.Use d = new ExtCodec.Use();
        for (int count : new int[]{72_000, 71_999, 0, 32}) {
            byte[] b = body(c, c.use(count));
            d.read(b, 0, b.length);
            assertEquals(count, d.count);
        }
        assertArrayEquals(bytes(0xC0, 0xB2, 0x04), body(c, c.use(72_000)));
    }

    @Test
    public void outKeepsThePacketIdAndTheVanillaBytes() {
        ExtCodec c = new ExtCodec();
        byte[] data = {9, 8, 7, 6, 5};
        byte[] b = body(c, c.out(0x0A, data, 1, 3));
        assertArrayEquals(bytes(0x0A, 8, 7, 6), b);
        ExtCodec.Out d = new ExtCodec.Out().read(b, 0, b.length);
        assertEquals(0x0A, d.packetId);
        assertArrayEquals(new byte[]{8, 7, 6}, Arrays.copyOfRange(d.buf, d.dataOff, d.dataOff + d.dataLen));
        for (int id : new int[]{0x02, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x16}) {
            assertTrue("0x" + Integer.toHexString(id), RewindFormat.isStoredOutbound(id));
        }
        for (int id : new int[]{0x00, 0x01, 0x03, 0x04, 0x05, 0x06, 0x0C, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15,
                0x17, 0x18, 0x19}) {
            assertFalse("0x" + Integer.toHexString(id), RewindFormat.isStoredOutbound(id));
        }
        // An empty packet body (C16 respawn carries only its enum, C0D only the window id) is fine.
        b = body(c, c.out(0x16, data, 0, 0));
        assertArrayEquals(bytes(0x16), b);
    }

    @Test
    public void digAndBlockRoundTrip() {
        ExtCodec c = new ExtCodec();
        long pos = 0x123456789ABCDEF0L;
        byte[] b = body(c, c.dig(RewindFormat.DIG_HITTING, pos, 3, 9));
        assertEquals(1 + 8 + 1 + 1, b.length);
        ExtCodec.Dig d = new ExtCodec.Dig().read(b, 0, b.length);
        assertTrue(d.hitting());
        assertEquals(pos, d.pos);
        assertEquals(3, d.face);
        assertEquals(9, d.stage);
        b = body(c, c.dig(0, -1L, 0, -1));
        d.read(b, 0, b.length);
        assertFalse(d.hitting());
        assertEquals(-1L, d.pos);
        assertEquals(-1, d.stage);
        assertEquals(0xFF, b[b.length - 1] & 0xFF); // i8 -1

        b = body(c, c.block(pos, 0, 35 << 4 | 14, RewindFormat.BLOCK_CAUSE_OWN_BREAK));
        ExtCodec.Block k = new ExtCodec.Block().read(b, 0, b.length);
        assertEquals(pos, k.pos);
        assertEquals(0, k.oldState);
        assertEquals(35 << 4 | 14, k.newState);
        assertEquals(RewindFormat.BLOCK_CAUSE_OWN_BREAK, k.cause);
        assertEquals(8 + 1 + 2 + 1, b.length);
    }

    @Test
    public void soundRoundTripsFromAStringOrPreEncodedNameBytes() {
        ExtCodec c = new ExtCodec();
        byte[] b = body(c, c.sound("step.stone", 1.5f, 64f, -2.25f, 0.15f, 1f));
        ExtCodec.Sound d = new ExtCodec.Sound().read(b, 0, b.length);
        assertEquals("step.stone", d.name);
        assertEquals(1.5f, d.x, 0f);
        assertEquals(64f, d.y, 0f);
        assertEquals(-2.25f, d.z, 0f);
        assertEquals(0.15f, d.volume, 0f);
        assertEquals(1f, d.pitch, 0f);
        assertEquals(1 + 10 + 5 * 4, b.length);
        byte[] name = "dig.wood".getBytes(StandardCharsets.UTF_8);
        byte[] b2 = body(c, c.sound(name, 0, name.length, 1.5f, 64f, -2.25f, 0.15f, 1f));
        d.read(b2, 0, b2.length);
        assertEquals("dig.wood", d.name);
    }

    @Test
    public void viewMouseChatInputFovInputAndTapRoundTrip() {
        ExtCodec c = new ExtCodec();
        int flags = RewindFormat.VIEW_HIDE_GUI | RewindFormat.VIEW_TAB;
        byte[] b = body(c, c.view(2, flags, RewindFormat.SCREEN_CHEST));
        assertArrayEquals(bytes(2, flags, RewindFormat.SCREEN_CHEST), b);
        ExtCodec.View v = new ExtCodec.View().read(b, 0, b.length);
        assertEquals(2, v.perspective);
        assertEquals(flags, v.flags);
        assertEquals(RewindFormat.SCREEN_CHEST, v.screenKind);

        b = body(c, c.mouse(-1234, 567));
        ExtCodec.Mouse m = new ExtCodec.Mouse().read(b, 0, b.length);
        assertEquals(-1234, m.dx);
        assertEquals(567, m.dy);
        assertEquals(4, b.length);
        b = body(c, c.mouse(100_000, -100_000)); // clamped to i16
        m.read(b, 0, b.length);
        assertEquals(Short.MAX_VALUE, m.dx);
        assertEquals(Short.MIN_VALUE, m.dy);

        String typed = "gg é中 😀"; // ASCII, 2-byte, 3-byte and a surrogate pair
        b = body(c, c.chatInput(new StringBuilder(typed), 4));
        ExtCodec.ChatInput ci = new ExtCodec.ChatInput().read(b, 0, b.length);
        assertEquals(typed, ci.text);
        assertEquals(4, ci.cursor);
        byte[] utf8 = typed.getBytes(StandardCharsets.UTF_8);
        assertEquals(RewindFormat.varIntSize(utf8.length) + utf8.length + 1, b.length);
        assertArrayEquals(utf8, Arrays.copyOfRange(b, 1, 1 + utf8.length));
        // A lone surrogate is written as the JDK writes it ('?'), so encoding never allocates to find out.
        String lone = "a\ud83db";
        b = body(c, c.chatInput(lone, 0));
        ci.read(b, 0, b.length);
        assertEquals(new String(lone.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8), ci.text);

        b = body(c, c.fov(85.75f));
        assertEquals(85.75f, new ExtCodec.Fov().read(b, 0, b.length).degrees, 0f);
        assertEquals(4, b.length);

        int keys = RewindFormat.INPUT_W | RewindFormat.INPUT_JUMP | RewindFormat.INPUT_LMB;
        b = body(c, c.input(keys));
        assertArrayEquals(bytes(keys), b);
        assertEquals(keys, new ExtCodec.Input().read(b, 0, b.length).keys);

        b = body(c, c.tap(RewindFormat.TAP_RMB));
        assertArrayEquals(bytes(6), b);
        assertEquals(RewindFormat.TAP_RMB, new ExtCodec.Tap().read(b, 0, b.length).button);
        assertEquals(5, RewindFormat.TAP_LMB);
    }

    @Test
    public void invAndContCarryProtocolSlotBytes() {
        ExtCodec c = new ExtCodec();
        byte[] slot = RecordingFormatTest.itemSlotWithNbt();
        byte[] b = body(c, c.inv(RewindFormat.INV_CURSOR, slot, 0, slot.length));
        ExtCodec.Inv d = new ExtCodec.Inv().read(b, 0, b.length);
        assertEquals(40, d.slot);
        assertArrayEquals(slot, Arrays.copyOfRange(d.buf, d.slotOff, d.slotOff + d.slotLen));
        byte[] empty = {(byte) 0xFF, (byte) 0xFF};
        b = body(c, c.inv(0, empty, 0, 2));
        d.read(b, 0, b.length);
        assertEquals(0, d.slot);
        assertEquals(2, d.slotLen);
        assertEquals(41, RewindFormat.INV_SLOTS);
        assertEquals(36, RewindFormat.INV_ARMOR_FIRST);

        b = body(c, c.cont(3, 53, slot, 0, slot.length));
        ExtCodec.Cont k = new ExtCodec.Cont().read(b, 0, b.length);
        assertEquals(3, k.windowId);
        assertEquals(53, k.slot);
        assertArrayEquals(slot, Arrays.copyOfRange(k.buf, k.slotOff, k.slotOff + k.slotLen));

        // A slot past 40, or slot bytes that are not a protocol-47 stack, is a malformed body.
        byte[] bad = body(c, c.inv(0, slot, 0, slot.length - 1));
        try {
            d.read(bad, 0, bad.length);
            fail("cut NBT accepted");
        } catch (RewindFormat.FormatException expected) {
            // the walker would size it; the decoder rejects it
        }
        byte[] wrongSlot = body(c, c.inv(0, empty, 0, 2));
        wrongSlot[0] = 41;
        try {
            d.read(wrongSlot, 0, wrongSlot.length);
            fail("slot 41 accepted");
        } catch (RewindFormat.FormatException expected) {
            // 0-35 main, 36-39 armour, 40 cursor
        }
    }

    @Test
    public void settingsRoundTripEveryTypeAndSkipAnUnknownKeyByItsType() {
        ExtCodec c = new ExtCodec();
        c.beginSettings();
        c.settingFloat(RewindFormat.SETTING_FOV, 90f);
        c.settingInt(RewindFormat.SETTING_GUI_SCALE, 3);
        c.settingInt(RewindFormat.SETTING_VIEW_BOBBING, 1);
        c.settingString(RewindFormat.SETTING_GUI_FONT, "modern");
        c.settingInt(RewindFormat.settingHudKey(RewindFormat.HUD_FPS, RewindFormat.HUD_FIELD_X), -5);
        c.settingFloat(RewindFormat.settingHudKey(RewindFormat.HUD_ITEM_TAB_LIST, RewindFormat.HUD_FIELD_SCALE),
                0.75f);
        c.settingInt(0xFF, 7); // a key a later build numbered: read and passed on, unknown to this one
        byte[] b = body(c, c.endSettings());
        assertEquals(7, b[0]);
        ExtCodec.Settings d = new ExtCodec.Settings().read(b, 0, b.length);
        assertEquals(7, d.count);
        assertEquals(RewindFormat.SETTING_FOV, d.keys[0]);
        assertEquals(RewindFormat.SETTING_TYPE_F32, d.types[0]);
        assertEquals(90f, d.floats[0], 0f);
        assertEquals(3, d.ints[1]);
        assertEquals(1, d.ints[2]);
        assertEquals(RewindFormat.SETTING_TYPE_STRING, d.types[3]);
        assertEquals("modern", d.strings[3]);
        assertEquals(-5, d.ints[4]);
        assertEquals(0.75f, d.floats[5], 0f);
        assertEquals(0xFF, d.keys[6]);
        assertEquals(-1, RewindFormat.settingType(0xFF));
        assertEquals(7, d.ints[6]);
        assertEquals(-1, d.indexOf(0x3E));
        assertEquals(3, d.indexOf(RewindFormat.SETTING_GUI_FONT));

        // An empty snapshot is a count of zero.
        c.beginSettings();
        assertArrayEquals(bytes(0), body(c, c.endSettings()));
        // A type this build does not know cannot be skipped: malformed.
        byte[] unknownType = bytes(1, 5, 9, 0);
        try {
            d.read(unknownType, 0, unknownType.length);
            fail("unknown type accepted");
        } catch (RewindFormat.FormatException expected) {
            // the record is skipped and counted by the loader
        }
    }

    @Test
    public void everySettingKeyHasOneTypeAndTheHudKeysFitAByte() {
        int[] fixed = {RewindFormat.SETTING_FOV, RewindFormat.SETTING_GUI_SCALE, RewindFormat.SETTING_VIEW_BOBBING,
                RewindFormat.SETTING_SCALED_WIDTH, RewindFormat.SETTING_SCALED_HEIGHT,
                RewindFormat.SETTING_DISPLAY_WIDTH, RewindFormat.SETTING_DISPLAY_HEIGHT,
                RewindFormat.SETTING_PARTICLES_VANILLA, RewindFormat.SETTING_GAMMA, RewindFormat.SETTING_GUI_FONT,
                RewindFormat.SETTING_GUI_ACCENT, RewindFormat.SETTING_HUD_TEXT_SIZE,
                RewindFormat.SETTING_OLD_ANIMATIONS, RewindFormat.SETTING_OA_BLOCK_HIT,
                RewindFormat.SETTING_OA_ITEM_POSITION, RewindFormat.SETTING_OA_SMOOTH_SNEAK,
                RewindFormat.SETTING_OA_HEARTS, RewindFormat.SETTING_OA_RED_ARMOR,
                RewindFormat.SETTING_HAND_POSITION, RewindFormat.SETTING_HAND_POS_X, RewindFormat.SETTING_HAND_POS_Y,
                RewindFormat.SETTING_HAND_POS_Z, RewindFormat.SETTING_HAND_SCALE, RewindFormat.SETTING_NO_HURT_CAM,
                RewindFormat.SETTING_DYNAMIC_FOV, RewindFormat.SETTING_ZOOM, RewindFormat.SETTING_FULLBRIGHT,
                RewindFormat.SETTING_MOTION_BLUR, RewindFormat.SETTING_MOTION_BLUR_STRENGTH,
                RewindFormat.SETTING_ITEM_PHYSICS, RewindFormat.SETTING_PARTICLES,
                RewindFormat.SETTING_PT_ALWAYS_CRIT, RewindFormat.SETTING_PT_HIDE_OWN_POTION,
                RewindFormat.SETTING_PT_HIDE_BLOCK_BREAK, RewindFormat.SETTING_PT_MULTIPLIER};
        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        for (int k : fixed) {
            assertTrue("key " + k + " below the HUD keys", k > 0 && k < RewindFormat.SETTING_HUD_BASE);
            assertTrue("unique " + k, seen.add(k));
            assertTrue("typed " + k, RewindFormat.settingType(k) >= 0);
        }
        assertEquals(RewindFormat.SETTING_TYPE_F32, RewindFormat.settingType(RewindFormat.SETTING_FOV));
        assertEquals(RewindFormat.SETTING_TYPE_F32, RewindFormat.settingType(RewindFormat.SETTING_GAMMA));
        assertEquals(RewindFormat.SETTING_TYPE_STRING, RewindFormat.settingType(RewindFormat.SETTING_GUI_ACCENT));
        assertEquals(RewindFormat.SETTING_TYPE_VARINT, RewindFormat.settingType(RewindFormat.SETTING_GUI_SCALE));
        for (int item = RewindFormat.HUD_POTION; item <= RewindFormat.HUD_ITEM_TAB_LIST; item++) {
            for (int field = 0; field < RewindFormat.HUD_FIELDS; field++) {
                int k = RewindFormat.settingHudKey(item, field);
                assertTrue(k + " fits a u8", k >= RewindFormat.SETTING_HUD_BASE && k <= 0xFF);
                assertTrue("unique " + k, seen.add(k));
                boolean f32 = field == RewindFormat.HUD_FIELD_SCALE || field == RewindFormat.HUD_FIELD_SUB_X
                        || field == RewindFormat.HUD_FIELD_SUB_Y;
                assertEquals(f32 ? RewindFormat.SETTING_TYPE_F32 : RewindFormat.SETTING_TYPE_VARINT,
                        RewindFormat.settingType(k));
                assertEquals(item, RewindFormat.settingHudItem(k));
                assertEquals(field, RewindFormat.settingHudField(k));
            }
        }
        assertEquals(-1, RewindFormat.settingHudItem(RewindFormat.SETTING_FOV));
    }

    @Test
    public void hudModuleIdsFollowTheModuleList() {
        String[] names = {"potion", "armor", "inventory", "diamondtimer", "emeraldtimer", "keystrokes",
                "sessionstats", "heightlimit", "teamupgrades", "fps", "cps", "ping", "scoreboard", "chat", "tablist"};
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], i + 1, RewindFormat.hudModuleId(names[i]));
            assertEquals(names[i], RewindFormat.hudModuleName(i + 1));
        }
        assertEquals(1, RewindFormat.HUD_POTION);
        assertEquals(12, RewindFormat.HUD_PING);
        assertEquals(15, RewindFormat.HUD_ITEM_TAB_LIST);
        assertEquals(0, RewindFormat.hudModuleId("nope"));
        assertEquals(null, RewindFormat.hudModuleName(0));
    }

    @Test
    public void identityAndHudRoundTrip() {
        ExtCodec c = new ExtCodec(16); // small on purpose: the skin property grows the buffer
        StringBuilder skin = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            skin.append((char) ('A' + i % 26));
        }
        byte[] b = body(c, c.identity(0x0123456789ABCDEFL, -2L, "FakeName", skin, ""));
        ExtCodec.Identity d = new ExtCodec.Identity().read(b, 0, b.length);
        assertEquals(0x0123456789ABCDEFL, d.uuidMost);
        assertEquals(-2L, d.uuidLeast);
        assertEquals("FakeName", d.name);
        assertEquals(skin.toString(), d.skinValue);
        assertEquals("", d.skinSignature);

        c.beginHud(RewindFormat.HUD_CPS, RewindFormat.HUD_FLAG_ON_SCREEN).putVarInt(7).putVarInt(12);
        b = body(c, c.end());
        assertArrayEquals(bytes(RewindFormat.HUD_CPS, 1, 7, 12), b);
        ExtCodec.Hud h = new ExtCodec.Hud().read(b, 0, b.length);
        assertEquals(RewindFormat.HUD_CPS, h.module);
        assertTrue(h.onScreen());
        assertEquals(2, h.valuesLen);
        RewindFormat.Cursor v = h.values();
        assertEquals(7, v.varInt());
        assertEquals(12, v.varInt());
        assertFalse(v.hasMore());

        c.beginHud(RewindFormat.HUD_HEIGHT_LIMIT, 0).putVarInt(1).putString("FakeMapName").putU8(2);
        b = body(c, c.end());
        h.read(b, 0, b.length);
        assertFalse(h.onScreen());
        v = h.values();
        assertEquals(1, v.varInt());
        assertEquals("FakeMapName", v.string(RewindFormat.MAX_STRING_BYTES));
        assertEquals(2, v.u8());
    }

    @Test
    public void aCutBodyThrowsTheFormatExceptionNeverAnIndexError() {
        ExtCodec c = new ExtCodec();
        byte[][] records = {
                body(c, c.dig(1, 5L, 2, 3)), body(c, c.block(5L, 1, 2, 0)), body(c, c.sound("a.b", 1, 2, 3, 4, 5)),
                body(c, c.view(1, 0, 0)), body(c, c.mouse(1, 2)), body(c, c.chatInput("hey", 1)),
                body(c, c.fov(70f)), body(c, c.identity(1, 2, "n", "v", "s")),
                body(c, c.inv(3, RecordingFormatTest.itemSlotWithNbt(), 0, RecordingFormatTest.itemSlotWithNbt().length)),
                body(c, c.cont(1, 2, new byte[]{(byte) 0xFF, (byte) 0xFF}, 0, 2)),
        };
        ExtCodec.Body[] decoders = {new ExtCodec.Dig(), new ExtCodec.Block(), new ExtCodec.Sound(),
                new ExtCodec.View(), new ExtCodec.Mouse(), new ExtCodec.ChatInput(), new ExtCodec.Fov(),
                new ExtCodec.Identity(), new ExtCodec.Inv(), new ExtCodec.Cont()};
        for (int r = 0; r < records.length; r++) {
            decoders[r].read(records[r], 0, records[r].length);
            for (int cut = 0; cut < records[r].length; cut++) {
                try {
                    decoders[r].read(records[r], 0, cut);
                    fail(decoders[r].getClass().getSimpleName() + " accepted a cut at " + cut);
                } catch (RewindFormat.FormatException expected) {
                    // never an IndexOutOfBounds
                }
            }
        }
        // Trailing bytes a later build appended to a body are ignored, so a body may grow without a new tag.
        byte[] grown = Arrays.copyOf(records[3], records[3].length + 2);
        new ExtCodec.View().read(grown, 0, grown.length);
    }

    @Test
    public void encodingIntoTheReusedBufferAllocatesNothing() {
        java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue(base instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) base;
        long self = Thread.currentThread().getId();
        ExtCodec c = new ExtCodec();
        byte[] name = "step.stone".getBytes(StandardCharsets.UTF_8);
        byte[] slot = RecordingFormatTest.itemSlotWithNbt();
        StringBuilder typed = new StringBuilder("hello there");
        long sum = 0;
        for (int round = 0; round < 2; round++) { // round 0 warms up and grows the buffer once
            long before = mx.getThreadAllocatedBytes(self);
            for (int i = 0; i < 100_000; i++) {
                sum += c.tickHead(i & 1023);
                sum += c.dig(i & 1, i, 2, (i % 11) - 1);
                sum += c.block(i, i & 15, 7, 0);
                sum += c.sound(name, 0, name.length, i, 64, 0, 1f, 1f);
                sum += c.view(i % 3, 0, 1);
                sum += c.mouse(i & 255, -(i & 255));
                sum += c.chatInput(typed, i & 7);
                sum += c.fov(70f + (i & 7));
                sum += c.use(72_000 - (i & 63));
                sum += c.inv(i % 41, slot, 0, slot.length);
                sum += c.cont(1, i % 54, slot, 0, slot.length);
                c.beginSettings();
                c.settingInt(RewindFormat.SETTING_GUI_SCALE, i & 3);
                c.settingFloat(RewindFormat.SETTING_FOV, 70f);
                c.settingString(RewindFormat.SETTING_GUI_ACCENT, "pale-blue");
                sum += c.endSettings();
                sum += c.input(i & 127);
                sum += c.tap(5 + (i & 1));
                sum += c.beginHud(RewindFormat.HUD_FPS, 1).putVarInt(i).end();
            }
            long spent = mx.getThreadAllocatedBytes(self) - before;
            if (round == 1) {
                assertTrue("encoding allocated " + spent + " B over 100k rounds", spent < 1024);
            }
        }
        assertTrue(sum > 0);
    }

    /**
     * Review code-2 O1 (corrupt files): a SETTINGS float that is NaN or infinite is read past but not kept, so no
     * later reader sees it (a NaN HUD scale froze the render thread in {@code HudLayout.fit}); the other entries of
     * the record stay. A SOUND or FOV with a non-finite value, or an FOV outside (0, 180) degrees, is malformed.
     */
    @Test
    public void nonFiniteFloatsAreRejectedAtDecode() {
        ExtCodec c = new ExtCodec();
        int scale = RewindFormat.settingHudKey(RewindFormat.HUD_TEAM_UPGRADES, RewindFormat.HUD_FIELD_SCALE);
        int subX = RewindFormat.settingHudKey(RewindFormat.HUD_FPS, RewindFormat.HUD_FIELD_SUB_X);
        c.beginSettings();
        c.settingFloat(scale, Float.NaN);
        c.settingInt(RewindFormat.SETTING_GUI_SCALE, 2);
        c.settingFloat(RewindFormat.SETTING_FOV, Float.POSITIVE_INFINITY);
        c.settingFloat(subX, 0.25f);
        c.settingFloat(RewindFormat.settingHudKey(RewindFormat.HUD_PING, RewindFormat.HUD_FIELD_SCALE),
                Float.NEGATIVE_INFINITY);
        byte[] b = body(c, c.endSettings());
        ExtCodec.Settings d = new ExtCodec.Settings().read(b, 0, b.length);
        assertEquals("the two finite entries are kept", 2, d.count);
        assertEquals(-1, d.indexOf(scale));
        assertEquals(-1, d.indexOf(RewindFormat.SETTING_FOV));
        assertEquals(2, d.ints[d.indexOf(RewindFormat.SETTING_GUI_SCALE)]);
        assertEquals(0.25f, d.floats[d.indexOf(subX)], 0f);

        for (float bad : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            for (int field = 0; field < 5; field++) {
                float[] v = {1f, 64f, 1f, 1f, 1f};
                v[field] = bad;
                byte[] sound = body(c, c.sound("random.click", v[0], v[1], v[2], v[3], v[4]));
                try {
                    new ExtCodec.Sound().read(sound, 0, sound.length);
                    fail("sound field " + field + " = " + bad + " accepted");
                } catch (RewindFormat.FormatException expected) {
                    // skipped and counted by the loader
                }
            }
        }
        for (float bad : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 0f, -5f, 180f, 1e9f}) {
            byte[] fov = body(c, c.fov(bad));
            try {
                new ExtCodec.Fov().read(fov, 0, fov.length);
                fail("fov " + bad + " accepted");
            } catch (RewindFormat.FormatException expected) {
                // no recorded FOV at that time
            }
        }
        byte[] zoom = body(c, c.fov(10.5f));
        assertEquals("a zoomed FOV is fine", 10.5f, new ExtCodec.Fov().read(zoom, 0, zoom.length).degrees, 0f);
    }
}
