package com.bedwarsqol.hud;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * FPS, CPS and Ping: their text runs, Ping's dashes, boxes that hug the text (the approved sandbox's numbers), and the
 * word after the number, before it or left out.
 */
public class TextModulesTest {

    /** Vanilla advances: digits 6, space 4, F P S C m s - 6, brackets 4; a trailing 1 px column is included. */
    private static final TextModules.Advance VANILLA = new TextModules.Advance() {
        @Override
        public float of(String s) {
            float w = 0;
            for (char c : s.toCharArray()) w += c == ' ' ? 4 : c == '[' || c == ']' ? 4 : 6;
            return w;
        }
    };

    private static List<String> described(List<TextModules.Seg> segs) {
        List<String> out = new ArrayList<String>();
        for (TextModules.Seg s : segs) out.add(s.role + ":" + s.text);
        return out;
    }

    @Test
    public void segmentsAndRoles() {
        assertEquals(Arrays.asList("NUMBER:150", "UNIT: FPS"), described(TextModules.fps(150)));
        assertEquals(Arrays.asList("NUMBER:9", "DIVIDER:", "NUMBER:3", "UNIT: CPS"), described(TextModules.cps(9, 3)));
        assertEquals(Arrays.asList("NUMBER:27", "UNIT: ms"), described(TextModules.ping("27")));
        assertEquals(Arrays.asList("NUMBER:--", "UNIT: ms"), described(TextModules.ping("--")));
    }

    @Test
    public void pingDashes() {
        assertEquals("--", TextModules.pingText(true, true, 27));
        assertEquals("--", TextModules.pingText(false, false, 27));
        assertEquals("--", TextModules.pingText(false, true, 0));
        assertEquals("--", TextModules.pingText(false, true, -1));
        assertEquals("27", TextModules.pingText(false, true, 27));
    }

    @Test
    public void boxHugsTheText() {
        assertEquals(47f, TextModules.boxWidth(TextModules.fps(150), VANILLA, 1f), 0f);
        assertEquals(48f, TextModules.boxWidth(TextModules.cps(9, 3), VANILLA, 1f), 0f);
        assertEquals(35f, TextModules.boxWidth(TextModules.ping("--"), VANILLA, 1f), 0f);
        assertEquals(18, TextModules.BOX_H);
        assertEquals(4, TextModules.HPAD);
        assertEquals(5, TextModules.TEXT_DY);
    }

    @Test
    public void bareTextWithAndWithoutBrackets() {
        assertEquals(40f, TextModules.bareWidth(TextModules.fps(150), VANILLA), 0f);
        assertEquals(48f, TextModules.bareWidth(TextModules.bracketed(TextModules.fps(150)), VANILLA), 0f);
        assertEquals(9, TextModules.BARE_H);
    }

    @Test
    public void bracketsAreWhiteRunsAroundTheText() {
        assertEquals(Arrays.asList("BRACKET:[", "NUMBER:9", "DIVIDER:", "NUMBER:3", "UNIT: CPS", "BRACKET:]"),
                described(TextModules.bracketed(TextModules.cps(9, 3))));
        // the box grows by the brackets' advance; the divider keeps its place after "[9"
        assertEquals(56f, TextModules.boxWidth(TextModules.bracketed(TextModules.cps(9, 3)), VANILLA, 1f), 0f);
        List<TextModules.Placed> p = TextModules.layout(TextModules.bracketed(TextModules.cps(9, 3)), VANILLA);
        assertEquals(0f, p.get(0).x, 0f);
        assertArrayEquals(new float[]{12, 0, 13, 8}, p.get(2).rect, 0f);
        assertEquals(45f, p.get(5).x, 0f);
        assertEquals(0xFFFFFFFF, TextModules.BRACKET);
    }

