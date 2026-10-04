#!/usr/bin/env python3
"""Bake the settings sheet's Inter glyph atlases and metrics.

The sheet draws Inter 4.001 (the files in inter-4.001/, the exact fonts the approved prototype uses) at
Regular 400, Medium 500 and SemiBold 600. This writes, into both trees' assets/bedwarsqol/font/sheet/:

  inter-<w>.metrics          advances and kerning pairs in font units (HarfBuzz shaping, as WebKit measures)
  inter-<w>.outlines         the glyphs' TrueType contours in font units; the game rasterizes them at the exact
                             pixel size it needs (common/.../GlyphRaster.java, which draws as FreeType does)
  inter-<w>-m<px>.png / .txt a padded master, mipmapped at load time: the fallback if rasterizing ever fails
  LICENSE-inter.txt          the font's licence (SIL OFL 1.1), shipped with the glyphs

and, as test fixtures in common/src/test/resources/sheet-font-ref/, the same glyphs rasterized by FreeType
(unhinted) at the sizes the sheet uses at GUI scale 2 and GUI Size Large: GlyphRasterTest holds the Java rasterizer
to them.

Needs: pip install fonttools brotli freetype-py uharfbuzz pillow
Run from the repo root: python3 tools/fonts/bake-sheet-font.py
"""
import io
import os

import freetype
import uharfbuzz as hb
from fontTools.ttLib import TTFont
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUTS = [os.path.join(ROOT, "src/main/resources/assets/bedwarsqol/font/sheet"),
        os.path.join(ROOT, "lunar/src/main/resources/assets/bedwarsqol/font/sheet")]
REFS = os.path.join(ROOT, "common/src/test/resources/sheet-font-ref")
SOURCES = {400: "Inter-Regular.woff2", 500: "Inter-Medium.woff2", 600: "Inter-SemiBold.woff2"}
CHARS = [c for c in range(0x20, 0x100) if c < 0x7F or c >= 0xA0]

# Reference sizes: sheet type size (su) x 2 device px per su, for the weights each size is drawn in (SHEET-SPEC §3).
EXACT = {400: [13, 15, 16, 17], 500: [13, 17, 18], 600: [18, 20, 22]}
# Masters for every other size: the canvas picks the smallest master at least as big as the text.
MASTERS = {24: 4, 48: 8}  # px -> padding between glyphs, so 3 mip levels never bleed


def ttf_bytes(woff2_path):
    f = TTFont(woff2_path)
    f.flavor = None
    out = io.BytesIO()
    f.save(out)
    return out.getvalue()


def metrics(weight, data):
    font = TTFont(io.BytesIO(data))
    cmap = font.getBestCmap()
    upm = font["head"].unitsPerEm
    hhea = font["hhea"]
    hbfont = hb.Font(hb.Face(hb.Blob(data)))
    glyph_adv = {}
    lines = ["inter %d upm %d ascent %d descent %d" % (weight, upm, hhea.ascent, -hhea.descent)]
    chars = [c for c in CHARS if c in cmap]
    for c in chars:
        adv = font["hmtx"][cmap[c]][0]
        glyph_adv[c] = adv
        lines.append("a %d %d" % (c, adv))
    pairs = 0
    for a in chars:
        for b in chars:
            buf = hb.Buffer()
            buf.add_codepoints([a, b])
            buf.guess_segment_properties()
            hb.shape(hbfont, buf, {})
            pos = buf.glyph_positions
            if len(pos) != 2:
                continue
            k = pos[0].x_advance - glyph_adv[a]
            if k:
                lines.append("k %d %d %d" % (a, b, k))
                pairs += 1
    return "\n".join(lines) + "\n", chars, pairs


