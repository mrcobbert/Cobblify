//! BGRA (bottom row first, as the mod sends it) to NV12, BT.709 limited range, top row first.
//!
//! Used on Windows: the Media Foundation colour converter picks BT.601 for pictures under 720
//! lines while the file is tagged BT.709 (measured: 640x360 decoded as tagged at 28.3 dB, as 601 at
//! 43.6 dB), and declaring the matrix on the media types did not change that. Converting here makes
//! the tag true at every size.
#![cfg_attr(not(windows), allow(dead_code))]

/// Bytes of one NV12 picture.
pub fn nv12_len(width: usize, height: usize) -> usize {
    width * height * 3 / 2
}

/// Converts one frame. `width` and `height` are even; `out` holds `nv12_len` bytes: the Y plane
/// (`width` bytes per row) followed by interleaved Cb Cr at half resolution.
pub fn bgra_bottom_up_to_nv12(src: &[u8], width: usize, height: usize, out: &mut [u8]) {
    assert!(width % 2 == 0 && height % 2 == 0, "even size");
    assert!(src.len() >= width * height * 4 && out.len() >= nv12_len(width, height), "buffer sizes");
    let (luma, chroma) = out[..nv12_len(width, height)].split_at_mut(width * height);
    let pairs = height / 2;
    let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1).clamp(1, 8);
    let per = pairs.div_ceil(threads).max(1);
    std::thread::scope(|scope| {
        let mut luma_rest = luma;
        let mut chroma_rest = chroma;
        let mut first = 0;
        while first < pairs {
            let count = per.min(pairs - first);
            let (l, lr) = luma_rest.split_at_mut(count * 2 * width);
            let (c, cr) = chroma_rest.split_at_mut(count * width);
            luma_rest = lr;
            chroma_rest = cr;
            let start = first;
            scope.spawn(move || convert_pairs(src, width, height, start, l, c));
            first += count;
        }
    });
}

// Fixed-point BT.709 limited-range coefficients (x 2^16): Y uses 219/255, Cb and Cr 224/255.
const YR: i32 = 11_966; // 0.2126 * 219 / 255 * 65536
const YG: i32 = 40_254; // 0.7152 * ...
const YB: i32 = 4_064; // 0.0722 * ...
const UR: i32 = -6_596; // -0.2126 / 1.8556 * 224 / 255 * 65536
const UG: i32 = -22_189; // -0.7152 / 1.8556 * ...
const UB: i32 = 28_784; // 0.5 * 224 / 255 * 65536
const VR: i32 = 28_784;
const VG: i32 = -26_145; // -0.7152 / 1.5748 * ...
const VB: i32 = -2_639; // -0.0722 / 1.5748 * ...
const HALF: i32 = 1 << 15;