    @Test
    public void labelAfterBeforeOrOff() {
        // After is the runs as built
        assertEquals(described(TextModules.fps(150)),
                described(TextModules.labelled(TextModules.fps(150), TextModules.Label.AFTER)));
        assertEquals(Arrays.asList("UNIT:FPS ", "NUMBER:150"),
                described(TextModules.labelled(TextModules.fps(150), TextModules.Label.BEFORE)));
        assertEquals(Arrays.asList("UNIT:CPS ", "NUMBER:9", "DIVIDER:", "NUMBER:3"),
                described(TextModules.labelled(TextModules.cps(9, 3), TextModules.Label.BEFORE)));
        // "ms 27" would not read; in front it is "Ping 27"
        assertEquals(Arrays.asList("UNIT:Ping ", "NUMBER:27"),
                described(TextModules.labelled(TextModules.ping("27"), TextModules.Label.BEFORE)));
        assertEquals(Arrays.asList("NUMBER:150"),
                described(TextModules.labelled(TextModules.fps(150), TextModules.Label.OFF)));
        assertEquals(Arrays.asList("NUMBER:9", "DIVIDER:", "NUMBER:3"),
                described(TextModules.labelled(TextModules.cps(9, 3), TextModules.Label.OFF)));
        assertEquals(Arrays.asList("NUMBER:--"),
                described(TextModules.labelled(TextModules.ping("--"), TextModules.Label.OFF)));
        // brackets go around whatever is left: [FPS 150], [150]
        assertEquals(Arrays.asList("BRACKET:[", "UNIT:FPS ", "NUMBER:150", "BRACKET:]"),
                described(TextModules.bracketed(TextModules.labelled(TextModules.fps(150), TextModules.Label.BEFORE))));
        assertEquals(Arrays.asList("BRACKET:[", "NUMBER:150", "BRACKET:]"),
                described(TextModules.bracketed(TextModules.labelled(TextModules.fps(150), TextModules.Label.OFF))));
        // the same width either side; without the word the box hugs the number alone
        assertEquals(47f, TextModules.boxWidth(TextModules.labelled(TextModules.fps(150), TextModules.Label.BEFORE), VANILLA, 1f), 0f);
        assertEquals(25f, TextModules.boxWidth(TextModules.labelled(TextModules.fps(150), TextModules.Label.OFF), VANILLA, 1f), 0f);
        // CPS in front: the divider moves right by "CPS "
        List<TextModules.Placed> p = TextModules.layout(TextModules.labelled(TextModules.cps(9, 3), TextModules.Label.BEFORE), VANILLA);
        assertArrayEquals(new float[]{30, 0, 31, 8}, p.get(2).rect, 0f);
    }

    @Test
    public void labelStoredAsItsOrdinal() {
        assertEquals(TextModules.Label.AFTER, TextModules.Label.of(0));
        assertEquals(TextModules.Label.BEFORE, TextModules.Label.of(1));
        assertEquals(TextModules.Label.OFF, TextModules.Label.of(2));
        assertEquals(TextModules.Label.AFTER, TextModules.Label.of(3));
        assertEquals(TextModules.Label.AFTER, TextModules.Label.of(-1));
        assertEquals(TextModules.Label.BEFORE, TextModules.Label.named("Before"));
        assertEquals(TextModules.Label.OFF, TextModules.Label.named("Off"));
        assertNull(TextModules.Label.named("Hidden"));
    }

    @Test
    public void dividerGeometry() {
        List<TextModules.Placed> p = TextModules.layout(TextModules.cps(9, 3), VANILLA);
        assertEquals(4, p.size());
        assertEquals(0f, p.get(0).x, 0f);
        assertNull(p.get(0).rect);
        assertArrayEquals(new float[]{8, 0, 9, 8}, p.get(1).rect, 0f);
        assertArrayEquals(new float[]{9, 1, 10, 9}, p.get(1).shadow, 0f);
        assertEquals(13f, p.get(2).x, 0f);
        assertEquals(19f, p.get(3).x, 0f);
        assertEquals(41f, TextModules.segWidth(TextModules.cps(9, 3), VANILLA), 0f);
        assertEquals(0xFFAAAAAA, TextModules.DIVIDER);
        assertEquals(0xFF2A2A2A, TextModules.DIVIDER_SHADOW);
    }
}
