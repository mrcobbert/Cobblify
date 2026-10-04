package com.bedwarsqol.gui.sheet;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The Java rasterizer against FreeType: {@code sheet-font-ref/} holds the glyphs FreeType drew (unhinted) at the
 * sizes the sheet uses at GUI scale 2, the look the prototype was approved in. Every glyph must land in the same
 * pixel box, within 2 of 255 on any pixel.
 */
public class GlyphRasterTest {

    static {
        // ImageIO only decodes here; a forked test JVM on macOS aborts if it brings up the windowing toolkit
        System.setProperty("java.awt.headless", "true");
    }

    private static final int[][] REFS = {
            {400, 13}, {400, 15}, {400, 16}, {400, 17}, {500, 13}, {500, 17}, {500, 18}, {600, 18}, {600, 20}, {600, 22}
    };

    private static GlyphRaster.Outlines outlines(int weight) throws Exception {
        InputStream in = GlyphRasterTest.class.getResourceAsStream(
                "/assets/bedwarsqol/font/sheet/inter-" + weight + ".outlines");
        assertNotNull("outlines for " + weight, in);
        try {
            return GlyphRaster.Outlines.read(in);
        } finally {
            in.close();
        }
    }

    @Test
    public void matchesFreeTypeAtEveryReferenceSize() throws Exception {
        for (int[] ref : REFS) {
            GlyphRaster.Outlines o = outlines(ref[0]);
            String base = "/sheet-font-ref/inter-" + ref[0] + "-" + ref[1];
            InputStream png = getClass().getResourceAsStream(base + ".png");
            BufferedImage img = ImageIO.read(png);
            png.close();
            BufferedReader table = new BufferedReader(new InputStreamReader(
                    getClass().getResourceAsStream(base + ".txt"), StandardCharsets.UTF_8));
            table.readLine();
            String line;
            int glyphs = 0, worst = 0;
            long diff = 0, pixels = 0;
            while ((line = table.readLine()) != null) {
                String[] t = line.split(" ");
                int c = Integer.parseInt(t[1]), ax = Integer.parseInt(t[2]), ay = Integer.parseInt(t[3]);
                GlyphRaster.Glyph g = GlyphRaster.glyph(o, c, ref[1]);
                String at = ref[0] + "/" + ref[1] + "px char " + c;
                assertEquals(at + " width", Integer.parseInt(t[4]), g.width);
                assertEquals(at + " height", Integer.parseInt(t[5]), g.height);
                assertEquals(at + " left", Integer.parseInt(t[6]), g.left);
                assertEquals(at + " top", Integer.parseInt(t[7]), g.top);
                for (int y = 0; y < g.height; y++) {
                    for (int x = 0; x < g.width; x++) {
                        int d = Math.abs(img.getRaster().getSample(ax + x, ay + y, 0) - g.at(x, y));
                        worst = Math.max(worst, d);
                        diff += d;
                        pixels++;
                    }
                }
                glyphs++;
            }
            table.close();
            assertEquals(190, glyphs);
            assertTrue(ref[0] + "/" + ref[1] + "px: a pixel is " + worst + " off", worst <= 2);
            assertTrue(ref[0] + "/" + ref[1] + "px: mean " + diff / (double) pixels, diff <= pixels / 8);
        }
    }

    @Test
    public void rasterizesFractionalSizesBetweenTheirNeighbours() throws Exception {
        GlyphRaster.Outlines o = outlines(500);
        // ROW_LABEL at GUI scale 3, GUI Size Small: 9 su x 2.1 px
        GlyphRaster.Glyph small = GlyphRaster.glyph(o, 'H', 18f), mid = GlyphRaster.glyph(o, 'H', 18.9f),
                big = GlyphRaster.glyph(o, 'H', 20f);
        assertTrue(small.height <= mid.height && mid.height <= big.height);
        assertTrue(ink(small) < ink(mid) && ink(mid) < ink(big));
    }

    @Test
    public void atlasHoldsEveryGlyphApart() throws Exception {
        GlyphRaster.Outlines o = outlines(600);
        GlyphRaster.Atlas a = GlyphRaster.atlas(o, 23.1f, 1);
        assertEquals(Integer.bitCount(a.width), 1);
        assertEquals(Integer.bitCount(a.height), 1);
        for (int c = 0; c < 256; c++) {
            if (!o.has(c) || a.w[c] == 0) continue;
            GlyphRaster.Glyph g = GlyphRaster.glyph(o, c, 23.1f);
            assertEquals(g.left, a.left[c]);
            assertEquals(g.top, a.top[c]);
            for (int y = 0; y < g.height; y++) {
                for (int x = 0; x < g.width; x++) {
                    assertEquals(g.at(x, y), a.alpha[(a.y[c] + y) * a.width + a.x[c] + x] & 255);
                }
            }
            for (int d = 0; d < 256; d++) {
                if (d == c || !o.has(d) || a.w[d] == 0) continue;
                boolean overlap = a.x[c] < a.x[d] + a.w[d] && a.x[d] < a.x[c] + a.w[c]
                        && a.y[c] < a.y[d] + a.h[d] && a.y[d] < a.y[c] + a.h[c];
                assertTrue("chars " + c + " and " + d + " overlap", !overlap);
            }
        }
    }

    private static long ink(GlyphRaster.Glyph g) {
        long sum = 0;
        for (byte b : g.alpha) sum += b & 255;
        return sum;
    }
}
