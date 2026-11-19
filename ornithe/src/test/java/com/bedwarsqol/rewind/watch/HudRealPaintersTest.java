package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Review code-2 I2 (TASK D5): the recorded Cobblify HUD drawn by the real live painters, not a spy. In a class loader
 * where OpenGL does nothing ({@link NoGlClassLoader}), {@link HudRealPaintersDriver} runs {@link HudPlayback#draw}
 * with {@link HudPlayback#LIVE_PAINTERS} on a v2 recording's synthetic FPS, CPS, Ping, generator timer and Session
 * Stats records, with today's font set to the modern one and the recorded one Minecraft's. The font that is asked to
 * draw notes each string: the recorded values are drawn by the painters, a later record replaces an earlier one, no
 * painter fails, nothing is handed to the recorder, and the live trackers' picture is the same after as before.
 *
 * <p>What this cannot show (gate G3): where on screen, in what colour and size the text lands (OpenGL is stubbed), the
 * item icons (no item renderer here), Keystrokes, Map Info and Upgrades &amp; Traps drawing.
 */
public class HudRealPaintersTest {

    @Test
    @SuppressWarnings("unchecked")
    public void theRealPaintersDrawTheRecordedValuesAndLeaveTheLiveTrackersAsTheyWere() throws Exception {
        NoGlClassLoader loader = new NoGlClassLoader(HudRealPaintersTest.class.getClassLoader());
        Object[] r = (Object[]) Class.forName(HudRealPaintersDriver.class.getName(), true, loader)
                .getMethod("run").invoke(null);
        List<String> drawn = (List<String>) r[0];
        assertNull("a painter failed:\n" + r[4], r[4]);
        assertTrue("OpenGL was stubbed: " + loader.stubbed, loader.stubbed.contains("org.lwjgl.opengl.GL11"));
        int second = drawn.indexOf("frame 316");
        assertTrue(drawn.toString(), drawn.indexOf("frame 100") == 0 && second > 0);
        List<String> first = drawn.subList(1, second);
        List<String> later = drawn.subList(second + 1, drawn.size());
        for (String value : new String[]{"144", "7", "3", "27", "23s", "--", "Game", "Kills: 3"}) {
            assertTrue("drawn: " + value + " in " + first, containsPart(first, value));
        }
        assertTrue("the later FPS record: " + later, containsPart(later, "120") && !containsPart(later, "144"));
        assertEquals("nothing handed to the recorder", 0, r[3]);
        assertEquals("live trackers unchanged", r[1], r[2]);
    }

    /** True if some drawn string is {@code value}, or the painter split {@code value} over adjacent strings. */
    private static boolean containsPart(List<String> drawn, String value) {
        for (int i = 0; i < drawn.size(); i++) {
            StringBuilder b = new StringBuilder();
            for (int j = i; j < drawn.size() && b.length() < value.length() + 8; j++) {
                b.append(drawn.get(j));
                if (b.toString().trim().equals(value) || drawn.get(j).trim().equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }
}