def outlines(weight, data, chars):
    """Each glyph's contours as FreeType loads them. A contour line is its component's offset, then its points as
    x y on-curve; FreeType scales the points and the offset separately, so they stay apart. Inter's Latin-1
    composites are one level deep and unscaled, which the asserts hold."""
    font = TTFont(io.BytesIO(data))
    cmap = font.getBestCmap()
    glyf = font["glyf"]
    lines = ["outlines %d upm %d" % (weight, font["head"].unitsPerEm)]
    for c in chars:
        g = glyf[cmap[c]]
        parts = []
        if g.isComposite():
            for comp in g.components:
                assert not hasattr(comp, "transform") and not glyf[comp.glyphName].isComposite(), (c, comp.glyphName)
                parts.append((comp.x, comp.y, glyf[comp.glyphName]))
        else:
            parts.append((0, 0, g))
        contours = []
        for ox, oy, part in parts:
            coords, ends, flags = part.getCoordinates(glyf)
            start = 0
            for end in ends:
                contours.append("%d %d " % (ox, oy) + " ".join("%d %d %d" % (coords[i][0], coords[i][1], flags[i] & 1)
                                                            for i in range(start, end + 1)))
                start = end + 1
        lines.append("g %d %d" % (c, len(contours)))
        lines.extend(contours)
    return "\n".join(lines) + "\n"


def raster(data, chars, px, pad):
    face = freetype.Face(io.BytesIO(data))
    face.set_char_size(int(round(px * 64)))
    glyphs = []
    for c in chars:
        face.load_char(c, freetype.FT_LOAD_NO_HINTING | freetype.FT_LOAD_RENDER)
        g = face.glyph
        bm = g.bitmap
        img = Image.new("L", (bm.width, bm.rows), 0)
        if bm.width and bm.rows:
            img.putdata([bm.buffer[r * bm.pitch + col] for r in range(bm.rows) for col in range(bm.width)])
        glyphs.append((c, img, g.bitmap_left, g.bitmap_top))
    # shelf packing into a power-of-two-wide atlas, tallest first
    order = sorted(glyphs, key=lambda t: -t[1].size[1])
    width = 128
    while True:
        x, y, shelf, placed = pad, pad, 0, {}
        for c, img, _, _ in order:
            w, h = img.size
            if x + w + pad > width:
                x, y, shelf = pad, y + shelf + pad, 0
            placed[c] = (x, y)
            x += w + pad
            shelf = max(shelf, h)
        height = y + shelf + pad
        if height <= width:
            break
        width *= 2
    height = 1 << (height - 1).bit_length()
    alpha = Image.new("L", (width, height), 0)
    table = ["glyphs %g %d %d" % (px, width, height)]
    for c, img, left, top in glyphs:
        gx, gy = placed[c]
        alpha.paste(img, (gx, gy))
        table.append("g %d %d %d %d %d %d %d" % (c, gx, gy, img.size[0], img.size[1], left, top))
    return alpha, "\n".join(table) + "\n"


def main():
    for out in OUTS + [REFS]:
        os.makedirs(out, exist_ok=True)
    for weight, name in SOURCES.items():
        data = ttf_bytes(os.path.join(HERE, "inter-4.001", name))
        text, chars, pairs = metrics(weight, data)
        files = {"inter-%d.metrics" % weight: text.encode(),
                 "inter-%d.outlines" % weight: outlines(weight, data, chars).encode()}
        refs = {}
        for px, key, pad, dest in ([(px, "%d" % px, 1, refs) for px in EXACT[weight]]
                                   + [(px, "m%d" % px, pad, files) for px, pad in MASTERS.items()]):
            atlas, table = raster(data, chars, px, pad)
            buf = io.BytesIO()
            atlas.save(buf, "PNG", optimize=True)
            dest["inter-%d-%s.png" % (weight, key)] = buf.getvalue()
            dest["inter-%d-%s.txt" % (weight, key)] = table.encode()
        files["LICENSE-inter.txt"] = open(os.path.join(HERE, "LICENSE-inter.txt"), "rb").read()
        for out, blobs in [(o, files) for o in OUTS] + [(REFS, refs)]:
            for fname, blob in blobs.items():
                with open(os.path.join(out, fname), "wb") as f:
                    f.write(blob)
        print("Inter %d: %d glyphs, %d kerning pairs, %d files" % (weight, len(chars), pairs, len(files)))


if __name__ == "__main__":
    main()
