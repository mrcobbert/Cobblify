package com.bedwarsqol.hud;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** FPS, CPS and Ping: their text runs, Ping's dashes, and boxes that hug the text (the approved sandbox's numbers). */
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
    public void bareTextInBrackets() {
        assertEquals(48f, TextModules.bareWidth(TextModules.fps(150), VANILLA), 0f);
        assertEquals(9, TextModules.BARE_H);
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
