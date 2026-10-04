package com.bedwarsqol.gui.sheet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * One Inter weight's advances and kerning pairs, in font units, read from the {@code inter-<w>.metrics} file that
 * {@code tools/fonts/bake-sheet-font.py} writes. Widths use fractional advances plus kerning, as WebKit lays text out.
 */
public final class FontMetrics {

    public final int weight;
    public final int upm;
    public final int ascent;
    public final int descent;
    private final int[] advance = new int[256];
    private final boolean[] has = new boolean[256];
    private final Map<Integer, Integer> kern = new HashMap<Integer, Integer>();

    private FontMetrics(int weight, int upm, int ascent, int descent) {
        this.weight = weight;
        this.upm = upm;
        this.ascent = ascent;
        this.descent = descent;
    }

    public static FontMetrics read(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String[] head = r.readLine().split(" ");
        FontMetrics m = new FontMetrics(Integer.parseInt(head[1]), Integer.parseInt(head[3]),
                Integer.parseInt(head[5]), Integer.parseInt(head[7]));
        String line;
        while ((line = r.readLine()) != null) {
            String[] t = line.split(" ");
            if (t[0].equals("a")) {
                int c = Integer.parseInt(t[1]);
                m.advance[c] = Integer.parseInt(t[2]);
                m.has[c] = true;
            } else if (t[0].equals("k")) {
                m.kern.put((Integer.parseInt(t[1]) << 8) | Integer.parseInt(t[2]), Integer.parseInt(t[3]));
            }
        }
        return m;
    }

    /** The character drawn for {@code c}: itself when the font has it, else '?'. */
    public char mapped(char c) {
        return c < 256 && has[c] ? c : '?';
    }

    /** Advance of {@code c} in font units. */
    public int advance(char c) {
        return advance[mapped(c)];
    }

    /** Kerning in font units between {@code prev} and {@code c}; it moves {@code c}. {@code prev} 0 means none. */
    public int kerning(char prev, char c) {
        if (prev == 0) return 0;
        Integer k = kern.get((mapped(prev) << 8) | mapped(c));
        return k == null ? 0 : k;
    }

    /** Width of {@code s} at {@code size} (any unit; the result is in the same unit). */
    public float width(String s, float size) {
        int units = 0;
        char prev = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            units += kerning(prev, c) + advance(c);
            prev = c;
        }
        return units * size / upm;
    }
}