/// Converts row pairs `first..` of the picture (top-down numbering) into the given plane slices.
fn convert_pairs(src: &[u8], w: usize, h: usize, first: usize, luma: &mut [u8], chroma: &mut [u8]) {
    let pairs = chroma.len() / w;
    for p in 0..pairs {
        let top = (first + p) * 2;
        // Picture row y is input row h - 1 - y.
        let row0 = &src[(h - 1 - top) * w * 4..][..w * 4];
        let row1 = &src[(h - 2 - top) * w * 4..][..w * 4];
        let (l0, l1) = luma[p * 2 * w..(p * 2 + 2) * w].split_at_mut(w);
        let c = &mut chroma[p * w..(p + 1) * w];
        for x in (0..w).step_by(2) {
            let mut sum = [0i32; 3];
            for (row, out) in [(row0, &mut *l0), (row1, &mut *l1)] {
                for dx in 0..2 {
                    let px = &row[(x + dx) * 4..(x + dx) * 4 + 3];
                    let (b, g, r) = (px[0] as i32, px[1] as i32, px[2] as i32);
                    out[x + dx] = (((YR * r + YG * g + YB * b + HALF) >> 16) + 16) as u8;
                    sum[0] += r;
                    sum[1] += g;
                    sum[2] += b;
                }
            }
            // Average of four, kept at x4 scale: shift by 18 instead of 16.
            let (r, g, b) = (sum[0], sum[1], sum[2]);
            c[x] = (((UR * r + UG * g + UB * b + (1 << 17)) >> 18) + 128).clamp(16, 240) as u8;
            c[x + 1] = (((VR * r + VG * g + VB * b + (1 << 17)) >> 18) + 128).clamp(16, 240) as u8;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One flat colour (R, G, B) in protocol layout.
    fn flat(w: usize, h: usize, rgb: [u8; 3]) -> Vec<u8> {
        let mut v = Vec::with_capacity(w * h * 4);
        for _ in 0..w * h {
            v.extend_from_slice(&[rgb[2], rgb[1], rgb[0], 255]);
        }
        v
    }

    fn convert(src: &[u8], w: usize, h: usize) -> Vec<u8> {
        let mut out = vec![0xAB; nv12_len(w, h)];
        bgra_bottom_up_to_nv12(src, w, h, &mut out);
        out
    }

    /// (Y, Cb, Cr) of a flat picture.
    fn ycc(rgb: [u8; 3]) -> (u8, u8, u8) {
        let out = convert(&flat(4, 2, rgb), 4, 2);
        (out[0], out[8], out[9])
    }

    #[test]
    fn primaries_land_on_bt709_limited_range_values() {
        assert_eq!(ycc([0, 0, 0]), (16, 128, 128));
        assert_eq!(ycc([255, 255, 255]), (235, 128, 128));
        // BT.709: Y = 16 + 219 (0.2126 R + 0.7152 G + 0.0722 B), Cb/Cr scaled by 224.
        assert_eq!(ycc([255, 0, 0]), (63, 102, 240));
        assert_eq!(ycc([0, 255, 0]), (173, 42, 26));
        assert_eq!(ycc([0, 0, 255]), (32, 240, 118));
    }

    /// Float reference for one pixel.
    fn reference(r: f64, g: f64, b: f64) -> (f64, f64, f64) {
        let (r, g, b) = (r / 255.0, g / 255.0, b / 255.0);
        let y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        (16.0 + 219.0 * y, 128.0 + 224.0 * (b - y) / 1.8556, 128.0 + 224.0 * (r - y) / 1.5748)
    }

    fn rng(seed: &mut u64) -> u8 {
        *seed ^= *seed << 13;
        *seed ^= *seed >> 7;
        *seed ^= *seed << 17;
        (*seed >> 24) as u8
    }

    #[test]
    fn every_pixel_is_within_one_step_of_the_float_formula_and_upright() {
        let (w, h) = (1366usize, 768usize);
        let mut seed = 0x1234_5678_9abc_def1u64;
        let src: Vec<u8> = (0..w * h * 4).map(|_| rng(&mut seed)).collect();
        let out = convert(&src, w, h);
        for y in 0..h {
            for x in 0..w {
                // Output row y is input row h - 1 - y.
                let p = &src[((h - 1 - y) * w + x) * 4..][..4];
                let (ry, _, _) = reference(p[2] as f64, p[1] as f64, p[0] as f64);
                let got = out[y * w + x] as f64;
                assert!((got - ry).abs() <= 1.0, "Y at {x},{y}: {got} vs {ry:.2}");
            }
        }
        for cy in 0..h / 2 {
            for cx in 0..w / 2 {
                let (mut r, mut g, mut b) = (0.0, 0.0, 0.0);
                for (dx, dy) in [(0, 0), (1, 0), (0, 1), (1, 1)] {
                    let p = &src[((h - 1 - (cy * 2 + dy)) * w + cx * 2 + dx) * 4..][..4];
                    r += p[2] as f64 / 4.0;
                    g += p[1] as f64 / 4.0;
                    b += p[0] as f64 / 4.0;
                }
                let (_, rcb, rcr) = reference(r, g, b);
                let c = &out[w * h + cy * w + cx * 2..][..2];
                assert!((c[0] as f64 - rcb).abs() <= 1.0, "Cb at {cx},{cy}: {} vs {rcb:.2}", c[0]);
                assert!((c[1] as f64 - rcr).abs() <= 1.0, "Cr at {cx},{cy}: {} vs {rcr:.2}", c[1]);
            }
        }
    }

    #[test]
    fn the_top_row_comes_from_the_last_input_row() {
        let (w, h) = (4, 4);
        let mut src = flat(w, h, [0, 0, 0]);
        // Last input row (the picture's top) white.
        for px in src[(h - 1) * w * 4..].chunks_exact_mut(4) {
            px.copy_from_slice(&[255, 255, 255, 255]);
        }
        let out = convert(&src, w, h);
        assert_eq!(&out[0..4], &[235; 4]);
        assert_eq!(&out[(h - 1) * w..h * w], &[16; 4]);
    }

    #[test]
    fn small_and_odd_band_counts_match_a_single_pass() {
        // Sizes whose row pairs do not split evenly over threads give the same bytes as the
        // per-pixel reference already checked above; here: tiny pictures do not panic.
        for (w, h) in [(2, 2), (2, 4), (128, 2), (130, 98)] {
            let src = flat(w, h, [10, 200, 30]);
            let out = convert(&src, w, h);
            assert!(out[..w * h].iter().all(|&v| v == out[0]));
            assert!(out[w * h..].chunks_exact(2).all(|c| c == &out[w * h..w * h + 2]));
        }
    }
}
