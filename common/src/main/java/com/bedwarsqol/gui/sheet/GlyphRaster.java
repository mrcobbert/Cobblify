package com.bedwarsqol.gui.sheet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Rasterizes the sheet's Inter glyphs at any pixel size, the way FreeType 2.13 draws them unhinted (the renderer the
 * approved look was baked with), so the sheet is sharp at every GUI scale and GUI Size, not only at the sizes that
 * were baked. It follows FreeType step by step: the size and every point scaled to 26.6 fixed point, the pixel box
 * from the control box, contours decomposed as {@code FT_Outline_Decompose} does, conics split into the number of
 * pieces the smooth rasterizer uses, then exact area coverage under the non-zero rule, quantized as FreeType does.
 * The contours come from {@code inter-<w>.outlines}, which {@code tools/fonts/bake-sheet-font.py} writes.
 */
public final class GlyphRaster {

    private GlyphRaster() {
    }

    // ------------------------------------------------------------------ outlines

    /**
     * One weight's TrueType contours in font units: per character, per contour, the offset of the component it comes
     * from, its points and their on-curve flags.
     */
    public static final class Outlines {
        public final int weight;
        public final int upm;
        final int[][] ox = new int[256][], oy = new int[256][];
        final int[][][] xs = new int[256][][];
        final int[][][] ys = new int[256][][];
        final boolean[][][] on = new boolean[256][][];

        private Outlines(int weight, int upm) {
            this.weight = weight;
            this.upm = upm;
        }

        public boolean has(int c) {
            return c >= 0 && c < 256 && xs[c] != null;
        }

