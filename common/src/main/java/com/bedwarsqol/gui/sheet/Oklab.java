package com.bedwarsqol.gui.sheet;

/**
 * Colour shading in OKLab, ported from the approved prototype's {@code shade()} so every accent gets the same
 * visible light and depth. Pure Java; the maths is kept in doubles to match the prototype's JavaScript.
 */
public final class Oklab {

    private Oklab() {
    }

    /** Lightness step {@code dL} and chroma multiplier {@code cm} applied in OKLab; returns opaque ARGB. */
    public static int shade(int rgb, double dL, double cm) {
        double[] lab = toLab(rgb);
        double l = Math.min(0.985, Math.max(0.05, lab[0] + dL));
        return fromLab(l, lab[1] * cm, lab[2] * cm);
    }

    static double[] toLab(int rgb) {
        double r = lin((rgb >> 16) & 255), g = lin((rgb >> 8) & 255), b = lin(rgb & 255);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[]{
                0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s};
    }

    static int fromLab(double lL, double a, double b) {
        double l = Math.pow(lL + 0.3963377774 * a + 0.2158037573 * b, 3);
        double m = Math.pow(lL - 0.1055613458 * a - 0.0638541728 * b, 3);
        double s = Math.pow(lL - 0.0894841775 * a - 1.291485548 * b, 3);
        int r = unlin(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s);
        int g = unlin(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s);
        int bl = unlin(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static double lin(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int unlin(double x) {
        x = Math.min(1, Math.max(0, x));
        // JavaScript Math.round: half rounds up, the same as Java's for non-negative values.
        return (int) Math.round(255 * (x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055));
    }
}
