package com.bedwarsqol.hud;

import org.junit.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The pinned icon table and the parser that checks each icon. Parser tests use synthetic bytes only. */
public class EffectIconsTest {

    @Test
    public void tableCoversPotionIdsOneToTwentyThree() {
        Set<String> names = new HashSet<String>();
        for (EffectIcons.Entry e : EffectIcons.TABLE) names.add(e.name);
        assertEquals(23, EffectIcons.TABLE.size());
        assertEquals("names are distinct", 23, names.size());
        Set<String> byId = new HashSet<String>();
        for (int id = 1; id <= 23; id++) {
            String n = EffectIcons.nameFor(id);
            assertTrue(id + " -> " + n, names.contains(n));
            byId.add(n);
        }
        assertEquals("every icon is some id's", names, byId);
        assertEquals("speed", EffectIcons.nameFor(1));
        assertEquals("wither", EffectIcons.nameFor(20));
        assertEquals("saturation", EffectIcons.nameFor(23));
    }

    @Test
    public void offsetsIncreaseInsideTheSpan() {
        long last = -1;
        for (EffectIcons.Entry e : EffectIcons.TABLE) {
            assertTrue(e.name, e.localHeaderOffset > last);
            assertTrue(e.name, e.localHeaderOffset >= EffectIcons.SPAN_FROM);
            int nameLen = (EffectIcons.PATH_PREFIX + e.name + ".png").getBytes(StandardCharsets.UTF_8).length;
            assertTrue(e.name, e.localHeaderOffset + 30 + nameLen + e.compressedSize <= EffectIcons.SPAN_TO + 1);
            assertTrue(e.name, e.compressedSize > 0);
            last = e.localHeaderOffset;
        }
        assertEquals("the span starts at the first entry", EffectIcons.SPAN_FROM, EffectIcons.TABLE.get(0).localHeaderOffset);
    }

    @Test
    public void hashesAreFortyHex() {
        for (EffectIcons.Entry e : EffectIcons.TABLE) assertTrue(e.name, e.sha1.matches("[0-9a-f]{40}"));
    }

    @Test
    public void urlIsMojangAndContentAddressed() throws Exception {
        URL u = new URL(EffectIcons.JAR_URL);
        assertEquals("https", u.getProtocol());
        assertEquals("piston-data.mojang.com", u.getHost());
        assertTrue(u.getPath(), u.getPath().matches("/v1/objects/[0-9a-f]{40}/client\\.jar"));
        assertNull(u.getQuery());
    }

    @Test
    public void spanIs12101Bytes() {
        assertEquals(12101L, EffectIcons.SPAN_TO - EffectIcons.SPAN_FROM + 1);
    }

    @Test
    public void unknownIdsHaveNoIcon() {
        assertNull(EffectIcons.nameFor(0));
        assertNull(EffectIcons.nameFor(24));
        assertNull(EffectIcons.nameFor(-1));
    }

    @Test
    public void parsesAndVerifiesEveryEntry() {
        EffectIconFixtures.Jar jar = EffectIconFixtures.jar("speed", "jump_boost", "wither");
        Map<String, byte[]> got = EffectIcons.parse(jar.span, EffectIcons.SPAN_FROM, jar.table);
        assertEquals(Arrays.asList("speed", "jump_boost", "wither"), Arrays.asList(got.keySet().toArray()));
        assertArrayEquals(EffectIconFixtures.png("jump_boost"), got.get("jump_boost"));
    }

    @Test
    public void hashMismatchDropsOnlyThatIcon() {
        EffectIconFixtures.Jar jar = EffectIconFixtures.jar("speed", "jump_boost");
        EffectIcons.Entry real = jar.table.get(1);
        List<EffectIcons.Entry> table = Arrays.asList(jar.table.get(0),
                new EffectIcons.Entry(real.name, real.localHeaderOffset, real.compressedSize,
                        "0000000000000000000000000000000000000000"));
        Map<String, byte[]> got = EffectIcons.parse(jar.span, EffectIcons.SPAN_FROM, table);
        assertEquals(new HashSet<String>(Arrays.asList("speed")), got.keySet());
    }

    @Test
    public void wrongSignatureOrNameDropsIt() {
        EffectIconFixtures.Jar jar = EffectIconFixtures.jar("speed", "jump_boost", "wither");
        byte[] span = jar.span.clone();
        int speed = (int) (jar.table.get(0).localHeaderOffset - EffectIcons.SPAN_FROM);
        span[speed] = 0; // signature
        int jump = (int) (jar.table.get(1).localHeaderOffset - EffectIcons.SPAN_FROM);
        span[jump + 30 + EffectIcons.PATH_PREFIX.length()] = 'J'; // "Jump_boost.png"
        Map<String, byte[]> got = EffectIcons.parse(span, EffectIcons.SPAN_FROM, jar.table);
        assertEquals(new HashSet<String>(Arrays.asList("wither")), got.keySet());

        // a table naming another icon at the same place is refused too
        EffectIcons.Entry w = jar.table.get(2);
        Map<String, byte[]> renamed = EffectIcons.parse(jar.span, EffectIcons.SPAN_FROM,
                Arrays.asList(new EffectIcons.Entry("poison", w.localHeaderOffset, w.compressedSize, w.sha1)));
        assertTrue(renamed.isEmpty());
    }

    @Test
    public void truncatedSpanDropsWhatDoesNotFit() {
        EffectIconFixtures.Jar jar = EffectIconFixtures.jar("speed", "jump_boost", "wither");
        int cut = (int) (jar.table.get(2).localHeaderOffset - EffectIcons.SPAN_FROM) + 40;
        byte[] span = Arrays.copyOf(jar.span, cut);
        Map<String, byte[]> got = EffectIcons.parse(span, EffectIcons.SPAN_FROM, jar.table);
        assertEquals(new HashSet<String>(Arrays.asList("speed", "jump_boost")), got.keySet());
        // an offset before the span, a null span or table: nothing, no exception
        assertTrue(EffectIcons.parse(jar.span, EffectIcons.SPAN_FROM + 1, jar.table).size() < 3);
        assertTrue(EffectIcons.parse(null, EffectIcons.SPAN_FROM, jar.table).isEmpty());
        assertTrue(EffectIcons.parse(jar.span, EffectIcons.SPAN_FROM, null).isEmpty());
        assertTrue(EffectIcons.parse(new byte[0], EffectIcons.SPAN_FROM, jar.table).isEmpty());
    }

    @Test
    public void inflatedSizeIsCapped() {
        byte[] atCap = new byte[EffectIcons.MAX_PNG];
        byte[] overCap = new byte[EffectIcons.MAX_PNG + 1];
        EffectIconFixtures.Jar jar = EffectIconFixtures.jar(new String[]{"speed", "haste"}, new byte[][]{atCap, overCap});
        Map<String, byte[]> got = EffectIcons.parse(jar.span, EffectIcons.SPAN_FROM, jar.table);
        assertTrue(got.containsKey("speed"));
        assertFalse("over the cap is refused even with a matching hash", got.containsKey("haste"));
    }
}