        public static Outlines read(InputStream in) throws IOException {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String[] head = r.readLine().split(" ");
            Outlines o = new Outlines(Integer.parseInt(head[1]), Integer.parseInt(head[3]));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("g ")) continue;
                String[] g = line.split(" ");
                int c = Integer.parseInt(g[1]), n = Integer.parseInt(g[2]);
                o.ox[c] = new int[n];
                o.oy[c] = new int[n];
                o.xs[c] = new int[n][];
                o.ys[c] = new int[n][];
                o.on[c] = new boolean[n][];
                for (int k = 0; k < n; k++) {
                    String[] t = r.readLine().split(" ");
                    o.ox[c][k] = Integer.parseInt(t[0]);
                    o.oy[c][k] = Integer.parseInt(t[1]);
                    int points = (t.length - 2) / 3;
                    int[] x = new int[points], y = new int[points];
                    boolean[] f = new boolean[points];
                    for (int i = 0; i < points; i++) {
                        x[i] = Integer.parseInt(t[2 + 3 * i]);
                        y[i] = Integer.parseInt(t[2 + 3 * i + 1]);
                        f[i] = t[2 + 3 * i + 2].equals("1");
                    }
                    o.xs[c][k] = x;
                    o.ys[c][k] = y;
                    o.on[c][k] = f;
                }
            }
            return o;
        }
    }

    // ------------------------------------------------------------------ one glyph

    /** A glyph's coverage (0..255, rows from the top) and where it sits: FreeType's bitmap_left and bitmap_top. */
    public static final class Glyph {
        public final int width, height, left, top;
        public final byte[] alpha;

        Glyph(int width, int height, int left, int top, byte[] alpha) {
            this.width = width;
            this.height = height;
            this.left = left;
            this.top = top;
            this.alpha = alpha;
        }

        public int at(int x, int y) {
            return alpha[y * width + x] & 255;
        }
    }

    /** Subpixel bits of FreeType's smooth rasterizer: 256 steps per pixel. */
    private static final int PIXEL_BITS = 8;
    private static final long ONE_PIXEL = 1L << PIXEL_BITS;

    /** Character {@code c} at {@code px} pixels per em. */
    public static Glyph glyph(Outlines o, int c, float px) {
        if (!o.has(c) || o.xs[c].length == 0) return new Glyph(0, 0, 0, 0, new byte[0]);
        long scale = divFix(Math.round(px * 64.0), o.upm);
        int n = o.xs[c].length;
        long[][] x = new long[n][], y = new long[n][];
        long xMin = Long.MAX_VALUE, yMin = Long.MAX_VALUE, xMax = Long.MIN_VALUE, yMax = Long.MIN_VALUE;
        for (int k = 0; k < n; k++) {
            int points = o.xs[c][k].length;
            x[k] = new long[points];
            y[k] = new long[points];
            // as TT_Process_Composite_Component: the component's points scaled, then its scaled offset added
            long dx = mulFix(o.ox[c][k], scale), dy = mulFix(o.oy[c][k], scale);
            for (int i = 0; i < points; i++) {
                x[k][i] = mulFix(o.xs[c][k][i], scale) + dx;
                y[k][i] = mulFix(o.ys[c][k][i], scale) + dy;
                xMin = Math.min(xMin, x[k][i]);
                xMax = Math.max(xMax, x[k][i]);
                yMin = Math.min(yMin, y[k][i]);
                yMax = Math.max(yMax, y[k][i]);
            }
        }
        // ft_glyphslot_preset_bitmap, normal mode: the control box floored and ceiled to whole pixels
        int left = (int) (xMin >> 6), bottom = (int) (yMin >> 6);
        int right = (int) ((xMax + 63) >> 6), top = (int) ((yMax + 63) >> 6);
        int w = right - left, h = top - bottom;
        if (w <= 0 || h <= 0) return new Glyph(0, 0, left, top, new byte[0]);
        Accumulator acc = new Accumulator(w, h);
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < x[k].length; i++) {
                x[k][i] -= left * 64L;
                y[k][i] -= bottom * 64L;
            }
            decompose(x[k], y[k], o.on[c][k], acc);
        }
        return new Glyph(w, h, left, top, acc.coverage());
    }

    /** {@code FT_DivFix}: a / b in 16.16, rounded. */
    static long divFix(long a, long b) {
        long s = 1;
        if (a < 0) {
            a = -a;
            s = -s;
        }
        if (b < 0) {
            b = -b;
            s = -s;
        }
        long q = b == 0 ? 0x7FFFFFFFL : ((a << 16) + (b >> 1)) / b;
        return s < 0 ? -q : q;
    }

    /** {@code FT_MulFix}: a * b / 65536, rounded half away from zero. */
    static long mulFix(long a, long b) {
        long s = 1;
        if (a < 0) {
            a = -a;
            s = -s;
        }
        if (b < 0) {
            b = -b;
            s = -s;
        }
        long c = (a * b + 0x8000L) >> 16;
        return s < 0 ? -c : c;
    }

    /** {@code FT_Outline_Decompose} for one TrueType contour (points in 26.6), into the rasterizer. */
    private static void decompose(long[] x, long[] y, boolean[] on, Accumulator acc) {
        int last = x.length - 1;
        int limit = last;
        long sx = x[0], sy = y[0];
        int point = 0;
        if (!on[0]) {
            // the first point is a conic control: start at the last point if it is on the curve, else midway
            if (on[last]) {
                sx = x[last];
                sy = y[last];
                limit--;
            } else {
                sx = (sx + x[last]) / 2;
                sy = (sy + y[last]) / 2;
            }
            point = -1;
        }
        acc.moveTo(sx, sy);
        while (point < limit) {
            point++;
            if (on[point]) {
                acc.lineTo(x[point], y[point]);
                continue;
            }
            long cx = x[point], cy = y[point];
            boolean closed = false;
            while (true) {
                if (point < limit) {
                    point++;
                    if (on[point]) {
                        acc.conicTo(cx, cy, x[point], y[point]);
                        break;
                    }
                    acc.conicTo(cx, cy, (cx + x[point]) / 2, (cy + y[point]) / 2);
                    cx = x[point];
                    cy = y[point];
                } else {
                    acc.conicTo(cx, cy, sx, sy);
                    closed = true;
                    break;
                }
            }
            if (closed) return;
        }
        acc.lineTo(sx, sy);
    }

    /**
     * Exact signed area per pixel (the font-rs accumulation), fed lines in FreeType's subpixel space; the running sum
     * of a row is the coverage, under the non-zero rule.
     */
    private static final class Accumulator {
        final int w, h, stride;
        final double[] a;
        long px, py; // the pen in subpixels (1/256 px), y up from the bitmap's bottom

        Accumulator(int w, int h) {
            this.w = w;
            this.h = h;
            this.stride = w + 2;
            this.a = new double[stride * h + 2];
        }

        void moveTo(long x, long y) {
            px = x << (PIXEL_BITS - 6);
            py = y << (PIXEL_BITS - 6);
        }

        void lineTo(long x, long y) {
            long tx = x << (PIXEL_BITS - 6), ty = y << (PIXEL_BITS - 6);
            line(px, py, tx, ty);
            px = tx;
            py = ty;
        }

        /** {@code gray_render_conic}: 2^shift equal steps by forward differencing, in 32.32 fixed point. */
        void conicTo(long cx, long cy, long x, long y) {
            long p0x = px, p0y = py;
            long p1x = cx << (PIXEL_BITS - 6), p1y = cy << (PIXEL_BITS - 6);
            long p2x = x << (PIXEL_BITS - 6), p2y = y << (PIXEL_BITS - 6);
            long bx = p1x - p0x, by = p1y - p0y;
            long ax = p2x - p1x - bx, ay = p2y - p1y - by;
            long d = Math.max(Math.abs(ax), Math.abs(ay));
            if (d <= ONE_PIXEL / 4) {
                line(px, py, p2x, p2y);
                px = p2x;
                py = p2y;
                return;
            }
            int shift = 0;
            do {
                d >>= 2;
                shift++;
            } while (d > ONE_PIXEL / 4);
            long rx = ax * (1L << (33 - 2 * shift)), ry = ay * (1L << (33 - 2 * shift));
            long qx = bx * (1L << (33 - shift)) + ax * (1L << (32 - 2 * shift));
            long qy = by * (1L << (33 - shift)) + ay * (1L << (32 - 2 * shift));
            long fx = p0x * (1L << 32), fy = p0y * (1L << 32);
            for (long count = 1L << shift; count > 0; count--) {
                fx += qx;
                fy += qy;
                qx += rx;
                qy += ry;
                long nx = fx >> 32, ny = fy >> 32;
                line(px, py, nx, ny);
                px = nx;
                py = ny;
            }
        }

        /** A line in subpixels; rows are counted from the top, so y flips here. */
        private void line(long sx0, long sy0, long sx1, long sy1) {
            double x0 = sx0 / (double) ONE_PIXEL, y0 = h - sy0 / (double) ONE_PIXEL;
            double x1 = sx1 / (double) ONE_PIXEL, y1 = h - sy1 / (double) ONE_PIXEL;
            if (y0 == y1) return;
            double dir;
            if (y0 < y1) {
                dir = 1.0;
            } else {
                dir = -1.0;
                double t = x0;
                x0 = x1;
                x1 = t;
                t = y0;
                y0 = y1;
                y1 = t;
            }
            double dxdy = (x1 - x0) / (y1 - y0);
            double x = x0;
            int yStart = Math.max(0, (int) Math.floor(y0));
            int yEnd = Math.min(h, (int) Math.ceil(y1));
            if (y0 < 0) x -= y0 * dxdy;
            for (int row = yStart; row < yEnd; row++) {
                int base = row * stride;
                double dy = Math.min(row + 1.0, y1) - Math.max(row, y0);
                double xNext = x + dxdy * dy;
                double d = dy * dir;
                double lo = Math.min(x, xNext), hi = Math.max(x, xNext);
                double loFloor = Math.floor(lo);
                int loI = (int) loFloor;
                double hiCeil = Math.ceil(hi);
                int hiI = (int) hiCeil;
                if (hiI <= loI + 1) {
                    double xmf = 0.5 * (x + xNext) - loFloor;
                    add(base, loI, d - d * xmf);
                    add(base, loI + 1, d * xmf);
                } else {
                    double s = 1.0 / (hi - lo);
                    double loF = lo - loFloor;
                    double a0 = 0.5 * s * (1.0 - loF) * (1.0 - loF);
                    double hiF = hi - hiCeil + 1.0;
                    double am = 0.5 * s * hiF * hiF;
                    add(base, loI, d * a0);
                    if (hiI == loI + 2) {
                        add(base, loI + 1, d * (1.0 - a0 - am));
                    } else {
                        double a1 = s * (1.5 - loF);
                        add(base, loI + 1, d * (a1 - a0));
                        for (int xi = loI + 2; xi < hiI - 1; xi++) add(base, xi, d * s);
                        double a2 = a1 + (hiI - loI - 3) * s;
                        add(base, hiI - 1, d * (1.0 - a2 - am));
                    }
                    add(base, hiI, d * am);
                }
                x = xNext;
            }
        }

        private void add(int base, int col, double v) {
            if (col < 0) col = 0;
            if (col > w) col = w;
            a[base + col] += v;
        }

        /** The running sum per row as 8-bit coverage, as {@code gray_hline} quantizes it (floor of 256ths, max 255). */
        byte[] coverage() {
            byte[] out = new byte[w * h];
            for (int row = 0; row < h; row++) {
                double sum = 0.0;
                int base = row * stride;
                for (int col = 0; col < w; col++) {
                    sum += a[base + col];
                    int v = (int) Math.floor(Math.abs(sum) * 256.0 + 1e-6);
                    out[row * w + col] = (byte) Math.min(255, v);
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ atlas

    /** Every glyph of a weight at one size, shelf-packed into one coverage image with {@code pad} px between. */
    public static final class Atlas {
        public final float px;
        public final int width, height;
        /** Coverage 0..255, row-major. */
        public final byte[] alpha;
        public final int[] x = new int[256], y = new int[256], w = new int[256], h = new int[256];
        public final int[] left = new int[256], top = new int[256];

        Atlas(float px, int width, int height) {
            this.px = px;
            this.width = width;
            this.height = height;
            this.alpha = new byte[width * height];
        }
    }

    public static Atlas atlas(final Outlines o, float px, int pad) {
        final Glyph[] glyphs = new Glyph[256];
        List<Integer> order = new ArrayList<Integer>();
        for (int c = 0; c < 256; c++) {
            if (!o.has(c)) continue;
            glyphs[c] = glyph(o, c, px);
            order.add(c);
        }
        // tallest first, onto shelves in a power-of-two-wide image that grows until it is at least as wide as tall
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return glyphs[b].height - glyphs[a].height;
            }
        });
        int width = 128, height;
        int[] gx = new int[256], gy = new int[256];
        while (true) {
            int x = pad, y = pad, shelf = 0;
            for (int c : order) {
                Glyph g = glyphs[c];
                if (x + g.width + pad > width) {
                    x = pad;
                    y += shelf + pad;
                    shelf = 0;
                }
                gx[c] = x;
                gy[c] = y;
                x += g.width + pad;
                shelf = Math.max(shelf, g.height);
            }
            height = y + shelf + pad;
            if (height <= width) break;
            width *= 2;
        }
        height = Integer.highestOneBit(Math.max(1, height - 1)) << 1;
        Atlas a = new Atlas(px, width, height);
        for (int c : order) {
            Glyph g = glyphs[c];
            a.x[c] = gx[c];
            a.y[c] = gy[c];
            a.w[c] = g.width;
            a.h[c] = g.height;
            a.left[c] = g.left;
            a.top[c] = g.top;
            for (int row = 0; row < g.height; row++) {
                System.arraycopy(g.alpha, row * g.width, a.alpha, (gy[c] + row) * width + gx[c], g.width);
            }
        }
        return a;
    }
}
